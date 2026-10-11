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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.ClassExpressionType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLObjectHasSelf;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Which object properties of a closure can have values between named individuals beyond the
 * asserted ones, so only those are asked of the engine. Every other property's values are its
 * assertions, extended over equal individuals, and are read from the closure in linear time.
 *
 * <p>A property {@code P} gets entailed values between named individuals only from: a sub-property,
 * an equivalent or an inverse property, a property chain it is the super-property of, being
 * symmetric or transitive (each of these over a property that has values), being reflexive, or a
 * has-value or has-self restriction on it. Equality between named individuals moves asserted values
 * between them, which the classifier applies itself. Some constructs can connect named individuals
 * through anonymous ones in ways that per-property analysis cannot see: nominals, anonymous
 * individuals, the universal and empty properties, and has-value or has-self restrictions together
 * with anything that can merge an anonymous individual into a named one (functional,
 * inverse-functional or maximum-cardinality restrictions). A closure using any of them has every
 * property asked of the engine.
 */
final class PropertyValues {
  private final boolean everyProperty;
  private final Set<OWLObjectProperty> entailed;
  private final String reason;

  private PropertyValues(
      final boolean everyProperty, final Set<OWLObjectProperty> entailed, final String reason) {
    this.everyProperty = everyProperty;
    this.entailed = entailed;
    this.reason = reason;
  }

  static PropertyValues analyse(final OWLOntology ontology) {
    final String global = globalReason(ontology);
    if (global != null) {
      return new PropertyValues(true, Set.of(), global);
    }
    final Map<OWLObjectProperty, Set<OWLObjectProperty>> sources = new HashMap<>();
    final Set<OWLObjectProperty> seeded = new HashSet<>();
    final Set<OWLObjectProperty> valued = new HashSet<>();
    final boolean individuals = ontology.individualsInSignature().findAny().isPresent();

    ontology
        .axioms(AxiomType.SUB_OBJECT_PROPERTY)
        .forEach(axiom -> link(sources, axiom.getSubProperty(), axiom.getSuperProperty()));
    ontology
        .axioms(AxiomType.EQUIVALENT_OBJECT_PROPERTIES)
        .forEach(axiom -> linkAll(sources, axiom.properties().toList()));
    ontology
        .axioms(AxiomType.INVERSE_OBJECT_PROPERTIES)
        .forEach(
            axiom ->
                linkAll(sources, List.of(axiom.getFirstProperty(), axiom.getSecondProperty())));
    ontology
        .axioms(AxiomType.SUB_PROPERTY_CHAIN_OF)
        .forEach(
            axiom ->
                axiom
                    .getPropertyChain()
                    .forEach(member -> link(sources, member, axiom.getSuperProperty())));
    ontology
        .axioms(AxiomType.SYMMETRIC_OBJECT_PROPERTY)
        .forEach(axiom -> link(sources, axiom.getProperty(), axiom.getProperty()));
    ontology
        .axioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY)
        .forEach(axiom -> link(sources, axiom.getProperty(), axiom.getProperty()));
    if (individuals) {
      ontology
          .axioms(AxiomType.REFLEXIVE_OBJECT_PROPERTY)
          .forEach(axiom -> seeded.add(axiom.getProperty().getNamedProperty()));
    }
    ontology
        .logicalAxioms()
        .flatMap(OWLAxiom::nestedClassExpressions)
        .forEach(
            expression -> {
              if (expression instanceof OWLObjectHasValue hasValue) {
                seeded.add(hasValue.getProperty().getNamedProperty());
              } else if (expression instanceof OWLObjectHasSelf hasSelf) {
                seeded.add(hasSelf.getProperty().getNamedProperty());
              }
            });
    ontology
        .axioms(AxiomType.OBJECT_PROPERTY_ASSERTION)
        .forEach(axiom -> valued.add(axiom.getProperty().getNamedProperty()));
    valued.addAll(seeded);

    // Values flow from a source to its targets; a property receiving values from any valued
    // source, itself included, is valued too.
    final Map<OWLObjectProperty, Set<OWLObjectProperty>> targets = new HashMap<>();
    sources.forEach(
        (target, from) ->
            from.forEach(
                source -> targets.computeIfAbsent(source, key -> new HashSet<>()).add(target)));
    final Deque<OWLObjectProperty> pending = new ArrayDeque<>(valued);
    while (!pending.isEmpty()) {
      for (final OWLObjectProperty target : targets.getOrDefault(pending.removeFirst(), Set.of())) {
        if (valued.add(target)) {
          pending.add(target);
        }
      }
    }
    final Set<OWLObjectProperty> entailed = new HashSet<>(seeded);
    sources.forEach(
        (target, from) -> {
          if (from.stream().anyMatch(valued::contains)) {
            entailed.add(target);
          }
        });
    return new PropertyValues(false, Set.copyOf(entailed), null);
  }

  /** True when the engine must be asked for this property's values. */
  boolean entailsValues(final OWLObjectProperty property) {
    return everyProperty || entailed.contains(property);
  }

  /** True when some property may have values only an engine can compute. */
  boolean needsEngine() {
    return everyProperty || !entailed.isEmpty();
  }

  /**
   * Why the engine is needed for property values, for the routing decision; null when it is not.
   */
  String reason() {
    if (everyProperty) {
      return reason;
    }
    if (entailed.isEmpty()) {
      return null;
    }
    final TreeSet<String> names = new TreeSet<>();
    entailed.forEach(property -> names.add(Classifier.iri(property)));
    return "entailed values of " + names;
  }

  private static String globalReason(final OWLOntology ontology) {
    final TreeSet<ClassExpressionType> types = new TreeSet<>();
    ontology
        .logicalAxioms()
        .flatMap(OWLAxiom::nestedClassExpressions)
        .map(OWLClassExpression::getClassExpressionType)
        .forEach(types::add);
    if (types.contains(ClassExpressionType.OBJECT_ONE_OF)) {
      return "nominals (ObjectOneOf)";
    }
    if (ontology.anonymousIndividuals().findAny().isPresent()) {
      return "anonymous individuals";
    }
    if (ontology
        .logicalAxioms()
        .flatMap(OWLAxiom::objectPropertiesInSignature)
        .anyMatch(p -> p.isOWLTopObjectProperty() || p.isOWLBottomObjectProperty())) {
      return "owl:topObjectProperty or owl:bottomObjectProperty";
    }
    if (ontology
        .axioms(AxiomType.OBJECT_PROPERTY_ASSERTION)
        .anyMatch(axiom -> axiom.getProperty().isAnonymous())) {
      return "assertions of inverse properties";
    }
    final boolean restrictsToIndividual =
        types.contains(ClassExpressionType.OBJECT_HAS_VALUE)
            || types.contains(ClassExpressionType.OBJECT_HAS_SELF);
    final boolean merges =
        types.contains(ClassExpressionType.OBJECT_MAX_CARDINALITY)
            || types.contains(ClassExpressionType.OBJECT_EXACT_CARDINALITY)
            || ontology.axioms(AxiomType.FUNCTIONAL_OBJECT_PROPERTY).findAny().isPresent()
            || ontology.axioms(AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY).findAny().isPresent();
    if (restrictsToIndividual && merges) {
      return "has-value or has-self restrictions together with functional or maximum-cardinality"
          + " constraints";
    }
    return null;
  }

  private static void link(
      final Map<OWLObjectProperty, Set<OWLObjectProperty>> sources,
      final OWLObjectPropertyExpression source,
      final OWLObjectPropertyExpression target) {
    sources
        .computeIfAbsent(target.getNamedProperty(), key -> new HashSet<>())
        .add(source.getNamedProperty());
  }

  private static void linkAll(
      final Map<OWLObjectProperty, Set<OWLObjectProperty>> sources,
      final List<OWLObjectPropertyExpression> properties) {
    // By position, not by name: InverseObjectProperties(P, P) makes P symmetric, a source of
    // itself.
    for (int i = 0; i < properties.size(); i++) {
      for (int j = 0; j < properties.size(); j++) {
        if (i != j) {
          link(sources, properties.get(i), properties.get(j));
        }
      }
    }
  }
}
