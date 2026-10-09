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

import com.clarkparsia.owlapi.explanation.BlackBoxExplanation;
import com.clarkparsia.owlapi.explanation.SatisfiabilityConverter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.store.WorkingDataset;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Computes one justification, a minimal set of closure axioms that entails the target: OWL API's
 * black-box explanation over HermiT for an entailment, and a minimal inconsistent subset for an
 * inconsistency, which the black-box generator cannot find because owl:Thing gives it no signature
 * to expand from. Only one is computed, never all of them; the step's deadline bounds the search,
 * and running out of time is INCOMPLETE, not a statement about the entailment. A target that is not
 * entailed is reported NOT_ENTAILED, which proves nothing about its negation.
 */
public final class ExplainStep implements Step {
  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final ClosureInput input;
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      input = ClosureInput.read(dataset, step);
    } finally {
      WorkingDataset.release(dataset);
    }
    final OWLOntology ontology = input.ontology();
    final OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
    final OWLAxiom axiom =
        step.explain().inconsistency() ? null : parse(step.explain().axiom(), input);
    final String target = axiom == null ? "INCONSISTENCY" : axiom.toString();

    final OWLReasoner reasoner = Engine.hermit(ontology);
    try {
      watchdog.guard(reasoner::interrupt);
      final boolean consistent = reasoner.isConsistent();
      final Collection<OWLAxiom> justification;
      if (axiom == null) {
        if (consistent) {
          return answer("NOT_ENTAILED", target, input);
        }
        justification = new MinimalInconsistentSubset(watchdog).find(ontology);
      } else {
        if (!consistent) {
          return answer("INCONSISTENT", target, input);
        }
        if (!reasoner.isEntailed(axiom)) {
          return answer("NOT_ENTAILED", target, input);
        }
        final OWLClassExpression unsatisfiable =
            new SatisfiabilityConverter(factory).convert(axiom);
        final BlackBoxExplanation generator =
            new BlackBoxExplanation(
                ontology,
                Engine.explanationFactory(created -> watchdog.guard(created::interrupt)),
                reasoner);
        try {
          justification = generator.getExplanation(unsatisfiable);
        } finally {
          generator.dispose();
        }
      }
      final List<String> axioms =
          justification.stream()
              .map(justified -> justified.getAxiomWithoutAnnotations().toString())
              .sorted()
              .toList();
      final List<String> report = new ArrayList<>();
      report.add("OUTCOME EXPLAINED");
      report.add("TARGET " + target);
      axioms.forEach(line -> report.add("JUSTIFICATION " + line));
      final JsonArray array = new JsonArray();
      axioms.forEach(array::add);
      return answer("EXPLAINED", target, input)
          .detail("justification", array)
          .detail("justificationSize", axioms.size())
          .report(report);
    } finally {
      watchdog.guard(null);
      reasoner.dispose();
    }
  }

  private static StepResult answer(
      final String outcome, final String target, final ClosureInput input) {
    return StepResult.succeeded(outcome)
        .detail("target", target)
        .detail("engine", Engine.HERMIT.toJson())
        .detail("closure", input.closureJson())
        .detail("admission", input.admission().toJson())
        .report(List.of("OUTCOME " + outcome, "TARGET " + target));
  }

  private static OWLAxiom parse(final String text, final ClosureInput input)
      throws InputRejectedException {
    final OWLOntology document =
        OntologyLoader.loadFunctional(OntologyLoader.newManager(), "Ontology(" + text + ")");
    final List<OWLAxiom> axioms =
        document.logicalAxioms().map(a -> (OWLAxiom) a.getAxiomWithoutAnnotations()).toList();
    if (axioms.size() != 1) {
      throw InputRejectedException.of(
          "INVALID_INPUT",
          "INVALID_TARGET",
          "explain.axiom must be exactly one logical axiom in OWL 2 functional syntax");
    }
    final String unknown = input.unknownEntities(axioms.get(0));
    if (unknown != null) {
      throw InputRejectedException.of("INVALID_INPUT", "UNKNOWN_ENTITY", unknown);
    }
    return axioms.get(0);
  }
}
