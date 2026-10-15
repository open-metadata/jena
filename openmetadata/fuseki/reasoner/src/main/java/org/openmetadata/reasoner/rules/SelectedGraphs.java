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
package org.openmetadata.reasoner.rules;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

/**
 * The graphs a rule reads, as one read-only graph over the working dataset: the triples of a set of
 * named graphs, without building a union in memory. Each lookup is one TDB2 index scan with the
 * graph left open; TDB2 then uses an index that ends in the graph, so copies of a triple in several
 * selected graphs arrive next to each other and are passed on once. Copies the scan does not
 * deliver together are passed on again, which only repeats a solution.
 */
final class SelectedGraphs extends GraphBase {
  private final DatasetGraph dataset;
  private final Set<Node> graphs;

  SelectedGraphs(final DatasetGraph dataset, final Set<Node> graphs) {
    this.dataset = dataset;
    this.graphs = Set.copyOf(graphs.stream().map(SelectedGraphs::normalize).toList());
  }

  /** The dataset a rule's pattern is evaluated over: these graphs as its default graph. */
  static DatasetGraph evaluation(
      final DatasetGraph dataset, final Set<Node> graphs, final Node delta) {
    final DatasetGraph evaluation =
        DatasetGraphFactory.createGeneral(new SelectedGraphs(dataset, graphs));
    if (delta != null) {
      evaluation.addGraph(delta, dataset.getGraph(delta));
    }
    return evaluation;
  }

  static Node normalize(final Node graph) {
    return Quad.isDefaultGraph(graph) ? Quad.defaultGraphIRI : graph;
  }

  /** The graphs of the selection holding this triple, scanned once. */
  Iterator<Node> graphsOf(final Triple triple) {
    final Iterator<Quad> quads =
        dataset.find(Node.ANY, triple.getSubject(), triple.getPredicate(), triple.getObject());
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return quads.hasNext();
      }

      @Override
      public Node next() {
        return normalize(quads.next().getGraph());
      }
    };
  }

  boolean selects(final Node graph) {
    return graphs.contains(normalize(graph));
  }

  @Override
  protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
    final Iterator<Quad> quads =
        dataset.find(
            Node.ANY,
            any(pattern.getSubject()),
            any(pattern.getPredicate()),
            any(pattern.getObject()));
    return new NiceIterator<>() {
      private Triple previous;
      private Triple next;

      @Override
      public boolean hasNext() {
        while (next == null && quads.hasNext()) {
          final Quad quad = quads.next();
          if (graphs.contains(normalize(quad.getGraph()))) {
            final Triple triple = quad.asTriple();
            if (!triple.equals(previous)) {
              next = triple;
            }
          }
        }
        return next != null;
      }

      @Override
      public Triple next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        previous = next;
        next = null;
        return previous;
      }

      @Override
      public void close() {
        Iter.close(quads);
      }
    };
  }

  private static Node any(final Node node) {
    return node == null || node.isVariable() ? Node.ANY : node;
  }

  /** Counted by a scan; rule evaluation never asks, so this only serves diagnostics. */
  @Override
  protected int graphBaseSize() {
    final ExtendedIterator<Triple> all = graphBaseFind(Triple.ANY);
    try {
      int size = 0;
      while (all.hasNext() && size < Integer.MAX_VALUE) {
        all.next();
        size++;
      }
      return size;
    } finally {
      all.close();
    }
  }
}
