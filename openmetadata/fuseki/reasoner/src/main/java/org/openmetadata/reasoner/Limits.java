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
 * truncated into an unsound answer. A step file may set any of them; the defaults of the limits
 * that bound the worker's heap grow with it, so raising the worker heap admits larger closures
 * without every caller changing its step files.
 *
 * @param maxInputBytes total size of the files one LOAD step reads
 * @param maxClosureTriples triples in one import closure before parsing
 * @param maxLogicalAxioms logical axioms in one parsed closure
 * @param maxIndividuals named individuals in one closure
 * @param maxExpressionDepth nesting depth of any class expression
 * @param maxOutputTriples entailment triples one CLASSIFY step may write
 * @param maxChecks entailment checks in one CLASSIFY step
 * @param maxExplainChecks consistency checks, each on a fresh reasoner, one EXPLAIN step may run
 */
public record Limits(
    long maxInputBytes,
    long maxClosureTriples,
    long maxLogicalAxioms,
    long maxIndividuals,
    long maxExpressionDepth,
    long maxOutputTriples,
    long maxChecks,
    long maxExplainChecks) {

  private static final double GIB = 1L << 30;

  /**
   * The defaults for a worker heap. Per GiB, measured on a catalog-shaped closure (a transitive
   * containment hierarchy, its inverse, and three assertions per asset) classified with HermiT:
   * 50,000 individuals, 297,000 triples and 247,000 logical axioms, writing 581,000 triples, peaked
   * at 0.67 GiB after collection in a 2 GiB heap. The axiom limit sits below that, because
   * terminological axioms cost the engines more than assertions.
   */
  public static Limits forHeap(final long heapBytes) {
    final double gib = heapBytes / GIB;
    return new Limits(
        16L << 30,
        scaled(150_000, gib),
        scaled(100_000, gib),
        scaled(25_000, gib),
        32,
        scaled(500_000, gib),
        1_000,
        1_000);
  }

  /** The defaults for this JVM's heap. */
  public static Limits defaults() {
    return forHeap(Runtime.getRuntime().maxMemory());
  }

  private static long scaled(final long perGib, final double gib) {
    return Math.max(1, Math.round(perGib * gib));
  }

  static Limits fromJson(final JsonValue value) throws InvalidStepException {
    final Limits defaults = defaults();
    if (value == null) {
      return defaults;
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
            "maxChecks",
            "maxExplainChecks" -> {}
        default -> throw new InvalidStepException("Unknown limit '" + key + "'");
      }
    }
    return new Limits(
        positive(json, "maxInputBytes", defaults.maxInputBytes),
        positive(json, "maxClosureTriples", defaults.maxClosureTriples),
        positive(json, "maxLogicalAxioms", defaults.maxLogicalAxioms),
        positive(json, "maxIndividuals", defaults.maxIndividuals),
        positive(json, "maxExpressionDepth", defaults.maxExpressionDepth),
        positive(json, "maxOutputTriples", defaults.maxOutputTriples),
        positive(json, "maxChecks", defaults.maxChecks),
        positive(json, "maxExplainChecks", defaults.maxExplainChecks));
  }

  private static long positive(final JsonObject json, final String key, final long fallback)
      throws InvalidStepException {
    if (!json.hasKey(key)) {
      return fallback;
    }
    final long value = StepFile.requiredLong(json, key);
    if (value <= 0) {
      throw new InvalidStepException(key + " must be a positive integer");
    }
    return value;
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
    json.put("maxExplainChecks", maxExplainChecks);
    return json;
  }
}
