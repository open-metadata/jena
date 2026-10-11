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

import java.util.Comparator;
import java.util.List;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Problem;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLNaryBooleanClassExpression;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLQuantifiedObjectRestriction;
import org.semanticweb.owlapi.profiles.OWL2DLProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;

/**
 * Admission of one parsed closure: the size limits first, then OWL API's OWL 2 DL profile check
 * over the whole closure, which catches what per-axiom checks cannot see, such as a transitive
 * property in a cardinality restriction or an irregular property chain. A closure outside OWL 2 DL
 * is rejected with the violations; it is never classified with a construct dropped.
 */
record Admission(
    long logicalAxioms,
    long classes,
    long objectProperties,
    long dataProperties,
    long individuals,
    long expressionDepth) {

  static Admission check(final OWLOntology ontology, final Limits limits)
      throws InputRejectedException {
    final long logicalAxioms = ontology.getLogicalAxiomCount();
    if (logicalAxioms > limits.maxLogicalAxioms()) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "TOO_MANY_AXIOMS",
          logicalAxioms + " logical axioms, over the limit of " + limits.maxLogicalAxioms());
    }
    final long individuals = ontology.individualsInSignature().count();
    if (individuals > limits.maxIndividuals()) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "TOO_MANY_INDIVIDUALS",
          individuals + " named individuals, over the limit of " + limits.maxIndividuals());
    }
    final long depth =
        ontology
            .logicalAxioms()
            .flatMap(axiom -> axiom.nestedClassExpressions())
            .mapToLong(Admission::depth)
            .max()
            .orElse(0);
    if (depth > limits.maxExpressionDepth()) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "EXPRESSION_TOO_DEEP",
          "A class expression nests "
              + depth
              + " deep, over the limit of "
              + limits.maxExpressionDepth());
    }
    final OWLProfileReport report = new OWL2DLProfile().checkOntology(ontology);
    if (!report.isInProfile()) {
      final List<Problem> problems =
          report.getViolations().stream()
              .sorted(Comparator.comparing(OWLProfileViolation::toString))
              .limit(Problem.MAX_REPORTED)
              .map(
                  violation ->
                      new Problem(violation.getClass().getSimpleName(), violation.toString()))
              .toList();
      throw new InputRejectedException("NOT_OWL2_DL", problems, report.getViolations().size());
    }
    return new Admission(
        logicalAxioms,
        ontology.classesInSignature().count(),
        ontology.objectPropertiesInSignature().count(),
        ontology.dataPropertiesInSignature().count(),
        individuals,
        depth);
  }

  /** Nesting depth of a class expression; a named class is 0. */
  static long depth(final OWLClassExpression expression) {
    if (expression instanceof OWLNaryBooleanClassExpression nary) {
      return 1 + nary.operands().mapToLong(Admission::depth).max().orElse(0);
    }
    if (expression instanceof OWLObjectComplementOf complement) {
      return 1 + depth(complement.getOperand());
    }
    if (expression instanceof OWLQuantifiedObjectRestriction restriction) {
      return 1 + depth(restriction.getFiller());
    }
    return expression.isAnonymous() ? 1 : 0;
  }

  JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("logicalAxioms", logicalAxioms);
    json.put("classes", classes);
    json.put("objectProperties", objectProperties);
    json.put("dataProperties", dataProperties);
    json.put("individuals", individuals);
    json.put("expressionDepth", expressionDepth);
    return json;
  }
}
