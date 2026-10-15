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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
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
 * Measures a CHECK on a catalog-sized closure laid out one graph per table, and a capture of a
 * closure five times larger in a small heap of its own. Run with {@code
 * -Dreasoning.performance=true}; it writes its numbers to target/capture-performance.json.
 */
@EnabledIfSystemProperty(named = "reasoning.performance", matches = "true")
class CapturePerformanceIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String NS = "https://open-metadata.org/catalog#";
  private static final String GRAPH = "https://open-metadata.org/graph/table/";
  private static final String SCHEMA = "https://open-metadata.org/graph/schema";
  private static final String CATALOG = "https://open-metadata.org/ontology/catalog";
  private static final String LARGE = "https://open-metadata.org/ontology/large";
  private static final int COLUMNS = 20;
  private static final int CATALOG_TABLES = 1_800;
  private static final int LARGE_TABLES = 9_000;

  /**
   * The probe opens the dataset with TDB2's default caches, as Fuseki does; they take part of this
   * heap whatever the capture does.
   */
  private static final String PROBE_HEAP = "256m";

  @TempDir Path root;

  @Test
  void measure() throws Exception {
    final Path nquads = root.resolve("serving.nq");
    write(nquads);
    final Path location = root.resolve("serving");
    final DatasetGraph loading = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final DataLoader loader = LoaderFactory.phasedLoader(loading, MonitorOutputs.nullOutput());
    loader.startBulk();
    loader.load(nquads.toString());
    loader.finishBulk();
    Files.delete(nquads);
    final long publishing = System.nanoTime();
    final Manifests.Published catalog = publish(loading, CATALOG, CATALOG_TABLES);
    final Manifests.Published large = publish(loading, LARGE, LARGE_TABLES);
    final long publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishing);
    TDBInternal.expel(loading);

    final JsonObject report = new JsonObject();
    report.put("publishTwoManifestsMillis", publishMillis);
    report.put("large", probe(location, large, PROBE_HEAP));

    final DatasetGraph serving = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final ImageLayout image = ImageLayout.create(root.resolve("image"), FUSEKI_SERVER, REASONER);
    final ReasoningSettings settings = image.settings("2g");
    final JobSettings jobs = JobSettings.fromEnvironment(Map.of());
    final JobService service =
        new JobService(
            settings,
            jobs,
            image.launcher(settings),
            image.launcher(settings)::readiness,
            Plans.current(new Operations(settings, jobs), name -> serving),
            System::currentTimeMillis);
    try {
      service.open();
      service.start();
      final JsonObject request = new JsonObject();
      request.put("requestId", UUID.randomUUID().toString());
      request.put("operation", "CHECK");
      request.put(
          "sourceRevision",
          JSON.parse(
              "{\"datasetGeneration\": \"9d72cf3b-3657-4ee3-809c-5bc70b9308bd\","
                  + " \"liveWriteWatermark\": 1}"));
      request.put("ontologySelection", "APPROVED");
      request.put("ontologyIri", CATALOG);
      request.put("ontologyDigest", catalog.digest());
      request.put("check", JSON.parse("{\"kind\": \"CONSISTENCY\"}"));
      final long start = System.nanoTime();
      final UUID id =
          UUID.fromString(
              service.submit(JobRequest.validate(request, "openmetadata")).job().getString("id"));
      JsonObject job;
      do {
        Thread.sleep(200);
        job = service.job(id).orElseThrow();
      } while (List.of("QUEUED", "RUNNING").contains(job.getString("state")));
      final Path directory = settings.reasoningRoot().resolve("jobs").resolve(id.toString());
      final JsonObject check = new JsonObject();
      check.put("job", job);
      check.put("jobMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      final JsonObject capture = JSON.read(directory.resolve("capture.json").toString());
      capture.remove("graphs");
      check.put("capture", capture);
      check.put("load", step(directory, "load", "inputBytes", "quads", "graphs", "datasetBytes"));
      check.put("classify", step(directory, "classify", "admission", "entailments", "routing"));
      report.put("catalogCheck", check);
      Files.writeString(
          Path.of("target", "capture-performance.json"), JSON.toString(report) + "\n");
      System.out.println(JSON.toString(report));
      assertEquals("SUCCEEDED", job.getString("state"), job.toString());
      assertEquals("NOT_ENTAILED", job.getString("outcome"), job.toString());
    } finally {
      service.close();
      TDBInternal.expel(serving);
    }
  }

  /** A manifest pinning the schema graph and the first {@code tables} table graphs. */
  private static Manifests.Published publish(
      final DatasetGraph dataset, final String ontology, final int tables) {
    final List<String> members = new java.util.ArrayList<>(List.of(SCHEMA));
    for (int t = 0; t < tables; t++) {
      members.add(GRAPH + t);
    }
    return Manifests.publish(
        dataset,
        "https://open-metadata.org/",
        ontology,
        ontology.replace("ontology", "glossary"),
        "APPROVED",
        members,
        List.of());
  }

  /** Captures {@code closure} in a JVM with {@code heap}, which must be enough. */
  private JsonObject probe(
      final Path location, final Manifests.Published closure, final String heap)
      throws IOException, InterruptedException {
    final Path spool = root.resolve("probe.nq.gz");
    final Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms" + heap,
                "-Xmx" + heap,
                "-XX:+UseParallelGC",
                "-cp",
                System.getProperty("java.class.path"),
                CaptureProbe.class.getName(),
                location.toString(),
                closure.ontologyIri(),
                closure.digest(),
                spool.toString())
            .redirectErrorStream(true)
            .start();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    final String last = output.lines().reduce((first, second) -> second).orElse("{}");
    final JsonObject result = JSON.parse(last);
    assertTrue(result.getNumber("triples").longValue() > 1_000_000, output);
    Files.delete(spool);
    return result;
  }

  private static JsonObject step(final Path directory, final String name, final String... keys) {
    final JsonObject manifest = JSON.read(directory.resolve(name + ".result.json").toString());
    final JsonObject step = new JsonObject();
    step.put("elapsedMillis", manifest.get("elapsedMillis"));
    step.put("worker", manifest.get("worker"));
    for (final String key : keys) {
      if (manifest.hasKey(key)) {
        step.put(key, manifest.get(key));
      }
    }
    return step;
  }

  /**
   * The schema graph, one graph per table with the table and its columns, and two root ontologies:
   * the catalog, which imports the schema and the first tables, and a large one that imports all.
   * partOf is transitive with an inverse, which sends the closure to HermiT.
   */
  private static void write(final Path file) throws IOException {
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      final String owl = "http://www.w3.org/2002/07/owl#";
      final String type = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";
      final String sub = "<http://www.w3.org/2000/01/rdf-schema#subClassOf>";
      final String schema = " <" + SCHEMA + "> .\n";
      out.write("<" + NS + "partOf> " + type + " <" + owl + "ObjectProperty>" + schema);
      out.write("<" + NS + "partOf> " + type + " <" + owl + "TransitiveProperty>" + schema);
      out.write("<" + NS + "hasPart> " + type + " <" + owl + "ObjectProperty>" + schema);
      out.write("<" + NS + "hasPart> <" + owl + "inverseOf> <" + NS + "partOf>" + schema);
      for (final String property : List.of("owner", "tag", "domain")) {
        out.write("<" + NS + property + "> " + type + " <" + owl + "ObjectProperty>" + schema);
      }
      for (final String kind : List.of("Asset", "Table", "Column", "User", "Tag")) {
        out.write("<" + NS + kind + "> " + type + " <" + owl + "Class>" + schema);
      }
      out.write("<" + NS + "Table> " + sub + " <" + NS + "Asset>" + schema);
      out.write("<" + NS + "Column> " + sub + " <" + NS + "Asset>" + schema);
      for (int i = 0; i < 50; i++) {
        out.write("<" + NS + "user" + i + "> " + type + " <" + NS + "User>" + schema);
        out.write("<" + NS + "tag" + i + "> " + type + " <" + NS + "Tag>" + schema);
      }
      for (int t = 0; t < LARGE_TABLES; t++) {
        final String graph = " <" + GRAPH + t + "> .\n";
        final String table = "<" + NS + "table" + t + ">";
        out.write(table + " " + type + " <" + owl + "NamedIndividual>" + graph);
        out.write(table + " " + type + " <" + NS + "Table>" + graph);
        assertions(out, table, t, graph);
        for (int c = 0; c < COLUMNS; c++) {
          final String column = "<" + NS + "column" + t + "_" + c + ">";
          out.write(column + " " + type + " <" + owl + "NamedIndividual>" + graph);
          out.write(column + " " + type + " <" + NS + "Column>" + graph);
          out.write(column + " <" + NS + "partOf> " + table + graph);
          assertions(out, column, t * COLUMNS + c, graph);
        }
      }
    }
  }

  private static void assertions(
      final BufferedWriter out, final String subject, final int seed, final String graph)
      throws IOException {
    out.write(subject + " <" + NS + "owner> <" + NS + "user" + seed % 50 + ">" + graph);
    out.write(subject + " <" + NS + "tag> <" + NS + "tag" + seed % 47 + ">" + graph);
    out.write(subject + " <" + NS + "domain> <" + NS + "tag" + seed % 43 + ">" + graph);
  }
}
