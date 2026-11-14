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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.Test;

/** The axiom each question of OpenMetadata's requests is answered by. */
class FunctionalSyntaxTest {
  private static final String F = "https://example.org/finance#";

  @Test
  void theExampleQuestionsBecomeTheirAxioms() throws Exception {
    assertEquals(
        "SubClassOf(ObjectOneOf(<%1$squarterlyRevenue>) ObjectSomeValuesFrom(<%1$sreportedIn>"
                .formatted(F)
            + " <%sCurrency>))".formatted(F),
        FunctionalSyntax.check(Contract.example("check-subsumption-request").getObj("check")));
    assertEquals(
        "SubClassOf(<%1$sNetRevenue> <%1$sFinancialMetric>)".formatted(F),
        FunctionalSyntax.statement(
            Contract.example("explain-request").getObj("explain").getObj("statement")));
    assertNull(FunctionalSyntax.check(JSON.parse("{\"kind\": \"CONSISTENCY\"}")));
  }

  @Test
  void expressionsAndStatementsCoverTheSchema() throws Exception {
    assertEquals(
        "SubClassOf(ObjectIntersectionOf(<%1$sA> ObjectUnionOf(<%1$sB> <%1$sC>)) owl:Nothing)"
            .formatted(F),
        FunctionalSyntax.check(
            JSON.parse(
                """
{"kind": "SATISFIABILITY", "classExpression": {"kind": "INTERSECTION", "operands": [
  {"kind": "NAMED_CLASS", "classIri": "%1$sA"},
  {"kind": "UNION", "operands": [{"kind": "NAMED_CLASS", "classIri": "%1$sB"},
                                {"kind": "NAMED_CLASS", "classIri": "%1$sC"}]}]}}
"""
                    .formatted(F))));
    assertEquals(
        "ObjectMaxCardinality(2 <%1$sp> <%1$sB>)".formatted(F),
        FunctionalSyntax.expression(
            JSON.parse(
                """
                {"kind": "RESTRICTION", "restrictionKind": "MAX", "propertyIri": "%1$sp",
                 "cardinality": 2, "filler": {"kind": "NAMED_CLASS", "classIri": "%1$sB"}}
                """
                    .formatted(F))));
    assertEquals(
        "DataHasValue(<%1$scode> \"a\\\"b\"^^<http://www.w3.org/2001/XMLSchema#string>)"
            .formatted(F),
        FunctionalSyntax.expression(
            JSON.parse(
                """
                {"kind": "RESTRICTION", "restrictionKind": "VALUE", "propertyIri": "%1$scode",
                 "literal": {"value": "a\\"b"}}
                """
                    .formatted(F))));
    assertEquals(
        "ClassAssertion(<%1$sMetric> <%1$srevenue>)".formatted(F),
        FunctionalSyntax.statement(statement("revenue", FunctionalSyntax.RDF + "type", "Metric")));
    assertEquals(
        "ObjectPropertyAssertion(<%1$sowns> <%1$sa> <%1$sb>)".formatted(F),
        FunctionalSyntax.statement(statement("a", F + "owns", "b")));
    assertEquals(
        "DataPropertyAssertion(<%1$scode> <%1$sa> \"EUR\"@en)".formatted(F),
        FunctionalSyntax.statement(
            JSON.parse(
                """
                {"subjectIri": "%1$sa", "predicateIri": "%1$scode", "objectKind": "LITERAL",
                 "literalValue": "EUR", "language": "en"}
                """
                    .formatted(F))));
  }

  @Test
  void whatOwlCannotStateIsRefusedWithTheRequest() {
    final JsonObject request = Contract.example("check-subsumption-request");
    request.getObj("check").put("classExpression", JSON.parse("{\"kind\": \"INTERSECTION\"}"));
    final JobRequest.Invalid invalid =
        assertThrows(JobRequest.Invalid.class, () -> JobRequest.validate(request, "ds"));
    assertTrue(invalid.getMessage().contains("INTERSECTION needs at least one operand"));
    final JsonObject literal = Contract.example("explain-request");
    literal.getObj("explain").getObj("statement").put("objectKind", "LITERAL");
    assertThrows(JobRequest.Invalid.class, () -> JobRequest.validate(literal, "ds"));
  }

  private static JsonObject statement(
      final String subject, final String predicate, final String object) {
    final JsonObject json = new JsonObject();
    json.put("subjectIri", F + subject);
    json.put("predicateIri", predicate);
    json.put("objectKind", "IRI");
    json.put("objectIri", F + object);
    return json;
  }
}
