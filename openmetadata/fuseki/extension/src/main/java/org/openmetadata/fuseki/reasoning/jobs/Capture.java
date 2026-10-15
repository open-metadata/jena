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

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

/**
 * Copies the input of a CHECK or EXPLAIN from a serving dataset to a gzip-compressed N-Quads spool
 * in the job directory, inside one read transaction, so the job sees one version of every graph and
 * writers to the serving dataset are never blocked.
 *
 * <p>The input is the closure OpenMetadata pinned in a version manifest (OpenMetadata's
 * ADR:2026-10-15-reasoning-closures-are-approved-graphs-pinned-by-a-content-manifest): the manifest
 * whose closure digest is the request's {@code ontologyDigest}, or, for an Approved check without
 * one, the manifest the activations graph names for the ontology. The capture copies each
 * manifest's {@code owl:Ontology} header, never its control triples, and every graph it pins, then
 * does the same for each manifest it imports. It recomputes each manifest's closure digest and each
 * pinned graph's digest from what the store holds and refuses the capture when one differs, so a
 * job reasons over exactly the content OpenMetadata pinned or not at all.
 *
 * <p>Pinned graphs are read one at a time; the server holds one graph, the members of one manifest
 * and the set of captured graph names, never the closure. Every bound is checked while copying, and
 * reaching one stops the copy and removes the partial spool.
 */
public final class Capture {
  public static final String CLOSURE_ALGORITHM = "om-closure-v2";
  static final String OMR = "https://open-metadata.org/reasoning/";

  /** Bound checks run once per this many quads, which keeps them off the copy's hot path. */
  private static final int CHECK_EVERY = 4096;

  private static final String MANIFEST_PATH = "graph/manifest/";
  private static final Node TYPE = RDF.type.asNode();
  private static final Node ONTOLOGY = OWL2.Ontology.asNode();
  private static final Node IMPORTS = OWL2.imports.asNode();
  private static final Node VERSION_IRI = OWL2.versionIRI.asNode();
  private static final Node PINNED = NodeFactory.createURI(OMR + "pinnedDigest");
  private static final Node IMPORTS_MANIFEST = NodeFactory.createURI(OMR + "importsManifest");
  private static final Node OMR_ONTOLOGY = NodeFactory.createURI(OMR + "ontology");
  private static final Node GLOSSARY = NodeFactory.createURI(OMR + "glossary");
  private static final Node SELECTION = NodeFactory.createURI(OMR + "selection");
  private static final Node CLOSURE_DIGEST = NodeFactory.createURI(OMR + "closureDigest");
  private static final Node ALGORITHM = NodeFactory.createURI(OMR + "digestAlgorithm");
  private static final Node ACTIVE_MANIFEST = NodeFactory.createURI(OMR + "activeManifest");

  /**
   * The closure a request names.
   *
   * @param closureDigest the request's ontologyDigest; null to use the active Approved manifest
   */
  public record Selection(String ontologyIri, String selection, String closureDigest) {}

  /**
   * @param maxGraphs graphs in one capture, manifests and pinned graphs together: the protocol's
   *     maxImports
   * @param maxTriples quads in one capture; the closure is refused before parsing beyond it
   * @param maxBytes compressed bytes of the spool
   * @param deadlineNanos {@link System#nanoTime()} when the job's time runs out
   */
  public record Bounds(int maxGraphs, long maxTriples, long maxBytes, long deadlineNanos) {}

  /** What was captured, as the job directory records it. */
  public record Result(
      String root,
      String versionIri,
      String closureDigest,
      List<String> manifests,
      List<String> graphs,
      long triples,
      long bytes,
      long millis) {
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("root", root);
      json.put("versionIri", versionIri);
      json.put("closureDigest", closureDigest);
      json.put("manifestCount", manifests.size());
      json.put("graphCount", graphs.size());
      json.put("triples", triples);
      json.put("bytes", bytes);
      json.put("millis", millis);
      final JsonArray manifestNames = new JsonArray();
      manifests.forEach(manifestNames::add);
      json.put("manifests", manifestNames);
      final JsonArray names = new JsonArray();
      graphs.forEach(names::add);
      json.put("graphs", names);
      return json;
    }
  }

  /** The capture cannot be used: a manifest is missing, a bound was reached, or content changed. */
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

  private Capture() {}

  /** The version IRI OpenMetadata gives a closure, from which its manifest is found. */
  static String versionIri(final String ontologyIri, final String selection, final String digest) {
    String stem = ontologyIri;
    while (stem.endsWith("/") || stem.endsWith("#")) {
      stem = stem.substring(0, stem.length() - 1);
    }
    return stem + ("CANDIDATE".equals(selection) ? "/candidate/" : "/version/") + hex(digest);
  }

  /** Captures {@code selection} into {@code spool}, which exists only if the capture succeeds. */
  public static Result run(
      final DatasetGraph dataset,
      final Selection selection,
      final Path spool,
      final Bounds bounds,
      final BooleanSupplier cancelled)
      throws Refused, Cancelled, IOException {
    final long start = System.nanoTime();
    final Path partial = spool.resolveSibling(spool.getFileName() + ".tmp");
    boolean complete = false;
    dataset.begin(TxnType.READ);
    try {
      final Copy copy = new Copy(bounds, cancelled);
      final Node rootManifest = rootManifest(dataset, selection);
      final Manifest root = Manifest.read(dataset, rootManifest);
      if (!root.selection().equals(selection.selection())) {
        throw new Refused(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "Manifest <"
                        + rootManifest.getURI()
                        + "> is a "
                        + root.selection()
                        + " closure, not "
                        + selection.selection())
                .withSubject(selection.ontologyIri()));
      }
      if (!root.ontologyIri().equals(selection.ontologyIri())) {
        throw new Refused(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "Manifest <"
                        + rootManifest.getURI()
                        + "> is the closure of <"
                        + root.ontologyIri()
                        + ">, not of <"
                        + selection.ontologyIri()
                        + ">")
                .withSubject(selection.ontologyIri()));
      }
      final List<String> manifests = new ArrayList<>();
      final Set<String> graphs = new LinkedHashSet<>();
      try (CountingOutputStream file = new CountingOutputStream(Files.newOutputStream(partial));
          OutputStream out = new BufferedOutputStream(new FastGzip(file), 1 << 16)) {
        copy.file = file;
        final StreamRDF writer = StreamRDFWriter.getWriterStream(out, RDFFormat.NQUADS_UTF8);
        writer.start();
        copyClosure(dataset, rootManifest, root, writer, copy, manifests, graphs);
        writer.finish();
      }
      copy.checkBytes(copy.file.count());
      Files.move(partial, spool, StandardCopyOption.REPLACE_EXISTING);
      complete = true;
      return new Result(
          root.ontologyIri(),
          root.versionIri().getURI(),
          root.closureDigest(),
          List.copyOf(manifests),
          List.copyOf(graphs),
          copy.triples,
          Files.size(spool),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      dataset.end();
      if (!complete) {
        Files.deleteIfExists(partial);
      }
    }
  }

  /**
   * Copies the closure of one manifest: its header and pinned graphs, then those of each manifest
   * it imports, breadth first, every manifest and graph checked against its digest. Graphs already
   * in {@code graphs} are not copied again.
   */
  static void copyClosure(
      final DatasetGraph dataset,
      final Node rootManifest,
      final Manifest root,
      final StreamRDF writer,
      final Copy copy,
      final List<String> manifests,
      final Set<String> graphs)
      throws Refused, Cancelled, IOException {
    final Deque<Node> pending = new ArrayDeque<>();
    final Set<Node> seen = new HashSet<>();
    pending.add(rootManifest);
    seen.add(rootManifest);
    while (!pending.isEmpty()) {
      final Node graph = pending.removeFirst();
      final Manifest manifest = graph.equals(rootManifest) ? root : Manifest.read(dataset, graph);
      copyManifest(dataset, manifest, writer, copy, graphs);
      manifests.add(graph.getURI());
      for (final Node imported : manifest.imports()) {
        if (seen.add(imported)) {
          pending.add(imported);
        }
      }
    }
  }

  /**
   * The manifest graph for the request: found by the version IRI its digest gives, or by the
   * activation of its ontology. The store does not know OpenMetadata's base IRI, so it never builds
   * a manifest's name; it checks that the graph it finds has a manifest's name.
   */
  private static Node rootManifest(final DatasetGraph dataset, final Selection selection)
      throws Refused {
    final Node ontology = NodeFactory.createURI(selection.ontologyIri());
    if (selection.closureDigest() == null) {
      if (!"APPROVED".equals(selection.selection())) {
        throw new Refused(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "A CANDIDATE check names its closure with ontologyDigest")
                .withSubject(selection.ontologyIri()));
      }
      final Set<Node> active = new LinkedHashSet<>();
      final Iterator<Quad> activations = dataset.find(Node.ANY, Node.ANY, OMR_ONTOLOGY, ontology);
      while (activations.hasNext() && active.size() < 2) {
        final Quad quad = activations.next();
        final Iterator<Quad> manifest =
            dataset.find(quad.getGraph(), quad.getSubject(), ACTIVE_MANIFEST, Node.ANY);
        while (manifest.hasNext()) {
          active.add(manifest.next().getObject());
        }
      }
      if (active.size() != 1) {
        throw new Refused(
            Problem.of(
                    Problem.Code.MISSING_IMPORT,
                    active.isEmpty()
                        ? "No closure of <" + selection.ontologyIri() + "> is active"
                        : "More than one active closure names <" + selection.ontologyIri() + ">")
                .withSubject(selection.ontologyIri()));
      }
      return active.iterator().next();
    }
    final String version =
        versionIri(selection.ontologyIri(), selection.selection(), selection.closureDigest());
    final String suffix = MANIFEST_PATH + hex(selection.closureDigest());
    final Iterator<Quad> headers =
        dataset.find(Node.ANY, ontology, VERSION_IRI, NodeFactory.createURI(version));
    while (headers.hasNext()) {
      final Node graph = headers.next().getGraph();
      if (graph.isURI() && graph.getURI().endsWith(suffix)) {
        return graph;
      }
    }
    throw new Refused(
        Problem.of(
                Problem.Code.MISSING_IMPORT,
                "No manifest of <"
                    + selection.ontologyIri()
                    + "> has version <"
                    + version
                    + ">; it was never published or has been dropped, so publish the closure"
                    + " again")
            .withSubject(selection.ontologyIri()));
  }

  /**
   * Checks a manifest against its closure digest, then writes its header and every graph it pins
   * that is not captured yet, each checked against its pinned digest.
   */
  private static void copyManifest(
      final DatasetGraph dataset,
      final Manifest manifest,
      final StreamRDF writer,
      final Copy copy,
      final Set<String> graphs)
      throws Refused, Cancelled, IOException {
    copy.checkTime();
    final TreeMap<String, String> members = new TreeMap<>();
    final Iterator<Quad> pinned = dataset.find(manifest.graph(), Node.ANY, PINNED, Node.ANY);
    while (pinned.hasNext()) {
      final Quad quad = pinned.next();
      if (quad.getSubject().isURI() && quad.getObject().isLiteral()) {
        members.put(quad.getSubject().getURI(), quad.getObject().getLiteralLexicalForm());
        if (graphs.size() + members.size() >= copy.bounds.maxGraphs()) {
          throw tooManyGraphs(copy.bounds.maxGraphs());
        }
      }
    }
    final List<String> importLines = new ArrayList<>();
    for (final Node imported : manifest.imports()) {
      final Manifest importedManifest = Manifest.read(dataset, imported);
      importLines.add(
          "import <" + importedManifest.ontologyIri() + "> " + importedManifest.closureDigest());
    }
    importLines.sort(String::compareTo);
    final String recomputed = manifest.closureDigest(members, importLines);
    if (!recomputed.equals(manifest.closureDigest())) {
      throw new Refused(
          Problem.of(
                  Problem.Code.GENERATION_MISMATCH,
                  "Manifest <"
                      + manifest.graph().getURI()
                      + "> pins content whose closure digest is "
                      + recomputed
                      + ", not its "
                      + manifest.closureDigest())
              .withSubject(manifest.versionIri().getURI()));
    }

    graphs.add(manifest.graph().getURI());
    for (final Triple header : manifest.header(dataset)) {
      writer.quad(Quad.create(manifest.graph(), header));
      copy.count();
    }
    for (final Map.Entry<String, String> member : members.entrySet()) {
      if (!graphs.add(member.getKey())) {
        continue;
      }
      final Node graph = NodeFactory.createURI(member.getKey());
      final List<Triple> triples = new ArrayList<>();
      final Iterator<Quad> quads = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY);
      while (quads.hasNext()) {
        triples.add(quads.next().asTriple());
        copy.count();
      }
      final String digest;
      try {
        digest = GraphDigest.of(triples);
      } catch (final GraphDigest.TooSymmetricException e) {
        // OpenMetadata gives such a graph no digest and never pins it, so this manifest was not
        // written by OpenMetadata from the content the store now holds.
        throw new Refused(
            Problem.of(
                    Problem.Code.GENERATION_MISMATCH,
                    "Graph <"
                        + member.getKey()
                        + "> pinned by manifest <"
                        + manifest.graph().getURI()
                        + "> has no digest: "
                        + e.getMessage())
                .withSubject(member.getKey()));
      }
      if (!digest.equals(member.getValue())) {
        throw new Refused(
            Problem.of(
                    Problem.Code.GENERATION_MISMATCH,
                    "Graph <"
                        + member.getKey()
                        + (triples.isEmpty() ? "> is gone" : "> has changed")
                        + " since manifest <"
                        + manifest.graph().getURI()
                        + "> pinned it: its digest is "
                        + digest
                        + ", not "
                        + member.getValue())
                .withSubject(member.getKey()));
      }
      for (final Triple triple : triples) {
        writer.quad(Quad.create(graph, triple));
      }
    }
  }

  private static Refused tooManyGraphs(final int maxGraphs) {
    return new Refused(
        Problem.of(
                Problem.Code.LIMIT_EXCEEDED, "The closure pins more than " + maxGraphs + " graphs")
            .withSubject("maxImports"));
  }

  private static String hex(final String digest) {
    return digest.startsWith(GraphDigest.PREFIX)
        ? digest.substring(GraphDigest.PREFIX.length())
        : digest;
  }

  /** One manifest's header, read from its graph; a manifest without one is refused. */
  record Manifest(
      Node graph,
      String ontologyIri,
      Node versionIri,
      String glossary,
      String selection,
      String closureDigest,
      List<Node> imports) {

    static Manifest read(final DatasetGraph dataset, final Node graph) throws Refused {
      final Quad version = one(dataset, graph, Node.ANY, ALGORITHM, null);
      final String algorithm = version.getObject().getLiteralLexicalForm();
      if (!algorithm.equals(CLOSURE_ALGORITHM)) {
        throw new Refused(
            Problem.of(
                    Problem.Code.NOT_IMPLEMENTED,
                    "Manifest <"
                        + graph.getURI()
                        + "> uses the closure digest "
                        + algorithm
                        + ", which this build cannot check")
                .withSubject(graph.getURI()));
      }
      final Node versionIri = version.getSubject();
      final Node ontology = one(dataset, graph, versionIri, OMR_ONTOLOGY, null).getObject();
      if (!dataset.contains(graph, ontology, TYPE, ONTOLOGY)
          || !dataset.contains(graph, ontology, VERSION_IRI, versionIri)) {
        throw incomplete(graph, "has no owl:Ontology header with its version IRI");
      }
      final List<Node> imports = new ArrayList<>();
      final Iterator<Quad> imported = dataset.find(graph, versionIri, IMPORTS_MANIFEST, Node.ANY);
      while (imported.hasNext()) {
        imports.add(imported.next().getObject());
      }
      return new Manifest(
          graph,
          ontology.getURI(),
          versionIri,
          one(dataset, graph, versionIri, GLOSSARY, null).getObject().getURI(),
          one(dataset, graph, versionIri, SELECTION, null).getObject().getLiteralLexicalForm(),
          one(dataset, graph, versionIri, CLOSURE_DIGEST, null).getObject().getLiteralLexicalForm(),
          List.copyOf(imports));
    }

    /** The reasoner's view of the manifest: its owl:Ontology header, never the control triples. */
    List<Triple> header(final DatasetGraph dataset) {
      final List<Triple> header = new ArrayList<>();
      final Node ontology = NodeFactory.createURI(ontologyIri);
      header.add(Triple.create(ontology, TYPE, ONTOLOGY));
      header.add(Triple.create(ontology, VERSION_IRI, versionIri));
      final Iterator<Quad> imports = dataset.find(graph, ontology, IMPORTS, Node.ANY);
      while (imports.hasNext()) {
        header.add(imports.next().asTriple());
      }
      return header;
    }

    /**
     * The closure digest, {@value Capture#CLOSURE_ALGORITHM}, of these members and import lines.
     * Every line comes from a manifest: OpenMetadata publishes no closure with a missing import or
     * an import cycle, which no manifest could record.
     */
    String closureDigest(final TreeMap<String, String> members, final List<String> importLines) {
      final MessageDigest sha256 = GraphDigest.sha256();
      final List<String> lines = new ArrayList<>();
      lines.add(CLOSURE_ALGORITHM);
      lines.add("ontology <" + ontologyIri + ">");
      lines.add("glossary <" + glossary + ">");
      lines.add("selection " + selection);
      members.forEach((graph, digest) -> lines.add("member <" + graph + "> " + digest));
      lines.addAll(importLines);
      for (final String line : lines) {
        sha256.update((line + "\n").getBytes(StandardCharsets.UTF_8));
      }
      return GraphDigest.PREFIX + HexFormat.of().formatHex(sha256.digest());
    }

    private static Quad one(
        final DatasetGraph dataset,
        final Node graph,
        final Node subject,
        final Node predicate,
        final Node object)
        throws Refused {
      final Iterator<Quad> quads =
          dataset.find(graph, subject, predicate, object == null ? Node.ANY : object);
      if (!quads.hasNext()) {
        throw incomplete(graph, "has no " + predicate.getURI());
      }
      final Quad quad = quads.next();
      if (quads.hasNext()) {
        throw incomplete(graph, "has more than one " + predicate.getURI());
      }
      final Node value = quad.getObject();
      final boolean literal =
          predicate.equals(SELECTION)
              || predicate.equals(CLOSURE_DIGEST)
              || predicate.equals(ALGORITHM);
      if (literal != value.isLiteral() || (!literal && !value.isURI())) {
        throw incomplete(graph, "has a malformed " + predicate.getURI());
      }
      return quad;
    }

    private static Refused incomplete(final Node graph, final String what) {
      return new Refused(
          Problem.of(
                  Problem.Code.MISSING_IMPORT,
                  "Manifest <" + graph.getURI() + "> " + what + ", so it is not a manifest")
              .withSubject(graph.getURI()));
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
                    "The closure has more than "
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
