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
import com.clarkparsia.owlapi.explanation.HSTExplanationGenerator;
import com.clarkparsia.owlapi.explanation.SatisfiabilityConverter;
import com.clarkparsia.owlapi.explanation.util.ExplanationProgressMonitor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
 * Computes justifications, minimal sets of closure axioms that entail the target: OWL API's
 * black-box explanation over HermiT for an entailment, and a minimal inconsistent subset for an
 * inconsistency, which the black-box generator cannot find because owl:Thing gives it no signature
 * to expand from. An entailment of an inconsistent closure is answered INCONSISTENT with the
 * inconsistency's justification, since that is what entails it. One justification is computed by
 * default; up to {@code maxJustifications} entailment justifications come from Reiter's hitting set
 * tree over the black-box generator, never all of them. The step's deadline bounds the search in
 * time and {@code maxExplainChecks} in consistency checks, each on a fresh reasoner; running out of
 * time is INCOMPLETE and of checks before any justification is found LIMIT_EXCEEDED, neither a
 * statement about the entailment. Running out of checks after the first justification answers with
 * those found and says the list is incomplete. A target that is not entailed is reported
 * NOT_ENTAILED, which proves nothing about its negation.
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

    final ExplainBudget budget = new ExplainBudget(step.limits().maxExplainChecks());
    final OWLReasoner reasoner = Engine.hermit(ontology);
    // Both the closure's reasoner and the latest fresh one the generator made can be running a
    // check, so a deadline interrupts both; see Watchdog for why it keeps interrupting.
    final AtomicReference<OWLReasoner> latest = new AtomicReference<>();
    try {
      watchdog.guard(
          () -> {
            reasoner.interrupt();
            final OWLReasoner fresh = latest.get();
            if (fresh != null) {
              fresh.interrupt();
            }
          });
      final boolean consistent = reasoner.isConsistent();
      final List<List<String>> justifications = new ArrayList<>();
      String incomplete = null;
      final String outcome;
      if (!consistent) {
        outcome = axiom == null ? "EXPLAINED" : "INCONSISTENT";
        watchdog.guard(null);
        justifications.add(render(new MinimalInconsistentSubset(watchdog, budget).find(ontology)));
      } else if (axiom == null) {
        return answer("NOT_ENTAILED", target, input);
      } else if (!reasoner.isEntailed(axiom)) {
        return answer("NOT_ENTAILED", target, input);
      } else {
        outcome = "EXPLAINED";
        final OWLClassExpression unsatisfiable =
            new SatisfiabilityConverter(factory).convert(axiom);
        final BlackBoxExplanation generator =
            new BlackBoxExplanation(
                ontology,
                Engine.explanationFactory(
                    created -> {
                      budget.spend();
                      latest.set(created);
                    }),
                reasoner);
        try {
          if (step.explain().maxJustifications() == 1) {
            justifications.add(render(generator.getExplanation(unsatisfiable)));
          } else {
            incomplete =
                several(
                    generator, unsatisfiable, step.explain().maxJustifications(), justifications);
          }
        } finally {
          generator.dispose();
        }
      }
      justifications.sort(Comparator.comparing(lines -> String.join("\n", lines)));
      final List<String> report = new ArrayList<>();
      report.add("OUTCOME " + outcome);
      report.add("TARGET " + target);
      final JsonArray all = new JsonArray();
      for (int i = 0; i < justifications.size(); i++) {
        final String label = i == 0 ? "JUSTIFICATION " : "JUSTIFICATION#" + (i + 1) + " ";
        justifications.get(i).forEach(line -> report.add(label + line));
        final JsonArray array = new JsonArray();
        justifications.get(i).forEach(array::add);
        all.add(array);
      }
      final StepResult result =
          answer(outcome, target, input)
              .detail("justification", all.get(0))
              .detail("justificationSize", justifications.get(0).size())
              .detail("justifications", all)
              .detail("consistencyChecks", budget.spent())
              .report(report);
      if (incomplete != null) {
        result.detail("justificationsIncomplete", incomplete);
      }
      return result;
    } catch (final ExplainBudget.Exceeded e) {
      throw InputRejectedException.of("LIMIT_EXCEEDED", "EXPLAIN_CHECKS_EXCEEDED", e.getMessage());
    } finally {
      watchdog.guard(null);
      reasoner.dispose();
    }
  }

  /**
   * Up to {@code max} justifications from the hitting set tree. The first is found as with one; if
   * the checks run out after that, the ones found are kept and the reason is returned.
   */
  private static String several(
      final BlackBoxExplanation generator,
      final OWLClassExpression unsatisfiable,
      final int max,
      final List<List<String>> justifications) {
    // The tree reports its first justification again when it finds it from a new node.
    final Set<Set<OWLAxiom>> found = new LinkedHashSet<>();
    final HSTExplanationGenerator tree = new HSTExplanationGenerator(generator);
    tree.setProgressMonitor(
        new ExplanationProgressMonitor() {
          @Override
          public void foundExplanation(final Set<OWLAxiom> justification) {
            if (found.size() < max) {
              found.add(Set.copyOf(justification));
            }
          }

          @Override
          public boolean isCancelled() {
            return found.size() >= max;
          }
        });
    String incomplete = null;
    try {
      tree.getExplanations(unsatisfiable, max);
    } catch (final ExplainBudget.Exceeded e) {
      if (found.isEmpty()) {
        throw e;
      }
      incomplete = e.getMessage();
    }
    found.forEach(justification -> justifications.add(render(justification)));
    return incomplete;
  }

  private static List<String> render(final Collection<OWLAxiom> justification) {
    return justification.stream()
        .map(justified -> justified.getAxiomWithoutAnnotations().toString())
        .sorted()
        .toList();
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
