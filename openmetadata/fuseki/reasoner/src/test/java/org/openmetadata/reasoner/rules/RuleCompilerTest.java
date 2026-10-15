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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openmetadata.reasoner.Problem;
import org.openmetadata.reasoner.StepFile;

/**
 * The trust boundary: the monotonic, finite subset OpenMetadata's FixedPointRuleCheck accepts,
 * checked again on the algebra, plus the worker's own stricter refusals.
 */
class RuleCompilerTest {
  private static final String P = "PREFIX : <http://example.org/> ";

  @Test
  void theStarterRulesCompile() {
    for (final String name : RuleFixtures.STARTER_RULES) {
      final RuleCompiler.Result result = compile(RuleFixtures.starterBody(name));
      assertNotNull(result.rule(), () -> name + ": " + result.problems());
    }
  }

  @Test
  void nonMonotonicRulesAreRefused() {
    final Map<String, String> bodies =
        Map.of(
            "optional", "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y OPTIONAL { ?y :r ?z } }",
            "minus", "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y MINUS { ?y :r ?x } }",
            "not-exists",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER NOT EXISTS { ?y :r ?x } }",
            "negated-exists",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(!EXISTS { ?y :r ?x }) }",
            "exists-in-or",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(EXISTS { ?y :r ?x } || ?x = ?y) }",
            "exists-compared",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(EXISTS { ?y :r ?x } = false) }",
            "exists-in-if",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(IF(EXISTS { ?y :r ?x }, false,"
                    + " true)) }",
            "bound", "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(!BOUND(?z)) }",
            "exists-in-bind",
                "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y BIND(EXISTS { ?y :r ?x } AS ?e) }");
    bodies.forEach((label, body) -> assertCode(body, "RULE_NOT_MONOTONIC", label));
  }

  @Test
  void rulesThatMintTermsOrDependOnTimeOrChanceAreRefused() {
    assertCode("CONSTRUCT { ?x :q _:b } WHERE { ?x :p ?y }", "RULE_TEMPLATE", "blank node");
    assertCode("CONSTRUCT { ?x :q [] } WHERE { ?x :p ?y }", "RULE_TEMPLATE", "anonymous node");
    assertCode(
        "CONSTRUCT { ?x :q ?n } WHERE { ?x :p ?y BIND(STR(?y) AS ?n) }",
        "RULE_TEMPLATE",
        "computed template variable");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(RAND() < 0.5) }",
        "RULE_NONDETERMINISTIC",
        "RAND");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(NOW() > ?y) }",
        "RULE_NONDETERMINISTIC",
        "NOW");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y BIND(UUID() AS ?u) }",
        "RULE_NONDETERMINISTIC",
        "UUID");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y BIND(BNODE() AS ?u) }",
        "RULE_NONDETERMINISTIC",
        "BNODE");
    assertCode(
        "PREFIX fn: <http://www.w3.org/2005/xpath-functions#> CONSTRUCT { ?x :q ?y } WHERE { ?x :p"
            + " ?y FILTER(fn:current-dateTime() > ?y) }",
        "RULE_NONDETERMINISTIC",
        "fn:current-dateTime");
    assertCode("CONSTRUCT { \"x\" :q ?y } WHERE { ?x :p ?y }", "RULE_TEMPLATE", "literal subject");
  }

  @Test
  void constructsOutsideTheWorkersBoundaryAreRefused() {
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { SERVICE <http://example.org/sparql> { ?x :p ?y } }",
        "RULE_UNSUPPORTED",
        "SERVICE");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { GRAPH ?g { ?x :p ?y } }", "RULE_UNSUPPORTED", "GRAPH");
    assertCode(
        "CONSTRUCT { ?x :q ?y } FROM <urn:g> WHERE { ?x :p ?y }", "RULE_UNSUPPORTED", "FROM");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { { SELECT ?x ?y WHERE { ?x :p ?y } LIMIT 1 } }",
        "RULE_UNSUPPORTED",
        "subquery");
    assertCode("CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y } LIMIT 10", "RULE_UNSUPPORTED", "LIMIT");
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(<java:org.example.Fn>(?y)) }",
        "RULE_UNSUPPORTED",
        "extension function");
    assertCode(
        "PREFIX apf: <http://jena.apache.org/ARQ/property#> CONSTRUCT { ?x :q ?y } WHERE { ?x"
            + " apf:strSplit (?y \",\") }",
        "RULE_UNSUPPORTED",
        "property function");
    assertCode("SELECT * WHERE { ?x :p ?y }", "RULE_NOT_CONSTRUCT", "SELECT");
    assertCode("CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y", "RULE_PARSE", "syntax");
    assertCode("CONSTRUCT { :a :q :b } WHERE { }", "RULE_EMPTY", "empty pattern");
  }

  @Test
  void rulesAreBoundedInSize() {
    final StringBuilder body = new StringBuilder("CONSTRUCT { ?x0 :q ?x0 } WHERE { ");
    for (int i = 0; i < 9; i++) {
      body.append("?x").append(i).append(" :p ?x").append(i + 1).append(" . ");
    }
    body.append('}');
    final RuleCompiler.Result result =
        RuleCompiler.compile(0, spec(P + body), 8, RulesStep.context());
    assertTrue(codes(result).contains("RULE_TOO_LARGE"), result.problems().toString());
    assertCode(
        "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(\"" + "x".repeat(20_000) + "\" != ?y) }",
        "RULE_TOO_LARGE",
        "body length");
  }

  @Test
  void positiveConditionsPathsValuesAndCastsAreAllowed() {
    for (final String body :
        List.of(
            "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(EXISTS { ?y :r ?x } && ?x != ?y) }",
            "CONSTRUCT { ?x :q ?y } WHERE { ?x :p+ ?y }",
            "CONSTRUCT { ?x :q ?y } WHERE { ?x (:p|^:r)/:s* ?y }",
            "CONSTRUCT { ?x :q ?k } WHERE { VALUES ?k { :a :b } ?x :p ?k }",
            "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(xsd:integer(?y) > 3) }",
            "CONSTRUCT { ?x ?pred ?y } WHERE { ?x ?pred ?y . ?pred :sub :p }",
            "CONSTRUCT { ?x :q ?y } WHERE { { ?x :p ?y } UNION { ?y :r ?x } }")) {
      final RuleCompiler.Result result =
          compile("PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> " + P + body);
      assertNotNull(result.rule(), () -> body + ": " + result.problems());
    }
  }

  @Test
  void variantsBindOneAtomToTheDeltaAndDropOtherUnionBranches() {
    final CompiledRule rule =
        compile(P + "CONSTRUCT { ?x :q ?y } WHERE { { ?x :p ?z . ?z :p ?y } UNION { ?x :r ?y } }")
            .rule();
    assertEquals(3, rule.atoms().size());
    final String second =
        rule.variant(1, org.apache.jena.graph.NodeFactory.createURI("urn:d")).toString();
    assertTrue(
        second.contains("(quadpattern (quad <urn:d> ?z <http://example.org/p> ?y))"), second);
    assertTrue(!second.contains("<http://example.org/r>"), second);
    final String third =
        rule.variant(2, org.apache.jena.graph.NodeFactory.createURI("urn:d")).toString();
    assertTrue(third.contains("<urn:d> ?x <http://example.org/r> ?y"), third);
    assertTrue(!third.contains("<http://example.org/p>"), third);
  }

  private static RuleCompiler.Result compile(final String body) {
    return RuleCompiler.compile(0, spec(body), 64, RulesStep.context());
  }

  private static StepFile.Rule spec(final String body) {
    return new StepFile.Rule("test-rule", "CONSTRUCT", body, true, 0, "urn:out");
  }

  private static void assertCode(final String body, final String code, final String label) {
    final RuleCompiler.Result result = compile(P + body);
    assertTrue(
        codes(result).contains(code),
        () -> label + ": expected " + code + ", got " + result.problems());
    assertTrue(result.rule() == null, label);
    for (final Problem problem : result.problems()) {
      assertTrue(problem.message().startsWith("rule 'test-rule': "), problem.message());
    }
  }

  private static List<String> codes(final RuleCompiler.Result result) {
    return result.problems().stream().map(Problem::code).collect(Collectors.toList());
  }
}
