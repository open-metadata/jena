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
package org.openmetadata.fuseki.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.ExitCodes;
import org.openmetadata.reasoner.InvalidStepException;
import org.openmetadata.reasoner.StepFile;

/**
 * The launcher cannot see the worker's classes, so it repeats part of the worker's contract. Here
 * both are compiled together: the repeated exit statuses must equal the worker's, and the launcher
 * must accept exactly the step ids and deadlines the worker accepts, reading the same values.
 */
class LauncherContractTest {
  @TempDir Path job;

  @Test
  void theLauncherKnowsTheWorkersExitStatuses() {
    assertEquals(ExitCodes.COMPLETED, WorkerExitCodes.COMPLETED);
    assertEquals(ExitCodes.INTERNAL_ERROR, WorkerExitCodes.INTERNAL_ERROR);
    assertEquals(ExitCodes.INVALID_STEP, WorkerExitCodes.INVALID_STEP);
    assertEquals(ExitCodes.OUT_OF_MEMORY, WorkerExitCodes.OUT_OF_MEMORY);
    assertEquals(ExitCodes.INPUT_REJECTED, WorkerExitCodes.INPUT_REJECTED);
    assertEquals(ExitCodes.TIME_BUDGET, WorkerExitCodes.TIME_BUDGET);
  }

  @Test
  void theLauncherReadsDeadlinesAsTheWorkerDoes() throws Exception {
    for (final String timeout :
        List.of(
            "1000",
            "1000.0",
            "1e3",
            "1000.5",
            "0",
            "-5",
            "\"1000\"",
            "true",
            "1e30",
            "9223372036854775807",
            "9223372036854775808")) {
      assertAgree(step("step-1", timeout, ""), timeout);
    }
  }

  @Test
  void theLauncherAcceptsTheStepIdsTheWorkerAccepts() throws Exception {
    for (final String stepId :
        List.of("a", "classify.v2_x-1", "-a", ".a", "a/b", "", "a".repeat(128), "a".repeat(129))) {
      assertAgree(step(stepId, "1000", ""), stepId);
    }
  }

  @Test
  void bothRefuseAnOversizedStepFile() throws Exception {
    final String padding =
        ", \"closure\": \"" + "x".repeat((int) StepHeader.MAX_STEP_FILE_BYTES) + "\"";
    assertAgree(step("step-1", "1000", padding), "oversized");
  }

  private void assertAgree(final Path step, final String label) {
    Long worker;
    try {
      worker = StepFile.read(step).timeoutMillis();
    } catch (final InvalidStepException e) {
      worker = null;
    }
    Long launcher;
    try {
      launcher = StepHeader.read(step).timeoutMillis();
    } catch (final StepHeader.Invalid e) {
      launcher = null;
    }
    assertEquals(worker, launcher, label);
  }

  private Path step(final String stepId, final String timeout, final String extra)
      throws Exception {
    final Path step = job.resolve("step.json");
    Files.writeString(
        step,
        "{\"protocol\": 1, \"stepId\": \""
            + stepId
            + "\", \"kind\": \"COMPACT\", \"dataset\": \"working\", \"timeoutMillis\": "
            + timeout
            + extra
            + "}");
    return step;
  }
}
