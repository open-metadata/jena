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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

/**
 * The facts one pass added, on disk, for the next pass to read as its delta. A delta is written
 * once, read by a few scans and thrown away, so it is a flat file: as a graph of the dataset each
 * fact cost six index insertions and, a pass later, six deletions.
 *
 * <p>The file has no index: every {@code find} on {@link #graph} reads all of it. That is the right
 * cost only where the delta pattern is evaluated once and drives the join ({@link
 * CompiledRule#deltaDrives}); the step evaluates a rule in full instead where it would be looked up
 * once per solution of something else.
 */
final class DeltaSpool {
  private static final int FACT = 1;
  private static final int END = 0;

  private DeltaSpool() {}

  static final class Writer implements AutoCloseable {
    private final SpoolBudget.Counting counting;
    private final DataOutputStream out;

    Writer(final Path file, final SpoolBudget budget) throws IOException {
      this.counting =
          budget.counting(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
      this.out = new DataOutputStream(counting);
    }

    void write(final Triple fact) throws IOException {
      out.writeByte(FACT);
      NodeCodec.write(out, fact.getSubject());
      NodeCodec.write(out, fact.getPredicate());
      NodeCodec.write(out, fact.getObject());
    }

    long bytes() {
      return counting.written();
    }

    @Override
    public void close() throws IOException {
      try {
        out.writeByte(END);
      } finally {
        out.close();
      }
    }
  }

  /** The facts of a finished file as a read-only graph. */
  static Graph graph(final Path file) {
    return new GraphBase() {
      @Override
      protected ExtendedIterator<Triple> graphBaseFind(final Triple pattern) {
        return new Scan(file, pattern);
      }
    };
  }

  /** One pass over the file, giving the facts a pattern matches; it holds the file open. */
  private static final class Scan extends NiceIterator<Triple> {
    private final Node subject;
    private final Node predicate;
    private final Node object;
    private DataInputStream in;
    private Triple next;

    Scan(final Path file, final Triple pattern) {
      this.subject = bound(pattern.getSubject());
      this.predicate = bound(pattern.getPredicate());
      this.object = bound(pattern.getObject());
      try {
        this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** The term a position must equal, or {@code null} when anything matches. */
    private static Node bound(final Node node) {
      return node == null || node == Node.ANY || node.isVariable() ? null : node;
    }

    @Override
    public boolean hasNext() {
      try {
        while (next == null && in != null) {
          if (in.readByte() == END) {
            close();
            break;
          }
          final Node s = NodeCodec.read(in);
          final Node p = NodeCodec.read(in);
          final Node o = NodeCodec.read(in);
          if ((subject == null || subject.equals(s))
              && (predicate == null || predicate.equals(p))
              && (object == null || object.equals(o))) {
            next = Triple.create(s, p, o);
          }
        }
      } catch (final IOException e) {
        close();
        throw new UncheckedIOException(e);
      }
      return next != null;
    }

    @Override
    public Triple next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      final Triple triple = next;
      next = null;
      return triple;
    }

    @Override
    public void close() {
      if (in != null) {
        try {
          in.close();
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        } finally {
          in = null;
        }
      }
    }
  }
}
