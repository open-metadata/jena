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
package org.openmetadata.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.ImageLayout;
import org.openmetadata.fuseki.reasoning.StepOutcome;
import org.openmetadata.reasoner.rules.RuleFixtures;

/**
 * The packaged worker in its own JVM, started by the extension's launcher with its real command:
 * the image's classpath (fuseki-server.jar first, then target/reasoner/*), the launcher's JVM flags
 * and environment, and the priority commands where the platform has them. A dependency left out of
 * the package, a classpath conflict, a launch flag the worker cannot start with or a broken exit
 * status fails here and not in the image.
 */
class WorkerProcessIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));

  @TempDir Path root;
  private ImageLayout image;
  private Path job;

  @BeforeEach
  void layOutTheImage() throws IOException {
    image = ImageLayout.create(root, FUSEKI_SERVER, REASONER);
    job = image.job("job");
  }

  @Test
  void thePackagedWorkerAnswersAsTheSuiteExpects() throws Exception {
    try (Stream<Path> files = Files.list(Conformance.sourceDirectory())) {
      for (final Path file : files.toList()) {
        Files.copy(file, job.resolve(file.getFileName().toString()));
      }
    }
    assertEquals(ExitCodes.COMPLETED, worker("512m", job.resolve("load.json")).exitCode());
    for (final String fixture :
        List.of(
            "el-intersection-existential",
            "dl-catalog-combination",
            "dl-xmlliteral-inconsistent",
            "imports-two-level",
            "invalid-irregular-chain")) {
      final StepOutcome outcome = worker("512m", job.resolve(fixture + ".json"));
      assertEquals(
          Conformance.expected(fixture),
          Files.readString(job.resolve(fixture + ".report.txt")),
          () -> fixture + " " + outcome.toJson() + ": " + log(fixture));
    }
    final JsonObject manifest = Jobs.manifest(job.resolve("el-intersection-existential.json"));
    final String version = manifest.getObj("worker").getString("version");
    assertTrue(
        System.getProperty("reasoner.workerJar").endsWith("-" + version + ".jar"),
        "worker version " + version);
  }

  @Test
  void aWorkerOutOfHeapExitsWithStatusThreeAndNothingElse() throws Exception {
    final Path classify = hardClassification(600_000);
    final StepOutcome outcome = worker("48m", classify);
    assertEquals(ExitCodes.OUT_OF_MEMORY, outcome.exitCode(), log("classify"));
    assertEquals(StepOutcome.Status.INCOMPLETE, outcome.status());
    assertTrue(outcome.reason().startsWith("MEMORY_BUDGET"), outcome.reason());
    assertTrue(log("classify").contains("java.lang.OutOfMemoryError"), log("classify"));
    assertTrue(Files.notExists(StepFile.resultPath(classify)));
    assertTrue(Files.notExists(job.resolve("classify.closure.nt")));
  }

  @Test
  void aWorkerPastItsDeadlineEndsItselfWithStatusFive() throws Exception {
    final Path classify = hardClassification(2_000);
    final long start = System.nanoTime();
    final StepOutcome outcome = worker("1g", classify);
    assertEquals(ExitCodes.TIME_BUDGET, outcome.exitCode(), log("classify"));
    assertEquals(StepOutcome.Status.INCOMPLETE, outcome.status());
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsed < 2_000 + Watchdog.UNWIND_GRACE_MILLIS + 10_000, elapsed + " ms");
    assertEquals("INCOMPLETE", Jobs.manifest(classify).getString("status"));
    assertTrue(Files.notExists(job.resolve("classify.closure.nt")));
  }

  /**
   * A closure shaped like an exported catalog, with an inverse property that sends it to HermiT,
   * classified in the default 2 GiB worker heap under the default limits. It takes about 16 s and
   * peaks near 0.3 GiB after collection; the bounds leave room for slower machines.
   */
  @Test
  void aCatalogShapedClosureClassifiesWithinTheDefaultHeap() throws Exception {
    Jobs.file(job, "catalog.trig", Jobs.catalogOntology(20_000, 30));
    assertEquals(
        ExitCodes.COMPLETED,
        worker("1g", Jobs.write(job, "load", Jobs.load("working", "catalog.trig"))).exitCode(),
        log("load"));
    final Path classify =
        Jobs.write(
            job,
            "classify",
            """
{"protocol": 1, "stepId": "classify", "kind": "CLASSIFY", "dataset": "working",
 "timeoutMillis": 300000, "output": "urn:catalog:entailments",
 "closure": {"root": "http://example.org/catalog", "graphs": ["urn:catalog"]}}
""");
    final StepOutcome outcome = worker("2g", classify);
    assertEquals(StepOutcome.Status.SUCCEEDED, outcome.status(), log("classify"));
    final JsonObject manifest = Jobs.manifest(classify);
    assertEquals("HermiT", manifest.getObj("routing").getString("engineUsed"));
    final JsonObject entailments = manifest.getObj("entailments");
    assertEquals(20_000, entailments.getNumber("type").longValue());
    assertTrue(entailments.getNumber("value").longValue() > 100_000, entailments.toString());
    final JsonObject worker = manifest.getObj("worker");
    final long peakAfterGc = worker.getNumber("heapPeakAfterGcBytes").longValue();
    assertTrue(outcome.elapsedMillis() < 120_000, outcome.elapsedMillis() + " ms");
    assertTrue(peakAfterGc < (1L << 30), peakAfterGc + " bytes after collection");
  }

  /**
   * Governance rules through the launcher: the starter bundle converges, and a pass budget that
   * runs out ends the step INCOMPLETE with the budget as its reason (exit status 6).
   */
  @Test
  void governanceRulesRunThroughTheLauncher() throws Exception {
    Jobs.file(job, "catalog.trig", RuleFixtures.lineageCatalog(3, 4, 3, 2));
    assertEquals(
        ExitCodes.COMPLETED,
        worker("256m", Jobs.write(job, "load", Jobs.load("working", "catalog.trig"))).exitCode(),
        log("load"));
    final Path rules =
        Jobs.write(
            job, "rules", RuleFixtures.step("rules", RuleFixtures.starterBundle(), null, true));
    final StepOutcome converged = worker("256m", rules);
    assertEquals(StepOutcome.Status.SUCCEEDED, converged.status(), log("rules"));
    assertEquals("CONVERGED", converged.reason());
    assertTrue(Jobs.report(rules).startsWith("OUTCOME CONVERGED\nFACT "), log("rules"));
    final Path budget =
        Jobs.write(
            job,
            "short",
            RuleFixtures.step("short", RuleFixtures.starterBundle(), "{\"maxPasses\": 2}", false));
    final StepOutcome incomplete = worker("256m", budget);
    assertEquals(ExitCodes.BUDGET, incomplete.exitCode(), log("short"));
    assertEquals(StepOutcome.Status.INCOMPLETE, incomplete.status());
    assertEquals("PASS_BUDGET", incomplete.reason());
    assertEquals("maxPasses", Jobs.manifest(budget).getString("limit"));
  }

  /**
   * The starter rules over a lineage-shaped catalog of 276,000 triples: six layers of 300 tables
   * with 25 columns each, every column feeding three in the next layer. They derive 157,805 facts
   * with a witness each in 8 passes; it takes about 25 s and peaks below 50 MiB after collection in
   * a 512 MiB heap, since solutions, facts and witnesses stay on disk. The bounds leave room for
   * slower machines.
   */
  @Test
  void aLineageCatalogConvergesInASmallHeap() throws Exception {
    Jobs.file(job, "catalog.trig", RuleFixtures.lineageCatalog(6, 300, 25, 3));
    final Path load = Jobs.write(job, "load", Jobs.load("working", "catalog.trig"));
    assertEquals(ExitCodes.COMPLETED, worker("512m", load).exitCode(), log("load"));
    assertEquals(275_893, Jobs.manifest(load).getNumber("quads").longValue());
    final Path rules =
        Jobs.write(
            job, "rules", RuleFixtures.step("rules", RuleFixtures.starterBundle(), null, false));
    final StepOutcome outcome = worker("512m", rules);
    assertEquals(StepOutcome.Status.SUCCEEDED, outcome.status(), log("rules"));
    final JsonObject manifest = Jobs.manifest(rules);
    assertEquals(8, manifest.getNumber("passes").longValue());
    assertEquals(157_805, manifest.getNumber("derived").longValue());
    final long peakAfterGc =
        manifest.getObj("worker").getNumber("heapPeakAfterGcBytes").longValue();
    assertTrue(outcome.elapsedMillis() < 120_000, outcome.elapsedMillis() + " ms");
    assertTrue(peakAfterGc < (256L << 20), peakAfterGc + " bytes after collection");
  }

  private Path hardClassification(final long timeoutMillis) throws Exception {
    Jobs.file(job, "hard.trig", Jobs.hardOntology(200));
    assertEquals(
        ExitCodes.COMPLETED,
        worker("256m", Jobs.write(job, "load", Jobs.load("working", "hard.trig"))).exitCode());
    return Jobs.write(
        job,
        "classify",
        """
{"protocol": 1, "stepId": "classify", "kind": "CLASSIFY", "dataset": "working",
 "timeoutMillis": %d, "closure": {"root": "http://example.org/hard", "graphs": ["urn:hard"]}}
"""
            .formatted(timeoutMillis));
  }

  /** Runs one step as the extension would, with the launcher's command for this worker heap. */
  private StepOutcome worker(final String heap, final Path step) throws IOException {
    return image.launcher(heap).run(step);
  }

  private String log(final String name) {
    try {
      return Files.readString(job.resolve(name + ".log"), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      return "(no log)";
    }
  }

  @Test
  void theShippedJarsAreExactlyThePinnedOnes() throws Exception {
    final Map<String, String> pinned = new TreeMap<>();
    for (final String line :
        Files.readAllLines(Path.of(System.getProperty("reasoner.dependencies")))) {
      final String[] parts = line.trim().split("\\s+");
      pinned.put(parts[1], parts[0]);
    }
    final Map<String, String> shipped = new TreeMap<>();
    try (Stream<Path> jars = Files.list(REASONER)) {
      for (final Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) {
        shipped.put(jar.getFileName().toString(), sha256(jar));
      }
    }
    final String thirdParty = Files.readString(Path.of(System.getProperty("reasoner.thirdParty")));
    for (final String jar : shipped.keySet()) {
      assertTrue(thirdParty.contains("`" + jar + "`"), jar + " is missing from THIRD-PARTY.md");
    }
    shipped.remove(System.getProperty("reasoner.workerJar"));
    shipped.remove(System.getProperty("reasoner.hermitJar"));
    assertEquals(pinned, shipped);
  }

  private static String sha256(final Path file) throws Exception {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(file)) {
      final byte[] buffer = new byte[1 << 16];
      for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
