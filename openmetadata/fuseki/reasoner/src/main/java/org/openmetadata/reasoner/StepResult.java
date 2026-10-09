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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.atlas.json.JsonNumber;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.atlas.json.JsonValue;

/**
 * What a step reports in its result manifest. {@code status} is the execution state the launcher
 * relies on; {@code outcome} is the step's logical answer, for example {@code INCONSISTENT}, which
 * is a completed result rather than a failure.
 */
public final class StepResult {
  public enum Status {
    SUCCEEDED,
    FAILED,
    INCOMPLETE
  }

  private final Status status;
  private final String outcome;
  private final int exitCode;
  private final List<Problem> problems = new ArrayList<>();
  private long problemCount;
  private final Map<String, JsonValue> details = new LinkedHashMap<>();
  private List<String> report;

  private StepResult(final Status status, final String outcome, final int exitCode) {
    this.status = status;
    this.outcome = outcome;
    this.exitCode = exitCode;
  }

  public static StepResult succeeded(final String outcome) {
    return new StepResult(Status.SUCCEEDED, outcome, ExitCodes.COMPLETED);
  }

  public static StepResult rejected(final InputRejectedException rejection) {
    final StepResult result =
        new StepResult(Status.FAILED, rejection.outcome(), ExitCodes.INPUT_REJECTED);
    result.problems.addAll(rejection.problems());
    result.problemCount = rejection.problemCount();
    return result;
  }

  public static StepResult invalidStep(final String message) {
    return new StepResult(Status.FAILED, "INVALID_STEP", ExitCodes.INVALID_STEP)
        .problem("INVALID_STEP", message);
  }

  public static StepResult internalError(final Throwable error) {
    return new StepResult(Status.FAILED, "INTERNAL_ERROR", ExitCodes.INTERNAL_ERROR)
        .problem("INTERNAL_ERROR", error.getClass().getName() + ": " + error.getMessage());
  }

  public static StepResult timeBudget(final long timeoutMillis) {
    return new StepResult(Status.INCOMPLETE, "TIME_BUDGET", ExitCodes.TIME_BUDGET)
        .problem(
            "TIME_BUDGET",
            "The step did not finish within its "
                + timeoutMillis
                + " ms budget; nothing it"
                + " produced is complete");
  }

  public StepResult problem(final String code, final String message) {
    problems.add(new Problem(code, message));
    problemCount++;
    return this;
  }

  public StepResult detail(final String key, final JsonValue value) {
    details.put(key, value);
    return this;
  }

  public StepResult detail(final String key, final String value) {
    details.put(key, new JsonString(value));
    return this;
  }

  public StepResult detail(final String key, final long value) {
    details.put(key, JsonNumber.value(value));
    return this;
  }

  /** The canonical report lines, written when the step file asks for a report. */
  public StepResult report(final List<String> lines) {
    this.report = List.copyOf(lines);
    return this;
  }

  public List<String> report() {
    return report;
  }

  public Status status() {
    return status;
  }

  public String outcome() {
    return outcome;
  }

  public int exitCode() {
    return exitCode;
  }

  public List<Problem> problems() {
    return List.copyOf(problems);
  }

  public JsonValue detail(final String key) {
    return details.get(key);
  }

  JsonObject toJson(final String stepId, final String kind, final long elapsedMillis) {
    final JsonObject json = new JsonObject();
    json.put("protocol", StepFile.PROTOCOL);
    if (stepId != null) {
      json.put("stepId", stepId);
    }
    if (kind != null) {
      json.put("kind", kind);
    }
    json.put("status", status.name());
    json.put("outcome", outcome);
    json.put("exitCode", exitCode);
    json.put("elapsedMillis", elapsedMillis);
    details.forEach(json::put);
    json.put("problemCount", problemCount);
    json.put("problems", Problem.toJson(problems));
    return json;
  }
}
