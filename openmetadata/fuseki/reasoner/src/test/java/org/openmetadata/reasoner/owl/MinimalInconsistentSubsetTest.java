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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openmetadata.reasoner.Watchdog;
import org.semanticweb.owlapi.model.OWLOntology;

class MinimalInconsistentSubsetTest {
  @Test
  void aClosureWithoutLogicalAxiomsHasAnEmptySubsetRatherThanEndlessRecursion() throws Exception {
    final OWLOntology empty =
        OntologyLoader.loadFunctional(
            OntologyLoader.newManager(),
            "Ontology(<http://example.org/empty>"
                + " Declaration(Class(<http://example.org/empty#A>)))");
    try (Watchdog watchdog = new Watchdog(60_000, expired -> {})) {
      final ExplainBudget budget = new ExplainBudget(10);
      assertEquals(
          List.of(),
          assertTimeoutPreemptively(
              Duration.ofSeconds(10),
              () -> new MinimalInconsistentSubset(watchdog, budget).find(empty)));
      assertEquals(0, budget.spent());
    }
  }

  @Test
  void theSubsetIsMinimalAndEveryCheckIsCounted() throws Exception {
    final OWLOntology inconsistent =
        OntologyLoader.loadFunctional(
            OntologyLoader.newManager(),
            """
            Prefix(:=<http://example.org/mis#>) Ontology(<http://example.org/mis>
            DisjointClasses(:A :B) SubClassOf(:C :A) SubClassOf(:C :B) ClassAssertion(:C :x)
            SubClassOf(:D :E) SubClassOf(:E :F) ClassAssertion(:D :y))
            """);
    try (Watchdog watchdog = new Watchdog(60_000, expired -> {})) {
      final ExplainBudget budget = new ExplainBudget(100);
      assertEquals(
          List.of(
              "ClassAssertion(<http://example.org/mis#C> <http://example.org/mis#x>)",
              "DisjointClasses(<http://example.org/mis#A> <http://example.org/mis#B>)",
              "SubClassOf(<http://example.org/mis#C> <http://example.org/mis#A>)",
              "SubClassOf(<http://example.org/mis#C> <http://example.org/mis#B>)"),
          new MinimalInconsistentSubset(watchdog, budget)
              .find(inconsistent).stream().map(Object::toString).sorted().toList());
      final long checks = budget.spent();
      assertThrows(
          ExplainBudget.Exceeded.class,
          () ->
              new MinimalInconsistentSubset(watchdog, new ExplainBudget(checks - 1))
                  .find(inconsistent));
    }
  }
}
