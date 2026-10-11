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

import java.util.Locale;
import java.util.function.Consumer;
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
 * individuals, their object property values and, from HermiT, their equalities. Anonymous
 * existential witnesses are never materialized. An inconsistent closure has none of these: the
 * consequences of an inconsistent ontology are not useful facts.
 *
 * <p>Facts are not kept here: each is handed to a sink as it is found, so a large closure's output
 * is never held in memory. The classifier records every fact exactly once; this class counts them
 * and refuses an output larger than its limit.
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
    private static final Node SUBCLASS_OF = RDFS.subClassOf.asNode();
    private static final Node EQUIVALENT_CLASS = OWL2.equivalentClass.asNode();
    private static final Node SAME_AS = OWL2.sameAs.asNode();

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

    /** How many triples {@link #triples} persists for this fact. */
    int tripleCount() {
      return switch (kind) {
        case EQUIVALENT -> 4;
        case SAME -> 2;
        default -> 1;
      };
    }

    /**
     * The triples persisted for this fact. Equivalent classes are also written as mutual
     * subclasses, so {@code rdfs:subClassOf*} property paths cross them; unsatisfiable classes are
     * written equivalent to {@code owl:Nothing}.
     */
    void triples(final Consumer<Triple> out) {
      final Node one = NodeFactory.createURI(first);
      final Node other = second == null ? null : NodeFactory.createURI(second);
      switch (kind) {
        case SUBCLASS -> out.accept(Triple.create(one, SUBCLASS_OF, other));
        case EQUIVALENT -> {
          out.accept(Triple.create(one, EQUIVALENT_CLASS, other));
          out.accept(Triple.create(other, EQUIVALENT_CLASS, one));
          out.accept(Triple.create(one, SUBCLASS_OF, other));
          out.accept(Triple.create(other, SUBCLASS_OF, one));
        }
        case UNSATISFIABLE ->
            out.accept(Triple.create(one, EQUIVALENT_CLASS, OWL2.Nothing.asNode()));
        case TYPE -> out.accept(Triple.create(one, RDF.type.asNode(), other));
        case VALUE -> out.accept(Triple.create(one, other, NodeFactory.createURI(third)));
        case SAME -> {
          out.accept(Triple.create(one, SAME_AS, other));
          out.accept(Triple.create(other, SAME_AS, one));
        }
      }
    }
  }

  private final boolean consistent;
  private final Consumer<Fact> sink;
  private final long maxTriples;
  private final long[] counts = new long[Kind.values().length];
  private long triples;

  private Classification(
      final boolean consistent, final Consumer<Fact> sink, final long maxTriples) {
    this.consistent = consistent;
    this.sink = sink;
    this.maxTriples = maxTriples;
  }

  /**
   * @param maxTriples the most triples the facts may persist; one more throws {@link
   *     OutputTooLarge}, so a huge output is refused as soon as it is known
   */
  static Classification consistent(final Consumer<Fact> sink, final long maxTriples) {
    return new Classification(true, sink, maxTriples);
  }

  static Classification inconsistent() {
    return new Classification(false, fact -> {}, 0);
  }

  boolean isConsistent() {
    return consistent;
  }

  void add(final Kind kind, final String first, final String second, final String third) {
    if (!consistent) {
      throw new IllegalStateException("An inconsistent closure has no entailments to record");
    }
    final Fact fact = new Fact(kind, first, second, third);
    triples += fact.tripleCount();
    if (triples > maxTriples) {
      throw new OutputTooLarge(maxTriples);
    }
    counts[kind.ordinal()]++;
    sink.accept(fact);
  }

  /** Records an unordered pair in a canonical order, so the result never depends on iteration. */
  void addPair(final Kind kind, final String one, final String other) {
    if (one.compareTo(other) <= 0) {
      add(kind, one, other, null);
    } else {
      add(kind, other, one, null);
    }
  }

  long count(final Kind kind) {
    return counts[kind.ordinal()];
  }

  JsonObject countsJson() {
    final JsonObject json = new JsonObject();
    json.put("consistent", consistent);
    for (final Kind kind : Kind.values()) {
      json.put(kind.name().toLowerCase(Locale.ROOT), count(kind));
    }
    return json;
  }

  /** Unchecked, to leave lambdas; the step turns it into a rejection. */
  static final class OutputTooLarge extends RuntimeException {
    OutputTooLarge(final long limit) {
      super("More than " + limit + " entailment triples");
    }
  }
}
