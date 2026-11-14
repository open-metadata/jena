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

import java.util.ArrayList;
import java.util.List;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * Writes the question of a CHECK or EXPLAIN request as one OWL 2 functional-syntax axiom, which the
 * worker parses with OWL API and checks against the closure: every entity it names must be in the
 * closure with that type, or the worker answers the question INVALID instead of NOT_ENTAILED. A
 * request has been validated against the schema before it gets here; what the schema allows but OWL
 * cannot express, such as an intersection without operands, is refused with {@link Inexpressible}.
 */
final class FunctionalSyntax {
  static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
  static final String OWL = "http://www.w3.org/2002/07/owl#";
  private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";
  private static final String NOTHING = "owl:Nothing";

  /** The request asks something OWL 2 cannot state. */
  static final class Inexpressible extends Exception {
    Inexpressible(final String message) {
      super(message);
    }
  }

  private FunctionalSyntax() {}

  /**
   * The axiom whose entailment answers a check, as rdfReasoningJob.json defines each outcome; null
   * for CONSISTENCY, which classification answers without one.
   */
  static String check(final JsonObject check) throws Inexpressible {
    return switch (check.getString("kind")) {
      case "CONSISTENCY" -> null;
      case "SATISFIABILITY" ->
          "SubClassOf(" + expression(check.getObj("classExpression")) + " " + NOTHING + ")";
      case "SUBSUMPTION" ->
          "SubClassOf("
              + expression(check.getObj("classExpression"))
              + " "
              + expression(check.getObj("superClassExpression"))
              + ")";
      case "ENTAILMENT" -> statement(check.getObj("statement"));
      default -> throw new Inexpressible("Unknown check kind " + check.getString("kind"));
    };
  }

  /**
   * An RDF statement as the axiom it asserts under the OWL 2 RDF mapping. The schema and class
   * vocabulary map to their axioms; any other predicate is an object or data property assertion, by
   * the object's kind. rdfs:subPropertyOf is read as between object properties.
   */
  static String statement(final JsonObject statement) throws Inexpressible {
    final String subject = iri(statement.getString("subjectIri"));
    final String predicate = statement.getString("predicateIri");
    final boolean literal = "LITERAL".equals(statement.getString("objectKind"));
    if (literal) {
      if (!statement.hasKey("literalValue")) {
        throw new Inexpressible("A LITERAL statement needs literalValue");
      }
      return "DataPropertyAssertion("
          + iri(predicate)
          + " "
          + subject
          + " "
          + literal(
              statement.getString("literalValue"),
              optional(statement, "datatypeIri"),
              optional(statement, "language"))
          + ")";
    }
    if (!statement.hasKey("objectIri")) {
      throw new Inexpressible("An IRI statement needs objectIri");
    }
    final String object = iri(statement.getString("objectIri"));
    return switch (predicate) {
      case RDF + "type" -> "ClassAssertion(" + object + " " + subject + ")";
      case RDFS + "subClassOf" -> "SubClassOf(" + subject + " " + object + ")";
      case OWL + "equivalentClass" -> "EquivalentClasses(" + subject + " " + object + ")";
      case OWL + "disjointWith" -> "DisjointClasses(" + subject + " " + object + ")";
      case RDFS + "subPropertyOf" -> "SubObjectPropertyOf(" + subject + " " + object + ")";
      case OWL + "equivalentProperty" ->
          "EquivalentObjectProperties(" + subject + " " + object + ")";
      case OWL + "inverseOf" -> "InverseObjectProperties(" + subject + " " + object + ")";
      case OWL + "sameAs" -> "SameIndividual(" + subject + " " + object + ")";
      case OWL + "differentFrom" -> "DifferentIndividuals(" + subject + " " + object + ")";
      default -> "ObjectPropertyAssertion(" + iri(predicate) + " " + subject + " " + object + ")";
    };
  }

  static String expression(final JsonObject expression) throws Inexpressible {
    return switch (expression.getString("kind")) {
      case "NAMED_CLASS" -> iri(required(expression, "classIri"));
      case "INTERSECTION" -> nary("ObjectIntersectionOf", operands(expression));
      case "UNION" -> nary("ObjectUnionOf", operands(expression));
      case "ONE_OF" -> {
        final List<String> individuals = new ArrayList<>();
        final JsonValue iris = expression.get("individualIris");
        if (iris == null || iris.getAsArray().isEmpty()) {
          throw new Inexpressible("ONE_OF needs at least one of individualIris");
        }
        for (final JsonValue individual : iris.getAsArray()) {
          individuals.add(iri(individual.getAsString().value()));
        }
        yield "ObjectOneOf(" + String.join(" ", individuals) + ")";
      }
      case "RESTRICTION" -> restriction(expression);
      default -> throw new Inexpressible("Unknown expression kind " + expression.getString("kind"));
    };
  }

  private static String restriction(final JsonObject expression) throws Inexpressible {
    final String property = iri(required(expression, "propertyIri"));
    final String kind = required(expression, "restrictionKind");
    final String filler =
        expression.hasKey("filler") ? expression(expression.getObj("filler")) : "owl:Thing";
    return switch (kind) {
      case "SOME" -> "ObjectSomeValuesFrom(" + property + " " + filler + ")";
      case "ONLY" -> "ObjectAllValuesFrom(" + property + " " + filler + ")";
      case "VALUE" -> {
        if (expression.hasKey("individualIri")) {
          yield "ObjectHasValue("
              + property
              + " "
              + iri(expression.getString("individualIri"))
              + ")";
        }
        if (expression.hasKey("literal")) {
          final JsonObject value = expression.getObj("literal");
          yield "DataHasValue("
              + property
              + " "
              + literal(value.getString("value"), optional(value, "datatypeIri"), null)
              + ")";
        }
        throw new Inexpressible("A VALUE restriction needs individualIri or literal");
      }
      case "MIN", "MAX", "EXACT" -> {
        if (!expression.hasKey("cardinality")) {
          throw new Inexpressible("A " + kind + " restriction needs cardinality");
        }
        final String constructor =
            switch (kind) {
              case "MIN" -> "ObjectMinCardinality";
              case "MAX" -> "ObjectMaxCardinality";
              default -> "ObjectExactCardinality";
            };
        yield constructor
            + "("
            + expression.getNumber("cardinality").longValue()
            + " "
            + property
            + (expression.hasKey("filler") ? " " + filler : "")
            + ")";
      }
      default -> throw new Inexpressible("Unknown restriction kind " + kind);
    };
  }

  private static List<String> operands(final JsonObject expression) throws Inexpressible {
    final JsonValue value = expression.get("operands");
    final JsonArray array = value == null ? new JsonArray() : value.getAsArray();
    if (array.isEmpty()) {
      throw new Inexpressible(expression.getString("kind") + " needs at least one operand");
    }
    final List<String> operands = new ArrayList<>();
    for (final JsonValue operand : array) {
      operands.add(expression(operand.getAsObject()));
    }
    return operands;
  }

  /** OWL needs two operands; one operand is that operand itself. */
  private static String nary(final String constructor, final List<String> operands) {
    return operands.size() == 1
        ? operands.get(0)
        : constructor + "(" + String.join(" ", operands) + ")";
  }

  /** The request validator admits only absolute URIs, which cannot contain '>' or spaces. */
  private static String iri(final String iri) {
    return "<" + iri + ">";
  }

  private static String literal(final String value, final String datatype, final String language) {
    final StringBuilder text = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        text.append('\\');
      }
      text.append(c);
    }
    text.append('"');
    if (language != null && !language.isEmpty()) {
      return text.append('@').append(language).toString();
    }
    return text.append("^^").append(iri(datatype == null ? XSD_STRING : datatype)).toString();
  }

  private static String required(final JsonObject json, final String key) throws Inexpressible {
    if (!json.hasKey(key)) {
      throw new Inexpressible(
          "A " + json.getString("kind") + " expression needs " + key + " to be stated in OWL");
    }
    return json.getString(key);
  }

  private static String optional(final JsonObject json, final String key) {
    return json.hasKey(key) ? json.getString(key) : null;
  }
}
