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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A closure that does not classify within the step's budget ends INCOMPLETE: the deadline
 * interrupts the engine, the step exits promptly with status 5, and the output graph keeps nothing.
 */
class TimeBudgetTest {
  @TempDir Path job;

  @Test
  void anOversizedClassificationEndsIncompleteAtItsDeadline() throws Exception {
    Jobs.file(job, "hard.trig", Jobs.hardOntology(200));
    assertEquals(
        ExitCodes.COMPLETED, Jobs.run(Jobs.write(job, "load", Jobs.load("working", "hard.trig"))));
    final Path step =
        Jobs.write(
            job,
            "classify",
            """
            {"protocol": 1, "stepId": "classify", "kind": "CLASSIFY", "dataset": "working",
             "timeoutMillis": 3000, "report": true, "output": "urn:hard:entailments",
             "closure": {"root": "http://example.org/hard", "graphs": ["urn:hard"]}}
            """);
    final long start = System.nanoTime();
    assertEquals(ExitCodes.TIME_BUDGET, Jobs.run(step));
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("INCOMPLETE", manifest.getString("status"));
    assertEquals("TIME_BUDGET", manifest.getString("outcome"));
    final long latency = manifest.getNumber("interruptLatencyMillis").longValue();
    assertTrue(latency < Watchdog.UNWIND_GRACE_MILLIS, "interrupt latency " + latency + " ms");
    assertTrue(elapsed < 3000 + Watchdog.UNWIND_GRACE_MILLIS + 2000, "elapsed " + elapsed + " ms");
    assertEquals("OUTCOME TIME_BUDGET\nPROBLEM TIME_BUDGET\n", Jobs.report(step));
    assertFalse(Files.exists(job.resolve("classify.closure.nt")));
  }
}
