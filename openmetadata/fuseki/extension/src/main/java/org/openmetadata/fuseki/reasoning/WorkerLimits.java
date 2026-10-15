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
 * The worker's admission limits for a worker heap, as org.openmetadata.reasoner.Limits computes
 * them; the reasoner's tests check that both agree. The extension writes them into every step file
 * it starts, so a worker enforces exactly what capabilities report, whatever heap size its JVM ends
 * up reporting (the parallel collector reports less than -Xmx).
 */
public record WorkerLimits(
    long maxInputBytes,
    long maxClosureTriples,
    long maxLogicalAxioms,
    long maxIndividuals,
    long maxExpressionDepth,
    long maxOutputTriples,
    long maxChecks,
    long maxExplainChecks) {

  private static final double GIB = 1L << 30;

  public static WorkerLimits forHeap(final long heapBytes) {
    final double gib = heapBytes / GIB;
    return new WorkerLimits(
        16L << 30,
        scaled(150_000, gib),
        scaled(100_000, gib),
        scaled(25_000, gib),
        32,
        scaled(500_000, gib),
        1_000,
        1_000);
  }

  private static long scaled(final long perGib, final double gib) {
    return Math.max(1, Math.round(perGib * gib));
  }

  /** The step file's {@code limits} object, with the input size capped at {@code maxInputBytes}. */
  public JsonObject toJson(final long inputBytes) {
    final JsonObject json = new JsonObject();
    json.put("maxInputBytes", Math.max(1, Math.min(maxInputBytes, inputBytes)));
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
