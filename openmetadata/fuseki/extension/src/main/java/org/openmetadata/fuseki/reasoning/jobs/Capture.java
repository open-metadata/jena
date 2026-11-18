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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.riot.thrift.ThriftRDF;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Turns the release a CHECK or EXPLAIN names into the gzip-compressed N-Quads spool the worker
 * loads. The release is RDF-Thrift that OpenMetadata uploaded first (OpenMetadata's
 * ADR:2026-11-10-reasoning-jobs-name-ontology-releases-and-carry-their-rules), so nothing here
 * reads the serving dataset: a release under review never enters it.
 *
 * <p>The capture accepts the upload only as the release the request named. The bytes must be as
 * long as {@code releaseRdf.byteSize} says and hash to its digest, and the RDF must hold exactly
 * one {@code owl:Ontology} whose {@code owl:versionIRI} is {@code
 * urn:openmetadata:ontology-release:{releaseId}}. Anything else is {@code INPUT_CHANGED}: the input
 * the request named is not what the store found. Imports between ontologies are deferred, so no
 * other ontology is in the input and an {@code owl:imports} cannot resolve.
 *
 * <p>The upload is parsed as a stream and written as it is read, so memory does not grow with the
 * release. Every bound is checked while copying, and reaching one stops the copy and removes the
 * partial spool.
 */
public final class Capture {
  public static final String RELEASE_IRI_PREFIX = "urn:openmetadata:ontology-release:";

  /** The graph an active release occupies in the serving dataset, which the spool reuses. */
  static final String ONTOLOGY_GRAPH_PREFIX = "https://open-metadata.org/graph/ontology/";

  /** Bound checks run once per this many quads, which keeps them off the copy's hot path. */
  private static final int CHECK_EVERY = 4096;

  private static final int BUFFER_BYTES = 64 * 1024;

  /**
   * The longest string one term of the upload may hold. Thrift reads a string into one array of its
   * declared length, which the parser then copies into a term, so a single huge literal needs more
   * than twice its size in heap; a term an ontology release has cause to hold is far smaller.
   */
  static final int MAX_TERM_BYTES = 1 << 20;

  static {
    // The vocabulary classes below initialise Jena from whichever thread first touches them, and
    // two threads doing that at once deadlock into a failed class; a job thread is one of them.
    JenaSystem.init();
  }

  private static final Node TYPE = RDF.type.asNode();
  private static final Node ONTOLOGY = OWL2.Ontology.asNode();
  private static final Node IMPORTS = OWL2.imports.asNode();
  private static final Node VERSION_IRI = OWL2.versionIRI.asNode();

  /** The release the request names, and the upload that holds it. */
  public record Release(String ontologyId, String releaseId, String digest, long byteSize) {}

  /**
   * @param maxTriples triples in one capture; the release is refused before parsing beyond it
   * @param maxBytes compressed bytes of the spool
   * @param deadlineNanos {@link System#nanoTime()} when the job's time runs out
   */
  public record Bounds(long maxTriples, long maxBytes, long deadlineNanos) {}

  /** What was captured, as the job directory records it. */
  public record Result(
      String root, String versionIri, String graph, long triples, long bytes, long millis) {
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("root", root);
      json.put("versionIri", versionIri);
      json.put("graph", graph);
      json.put("triples", triples);
      json.put("bytes", bytes);
      json.put("millis", millis);
      return json;
    }
  }

  /** The capture cannot be used: the input is not the release named, or a bound was reached. */
  public static final class Refused extends Exception {
    private final transient Problem problem;

    Refused(final Problem problem) {
      super(problem.message());
      this.problem = problem;
    }

    public Problem problem() {
      return problem;
    }
  }

  /** The job was cancelled while capturing. */
  public static final class Cancelled extends Exception {
    Cancelled() {
      super("cancelled");
    }
  }

  /** Carries a refusal or cancellation out of the parser, which only lets unchecked ones pass. */
  private static final class Abort extends RuntimeException {
    Abort(final Exception cause) {
      super(cause);
    }
  }

  private Capture() {}

  /**
   * Captures the {@code upload} of {@code release} into {@code spool}, which exists only on
   * success.
   */
  public static Result run(
      final Path upload,
      final Release release,
      final Path spool,
      final Bounds bounds,
      final BooleanSupplier cancelled)
      throws Refused, Cancelled, IOException {
    final long start = System.nanoTime();
    checkUpload(upload, release);
    final Path partial = spool.resolveSibling(spool.getFileName() + ".tmp");
    final Node graph = NodeFactory.createURI(ONTOLOGY_GRAPH_PREFIX + release.ontologyId());
    boolean complete = false;
    try {
      final Copy copy = new Copy(bounds, cancelled);
      final Header header = new Header("the upload");
      try (CountingOutputStream file = new CountingOutputStream(Files.newOutputStream(partial));
          OutputStream out = new BufferedOutputStream(new FastGzip(file), BUFFER_BYTES);
          InputStream in = Files.newInputStream(upload)) {
        copy.file = file;
        final StreamRDF writer = StreamRDFWriter.getWriterStream(out, RDFFormat.NQUADS_UTF8);
        writer.start();
        final TProtocol protocol = protocol(in);
        ThriftRDF.protocolToStream(protocol, new Spooling(writer, graph, header, copy));
        writer.finish();
      } catch (final Abort e) {
        if (e.getCause() instanceof Refused refused) {
          throw refused;
        }
        throw (Cancelled) e.getCause();
      } catch (final RiotException e) {
        if (overTermLimit(e)) {
          throw new Refused(
              Problem.of(
                      Problem.Code.LIMIT_EXCEEDED,
                      "The release holds a term longer than " + MAX_TERM_BYTES + " bytes")
                  .withSubject(release.digest()));
        }
        throw new Refused(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "The upload is not readable RDF-Thrift: " + e.getMessage())
                .withSubject(release.digest()));
      }
      copy.checkBytes(copy.file.count());
      final String versionIri = header.verify(release.ontologyId(), release.releaseId());
      Files.move(partial, spool, StandardCopyOption.REPLACE_EXISTING);
      complete = true;
      return new Result(
          header.ontology.getURI(),
          versionIri,
          graph.getURI(),
          copy.triples,
          Files.size(spool),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      if (!complete) {
        Files.deleteIfExists(partial);
      }
    }
  }

  private static TProtocol protocol(final InputStream in) throws IOException {
    try {
      return new TCompactProtocol(
          new TIOStreamTransport(new BufferedInputStream(in, BUFFER_BYTES)), MAX_TERM_BYTES, -1);
    } catch (final TTransportException e) {
      throw new IOException(e);
    }
  }

  private static boolean overTermLimit(final Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TProtocolException protocol
          && protocol.getType() == TProtocolException.SIZE_LIMIT) {
        return true;
      }
    }
    return false;
  }

  /**
   * The upload must still be what the request described: the store only holds uploads that hashed
   * right, but a job can wait in the queue past the upload's removal or replacement, and a disk can
   * corrupt a file, so the job checks its own input once more before relying on it.
   */
  private static void checkUpload(final Path upload, final Release release)
      throws Refused, IOException {
    if (!Files.isRegularFile(upload)) {
      throw changed(
          release.digest(),
          "The upload " + release.digest() + " is not held, so upload it again and resubmit");
    }
    final long size = Files.size(upload);
    if (size != release.byteSize()) {
      throw changed(
          release.digest(),
          "The upload " + release.digest() + " is " + size + " bytes, not " + release.byteSize());
    }
    final MessageDigest sha256 = sha256();
    try (InputStream in = Files.newInputStream(upload)) {
      final byte[] buffer = new byte[BUFFER_BYTES];
      int read;
      while ((read = in.read(buffer)) != -1) {
        sha256.update(buffer, 0, read);
      }
    }
    final String actual = "sha256:" + HexFormat.of().formatHex(sha256.digest());
    if (!actual.equals(release.digest())) {
      throw changed(
          release.digest(), "The upload held as " + release.digest() + " now hashes to " + actual);
    }
  }

  static Refused changed(final String subject, final String message) {
    return new Refused(Problem.of(Problem.Code.INPUT_CHANGED, message).withSubject(subject));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The few triples of the ontology header the capture decides on; the rest only pass through. */
  static final class Header {
    private static final int MAX_REMEMBERED = 8;

    private final Set<Node> ontologies = new LinkedHashSet<>();
    private final Set<Node> versions = new LinkedHashSet<>();
    private final Set<Node> imports = new LinkedHashSet<>();
    private final Set<Node> versionSubjects = new LinkedHashSet<>();
    Node ontology;
    private final String source;

    /**
     * @param source what the header was read from, as it reads mid-sentence: "the upload"
     */
    Header(final String source) {
      this.source = source;
    }

    void see(final Triple triple) {
      final Node predicate = triple.getPredicate();
      if (predicate.equals(TYPE) && triple.getObject().equals(ONTOLOGY)) {
        remember(ontologies, triple.getSubject());
      } else if (predicate.equals(VERSION_IRI)) {
        remember(versions, triple.getObject());
        remember(versionSubjects, triple.getSubject());
      } else if (predicate.equals(IMPORTS)) {
        remember(imports, triple.getObject());
      }
    }

    /** Remembering a bounded number is enough: more than one already fails the check. */
    private static void remember(final Set<Node> into, final Node node) {
      if (into.size() < MAX_REMEMBERED) {
        into.add(node);
      }
    }

    /** The release's version IRI, once the header is known to be the named release's. */
    String verify(final String ontologyId, final String releaseId) throws Refused {
      final String expected = RELEASE_IRI_PREFIX + releaseId;
      if (ontologies.size() != 1) {
        throw changed(
            ontologyId,
            Character.toUpperCase(source.charAt(0))
                + source.substring(1)
                + " has "
                + (ontologies.isEmpty() ? "no" : "more than one")
                + " owl:Ontology, not the one header of release "
                + releaseId);
      }
      ontology = ontologies.iterator().next();
      if (!ontology.isURI()) {
        throw changed(
            ontologyId, "The owl:Ontology in " + source + " is a blank node, not a named one");
      }
      if (!versions.contains(NodeFactory.createURI(expected))
          || versions.size() != 1
          || versionSubjects.size() != 1
          || !versionSubjects.contains(ontology)) {
        throw changed(
            ontologyId,
            "The owl:Ontology in "
                + source
                + " does not state owl:versionIRI <"
                + expected
                + ">, so it is not release "
                + releaseId
                + "; it has "
                + versions);
      }
      for (final Node imported : imports) {
        if (!imported.equals(ontology) && !imported.equals(NodeFactory.createURI(expected))) {
          throw new Refused(
              Problem.of(
                      Problem.Code.MISSING_IMPORT,
                      "The release imports <"
                          + imported
                          + ">, but no other ontology is in the input: imports between ontologies"
                          + " are not supported")
                  .withSubject(imported.isURI() ? imported.getURI() : ontologyId));
        }
      }
      return expected;
    }
  }

  /** Writes every triple of the upload into one graph of the spool and notes the header. */
  private static final class Spooling extends StreamRDFBase {
    private final StreamRDF writer;
    private final Node graph;
    private final Header header;
    private final Copy copy;

    Spooling(final StreamRDF writer, final Node graph, final Header header, final Copy copy) {
      this.writer = writer;
      this.graph = graph;
      this.header = header;
      this.copy = copy;
    }

    @Override
    public void triple(final Triple triple) {
      add(triple);
    }

    @Override
    public void quad(final Quad quad) {
      add(quad.asTriple());
    }

    private void add(final Triple triple) {
      try {
        copy.count();
      } catch (final Refused | Cancelled e) {
        throw new Abort(e);
      }
      header.see(triple);
      writer.quad(Quad.create(graph, triple));
    }
  }

  /** The copy's running counts and its checks against the bounds. */
  static final class Copy {
    private final Bounds bounds;
    private final BooleanSupplier cancelled;
    CountingOutputStream file;
    long triples;

    Copy(final Bounds bounds, final BooleanSupplier cancelled) {
      this.bounds = bounds;
      this.cancelled = cancelled;
    }

    void count() throws Refused, Cancelled {
      if (++triples > bounds.maxTriples()) {
        throw new Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "The release has more than "
                        + bounds.maxTriples()
                        + " triples, more than its axiom limit admits")
                .withSubject("maxClosureAxioms"));
      }
      if (triples % CHECK_EVERY == 0) {
        checkBytes(file.count());
        checkTime();
      }
    }

    void checkBytes(final long bytes) throws Refused {
      if (bytes > bounds.maxBytes()) {
        throw new Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "The captured input passed "
                        + bounds.maxBytes()
                        + " bytes, what the reasoning disk quota leaves for it")
                .withSubject("maxDiskBytes"));
      }
    }

    void checkTime() throws Refused, Cancelled {
      if (cancelled.getAsBoolean()) {
        throw new Cancelled();
      }
      if (System.nanoTime() - bounds.deadlineNanos() >= 0) {
        throw new Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "The job ran out of time while capturing its input")
                .withSubject("maxJobSeconds"));
      }
    }
  }

  /**
   * The lowest compression level: capture runs in the server's JVM, and the spool is read once and
   * deleted, so CPU matters more than its size.
   */
  static final class FastGzip extends GZIPOutputStream {
    FastGzip(final OutputStream out) throws IOException {
      super(out, 1 << 16);
      def.setLevel(Deflater.BEST_SPEED);
    }
  }

  static final class CountingOutputStream extends FilterOutputStream {
    private long count;

    CountingOutputStream(final OutputStream out) {
      super(out);
    }

    @Override
    public void write(final int b) throws IOException {
      out.write(b);
      count++;
    }

    @Override
    public void write(final byte[] bytes, final int offset, final int length) throws IOException {
      out.write(bytes, offset, length);
      count += length;
    }

    long count() {
      return count;
    }
  }
}
