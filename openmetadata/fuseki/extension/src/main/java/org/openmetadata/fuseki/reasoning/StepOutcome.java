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

import org.apache.jena.atlas.json.JsonObject;

/**
 * How one worker step ended, as the launcher saw it.
 *
 * <ul>
 *   <li>SUCCEEDED: the worker completed and wrote its manifest; the manifest's outcome is the
 *       answer, which may be INCONSISTENT.
 *   <li>FAILED: the step or its input was rejected, or the worker failed.
 *   <li>INCOMPLETE: a budget ran out (memory, or time); nothing the step produced is complete.
 *   <li>INTERRUPTED: the worker was killed by something other than its own budgets, or cancelled.
 *   <li>NOT_READY: the memory budget does not fit; no worker was started.
 *   <li>BUSY: another worker is running; at most one runs per container.
 * </ul>
 */
public record StepOutcome(
    Status status,
    String reason,
    int exitCode,
    long elapsedMillis,
    long terminationLatencyMillis,
    JsonObject manifest,
    Readiness readiness) {

  public enum Status {
    SUCCEEDED,
    FAILED,
    INCOMPLETE,
    INTERRUPTED,
    NOT_READY,
    BUSY
  }

  static StepOutcome notLaunched(
      final Status status, final String reason, final Readiness readiness) {
    return new StepOutcome(status, reason, -1, 0, -1, null, readiness);
  }

  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("status", status.name());
    json.put("reason", reason);
    if (exitCode >= 0) {
      json.put("exitCode", exitCode);
    }
    json.put("elapsedMillis", elapsedMillis);
    if (terminationLatencyMillis >= 0) {
      json.put("terminationLatencyMillis", terminationLatencyMillis);
    }
    if (readiness != null) {
      json.put("readiness", readiness.toJson());
    }
    if (manifest != null) {
      json.put("manifest", manifest);
    }
    return json;
  }
}
