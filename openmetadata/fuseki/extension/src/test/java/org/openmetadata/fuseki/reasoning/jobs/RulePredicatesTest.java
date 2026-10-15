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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The predicates a bundle can match, which scope the catalog a refresh captures. */
class RulePredicatesTest {
  @Test
  void patternsPathsAndExistsConditionsNameTheirPredicates() {
    final RulePredicates predicates =
        RulePredicates.of(
            List.of(
                """
                PREFIX om: <https://open-metadata.org/ontology/>
                CONSTRUCT { ?d om:hasTag ?t } WHERE {
                  ?u om:hasTag ?t . ?t om:tagFQN ?f .
                  ?u (om:upstream|^om:downstream)+ ?d .
                  FILTER EXISTS { ?d a om:Column }
                }
                """,
                "CONSTRUCT { ?s <urn:x> ?o } WHERE { { ?s <urn:a>/<urn:b> ?o } UNION { ?s <urn:c>"
                    + " ?o } }"));
    assertFalse(predicates.any());
    assertEquals(
        Set.of(
            "https://open-metadata.org/ontology/hasTag",
            "https://open-metadata.org/ontology/tagFQN",
            "https://open-metadata.org/ontology/upstream",
            "https://open-metadata.org/ontology/downstream",
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
            "urn:a",
            "urn:b",
            "urn:c"),
        predicates.predicates());
  }

  @Test
  void aVariablePredicateOrANegatedSetMatchesAnything() {
    assertTrue(RulePredicates.of(List.of("CONSTRUCT { ?s <urn:x> ?o } WHERE { ?s ?p ?o }")).any());
    assertTrue(
        RulePredicates.of(List.of("CONSTRUCT { ?s <urn:x> ?o } WHERE { ?s !<urn:a> ?o }")).any());
    assertTrue(
        RulePredicates.of(
                List.of(
                    "CONSTRUCT { ?s <urn:x> ?o } WHERE { ?s <urn:a> ?o FILTER EXISTS { ?o ?q ?s }"
                        + " }"))
            .any(),
        "inside EXISTS too");
  }
}
