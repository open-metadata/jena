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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;

/**
 * One pass's rule solutions on disk, written while the pass reads the dataset and read back while
 * it writes the conclusions, so neither the solutions nor the candidate facts are ever held in
 * memory and no read transaction is upgraded to a write. A record is the rule's index and its
 * witness variables' values; the template is instantiated when the record is applied. The file is
 * bounded: writing past {@code maxBytes} fails with {@link Full}.
 */
final class SolutionSpool {
  private static final int RECORD = 1;
  private static final int END = 0;

  private SolutionSpool() {}

  /** The spool reached its size limit. */
  static final class Full extends IOException {
    Full(final long maxBytes) {
      super("The pass's solutions exceed the spool limit of " + maxBytes + " bytes");
    }
  }

  static final class Writer implements AutoCloseable {
    private final Counting counting;
    private final DataOutputStream out;
    private final long maxBytes;
    private long records;

    Writer(final Path file, final long maxBytes) throws IOException {
      this.counting = new Counting(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
      this.out = new DataOutputStream(counting);
      this.maxBytes = maxBytes;
    }

    void write(final CompiledRule rule, final Binding solution) throws IOException {
      out.writeByte(RECORD);
      out.writeInt(rule.index());
      for (final Var var : rule.witnessVars()) {
        NodeCodec.write(out, solution.get(var));
      }
      records++;
      if (counting.bytes > maxBytes) {
        throw new Full(maxBytes);
      }
    }

    long records() {
      return records;
    }

    long bytes() {
      return counting.bytes;
    }

    @Override
    public void close() throws IOException {
      out.writeByte(END);
      out.close();
    }
  }

  /** Reads the records back in order; each is a rule index and its solution. */
  static final class Reader implements AutoCloseable {
    private final DataInputStream in;
    private final List<CompiledRule> rules;

    Reader(final Path file, final List<CompiledRule> rules) throws IOException {
      this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
      this.rules = rules;
    }

    /** The next record, or {@code null} after the last one. */
    Record next() throws IOException {
      if (in.readByte() == END) {
        return null;
      }
      final CompiledRule rule = rules.get(in.readInt());
      final BindingBuilder builder = BindingBuilder.create();
      for (final Var var : rule.witnessVars()) {
        final Node value = NodeCodec.read(in);
        if (value != null) {
          builder.add(var, value);
        }
      }
      return new Record(rule, builder.build());
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }

  record Record(CompiledRule rule, Binding solution) {}

  private static final class Counting extends FilterOutputStream {
    private long bytes;

    Counting(final OutputStream out) {
      super(out);
    }

    @Override
    public void write(final int b) throws IOException {
      out.write(b);
      bytes++;
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int length) throws IOException {
      out.write(buffer, offset, length);
      bytes += length;
    }
  }
}
