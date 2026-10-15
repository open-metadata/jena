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

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Why a job failed or a request was refused, as OpenMetadata's rdfReasoningJob.json defines a
 * problem. HTTP error responses carry the same object, so a client reads every refusal one way.
 *
 * @param subject what the problem concerns, such as the limit's name in capabilities; may be null
 * @param step the job step that hit it; null for a refused request
 */
public record Problem(Code code, String message, String subject, String step) {
  public Problem {
    if (message == null || message.isBlank()) {
      message = code.name();
    }
  }

  /** The protocol's problem codes; values outside this list would break OpenMetadata's client. */
  public enum Code {
    INVALID_REQUEST,
    NOT_READY,
    NOT_IMPLEMENTED,
    GENERATION_MISMATCH,
    SUPERSEDED,
    MISSING_IMPORT,
    PROFILE_VIOLATION,
    INCONSISTENT_ONTOLOGY,
    LIMIT_EXCEEDED,
    MEMORY_BUDGET_EXHAUSTED,
    WORKER_LOST,
    RULE_FAILED,
    INTERNAL
  }

  public static Problem of(final Code code, final String message) {
    return new Problem(code, message, null, null);
  }

  public Problem withSubject(final String newSubject) {
    return new Problem(code, message, newSubject, step);
  }

  public Problem atStep(final String newStep) {
    return new Problem(code, message, subject, newStep);
  }

  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("code", code.name());
    json.put("message", message);
    if (subject != null) {
      json.put("subject", subject);
    }
    if (step != null) {
      json.put("step", step);
    }
    return json;
  }

  static Problem fromJson(final JsonObject json) {
    return new Problem(
        Code.valueOf(json.getString("code")),
        json.getString("message"),
        optional(json, "subject"),
        optional(json, "step"));
  }

  private static String optional(final JsonObject json, final String key) {
    final JsonValue value = json.get(key);
    return value == null ? null : value.getAsString().value();
  }
}
