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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;

/**
 * The conformance suite in src/test/resources/conformance: one LOAD step for every fixture's TriG,
 * then one CLASSIFY step file per fixture, whose canonical report must equal {@code
 * <fixture>.expected}. The image smoke test runs the same files through the real launcher.
 */
final class Conformance {
  static final String LOAD = "load";

  private Conformance() {}

  static Path sourceDirectory() {
    try {
      return Path.of(Conformance.class.getResource("/conformance").toURI());
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Copies the suite into a fresh job directory and loads it, as the smoke test does. */
  static Path prepare(final Path job) throws IOException {
    try (Stream<Path> files = Files.list(sourceDirectory())) {
      for (final Path file : files.toList()) {
        Files.copy(file, job.resolve(file.getFileName().toString()));
      }
    }
    final int exit = run(job.resolve(LOAD + ".json"));
    assertEquals(ExitCodes.COMPLETED, exit, () -> "LOAD failed: " + manifest(job, LOAD));
    return job;
  }

  static List<String> fixtures() {
    try (Stream<Path> files = Files.list(sourceDirectory())) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".json") && !name.equals(LOAD + ".json"))
          .map(name -> name.substring(0, name.length() - ".json".length()))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static int run(final Path stepFile) {
    return new StepRunner(false).run(stepFile);
  }

  static JsonObject manifest(final Path job, final String stepName) {
    final Path path = job.resolve(stepName + ".result.json");
    return Files.exists(path) ? JSON.read(path.toString()) : new JsonObject();
  }

  static String report(final Path job, final String stepName) throws IOException {
    return Files.readString(job.resolve(stepName + ".report.txt"), StandardCharsets.UTF_8);
  }

  static String expected(final String fixture) throws IOException {
    return Files.readString(
        sourceDirectory().resolve(fixture + ".expected"), StandardCharsets.UTF_8);
  }

  /** The engine production routing must choose per answered fixture, from routing.expected. */
  static Map<String, String> expectedRouting() throws IOException {
    final Map<String, String> routing = new TreeMap<>();
    for (final String line :
        Files.readAllLines(sourceDirectory().resolve("routing.expected"), StandardCharsets.UTF_8)) {
      if (!line.isBlank() && !line.startsWith("#")) {
        final String[] parts = line.trim().split("\\s+");
        routing.put(parts[0], parts[1]);
      }
    }
    return routing;
  }

  /** Writes a copy of a fixture's step file with another engine, step id and output graph. */
  static Path variant(final Path job, final String fixture, final String engine)
      throws IOException {
    final JsonObject step = JSON.read(job.resolve(fixture + ".json").toString());
    final String name = fixture + "." + engine.toLowerCase(Locale.ROOT);
    step.put("stepId", name);
    step.put("engine", engine);
    step.put("output", "urn:fixture:" + name + ":entailments");
    final Path path = job.resolve(name + ".json");
    Files.writeString(path, JSON.toString(step), StandardCharsets.UTF_8);
    return path;
  }
}
