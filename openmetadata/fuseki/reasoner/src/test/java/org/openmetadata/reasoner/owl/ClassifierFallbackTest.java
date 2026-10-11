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
package org.openmetadata.reasoner.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Watchdog;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * When ELK reports possibly incomplete results at run time, the closure is classified again with
 * HermiT, and only HermiT's facts are recorded. The router keeps known cases away from ELK, so the
 * route is forced here.
 */
class ClassifierFallbackTest {
  @Test
  void anIncompleteElkAnswerIsReplacedByHermits() throws Exception {
    final OWLOntology ontology =
        OntologyLoader.loadFunctional(
            OntologyLoader.newManager(),
            """
            Prefix(:=<http://example.org/fallback#>) Ontology(<http://example.org/fallback>
            Declaration(ObjectProperty(:manages)) Declaration(Class(:Team))
            Declaration(NamedIndividual(:alice)) Declaration(NamedIndividual(:platform))
            ObjectPropertyRange(:manages :Team)
            ObjectPropertyAssertion(:manages :alice :platform))
            """);
    final PropertyValues values = PropertyValues.analyse(ontology);
    final List<String> facts = new ArrayList<>();
    try (Watchdog watchdog = new Watchdog(60_000, expired -> {})) {
      final Classifier.Outcome outcome =
          Classifier.run(
              ontology,
              new EngineRouter.Route(Engine.ELK, true, "forced for the test"),
              values,
              List.of(),
              watchdog,
              Limits.defaults(),
              fact -> facts.add(fact.line()));
      assertEquals(Engine.HERMIT, outcome.engine());
      assertNotNull(outcome.fallback());
    }
    assertEquals(
        List.of(
            "TYPE <http://example.org/fallback#platform> <http://example.org/fallback#Team>",
            "VALUE <http://example.org/fallback#alice> <http://example.org/fallback#manages>"
                + " <http://example.org/fallback#platform>"),
        facts.stream().sorted().toList());
  }
}
