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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.openmetadata.fuseki.reasoning.StepOutcome;
import org.openmetadata.fuseki.reasoning.StepOutcome.Status;

/** Each way a worker step can end, as the launcher reports it, in the protocol's terms. */
class WorkerStageTest {
  @Test
  void stepOutcomesBecomeTheProtocolsProblems() {
    assertProblem(
        Status.INCOMPLETE,
        "MEMORY_BUDGET: the worker exhausted its heap",
        null,
        Problem.Code.MEMORY_BUDGET_EXHAUSTED,
        null);
    assertProblem(
        Status.INCOMPLETE, "TIME_BUDGET", null, Problem.Code.LIMIT_EXCEEDED, "maxJobSeconds");
    assertProblem(
        Status.NOT_READY, "The container memory limit ...", null, Problem.Code.NOT_READY, null);
    assertProblem(Status.INTERRUPTED, "KILLED_BY_SIGNAL_1", null, Problem.Code.WORKER_LOST, null);
    assertProblem(
        Status.FAILED,
        "PROTOCOL_ERROR: no valid result manifest",
        null,
        Problem.Code.WORKER_LOST,
        null);
    assertProblem(
        Status.FAILED,
        "NOT_OWL2_DL",
        rejected("UNDECLARED", "x is not declared"),
        Problem.Code.PROFILE_VIOLATION,
        null);
    assertProblem(
        Status.FAILED,
        "INVALID_INPUT",
        rejected("MISSING_IMPORT", "y is missing"),
        Problem.Code.MISSING_IMPORT,
        null);
    assertProblem(
        Status.FAILED,
        "LIMIT_EXCEEDED",
        rejected("OUTPUT_TOO_LARGE", "z"),
        Problem.Code.LIMIT_EXCEEDED,
        null);
    assertProblem(
        Status.FAILED, "INVALID_STEP: stepId must match", null, Problem.Code.INTERNAL, null);
  }

  @Test
  void aRejectedInputKeepsTheWorkersFirstProblem() {
    final Problem problem =
        WorkerStage.result(
                "classify",
                outcome(
                    Status.FAILED,
                    "INVALID_INPUT",
                    rejected("MISSING_IMPORT", "Import <urn:b> is not in the closure")))
            .problem();
    assertEquals("INVALID_INPUT: Import <urn:b> is not in the closure", problem.message());
    assertEquals("classify", problem.step());
  }

  @Test
  void aCancelledStepStopsAndASucceededOneCompletesWithoutAnAnswer() {
    assertTrue(WorkerStage.result("s", outcome(Status.INTERRUPTED, "CANCELLED", null)).cancelled());
    final Stage.Result completed =
        WorkerStage.result("s", outcome(Status.SUCCEEDED, "INCONSISTENT", null));
    assertNull(completed.problem());
    assertNull(completed.outcome());
  }

  private static void assertProblem(
      final Status status,
      final String reason,
      final JsonObject manifest,
      final Problem.Code code,
      final String subject) {
    final Problem problem = WorkerStage.result("load", outcome(status, reason, manifest)).problem();
    assertEquals(code, problem.code(), reason);
    assertEquals(subject, problem.subject(), reason);
    assertEquals("load", problem.step());
  }

  private static StepOutcome outcome(
      final Status status, final String reason, final JsonObject manifest) {
    return new StepOutcome(status, reason, 0, 0, -1, manifest, null);
  }

  private static JsonObject rejected(final String code, final String message) {
    return JSON.parse(
        "{\"status\": \"FAILED\", \"problems\": [{\"code\": \""
            + code
            + "\", \"message\": \""
            + message
            + "\"}]}");
  }
}
