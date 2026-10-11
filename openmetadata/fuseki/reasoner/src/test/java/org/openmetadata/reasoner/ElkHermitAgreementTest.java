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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * ELK and HermiT must agree wherever production routing uses ELK: each fixture routed to ELK runs
 * through both engines, and both reports must equal the hand-derived expected report. Fixtures in
 * the OWL 2 EL profile that use what ELK 0.6.0 answers incompletely are never given to ELK: forcing
 * ELK on them must be refused rather than answered.
 */
class ElkHermitAgreementTest {
  @TempDir static Path job;
  private static Map<String, String> routing;

  @BeforeAll
  static void load() throws Exception {
    Conformance.prepare(job);
    routing = Conformance.expectedRouting();
  }

  @TestFactory
  Stream<DynamicTest> elkAndHermitGiveIdenticalResultsWhereElkIsUsed() {
    final List<String> elkFixtures =
        routing.entrySet().stream()
            .filter(entry -> entry.getValue().equals("ELK"))
            .map(Map.Entry::getKey)
            .toList();
    assertFalse(elkFixtures.isEmpty());
    return elkFixtures.stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture,
                    () -> {
                      final String expected = Conformance.expected(fixture);
                      for (final String engine : List.of("ELK", "HERMIT")) {
                        final Path step = Conformance.variant(job, fixture, engine);
                        final String name = step.getFileName().toString().replace(".json", "");
                        assertEquals(ExitCodes.COMPLETED, Conformance.run(step), name);
                        final JsonObject manifest = Conformance.manifest(job, name);
                        assertEquals(
                            engine.equals("ELK") ? "ELK" : "HermiT",
                            manifest.getObj("routing").getString("engineUsed"),
                            () -> name + " fell back: " + JSON.toString(manifest.get("routing")));
                        assertEquals(expected, Conformance.report(job, name), name);
                      }
                    }));
  }

  @TestFactory
  Stream<DynamicTest> elkIsRefusedForElProfileClosuresItAnswersIncompletely() throws Exception {
    final List<String> hermitInEl =
        routing.entrySet().stream()
            .filter(entry -> entry.getValue().equals("HermiT"))
            .map(Map.Entry::getKey)
            .filter(
                fixture -> {
                  Conformance.run(job.resolve(fixture + ".json"));
                  final JsonObject manifest = Conformance.manifest(job, fixture);
                  return manifest.getObj("routing").getString("profile").equals("OWL2_EL");
                })
            .toList();
    assertFalse(hermitInEl.isEmpty());
    return hermitInEl.stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture,
                    () -> {
                      final Path step = Conformance.variant(job, fixture, "ELK");
                      final String name = step.getFileName().toString().replace(".json", "");
                      assertEquals(ExitCodes.INPUT_REJECTED, Conformance.run(step), name);
                      assertEquals(
                          "ENGINE_UNSUITABLE",
                          Conformance.manifest(job, name).getString("outcome"));
                    }));
  }
}
