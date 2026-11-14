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
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.DatasetGraphWrapperView;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

/**
 * A published snapshot as a query sees it: read-only, with a default graph that is the union of
 * every graph, and with every quad a scan reads counted against a budget. The union is one index
 * scan with the graph left open, as the RULES step reads its selected graphs. A snapshot's database
 * holds only what queries may read: its witnesses are in a file beside it, not in a graph.
 *
 * <p>It is a {@link DatasetGraphWrapperView}: ARQ otherwise unwraps a wrapper and hands the query
 * to TDB2's own engine over the database, whose scans the budget would not count and whose default
 * graph depends on a server-wide setting.
 */
final class SnapshotView extends DatasetGraphWrapper implements DatasetGraphWrapperView {
  /** Thrown from a scan that passes its view's budget of quads; carries the budget. */
  static final class ScanBudgetExceeded extends RuntimeException {
    private final long budget;

    ScanBudgetExceeded(final long budget) {
      super("The query scanned more than " + budget + " quads", null, false, false);
      this.budget = budget;
    }

    long budget() {
      return budget;
    }
  }

  private final Graph union;
  private final long scanBudget;
  private final AtomicLong scanned = new AtomicLong();

  SnapshotView(final DatasetGraph snapshot) {
    this(snapshot, Long.MAX_VALUE);
  }

  /** A view that fails a scan once it has read {@code scanBudget} quads. */
  SnapshotView(final DatasetGraph snapshot, final long scanBudget) {
    super(snapshot);
    this.scanBudget = scanBudget;
    this.union = new Union();
  }

  private void scan() {
    if (scanned.incrementAndGet() > scanBudget) {
      throw new ScanBudgetExceeded(scanBudget);
    }
  }

  private Iterator<Quad> counted(final Iterator<Quad> quads) {
    if (scanBudget == Long.MAX_VALUE) {
      return quads;
    }
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return quads.hasNext();
      }

      @Override
      public Quad next() {
        try {
          scan();
        } catch (final ScanBudgetExceeded e) {
          Iter.close(quads);
          throw e;
        }
        return quads.next();
      }
    };
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
    final Graph named = super.getGraph(graph);
    return scanBudget == Long.MAX_VALUE ? named : new CountedGraph(named);
  }

  @Override
  public Iterator<Quad> find(final Quad quad) {
    return find(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject());
  }

  @Override
  public Iterator<Quad> find(final Node g, final Node s, final Node p, final Node o) {
    if (g != null && (Quad.isDefaultGraph(g) || Quad.isUnionGraph(g))) {
      return Iter.map(union.find(s, p, o), triple -> Quad.create(Quad.defaultGraphIRI, triple));
    }
    return counted(super.find(g, s, p, o));
  }

  @Override
  public Iterator<Quad> findNG(final Node g, final Node s, final Node p, final Node o) {
    return counted(super.findNG(g, s, p, o));
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

  /** A named graph whose scans count against the budget, as the quad scans do. */
  private final class CountedGraph extends GraphBase {
    private final Graph graph;

    CountedGraph(final Graph graph) {
      this.graph = graph;
    }

    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
      final ExtendedIterator<Triple> triples = graph.find(pattern);
      return new NiceIterator<>() {
        @Override
        public boolean hasNext() {
          return triples.hasNext();
        }

        @Override
        public Triple next() {
          try {
            scan();
          } catch (final ScanBudgetExceeded e) {
            triples.close();
            throw e;
          }
          return triples.next();
        }

        @Override
        public void close() {
          triples.close();
        }
      };
    }
  }

  /**
   * Every graph as one. TDB2 answers a scan with the graph open from an index ending in the graph,
   * so copies of one triple in several graphs arrive together and are passed on once; only a scan
   * with no subject, predicate or object bound can repeat a triple.
   */
  private final class Union extends GraphBase {
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
      final Iterator<Quad> quads =
          counted(
              SnapshotView.super.find(
                  Node.ANY,
                  any(pattern.getSubject()),
                  any(pattern.getPredicate()),
                  any(pattern.getObject())));
      return new NiceIterator<>() {
        private Triple previous;
        private Triple next;

        @Override
        public boolean hasNext() {
          while (next == null && quads.hasNext()) {
            final Triple triple = quads.next().asTriple();
            if (!triple.equals(previous)) {
              next = triple;
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
