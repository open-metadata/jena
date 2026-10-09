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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every fixture of the conformance suite through the CLASSIFY step, with production routing. Each
 * expected report was derived by hand from the OWL 2 Direct Semantics, not recorded from a run, and
 * each answered fixture must be routed to the engine routing.expected names for it.
 */
class ConformanceTest {
  @TempDir static Path job;

  @BeforeAll
  static void load() throws Exception {
    Conformance.prepare(job);
  }

  @TestFactory
  Stream<DynamicTest> everyFixtureMatchesItsExpectedReport() throws Exception {
    final Map<String, String> routing = Conformance.expectedRouting();
    return Conformance.fixtures().stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture,
                    () -> {
                      final int exit = Conformance.run(job.resolve(fixture + ".json"));
                      final JsonObject manifest = Conformance.manifest(job, fixture);
                      assertEquals(
                          Conformance.expected(fixture),
                          Conformance.report(job, fixture),
                          () -> fixture + " report differs; manifest: " + JSON.toString(manifest));
                      final String outcome = manifest.getString("outcome");
                      final boolean answered =
                          outcome.equals("CLASSIFIED") || outcome.equals("INCONSISTENT");
                      assertEquals(
                          answered ? ExitCodes.COMPLETED : ExitCodes.INPUT_REJECTED,
                          exit,
                          () -> fixture + " exit status; manifest: " + JSON.toString(manifest));
                      assertEquals(
                          routing.get(fixture),
                          answered ? manifest.getObj("routing").getString("engineUsed") : null,
                          () -> fixture + " routing: " + JSON.toString(manifest.get("routing")));
                    }));
  }

  @Test
  void theLoadStepListsEveryFixture() throws Exception {
    final JsonObject load =
        JSON.read(Conformance.sourceDirectory().resolve("load.json").toString());
    final List<String> inputs =
        load.getArray("inputs").map(value -> value.getAsString().value()).sorted().toList();
    try (Stream<Path> files = Files.list(Conformance.sourceDirectory())) {
      assertEquals(
          files
              .map(path -> path.getFileName().toString())
              .filter(name -> name.endsWith(".trig"))
              .sorted()
              .toList(),
          inputs);
    }
    for (final String fixture : Conformance.fixtures()) {
      assertTrue(
          Files.exists(Conformance.sourceDirectory().resolve(fixture + ".expected")),
          fixture + " has no expected report");
    }
  }
}
