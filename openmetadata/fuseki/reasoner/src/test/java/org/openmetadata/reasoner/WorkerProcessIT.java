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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged worker in its own JVM, on the classpath the image uses (fuseki-server.jar first,
 * then target/reasoner/*) and with the launcher's JVM flags, so a dependency left out of the
 * package, a classpath conflict or a broken exit status fails here and not in the image.
 */
class WorkerProcessIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));

  @TempDir Path job;

  @Test
  void thePackagedWorkerAnswersAsTheSuiteExpects() throws Exception {
    try (Stream<Path> files = Files.list(Conformance.sourceDirectory())) {
      for (final Path file : files.toList()) {
        Files.copy(file, job.resolve(file.getFileName().toString()));
      }
    }
    assertEquals(ExitCodes.COMPLETED, worker("512m", job.resolve("load.json")));
    for (final String fixture :
        List.of(
            "el-intersection-existential",
            "dl-catalog-combination",
            "dl-xmlliteral-inconsistent",
            "imports-two-level",
            "invalid-irregular-chain")) {
      final int exit = worker("512m", job.resolve(fixture + ".json"));
      assertEquals(
          Conformance.expected(fixture),
          Files.readString(job.resolve(fixture + ".report.txt")),
          () -> fixture + " exit " + exit + ": " + log(fixture));
    }
  }

  @Test
  void aWorkerOutOfHeapExitsWithStatusThreeAndNothingElse() throws Exception {
    final Path classify = hardClassification(600_000);
    assertEquals(ExitCodes.OUT_OF_MEMORY, worker("48m", classify), log("classify"));
    assertTrue(log("classify").contains("java.lang.OutOfMemoryError"), log("classify"));
    assertTrue(Files.notExists(StepFile.resultPath(classify)));
  }

  @Test
  void aWorkerPastItsDeadlineEndsItselfWithStatusFive() throws Exception {
    final Path classify = hardClassification(2_000);
    final long start = System.nanoTime();
    assertEquals(ExitCodes.TIME_BUDGET, worker("1g", classify), log("classify"));
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsed < 2_000 + Watchdog.UNWIND_GRACE_MILLIS + 10_000, elapsed + " ms");
    assertEquals("INCOMPLETE", Jobs.manifest(classify).getString("status"));
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

  private Path hardClassification(final long timeoutMillis) throws Exception {
    Jobs.file(job, "hard.trig", Jobs.hardOntology(200));
    assertEquals(
        ExitCodes.COMPLETED,
        worker("256m", Jobs.write(job, "load", Jobs.load("working", "hard.trig"))));
    return Jobs.write(
        job,
        "classify",
        """
{"protocol": 1, "stepId": "classify", "kind": "CLASSIFY", "dataset": "working",
 "timeoutMillis": %d, "closure": {"root": "http://example.org/hard", "graphs": ["urn:hard"]}}
"""
            .formatted(timeoutMillis));
  }

  /** Runs one step with the launcher's flags, minus the Linux-only priority commands. */
  private int worker(final String heap, final Path step) throws Exception {
    final List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(
        List.of(
            "-Xms" + heap,
            "-Xmx" + heap,
            "-Xss1m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:ReservedCodeCacheSize=128m",
            "-XX:MaxDirectMemorySize=64m",
            "-XX:ActiveProcessorCount=2",
            "-XX:+UseParallelGC",
            "-XX:+ExitOnOutOfMemoryError",
            "-Djava.io.tmpdir=" + job,
            "-cp",
            FUSEKI_SERVER + ":" + REASONER + "/*",
            "org.openmetadata.reasoner.Main",
            step.toString()));
    final String name = step.getFileName().toString().replace(".json", "");
    final Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(job.resolve(name + ".log").toFile())
            .start();
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new AssertionError(name + " did not exit");
    }
    return process.exitValue();
  }

  private String log(final String name) {
    try {
      return Files.readString(job.resolve(name + ".log"), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      return "(no log)";
    }
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
