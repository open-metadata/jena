/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.fuseki.reasoning.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.system.Txn;
import org.apache.jena.system.progress.MonitorOutputs;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.loader.DataLoader;
import org.apache.jena.tdb2.loader.LoaderFactory;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.ImageLayout;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;

/**
 * Measures a REFRESH of a catalog-sized serving dataset: 5,000 tables (or {@code
 * -Dreasoning.performance.tables}) of 10 columns with lineage from each table to the next, tags on
 * schemas, tables and source columns, and a description on every column that no rule reads; and one
 * activated closure of 1,000 term graphs. A reader and a writer use the serving dataset for ten
 * seconds before the refresh and all through it; their slowest operations in each period are
 * recorded. Run with {@code -Dreasoning.performance=true}; it writes
 * target/refresh-performance.json.
 */
@EnabledIfSystemProperty(named = "reasoning.performance", matches = "true")
class RefreshPerformanceIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String BASE = "https://open-metadata.org/";
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String C = "https://open-metadata.org/catalog/";
  private static final String KNOWLEDGE = BASE + "graph/knowledge";
  private static final String ROOT = BASE + "ontology/business";
  private static final String GLOSSARY = BASE + "glossary/business";
  private static final int TABLES = Integer.getInteger("reasoning.performance.tables", 5_000);
  private static final long BASELINE_MILLIS = 10_000;
  private static final int COLUMNS = 10;
  private static final int TERMS = 1_000;

  @TempDir Path root;

  @Test
  void measure() throws Exception {
    final Path location = root.resolve("serving");
    final long loaded = load(location);
    final DatasetGraph serving = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final List<String> members = new ArrayList<>();
    for (int t = 0; t < TERMS; t++) {
      members.add(BASE + "graph/glossaryTerm/" + t);
    }
    Bundles.members(serving, BASE, members);
    final Manifests.Published closure =
        Manifests.publish(serving, BASE, ROOT, GLOSSARY, "APPROVED", members, List.of());
    Bundles.activate(serving, BASE, Map.of(GLOSSARY, closure));
    final String bundle =
        Bundles.publish(
            serving,
            BASE,
            List.of(
                Bundles.starter("pii-propagation-via-lineage"),
                Bundles.starter("schema-tag-inheritance"),
                Bundles.starter("domain-membership-inheritance")));

    final ImageLayout image = ImageLayout.create(root.resolve("image"), FUSEKI_SERVER, REASONER);
    final ReasoningSettings settings = image.settings("1g");
    final Operations operations = new Operations(settings, JobSettings.fromEnvironment(Map.of()));
    final JobService service =
        new JobService(
            operations,
            image.launcher(settings),
            image.launcher(settings)::readiness,
            Plans.current(operations, name -> serving),
            System::currentTimeMillis);
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicLong slowestRead = new AtomicLong();
    final AtomicLong slowestWrite = new AtomicLong();
    final AtomicLong reads = new AtomicLong();
    final AtomicLong peakDisk = new AtomicLong();
    final AtomicLong peakHeap = new AtomicLong();
    final List<Thread> threads = new ArrayList<>();
    try {
      service.open();
      service.start();
      threads.add(
          loop(
              running,
              () -> {
                final long start = System.nanoTime();
                Txn.executeRead(
                    serving,
                    () ->
                        QueryExec.dataset(serving)
                            .query(
                                "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <"
                                    + KNOWLEDGE
                                    + "> { <"
                                    + C
                                    + "t1> ?p ?o } }")
                            .select()
                            .materialize());
                slowestRead.accumulateAndGet(System.nanoTime() - start, Math::max);
                reads.incrementAndGet();
              },
              20));
      threads.add(
          loop(
              running,
              () -> {
                final long start = System.nanoTime();
                Txn.executeWrite(
                    serving,
                    () ->
                        serving.add(
                            NodeFactory.createURI(KNOWLEDGE),
                            NodeFactory.createURI(C + "audit"),
                            NodeFactory.createURI(OM + "touched"),
                            NodeFactory.createLiteralString(UUID.randomUUID().toString())));
                slowestWrite.accumulateAndGet(System.nanoTime() - start, Math::max);
              },
              100));
      threads.add(
          loop(
              running,
              () -> {
                peakDisk.accumulateAndGet(JobStore.sizeOnDisk(settings.reasoningRoot()), Math::max);
                peakHeap.accumulateAndGet(heapAfterCollection(), Math::max);
              },
              200));

      Thread.sleep(BASELINE_MILLIS);
      final long baselineRead = slowestRead.getAndSet(0);
      final long baselineWrite = slowestWrite.getAndSet(0);
      final long baselineReads = reads.getAndSet(0);
      final JsonObject request = new JsonObject();
      request.put("requestId", UUID.randomUUID().toString());
      request.put("operation", "REFRESH");
      request.put(
          "sourceRevision",
          JSON.parse(
              "{\"datasetGeneration\": \"9d72cf3b-3657-4ee3-809c-5bc70b9308bd\","
                  + " \"liveWriteWatermark\": 1}"));
      request.put("ontologySelection", "APPROVED");
      request.put("ruleBundleDigest", bundle);
      final long start = System.nanoTime();
      final UUID id =
          UUID.fromString(
              service.submit(JobRequest.validate(request, "openmetadata")).job().getString("id"));
      final Map<String, Long> stepMillis = new LinkedHashMap<>();
      String step = null;
      long stepStart = start;
      JsonObject job;
      while (true) {
        job = service.job(id).orElseThrow();
        final String current =
            job.hasKey("progress") ? job.getObj("progress").getString("step") : null;
        if (current != null && !current.equals(step)) {
          if (step != null) {
            stepMillis.put(step, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStart));
          }
          step = current;
          stepStart = System.nanoTime();
        }
        if (!List.of("QUEUED", "RUNNING").contains(job.getString("state"))) {
          break;
        }
        Thread.sleep(50);
      }
      stepMillis.put(step, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStart));
      final long jobMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      running.set(false);
      for (final Thread thread : threads) {
        thread.join();
      }
      assertEquals("SUCCEEDED", job.getString("state"), job.toString());

      final Path directory = settings.reasoningRoot().resolve("jobs").resolve(id.toString());
      final JsonObject report = new JsonObject();
      report.put("servingTriples", loaded);
      report.put("jobMillis", jobMillis);
      final JsonObject steps = new JsonObject();
      stepMillis.forEach(steps::put);
      report.put("stepMillis", steps);
      final JsonObject capture = JSON.read(directory.resolve("capture.json").toString());
      capture.getObj("catalog").remove("graphs");
      capture.remove("closures");
      report.put("capture", capture);
      final JsonObject workers = new JsonObject();
      for (final String name : List.of("load", "classify-0", "rules", "compact")) {
        final JsonObject manifest = JSON.read(directory.resolve(name + ".result.json").toString());
        final JsonObject worker = manifest.getObj("worker");
        final JsonObject entry = new JsonObject();
        entry.put("heapPeakAfterGcBytes", worker.get("heapPeakAfterGcBytes"));
        entry.put("heapMaxBytes", worker.get("heapMaxBytes"));
        for (final String key :
            List.of("datasetBytes", "quads", "passes", "newFacts", "bytesBefore", "bytesAfter")) {
          if (manifest.hasKey(key)) {
            entry.put(key, manifest.get(key));
          }
        }
        if (manifest.hasKey("entailments")) {
          entry.put("entailments", manifest.get("entailments"));
        }
        workers.put(name, entry);
      }
      report.put("workers", workers);
      report.put(
          "snapshotBytes", JobStore.sizeOnDisk(settings.reasoningRoot().resolve("snapshots")));
      report.put("peakReasoningBytes", peakDisk.get());
      report.put("serverHeapPeakAfterGcBytes", peakHeap.get());
      report.put("baselineServingReads", baselineReads);
      report.put("baselineSlowestServingReadMillis", TimeUnit.NANOSECONDS.toMillis(baselineRead));
      report.put("baselineSlowestServingWriteMillis", TimeUnit.NANOSECONDS.toMillis(baselineWrite));
      report.put("servingReads", reads.get());
      report.put("slowestServingReadMillis", TimeUnit.NANOSECONDS.toMillis(slowestRead.get()));
      report.put("slowestServingWriteMillis", TimeUnit.NANOSECONDS.toMillis(slowestWrite.get()));
      final long queryStart = System.nanoTime();
      final JsonObject answer =
          JSON.parse(
              new String(
                  operations
                      .queries()
                      .run(
                          "openmetadata",
                          ("{\"query\": \"SELECT ?c WHERE { ?c <"
                                  + OM
                                  + "hasTag> <"
                                  + C
                                  + "pii> }\"}")
                              .getBytes(StandardCharsets.UTF_8))
                      .body(),
                  StandardCharsets.UTF_8));
      report.put(
          "snapshotQueryMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStart));
      report.put("snapshotQueryRows", answer.get("rows"));
      report.put("snapshotQueryTruncated", answer.get("truncated"));
      Files.writeString(
          Path.of("target", "refresh-performance-" + TABLES + ".json"),
          JSON.toString(report) + "\n");
      System.out.println(JSON.toString(report));
    } finally {
      running.set(false);
      service.close();
      TDBInternal.expel(serving);
    }
  }

  /** Writes and bulk-loads the catalog and the term graphs; returns the triple count. */
  private long load(final Path location) throws Exception {
    final Path nquads = root.resolve("serving.nq");
    long triples = 0;
    try (BufferedWriter out = Files.newBufferedWriter(nquads, StandardCharsets.UTF_8)) {
      final String g = " <" + KNOWLEDGE + "> .\n";
      out.write("<" + C + "pii> <" + OM + "tagFQN> \"PII.Sensitive\"" + g);
      out.write("<" + C + "tier> <" + OM + "tagFQN> \"Tier.Tier1\"" + g);
      triples += 2;
      for (int s = 0; s < TABLES / 100; s++) {
        out.write(
            "<"
                + C
                + "s"
                + s
                + "> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <"
                + OM
                + "DatabaseSchema>"
                + g);
        triples++;
        if (s % 5 == 0) {
          out.write("<" + C + "s" + s + "> <" + OM + "hasTag> <" + C + "tier>" + g);
          triples++;
        }
      }
      for (int t = 0; t < TABLES; t++) {
        final String table = "<" + C + "t" + t + ">";
        out.write(
            table + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <" + OM + "Table>" + g);
        out.write(table + " <" + OM + "belongsToSchema> <" + C + "s" + t / 100 + ">" + g);
        out.write(table + " <" + OM + "belongsToDomain> <" + C + "d" + t % 20 + ">" + g);
        triples += 3;
        if (t % 10 == 0) {
          out.write(table + " <" + OM + "hasTag> <" + C + "tier>" + g);
          triples++;
        }
        for (int c = 0; c < COLUMNS; c++) {
          final String column = "<" + C + "t" + t + "c" + c + ">";
          out.write(table + " <" + OM + "hasColumn> " + column + g);
          out.write(
              column + " <" + OM + "description> \"Column " + c + " of table " + t + "\"" + g);
          triples += 2;
          if (t % 100 == 0 && c == 0) {
            out.write(column + " <" + OM + "hasTag> <" + C + "pii>" + g);
            triples++;
          }
          if (t + 1 < TABLES && t % 100 < 20) {
            final String lineage = "<" + C + "l" + t + "c" + c + ">";
            out.write(lineage + " <" + OM + "fromColumn> " + column + g);
            out.write(lineage + " <" + OM + "toColumn> <" + C + "t" + (t + 1) + "c" + c + ">" + g);
            triples += 2;
          }
        }
      }
      for (int t = 0; t < TERMS; t++) {
        final String graph = " <" + BASE + "graph/glossaryTerm/" + t + "> .\n";
        final String term = "<" + BASE + "business#Term" + t + ">";
        out.write(
            term
                + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
                + " <http://www.w3.org/2002/07/owl#Class>"
                + graph);
        out.write(
            term
                + " <http://www.w3.org/2000/01/rdf-schema#subClassOf> <"
                + BASE
                + "business#Term"
                + (t / 10)
                + ">"
                + graph);
        triples += 2;
      }
    }
    final DatasetGraph loading = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final DataLoader loader = LoaderFactory.phasedLoader(loading, MonitorOutputs.nullOutput());
    loader.startBulk();
    loader.load(nquads.toString());
    loader.finishBulk();
    TDBInternal.expel(loading);
    Files.delete(nquads);
    return triples;
  }

  private static Thread loop(
      final AtomicBoolean running, final ThrowingRunnable body, final long pauseMillis) {
    final Thread thread =
        new Thread(
            () -> {
              while (running.get()) {
                try {
                  body.run();
                  Thread.sleep(pauseMillis);
                } catch (final Exception e) {
                  if (running.get()) {
                    throw new IllegalStateException(e);
                  }
                }
              }
            });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** The heap the last collections left in use, all heap pools together. */
  private static long heapAfterCollection() {
    long used = 0;
    for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      final MemoryUsage collection = pool.getCollectionUsage();
      if (pool.getType() == MemoryType.HEAP && collection != null) {
        used += collection.getUsed();
      }
    }
    return used;
  }
}
