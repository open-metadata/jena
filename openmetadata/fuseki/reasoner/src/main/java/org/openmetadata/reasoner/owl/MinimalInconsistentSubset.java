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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.openmetadata.reasoner.Watchdog;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * A minimal inconsistent subset of an inconsistent closure's logical axioms: inconsistent, and
 * consistent without any one of its axioms. QuickXplain (Junker, 2004) finds one with a number of
 * consistency checks logarithmic in the closure for a small conflict, each on a fresh HermiT
 * reasoner that the step's deadline can interrupt.
 */
final class MinimalInconsistentSubset {
  private final Watchdog watchdog;

  MinimalInconsistentSubset(final Watchdog watchdog) {
    this.watchdog = watchdog;
  }

  List<OWLAxiom> find(final OWLOntology inconsistent) throws OWLOntologyCreationException {
    final List<OWLAxiom> axioms =
        inconsistent
            .logicalAxioms()
            .map(axiom -> (OWLAxiom) axiom)
            .sorted(Comparator.comparing(Object::toString))
            .toList();
    return quickXplain(List.of(), false, axioms);
  }

  /** Requires background plus candidates to be inconsistent and background alone consistent. */
  private List<OWLAxiom> quickXplain(
      final List<OWLAxiom> background,
      final boolean backgroundGrew,
      final List<OWLAxiom> candidates)
      throws OWLOntologyCreationException {
    if (backgroundGrew && !consistent(background)) {
      return List.of();
    }
    if (candidates.size() == 1) {
      return candidates;
    }
    final List<OWLAxiom> first = candidates.subList(0, candidates.size() / 2);
    final List<OWLAxiom> second = candidates.subList(candidates.size() / 2, candidates.size());
    final List<OWLAxiom> fromSecond = quickXplain(concat(background, first), true, second);
    final List<OWLAxiom> fromFirst =
        quickXplain(concat(background, fromSecond), !fromSecond.isEmpty(), first);
    return concat(fromFirst, fromSecond);
  }

  private boolean consistent(final List<OWLAxiom> axioms) throws OWLOntologyCreationException {
    Classifier.checkDeadline(watchdog);
    final OWLOntology subset =
        OWLManager.createOWLOntologyManager().createOntology(axioms.stream());
    final OWLReasoner reasoner = Engine.hermit(subset);
    try {
      watchdog.guard(reasoner::interrupt);
      return reasoner.isConsistent();
    } finally {
      watchdog.guard(null);
      reasoner.dispose();
    }
  }

  private static List<OWLAxiom> concat(final List<OWLAxiom> one, final List<OWLAxiom> other) {
    final List<OWLAxiom> result = new ArrayList<>(one);
    result.addAll(other);
    return result;
  }
}
