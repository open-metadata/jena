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
import java.util.TreeSet;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * The finite, named entailments of one consistent closure, independent of the engine that computed
 * them: the direct class hierarchy, equivalences, unsatisfiable classes, the direct types of named
 * individuals and, from HermiT, their object property values and equalities. Anonymous existential
 * witnesses are never materialized. An inconsistent closure has none of these: the consequences of
 * an inconsistent ontology are not useful facts.
 */
final class Classification {
  enum Kind {
    SUBCLASS,
    EQUIVALENT,
    UNSATISFIABLE,
    TYPE,
    VALUE,
    SAME
  }

  /** One entailment over named entities, identified by IRIs. */
  record Fact(Kind kind, String first, String second, String third) {
    String line() {
      final StringBuilder line =
          new StringBuilder(kind.name()).append(" <").append(first).append('>');
      if (second != null) {
        line.append(" <").append(second).append('>');
      }
      if (third != null) {
        line.append(" <").append(third).append('>');
      }
      return line.toString();
    }
  }

  private static final Comparator<Fact> ORDER =
      Comparator.comparing(Fact::kind)
          .thenComparing(Fact::first)
          .thenComparing(Fact::second, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(Fact::third, Comparator.nullsFirst(Comparator.naturalOrder()));

  private final boolean consistent;
  private final TreeSet<Fact> facts = new TreeSet<>(ORDER);

  private Classification(final boolean consistent) {
    this.consistent = consistent;
  }

  static Classification consistent() {
    return new Classification(true);
  }

  static Classification inconsistent() {
    return new Classification(false);
  }

  boolean isConsistent() {
    return consistent;
  }

  void add(final Kind kind, final String first, final String second, final String third) {
    if (!consistent) {
      throw new IllegalStateException("An inconsistent closure has no entailments to record");
    }
    facts.add(new Fact(kind, first, second, third));
  }

  /** Records an unordered pair once, so the result never depends on iteration order. */
  void addPair(final Kind kind, final String one, final String other) {
    if (one.compareTo(other) <= 0) {
      add(kind, one, other, null);
    } else {
      add(kind, other, one, null);
    }
  }

  long count(final Kind kind) {
    return facts.stream().filter(fact -> fact.kind() == kind).count();
  }

  List<String> lines() {
    return facts.stream().map(Fact::line).toList();
  }

  /**
   * The triples persisted for this closure. Equivalent classes are also written as mutual
   * subclasses, so {@code rdfs:subClassOf*} property paths cross them; unsatisfiable classes are
   * written equivalent to {@code owl:Nothing}.
   */
  List<Triple> triples() {
    final List<Triple> triples = new ArrayList<>();
    final Node subClassOf = RDFS.subClassOf.asNode();
    final Node equivalentClass = OWL2.equivalentClass.asNode();
    final Node sameAs = OWL2.sameAs.asNode();
    for (final Fact fact : facts) {
      final Node first = NodeFactory.createURI(fact.first());
      final Node second = fact.second() == null ? null : NodeFactory.createURI(fact.second());
      switch (fact.kind()) {
        case SUBCLASS -> triples.add(Triple.create(first, subClassOf, second));
        case EQUIVALENT -> {
          triples.add(Triple.create(first, equivalentClass, second));
          triples.add(Triple.create(second, equivalentClass, first));
          triples.add(Triple.create(first, subClassOf, second));
          triples.add(Triple.create(second, subClassOf, first));
        }
        case UNSATISFIABLE ->
            triples.add(Triple.create(first, equivalentClass, OWL2.Nothing.asNode()));
        case TYPE -> triples.add(Triple.create(first, RDF.type.asNode(), second));
        case VALUE ->
            triples.add(Triple.create(first, second, NodeFactory.createURI(fact.third())));
        case SAME -> {
          triples.add(Triple.create(first, sameAs, second));
          triples.add(Triple.create(second, sameAs, first));
        }
      }
    }
    return triples;
  }

  JsonObject countsJson() {
    final JsonObject json = new JsonObject();
    json.put("consistent", consistent);
    for (final Kind kind : Kind.values()) {
      json.put(kind.name().toLowerCase(java.util.Locale.ROOT), count(kind));
    }
    return json;
  }
}
