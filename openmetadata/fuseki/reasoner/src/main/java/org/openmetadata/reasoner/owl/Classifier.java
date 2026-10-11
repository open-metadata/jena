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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.owl.Classification.Kind;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.elk.owlapi.ElkReasoner;
import org.semanticweb.elk.reasoner.completeness.IncompleteResult;
import org.semanticweb.elk.reasoner.completeness.Incompleteness;
import org.semanticweb.owlapi.model.HasIRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerRuntimeException;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;

/**
 * Runs the routed engine over one closure and collects its {@link Classification} and the outcomes
 * of requested entailment checks. ELK is used only while it reports complete results; the moment it
 * reports possible incompleteness, the closure is classified again with HermiT.
 */
final class Classifier {
  /** Outcomes of an entailment check. NOT_ENTAILED never means the negation was proven. */
  enum CheckOutcome {
    ENTAILED,
    NOT_ENTAILED,
    INCONSISTENT,
    INVALID
  }

  record Check(OWLAxiom axiom, String rendering, String invalidReason) {}

  record CheckResult(String rendering, CheckOutcome outcome, String engine) {
    String line() {
      return "CHECK " + rendering + " " + outcome;
    }
  }

  record Outcome(
      Classification classification, Engine engine, String fallback, List<CheckResult> checks) {}

  private Classifier() {}

  static Outcome run(
      final OWLOntology ontology,
      final EngineRouter.Route route,
      final PropertyValues values,
      final List<Check> checks,
      final Watchdog watchdog,
      final Limits limits,
      final Consumer<Classification.Fact> sink) {
    String fallback = null;
    if (route.engine() == Engine.ELK) {
      final ElkReasoner elk = Engine.elk(ontology);
      try {
        watchdog.guard(elk::interrupt);
        final IncompleteResult<Boolean> consistent = elk.checkIsConsistent();
        if (consistent.getIncompletenessMonitor().isIncompletenessDetected()) {
          fallback = "ELK reported possibly incomplete results; classified with HermiT";
        } else {
          final Classification classification =
              collect(
                  elk,
                  ontology,
                  Incompleteness.getValue(consistent),
                  null,
                  values,
                  watchdog,
                  limits,
                  sink);
          return new Outcome(
              classification,
              Engine.ELK,
              null,
              checkWithElk(elk, ontology, classification, checks, watchdog));
        }
      } finally {
        watchdog.guard(null);
        elk.dispose();
      }
    }
    final Reasoner hermit = Engine.hermit(ontology);
    try {
      watchdog.guard(hermit::interrupt);
      final Classification classification =
          collect(hermit, ontology, hermit.isConsistent(), hermit, values, watchdog, limits, sink);
      final List<CheckResult> results = new ArrayList<>();
      for (final Check check : checks) {
        results.add(checkWithHermit(hermit, classification, check, watchdog));
      }
      return new Outcome(classification, Engine.HERMIT, fallback, results);
    } finally {
      watchdog.guard(null);
      hermit.dispose();
    }
  }

  /**
   * @param hermit the same reasoner when it is HermiT, which then also answers equality and the
   *     property values {@code values} says only an engine can compute; null for ELK, which the
   *     router gives only closures whose property values are all asserted and that entail no
   *     equality
   */
  private static Classification collect(
      final OWLReasoner reasoner,
      final OWLOntology ontology,
      final boolean consistent,
      final Reasoner hermit,
      final PropertyValues values,
      final Watchdog watchdog,
      final Limits limits,
      final Consumer<Classification.Fact> sink) {
    if (!consistent) {
      return Classification.inconsistent();
    }
    final List<InferenceType> inferences =
        new ArrayList<>(List.of(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS));
    if (hermit != null) {
      inferences.add(InferenceType.SAME_INDIVIDUAL);
      if (values.needsEngine()) {
        inferences.add(InferenceType.OBJECT_PROPERTY_ASSERTIONS);
      }
    }
    reasoner.precomputeInferences(inferences.toArray(InferenceType[]::new));
    final Classification classification =
        Classification.consistent(sink, limits.maxOutputTriples());
    final Set<OWLClass> unsatisfiable = reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
    for (final OWLClass owlClass :
        sorted(ontology.classesInSignature().filter(c -> !c.isBuiltIn()))) {
      checkDeadline(watchdog);
      final String iri = iri(owlClass);
      if (unsatisfiable.contains(owlClass)) {
        classification.add(Kind.UNSATISFIABLE, iri, null, null);
        continue;
      }
      // Each pair is recorded from its first class in IRI order; built-in classes such as
      // owl:Thing are never visited, so a pair with one is recorded from the other side.
      for (final OWLClass equivalent : sorted(reasoner.getEquivalentClasses(owlClass).entities())) {
        if (!equivalent.isOWLNothing()
            && (equivalent.isBuiltIn() || iri.compareTo(iri(equivalent)) < 0)) {
          classification.addPair(Kind.EQUIVALENT, iri, iri(equivalent));
        }
      }
      reasoner
          .getSuperClasses(owlClass, true)
          .entities()
          .filter(superClass -> !superClass.isOWLThing())
          .forEach(superClass -> classification.add(Kind.SUBCLASS, iri, iri(superClass), null));
    }
    final List<OWLNamedIndividual> individuals = sorted(ontology.individualsInSignature());
    final Map<OWLNamedIndividual, Set<OWLNamedIndividual>> equal = new HashMap<>();
    if (hermit != null) {
      for (final OWLNamedIndividual individual : individuals) {
        checkDeadline(watchdog);
        final Set<OWLNamedIndividual> same =
            reasoner.getSameIndividuals(individual).entities().collect(Collectors.toSet());
        if (same.size() > 1) {
          equal.put(individual, same);
          for (final OWLNamedIndividual other : sorted(same.stream())) {
            if (iri(individual).compareTo(iri(other)) < 0) {
              classification.add(Kind.SAME, iri(individual), iri(other), null);
            }
          }
        }
      }
      for (final OWLObjectProperty property :
          sorted(
              ontology
                  .objectPropertiesInSignature()
                  .filter(p -> !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
                  .filter(values::entailsValues))) {
        checkDeadline(watchdog);
        hermit
            .getObjectPropertyInstances(property)
            .forEach(
                (subject, objects) -> {
                  for (final OWLNamedIndividual object : objects) {
                    classification.add(Kind.VALUE, iri(subject), iri(property), iri(object));
                  }
                });
      }
    }
    for (final OWLNamedIndividual individual : individuals) {
      checkDeadline(watchdog);
      final String iri = iri(individual);
      reasoner
          .getTypes(individual, true)
          .entities()
          .filter(type -> !type.isOWLThing())
          .forEach(type -> classification.add(Kind.TYPE, iri, iri(type), null));
      // Equal individuals can give the same value more than once; only this individual's values
      // are remembered, so the set stays small.
      final Set<String> recorded = new HashSet<>();
      for (final OWLNamedIndividual same : equal.getOrDefault(individual, Set.of(individual))) {
        ontology
            .objectPropertyAssertionAxioms(same)
            .filter(assertion -> !values.entailsValues(assertion.getProperty().getNamedProperty()))
            .filter(assertion -> assertion.getObject().isNamed())
            .forEach(
                assertion -> {
                  final String property = iri(assertion.getProperty().getNamedProperty());
                  final OWLNamedIndividual object = assertion.getObject().asOWLNamedIndividual();
                  for (final OWLNamedIndividual value :
                      equal.getOrDefault(object, Set.of(object))) {
                    if (recorded.add(property + ' ' + iri(value))) {
                      classification.add(Kind.VALUE, iri, property, iri(value));
                    }
                  }
                });
      }
    }
    return classification;
  }

  private static List<CheckResult> checkWithElk(
      final ElkReasoner elk,
      final OWLOntology ontology,
      final Classification classification,
      final List<Check> checks,
      final Watchdog watchdog) {
    final List<CheckResult> results = new ArrayList<>();
    OWLReasoner hermit = null;
    try {
      for (final Check check : checks) {
        final CheckResult answered = elkCheck(elk, classification, check);
        if (answered != null) {
          results.add(answered);
          continue;
        }
        if (hermit == null) {
          hermit = Engine.hermit(ontology);
        }
        watchdog.guard(hermit::interrupt);
        results.add(checkWithHermit(hermit, classification, check, watchdog));
        watchdog.guard(elk::interrupt);
      }
    } finally {
      if (hermit != null) {
        hermit.dispose();
      }
    }
    return results;
  }

  /** ELK's answer when it supports the check and reports it complete; otherwise null. */
  private static CheckResult elkCheck(
      final ElkReasoner elk, final Classification classification, final Check check) {
    if (check.invalidReason() != null) {
      return new CheckResult(check.rendering(), CheckOutcome.INVALID, null);
    }
    if (!classification.isConsistent()) {
      return new CheckResult(
          check.rendering(), CheckOutcome.INCONSISTENT, Engine.ELK.displayName());
    }
    if (!elk.isEntailmentCheckingSupported(check.axiom().getAxiomType())) {
      return null;
    }
    try {
      final IncompleteResult<Boolean> entailed = elk.checkEntailment(check.axiom());
      if (entailed.getIncompletenessMonitor().isIncompletenessDetected()) {
        return null;
      }
      return new CheckResult(
          check.rendering(),
          Incompleteness.getValue(entailed) ? CheckOutcome.ENTAILED : CheckOutcome.NOT_ENTAILED,
          Engine.ELK.displayName());
    } catch (final ReasonerInterruptedException e) {
      throw e;
    } catch (final OWLReasonerRuntimeException | UnsupportedOperationException e) {
      return null;
    }
  }

  private static CheckResult checkWithHermit(
      final OWLReasoner hermit,
      final Classification classification,
      final Check check,
      final Watchdog watchdog) {
    checkDeadline(watchdog);
    if (check.invalidReason() != null) {
      return new CheckResult(check.rendering(), CheckOutcome.INVALID, null);
    }
    if (!classification.isConsistent()) {
      return new CheckResult(
          check.rendering(), CheckOutcome.INCONSISTENT, Engine.HERMIT.displayName());
    }
    if (!hermit.isEntailmentCheckingSupported(check.axiom().getAxiomType())) {
      return new CheckResult(check.rendering(), CheckOutcome.INVALID, Engine.HERMIT.displayName());
    }
    return new CheckResult(
        check.rendering(),
        hermit.isEntailed(check.axiom()) ? CheckOutcome.ENTAILED : CheckOutcome.NOT_ENTAILED,
        Engine.HERMIT.displayName());
  }

  static void checkDeadline(final Watchdog watchdog) {
    if (watchdog.expired()) {
      throw new ReasonerInterruptedException("The step's time budget is exhausted");
    }
  }

  static String iri(final HasIRI entity) {
    return entity.getIRI().toString();
  }

  private static <T extends HasIRI> List<T> sorted(final java.util.stream.Stream<T> entities) {
    return entities
        .sorted(Comparator.comparing(Classifier::iri))
        .collect(Collectors.toCollection(ArrayList::new));
  }
}
