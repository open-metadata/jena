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

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Admission limits of one step. Exceeding one rejects the input explicitly; a closure is never
 * truncated into an unsound answer. The defaults are provisional starting values until conformance
 * and scale fixtures measure the envelope that fits the worker heap; callers pass their own.
 *
 * @param maxInputBytes total size of the files one LOAD step reads
 * @param maxClosureTriples triples in one import closure before parsing
 * @param maxLogicalAxioms logical axioms in one parsed closure
 * @param maxIndividuals named individuals in one closure
 * @param maxExpressionDepth nesting depth of any class expression
 * @param maxOutputTriples entailment triples one CLASSIFY step may write
 * @param maxChecks entailment checks in one CLASSIFY step
 */
public record Limits(
    long maxInputBytes,
    long maxClosureTriples,
    long maxLogicalAxioms,
    long maxIndividuals,
    long maxExpressionDepth,
    long maxOutputTriples,
    long maxChecks) {

  public static final Limits DEFAULTS =
      new Limits(16L << 30, 2_000_000, 500_000, 50_000, 32, 5_000_000, 1_000);

  static Limits fromJson(final JsonValue value) throws InvalidStepException {
    if (value == null) {
      return DEFAULTS;
    }
    if (!value.isObject()) {
      throw new InvalidStepException("limits must be an object");
    }
    final JsonObject json = value.getAsObject();
    for (final String key : json.keys()) {
      switch (key) {
        case "maxInputBytes",
            "maxClosureTriples",
            "maxLogicalAxioms",
            "maxIndividuals",
            "maxExpressionDepth",
            "maxOutputTriples",
            "maxChecks" -> {}
        default -> throw new InvalidStepException("Unknown limit '" + key + "'");
      }
    }
    return new Limits(
        positive(json, "maxInputBytes", DEFAULTS.maxInputBytes),
        positive(json, "maxClosureTriples", DEFAULTS.maxClosureTriples),
        positive(json, "maxLogicalAxioms", DEFAULTS.maxLogicalAxioms),
        positive(json, "maxIndividuals", DEFAULTS.maxIndividuals),
        positive(json, "maxExpressionDepth", DEFAULTS.maxExpressionDepth),
        positive(json, "maxOutputTriples", DEFAULTS.maxOutputTriples),
        positive(json, "maxChecks", DEFAULTS.maxChecks));
  }

  private static long positive(final JsonObject json, final String key, final long fallback)
      throws InvalidStepException {
    final JsonValue value = json.get(key);
    if (value == null) {
      return fallback;
    }
    if (!value.isNumber() || value.getAsNumber().value().longValue() <= 0) {
      throw new InvalidStepException(key + " must be a positive integer");
    }
    return value.getAsNumber().value().longValue();
  }

  JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("maxInputBytes", maxInputBytes);
    json.put("maxClosureTriples", maxClosureTriples);
    json.put("maxLogicalAxioms", maxLogicalAxioms);
    json.put("maxIndividuals", maxIndividuals);
    json.put("maxExpressionDepth", maxExpressionDepth);
    json.put("maxOutputTriples", maxOutputTriples);
    json.put("maxChecks", maxChecks);
    return json;
  }
}
