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
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;

/**
 * The provenance of a RULES step: one witness per derived fact and a record of the converged run,
 * in one file beside the working dataset. It is not RDF in the dataset, so the rules cannot read it
 * and no query can see it; only explanations read it, and always by fact.
 *
 * <p>A fact's witness is found from the fact alone: its key is a digest of the output graph and the
 * triple, and the index is sorted by key. The witness names the solution that first derived the
 * fact: the rule, the pass and the value of each of the rule's variables; the premises are the
 * rule's matched patterns instantiated with them. Facts derived from one solution share it.
 *
 * <pre>
 * magic
 * solutions   rule index, pass and one term per variable of the rule, in the order derived
 * index       witness key (16 bytes) and solution offset (8 bytes), sorted by key
 * run         JSON: passes, the graphs read, and each enabled rule's name, body, digest, output
 *             graph and variables
 * trailer     where the index and the run record are, and the closing magic
 * </pre>
 *
 * <p>A witness costs its 24-byte index entry and its share of a solution, whose terms are written
 * out in full. In the dataset the same witness was three or more quads in six indexes, about 2.6 KB
 * a derived fact, which every query then had to scan past.
 *
 * <p>The file is built under a temporary name and takes its own name only when the step has
 * converged, so a step that does not converge leaves no file that explanations would trust.
 */
public final class Provenance {
  static final String PREFIX = "urn:openmetadata:reasoner:";
  static final String CONVERGED = "CONVERGED";
  static final int KEY_BYTES = 16;
  static final int ENTRY_BYTES = KEY_BYTES + Long.BYTES;
  private static final byte[] MAGIC = "OMPROV1\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CLOSING = "\nOMPROV1".getBytes(StandardCharsets.US_ASCII);
  private static final int TRAILER_BYTES = 3 * Long.BYTES + Integer.BYTES + CLOSING.length;

  private Provenance() {}

  static Node deltaGraph(final int pass) {
    return NodeFactory.createURI(PREFIX + "delta:" + pass);
  }

  /** A run as its record describes it. */
  record Run(int passes, List<Node> read, Map<String, RuleRecord> rules) {}

  /**
   * @param vars the rule's variables in the order a solution lists their values, recorded so that a
   *     solution is read back without compiling the rule
   */
  record RuleRecord(String name, String body, String digest, Node output, List<Var> vars) {}

  /** A solution as its witness records it: the rule's name, the pass and the variables' values. */
  record Solution(String rule, int pass, Binding binding) {}

  /** Writes the file as the passes apply their conclusions; {@link #finish} completes it. */
  static final class Writer implements AutoCloseable {
    private final Path target;
    private final Path file;
    private final Path entriesFile;
    private final Path sortFile;
    private final FileChannel channel;
    private final SpoolBudget.Counting counting;
    private final DataOutputStream out;
    private final SpoolBudget.Counting entriesCounting;
    private final DataOutputStream entries;
    private long witnesses;
    private boolean finished;

    Writer(final Path target, final SpoolBudget budget) throws IOException {
      this.target = target;
      this.file = sibling(target, ".tmp");
      this.entriesFile = sibling(target, ".index.tmp");
      this.sortFile = sibling(target, ".sort.tmp");
      this.channel =
          FileChannel.open(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      this.counting =
          budget.counting(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16));
      this.out = new DataOutputStream(counting);
      this.entriesCounting =
          budget.counting(new BufferedOutputStream(Files.newOutputStream(entriesFile), 1 << 16));
      this.entries = new DataOutputStream(entriesCounting);
      out.write(MAGIC);
    }

    private static Path sibling(final Path target, final String suffix) {
      return target.resolveSibling(target.getFileName() + suffix);
    }

    /** Records a solution that derives a fact and returns where its witnesses find it. */
    long solution(final CompiledRule rule, final int pass, final Binding solution)
        throws IOException {
      final long offset = counting.written();
      out.writeInt(rule.index());
      out.writeInt(pass);
      for (final Var var : rule.witnessVars()) {
        NodeCodec.write(out, solution.get(var));
      }
      return offset;
    }

    /**
     * Records that {@code fact} in {@code graph} was first derived by the solution at an offset.
     */
    void witness(final Node graph, final Triple fact, final long solution) throws IOException {
      entries.write(key(graph, fact));
      entries.writeLong(solution);
      witnesses++;
    }

    long bytes() {
      return counting.written();
    }

    /**
     * Completes the file for a converged run: sorts the index, records the graphs the rules read
     * and every enabled rule's definition, forces the file to disk and gives it its name.
     */
    void finish(final List<Node> read, final List<CompiledRule> rules, final int passes)
        throws IOException {
      entries.close();
      final long indexOffset = counting.written();
      RecordSorter.sort(entriesFile, ENTRY_BYTES, RecordSorter.RUN_RECORDS, sortFile, out);
      final long runOffset = counting.written();
      final byte[] run = JSON.toString(run(read, rules, passes)).getBytes(StandardCharsets.UTF_8);
      out.write(run);
      out.writeLong(indexOffset);
      out.writeLong(witnesses);
      out.writeLong(runOffset);
      out.writeInt(run.length);
      out.write(CLOSING);
      out.flush();
      channel.force(true);
      out.close();
      Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      finished = true;
    }

    private static JsonObject run(
        final List<Node> read, final List<CompiledRule> rules, final int passes) {
      final JsonObject run = new JsonObject();
      run.put("status", CONVERGED);
      run.put("passes", passes);
      final JsonArray graphs = new JsonArray();
      read.forEach(graph -> graphs.add(graph.getURI()));
      run.put("read", graphs);
      final JsonArray array = new JsonArray();
      for (final CompiledRule rule : rules) {
        final JsonObject json = new JsonObject();
        json.put("name", rule.name());
        json.put("body", rule.body());
        json.put("digest", rule.digest());
        json.put("output", rule.output().getURI());
        final JsonArray vars = new JsonArray();
        rule.witnessVars().forEach(var -> vars.add(var.getVarName()));
        json.put("vars", vars);
        array.add(json);
      }
      run.put("rules", array);
      return run;
    }

    /** Closes the files and removes what an unfinished run wrote. */
    @Override
    public void close() throws IOException {
      try {
        entries.close();
      } finally {
        try {
          out.close();
        } finally {
          Files.deleteIfExists(entriesFile);
          Files.deleteIfExists(sortFile);
          if (!finished) {
            Files.deleteIfExists(file);
          }
        }
      }
    }
  }

  /** Reads witnesses by fact from a finished file. */
  static final class Reader implements AutoCloseable {
    private final FileChannel channel;
    private final long indexOffset;
    private final long witnesses;
    private final Run run;
    private final List<RuleRecord> rules;

    private Reader(
        final FileChannel channel, final long indexOffset, final long witnesses, final Run run) {
      this.channel = channel;
      this.indexOffset = indexOffset;
      this.witnesses = witnesses;
      this.run = run;
      this.rules = List.copyOf(run.rules().values());
    }

    /** Opens the provenance of a converged run, or returns {@code null} if there is no file. */
    static Reader open(final Path file) throws IOException {
      if (!Files.isRegularFile(file)) {
        return null;
      }
      final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
      try {
        final long size = channel.size();
        if (size < MAGIC.length + TRAILER_BYTES) {
          throw new IOException(file + " is not a provenance file: it is too short");
        }
        final ByteBuffer trailer = ByteBuffer.allocate(TRAILER_BYTES);
        read(channel, trailer, size - TRAILER_BYTES);
        final byte[] closing = new byte[CLOSING.length];
        final ByteBuffer magic = ByteBuffer.allocate(MAGIC.length);
        read(channel, magic, 0);
        final long indexOffset = trailer.getLong();
        final long witnesses = trailer.getLong();
        final long runOffset = trailer.getLong();
        final int runLength = trailer.getInt();
        trailer.get(closing);
        if (!Arrays.equals(MAGIC, magic.array())
            || !Arrays.equals(CLOSING, closing)
            || indexOffset < MAGIC.length
            || witnesses < 0
            || runOffset != indexOffset + witnesses * ENTRY_BYTES
            || runLength < 0
            || runOffset + runLength != size - TRAILER_BYTES) {
          throw new IOException(file + " is not a complete provenance file");
        }
        final ByteBuffer record = ByteBuffer.allocate(runLength);
        read(channel, record, runOffset);
        return new Reader(
            channel,
            indexOffset,
            witnesses,
            run(JSON.parse(new String(record.array(), StandardCharsets.UTF_8))));
      } catch (final IOException | RuntimeException e) {
        channel.close();
        throw e;
      }
    }

    private static Run run(final JsonObject json) {
      final List<Node> read = new ArrayList<>();
      json.get("read")
          .getAsArray()
          .forEach(graph -> read.add(NodeFactory.createURI(graph.getAsString().value())));
      final Map<String, RuleRecord> rules = new LinkedHashMap<>();
      for (final JsonValue value : json.get("rules").getAsArray()) {
        final JsonObject rule = value.getAsObject();
        final List<Var> vars = new ArrayList<>();
        rule.get("vars")
            .getAsArray()
            .forEach(name -> vars.add(Var.alloc(name.getAsString().value())));
        rules.put(
            rule.getString("name"),
            new RuleRecord(
                rule.getString("name"),
                rule.getString("body"),
                rule.getString("digest"),
                NodeFactory.createURI(rule.getString("output")),
                List.copyOf(vars)));
      }
      return new Run(json.getNumber("passes").intValue(), List.copyOf(read), rules);
    }

    Run run() {
      return run;
    }

    long witnesses() {
      return witnesses;
    }

    /** The solution that first derived {@code fact} in {@code graph}, or {@code null}. */
    Solution solution(final Node graph, final Triple fact) throws IOException {
      final byte[] key = key(graph, fact);
      final ByteBuffer entry = ByteBuffer.allocate(ENTRY_BYTES);
      long low = 0;
      long high = witnesses - 1;
      while (low <= high) {
        final long middle = (low + high) >>> 1;
        read(channel, entry.clear(), indexOffset + middle * ENTRY_BYTES);
        final int order = Arrays.compareUnsigned(entry.array(), 0, KEY_BYTES, key, 0, KEY_BYTES);
        if (order == 0) {
          return solution(entry.getLong(KEY_BYTES));
        }
        if (order < 0) {
          low = middle + 1;
        } else {
          high = middle - 1;
        }
      }
      return null;
    }

    private Solution solution(final long offset) throws IOException {
      if (offset < MAGIC.length || offset >= indexOffset) {
        throw new IOException("Corrupt provenance: a witness points outside the solutions");
      }
      final DataInputStream in =
          new DataInputStream(
              new BufferedInputStream(Channels.newInputStream(channel.position(offset)), 4096));
      final int index = in.readInt();
      if (index < 0 || index >= rules.size()) {
        throw new IOException("Corrupt provenance: a solution names rule " + index);
      }
      final RuleRecord rule = rules.get(index);
      final int pass = in.readInt();
      final BindingBuilder binding = BindingBuilder.create();
      for (final Var var : rule.vars()) {
        final Node value = NodeCodec.read(in);
        if (value != null) {
          binding.add(var, value);
        }
      }
      return new Solution(rule.name(), pass, binding.build());
    }

    private static void read(final FileChannel channel, final ByteBuffer target, final long from)
        throws IOException {
      while (target.hasRemaining()) {
        if (channel.read(target, from + target.position()) < 0) {
          throw new EOFException("The provenance file ends before " + (from + target.limit()));
        }
      }
      target.flip();
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  /** A witness's key: 128 bits of SHA-256 over the output graph and the fact's exact encoding. */
  static byte[] key(final Node graph, final Triple fact) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DataOutputStream out =
          new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
        NodeCodec.write(out, graph);
        NodeCodec.write(out, fact.getSubject());
        NodeCodec.write(out, fact.getPredicate());
        NodeCodec.write(out, fact.getObject());
      }
      return Arrays.copyOf(digest.digest(), KEY_BYTES);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
