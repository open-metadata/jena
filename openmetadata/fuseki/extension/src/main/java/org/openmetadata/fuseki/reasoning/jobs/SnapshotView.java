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

import java.util.Iterator;
import java.util.NoSuchElementException;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.DatasetGraphWrapperView;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphZero;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

/**
 * A published snapshot as a query sees it: read-only, without its provenance graph, and with a
 * default graph that is the union of every other graph. It never relies on TDB2's union default
 * graph, which the image turns on server-wide and which would include the provenance graph; the
 * union is one index scan with the graph left open, as the RULES step reads its selected graphs.
 *
 * <p>It is a {@link DatasetGraphWrapperView}: ARQ otherwise unwraps a wrapper and hands the query
 * to TDB2's own engine over the whole database, which would see the provenance graph.
 */
final class SnapshotView extends DatasetGraphWrapper implements DatasetGraphWrapperView {
  private final Node hidden;
  private final Graph union;

  SnapshotView(final DatasetGraph snapshot, final Node hidden) {
    super(snapshot);
    this.hidden = hidden;
    this.union = new VisibleUnion();
  }

  @Override
  public Graph getDefaultGraph() {
    return union;
  }

  @Override
  public Graph getUnionGraph() {
    return union;
  }

  @Override
  public Graph getGraph(final Node graph) {
    if (Quad.isDefaultGraph(graph) || Quad.isUnionGraph(graph)) {
      return union;
    }
    return graph.equals(hidden) ? GraphZero.instance() : super.getGraph(graph);
  }

  @Override
  public boolean containsGraph(final Node graph) {
    return !graph.equals(hidden) && super.containsGraph(graph);
  }

  @Override
  public Iterator<Node> listGraphNodes() {
    return Iter.filter(super.listGraphNodes(), graph -> !graph.equals(hidden));
  }

  @Override
  public Iterator<Quad> find(final Quad quad) {
    return find(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject());
  }

  @Override
  public Iterator<Quad> find(final Node g, final Node s, final Node p, final Node o) {
    if (g != null && g.equals(hidden)) {
      return Iter.nullIterator();
    }
    if (g != null && (Quad.isDefaultGraph(g) || Quad.isUnionGraph(g))) {
      return Iter.map(union.find(s, p, o), triple -> Quad.create(Quad.defaultGraphIRI, triple));
    }
    return Iter.filter(super.find(g, s, p, o), quad -> !hidden.equals(quad.getGraph()));
  }

  @Override
  public Iterator<Quad> findNG(final Node g, final Node s, final Node p, final Node o) {
    if (g != null && g.equals(hidden)) {
      return Iter.nullIterator();
    }
    return Iter.filter(super.findNG(g, s, p, o), quad -> !hidden.equals(quad.getGraph()));
  }

  @Override
  public boolean contains(final Node g, final Node s, final Node p, final Node o) {
    final Iterator<Quad> found = find(g, s, p, o);
    try {
      return found.hasNext();
    } finally {
      Iter.close(found);
    }
  }

  @Override
  public boolean contains(final Quad quad) {
    return contains(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject());
  }

  @Override
  public void add(final Quad quad) {
    throw readOnly();
  }

  @Override
  public void delete(final Quad quad) {
    throw readOnly();
  }

  @Override
  public void add(final Node g, final Node s, final Node p, final Node o) {
    throw readOnly();
  }

  @Override
  public void delete(final Node g, final Node s, final Node p, final Node o) {
    throw readOnly();
  }

  @Override
  public void deleteAny(final Node g, final Node s, final Node p, final Node o) {
    throw readOnly();
  }

  @Override
  public void addGraph(final Node graphName, final Graph graph) {
    throw readOnly();
  }

  @Override
  public void removeGraph(final Node graphName) {
    throw readOnly();
  }

  @Override
  public void clear() {
    throw readOnly();
  }

  private static UnsupportedOperationException readOnly() {
    return new UnsupportedOperationException("A reasoning snapshot is read-only");
  }

  /**
   * Every graph but the hidden one. TDB2 answers a scan with the graph open from an index ending in
   * the graph, so copies of one triple in several graphs arrive together and are passed on once;
   * only a scan with no subject, predicate or object bound can repeat a triple.
   */
  private final class VisibleUnion extends GraphBase {
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
      final Iterator<Quad> quads =
          SnapshotView.super.find(
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
            if (!hidden.equals(quad.getGraph())) {
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

  private static Node any(final Node node) {
    return node == null || node.isVariable() ? Node.ANY : node;
  }
}
