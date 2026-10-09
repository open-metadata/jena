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
import java.util.TreeSet;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.reasoner.InputRejectedException;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.ClassExpressionType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.profiles.OWL2ELProfile;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;

/**
 * Chooses the engine for one closure. A closure goes to ELK only when it is in the OWL 2 EL profile
 * and uses nothing for which ELK 0.6.0 reports possibly incomplete results (its
 * TopIncompletenessMonitor), and when the outputs do not need entailments ELK does not compute
 * (property assertions and individual equality). Everything else goes to HermiT. Nothing is ever
 * dropped to make a closure fit ELK; and if ELK still reports incompleteness at run time, the
 * classifier falls back to HermiT.
 */
final class EngineRouter {
  /** The routing decision, kept in the manifest. */
  record Route(Engine engine, boolean elProfile, String reason) {
    JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("engine", engine.displayName());
      json.put("profile", elProfile ? "OWL2_EL" : "OWL2_DL");
      json.put("reason", reason);
      return json;
    }
  }

  private EngineRouter() {}

  static Route route(final OWLOntology ontology, final EngineChoice choice)
      throws InputRejectedException {
    final OWLProfileReport el = new OWL2ELProfile().checkOntology(ontology);
    final List<String> gaps = elkGaps(ontology);
    return switch (choice) {
      case HERMIT -> new Route(Engine.HERMIT, el.isInProfile(), "HermiT requested");
      case ELK -> {
        if (!el.isInProfile()) {
          throw InputRejectedException.of(
              "ENGINE_UNSUITABLE",
              "NOT_OWL2_EL",
              "ELK requested for a closure outside OWL 2 EL: " + firstViolation(el));
        }
        if (!gaps.isEmpty()) {
          throw InputRejectedException.of(
              "ENGINE_UNSUITABLE",
              "ELK_UNSUPPORTED",
              "ELK requested, but it does not support " + gaps);
        }
        yield new Route(Engine.ELK, true, "ELK requested");
      }
      case AUTO -> {
        if (!el.isInProfile()) {
          yield new Route(
              Engine.HERMIT, false, "Outside the OWL 2 EL profile: " + firstViolation(el));
        }
        if (!gaps.isEmpty()) {
          yield new Route(Engine.HERMIT, true, "In OWL 2 EL, but ELK does not support " + gaps);
        }
        yield new Route(Engine.ELK, true, "In OWL 2 EL, using only constructs ELK supports");
      }
    };
  }

  /** What an OWL 2 EL closure uses that ELK would answer incompletely, or not at all. */
  static List<String> elkGaps(final OWLOntology ontology) {
    final TreeSet<String> gaps = new TreeSet<>();
    // Only logical axioms count: ELK ignores annotations, whose literals also carry datatypes.
    if (ontology
            .logicalAxioms()
            .anyMatch(
                axiom ->
                    axiom.dataPropertiesInSignature().findAny().isPresent()
                        || axiom.datatypesInSignature().findAny().isPresent())
        || ontology.axioms(AxiomType.DATATYPE_DEFINITION).findAny().isPresent()) {
      gaps.add("data properties and datatypes");
    }
    if (ontology.axioms(AxiomType.HAS_KEY).findAny().isPresent()) {
      gaps.add("keys (HasKey)");
    }
    if (ontology.anonymousIndividuals().findAny().isPresent()) {
      gaps.add("anonymous individuals");
    }
    if (ontology
        .objectPropertiesInSignature()
        .anyMatch(p -> p.isOWLTopObjectProperty() || p.isOWLBottomObjectProperty())) {
      gaps.add("owl:topObjectProperty or owl:bottomObjectProperty");
    }
    final TreeSet<ClassExpressionType> expressions = new TreeSet<>();
    ontology
        .logicalAxioms()
        .flatMap(axiom -> axiom.nestedClassExpressions())
        .map(expression -> expression.getClassExpressionType())
        .forEach(expressions::add);
    // ELK 0.6.0 does not support ObjectOneOf, supports ObjectHasValue only without property
    // ranges, and ObjectHasSelf only in superclass position. HermiT handles all of them, so a
    // closure using any of them goes there rather than relying on those distinctions.
    for (final ClassExpressionType type :
        List.of(
            ClassExpressionType.OBJECT_ONE_OF,
            ClassExpressionType.OBJECT_HAS_VALUE,
            ClassExpressionType.OBJECT_HAS_SELF)) {
      if (expressions.contains(type)) {
        gaps.add(type.getName());
      }
    }
    if (ontology.axioms(AxiomType.OBJECT_PROPERTY_ASSERTION).findAny().isPresent()) {
      gaps.add("object property assertions, whose entailments ELK does not compute");
    }
    if (ontology.axioms(AxiomType.SAME_INDIVIDUAL).findAny().isPresent()
        || ontology.axioms(AxiomType.DIFFERENT_INDIVIDUALS).findAny().isPresent()) {
      gaps.add("individual equality or inequality");
    }
    return List.copyOf(gaps);
  }

  private static String firstViolation(final OWLProfileReport report) {
    return report.getViolations().stream()
        .map(OWLProfileViolation::toString)
        .min(Comparator.naturalOrder())
        .orElse("unknown violation");
  }
}
