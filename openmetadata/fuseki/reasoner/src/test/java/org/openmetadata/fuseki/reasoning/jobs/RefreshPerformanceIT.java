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
import org.apache.jena.atlas.json.JsonArray;
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
 * active release (or {@code -Dreasoning.performance.releases}) of 1,000 classes (or {@code
 * -Dreasoning.performance.terms}), each in its ontology's graph. A reader and a writer use the
 * serving dataset for ten seconds before the refresh and all through it; their slowest operations
 * in each period are recorded. Run with {@code -Dreasoning.performance=true}; it writes
 * target/refresh-performance-<tables>.json, with {@code -r<releases>x<terms>} before the extension
 * for any other release input.
 */
@EnabledIfSystemProperty(named = "reasoning.performance", matches = "true")
class RefreshPerformanceIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String BASE = "https://open-metadata.org/";
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String C = "https://open-metadata.org/catalog/";
  private static final String KNOWLEDGE = BASE + "graph/knowledge";
  private static final int TABLES = Integer.getInteger("reasoning.performance.tables", 5_000);
  private static final int RELEASES = Integer.getInteger("reasoning.performance.releases", 1);
  private static final int TERMS = Integer.getInteger("reasoning.performance.terms", 1_000);
  private static final String SNAPSHOT_NODE_CACHE =
      System.getProperty("reasoning.performance.snapshotNodeCache");
  private static final int QUERY_REPEATS = 5;
  private static final long BASELINE_MILLIS = 10_000;
  private static final int COLUMNS = 10;

  @TempDir Path root;

  @Test
  void measure() throws Exception {
    final Path location = root.resolve("serving");
    final long loaded = load(location);
    final DatasetGraph serving = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final JsonArray rules = new JsonArray();
    for (final String name :
        List.of(
            "pii-propagation-via-lineage",
            "schema-tag-inheritance",
            "domain-membership-inheritance")) {
      rules.add(Bundles.starter(name).toJson());
    }

    final ImageLayout image = ImageLayout.create(root.resolve("image"), FUSEKI_SERVER, REASONER);
    final ReasoningSettings settings = image.settings("1g");
    final JobSettings jobSettings =
        JobSettings.fromEnvironment(
            SNAPSHOT_NODE_CACHE == null
                ? Map.of()
                : Map.of(JobSettings.SNAPSHOT_NODE_CACHE, SNAPSHOT_NODE_CACHE));
    final Operations operations = new Operations(settings, jobSettings);
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
      final JsonArray releases = new JsonArray();
      for (int r = 0; r < RELEASES; r++) {
        final JsonObject release = new JsonObject();
        release.put("ontologyId", id("ontology", r));
        release.put("releaseId", id("release", r));
        release.put("contentDigest", "0".repeat(64));
        releases.add(release);
      }
      request.put("ontologyReleases", releases);
      request.put("rules", rules);
      request.put("ruleBundleDigest", Bundles.digest(rules));
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
      report.put("releases", RELEASES);
      report.put("termsPerRelease", TERMS);
      report.put("jobMillis", jobMillis);
      final JsonObject steps = new JsonObject();
      stepMillis.forEach(steps::put);
      report.put("stepMillis", steps);
      final JsonObject capture = JSON.read(directory.resolve("capture.json").toString());
      capture.getObj("catalog").remove("graphs");
      capture.remove("closures");
      report.put("capture", capture);
      final JsonObject workers = new JsonObject();
      for (final String name : List.of("load", "classify", "rules", "compact")) {
        final JsonObject manifest = JSON.read(directory.resolve(name + ".result.json").toString());
        final JsonObject worker = manifest.getObj("worker");
        final JsonObject entry = new JsonObject();
        entry.put("heapPeakAfterGcBytes", worker.get("heapPeakAfterGcBytes"));
        entry.put("heapMaxBytes", worker.get("heapMaxBytes"));
        for (final String key :
            List.of(
                "datasetBytes",
                "quads",
                "closures",
                "outputTriples",
                "passes",
                "newFacts",
                "bytesBefore",
                "bytesAfter")) {
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
      report.put("snapshotNodeCache", jobSettings.snapshotNodeCache());
      final String pii = "SELECT ?c WHERE { ?c <" + OM + "hasTag> <" + C + "pii> }";
      final long queryStart = System.nanoTime();
      final JsonObject answer = query(operations, pii);
      report.put(
          "snapshotQueryMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStart));
      report.put("snapshotQueryRows", answer.get("rows"));
      report.put("snapshotQueryTruncated", answer.get("truncated"));
      report.put("snapshotQueryRepeatMicros", fastestRepeat(operations, pii));
      // A full page of two columns: more nodes than TDB2's small cache of 10,000 holds.
      final String page = "SELECT ?t ?c WHERE { ?t <" + OM + "hasColumn> ?c }";
      final long pageStart = System.nanoTime();
      final JsonObject pageAnswer = query(operations, page);
      report.put(
          "snapshotPageMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pageStart));
      report.put("snapshotPageRows", pageAnswer.get("rows"));
      report.put("snapshotPageRepeatMicros", fastestRepeat(operations, page));
      Files.writeString(
          Path.of(
              "target",
              "refresh-performance-"
                  + TABLES
                  + (RELEASES == 1 && TERMS == 1_000 ? "" : "-r" + RELEASES + "x" + TERMS)
                  + ".json"),
          JSON.toString(report) + "\n");
      System.out.println(JSON.toString(report));
    } finally {
      running.set(false);
      service.close();
      TDBInternal.expel(serving);
    }
  }

  private static JsonObject query(final Operations operations, final String sparql)
      throws Exception {
    final JsonObject request = new JsonObject();
    request.put("query", sparql);
    return JSON.parse(
        new String(
            operations
                .queries()
                .run("openmetadata", JSON.toString(request).getBytes(StandardCharsets.UTF_8))
                .body(),
            StandardCharsets.UTF_8));
  }

  /** The fastest of a few more runs of a query the snapshot has already answered, in µs. */
  private static long fastestRepeat(final Operations operations, final String sparql)
      throws Exception {
    long fastest = Long.MAX_VALUE;
    for (int i = 0; i < QUERY_REPEATS; i++) {
      final long start = System.nanoTime();
      query(operations, sparql);
      fastest = Math.min(fastest, System.nanoTime() - start);
    }
    return TimeUnit.NANOSECONDS.toMicros(fastest);
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
      for (int r = 0; r < RELEASES; r++) {
        triples += release(out, r);
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

  /** Writes one release, a hierarchy of classes under its header; returns its triple count. */
  private static long release(final BufferedWriter out, final int index) throws Exception {
    final String suffix = index == 0 ? "" : "-" + index;
    final String ontology = "<" + BASE + "ontology/business" + suffix + ">";
    final String terms = BASE + "business" + suffix + "#Term";
    final String graph = " <" + Capture.ONTOLOGY_GRAPH_PREFIX + id("ontology", index) + "> .\n";
    out.write(
        ontology
            + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
            + " <http://www.w3.org/2002/07/owl#Ontology>"
            + graph);
    out.write(
        ontology
            + " <http://www.w3.org/2002/07/owl#versionIRI> <"
            + Capture.RELEASE_IRI_PREFIX
            + id("release", index)
            + ">"
            + graph);
    for (int t = 0; t < TERMS; t++) {
      final String term = "<" + terms + t + ">";
      out.write(
          term
              + " <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
              + " <http://www.w3.org/2002/07/owl#Class>"
              + graph);
      out.write(
          term
              + " <http://www.w3.org/2000/01/rdf-schema#subClassOf> <"
              + terms
              + (t / 10)
              + ">"
              + graph);
    }
    return 2 + 2L * TERMS;
  }

  private static String id(final String kind, final int index) {
    return UUID.nameUUIDFromBytes((kind + "-" + index).getBytes(StandardCharsets.UTF_8)).toString();
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
