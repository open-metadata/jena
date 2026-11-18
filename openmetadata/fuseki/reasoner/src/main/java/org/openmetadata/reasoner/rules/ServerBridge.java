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
package org.openmetadata.reasoner.rules;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.util.Context;
import org.openmetadata.reasoner.Problem;
import org.openmetadata.reasoner.StepFile;

/**
 * What the Fuseki module asks of the rule code in its own JVM: admitting a bundle with the exact
 * compiler a RULES step runs, and explaining a fact of a published snapshot from the witnesses that
 * step recorded. The module loads this class from the worker's jar alone, in a class loader of its
 * own whose parent supplies Jena, so these methods take and return only Jena and JDK types and must
 * never reach the OWL API, which that loader cannot see.
 */
public final class ServerBridge {
  /** Output graph of a rule compiled only to admit it; compilation never writes anything. */
  private static final String ADMISSION_OUTPUT = "urn:openmetadata:admission:output";

  private ServerBridge() {}

  /**
   * Compiles every rule of a bundle, enabled or not, as a RULES step does before it runs.
   *
   * @param rulesJson a JSON array of rules with {@code name}, {@code ruleBody} and optionally
   *     {@code ruleType} and {@code enabled}
   * @return a JSON array of the problems, each {@code {"rule", "code", "message"}}; empty when
   *     every rule compiles
   */
  public static String compile(final String rulesJson, final long maxRulePatterns) {
    final JsonValue parsed = JSON.parseAny(rulesJson);
    final JsonArray problems = new JsonArray();
    final Context context = RuleCompiler.evaluationContext();
    int index = 0;
    for (final JsonValue value : parsed.getAsArray()) {
      final JsonObject rule = value.getAsObject();
      final String name = rule.getString("name");
      final StepFile.Rule spec =
          new StepFile.Rule(
              name,
              rule.hasKey("ruleType") ? rule.getString("ruleType") : "CONSTRUCT",
              rule.getString("ruleBody"),
              !rule.hasKey("enabled") || rule.getBoolean("enabled"),
              0,
              ADMISSION_OUTPUT);
      final RuleCompiler.Result result =
          RuleCompiler.compile(index++, spec, maxRulePatterns, context);
      for (final Problem problem : result.problems()) {
        final JsonObject json = new JsonObject();
        json.put("rule", name);
        json.put("code", problem.code());
        json.put("message", problem.message());
        problems.add(json);
      }
    }
    return JSON.toString(problems);
  }

  /**
   * Explains a fact from the provenance graph of the RULES step that built a snapshot, inside a
   * read transaction the caller holds.
   *
   * @return the explanation as JSON ({@link Explanations.Fact#toJson()}), or null when the
   *     provenance graph holds no converged run
   */
  public static String explain(
      final DatasetGraph dataset,
      final String provenance,
      final Triple fact,
      final int maxDepth,
      final int maxFacts,
      final int maxPathLength,
      final int maxPathNodes) {
    final Explanations.Fact explained =
        Explanations.explain(
            dataset,
            NodeFactory.createURI(provenance),
            fact,
            new Explanations.Bounds(maxDepth, maxFacts, maxPathLength, maxPathNodes));
    return explained == null ? null : JSON.toString(explained.toJson());
  }
}
