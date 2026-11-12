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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;

/**
 * Captures what a REFRESH reasons over, inside one read transaction of the serving dataset, into
 * one gzip-compressed N-Quads spool in the job directory (the plan's refresh lifecycle, step 2):
 *
 * <ol>
 *   <li>every release the request lists, from the graph an active release occupies ({@code
 *       graph/ontology/{ontologyId}}). The graph must state the listed release in its {@code
 *       owl:versionIRI}, or the ontology was activated again after the request was made and the job
 *       fails with INPUT_CHANGED; each release is one closure, since imports between ontologies are
 *       deferred (OpenMetadata's
 *       ADR:2026-11-10-reasoning-jobs-name-ontology-releases-and-carry-their-rules);
 *   <li>the catalog: every triple whose predicate a rule can match, from every graph that is not an
 *       ontology graph, a staging graph or a derived graph, so a release that is not listed, a
 *       half-replaced graph or a previous run's conclusions never reach the rules.
 * </ol>
 *
 * <p>Everything is streamed: the server holds one graph's header, one closure's graph name, the
 * catalog's graph names (at most {@value #MAX_CATALOG_GRAPHS}) and a bounded cache of graph
 * decisions, never the catalog. Every 4,096 quads the copy checks the disk the quota leaves, the
 * job's deadline and cancellation. A read transaction never blocks Fuseki's writer.
 */
public final class RefreshCapture {
  /** Releases one refresh classifies, each in a worker step of its own. */
  public static final int MAX_CLOSURES = 1_000;

  /** Graphs the catalog part of a capture may span; OpenMetadata writes it into a few. */
  public static final int MAX_CATALOG_GRAPHS = 10_000;

  /**
   * Graphs the rules may read in all, releases and catalog together: the worker's limit on a RULES
   * step's read file, and so the bound on the graph names the capture holds.
   */
  public static final int MAX_READ_GRAPHS = 100_000;

  /** The capture's graph decisions kept, a bounded LRU: catalog quads arrive in predicate order. */
  static final int GRAPH_DECISIONS = 4_096;

  static final String CLOSURES = "closures";
  static final String READ_FILE = "rules.read";
  static final String RULES_FILE = "rules.json";

  /** Graph IRIs the store names itself; nothing OpenMetadata writes under it is ever captured. */
  static final String STORE_GRAPHS = "urn:openmetadata:";

  /**
   * Graphs that are never rule input, by the path OpenMetadata gives them under its base IRI: every
   * ontology graph (the listed releases are read on their own, any other is not input at all),
   * graphs OpenMetadata stages a chunked replacement in, and the phase 0 materializer's output.
   */
  static final List<String> EXCLUDED_PATHS =
      List.of("/graph/inferred/", "/graph/ontology/", "/graph/staging/");

  private static final int CHECK_EVERY = 4096;

  /** One release the request lists. */
  public record Release(String ontologyId, String releaseId, String contentDigest) {}

  /** One captured release: what its classify step reads and where its entailments go. */
  public record Closure(
      int index,
      String ontologyId,
      String releaseId,
      String contentDigest,
      String ontologyIri,
      String versionIri,
      long triples,
      String graphsFile,
      String entailments) {
    JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("index", index);
      json.put("ontologyId", ontologyId);
      json.put("releaseId", releaseId);
      json.put("contentDigest", contentDigest);
      json.put("ontologyIri", ontologyIri);
      json.put("versionIri", versionIri);
      json.put("triples", triples);
      json.put("graphsFile", graphsFile);
      json.put("entailmentGraph", entailments);
      return json;
    }

    static Closure fromJson(final JsonObject json) {
      return new Closure(
          json.getNumber("index").intValue(),
          json.getString("ontologyId"),
          json.getString("releaseId"),
          json.getString("contentDigest"),
          json.getString("ontologyIri"),
          json.getString("versionIri"),
          json.getNumber("triples").longValue(),
          json.getString("graphsFile"),
          json.getString("entailmentGraph"));
    }
  }

  /** What was captured, as capture.json records it. */
  public record Result(
      int rules,
      List<Closure> closures,
      List<String> catalogGraphs,
      long catalogTriples,
      boolean anyPredicate,
      Set<String> predicates,
      long triples,
      long bytes,
      long millis) {
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("rules", rules);
      final JsonArray closureArray = new JsonArray();
      closures.forEach(closure -> closureArray.add(closure.toJson()));
      json.put("closures", closureArray);
      final JsonObject catalog = new JsonObject();
      final JsonArray graphs = new JsonArray();
      catalogGraphs.forEach(graphs::add);
      catalog.put("graphs", graphs);
      catalog.put("triples", catalogTriples);
      catalog.put("anyPredicate", anyPredicate);
      final JsonArray predicateArray = new JsonArray();
      predicates.forEach(predicateArray::add);
      catalog.put("predicates", predicateArray);
      json.put("catalog", catalog);
      json.put("triples", triples);
      json.put("bytes", bytes);
      json.put("millis", millis);
      return json;
    }
  }

  private RefreshCapture() {}

  /** The graph a release's entailments are written to in the working dataset and the snapshot. */
  public static String entailmentGraph(final String releaseId) {
    return STORE_GRAPHS + "entailments:" + releaseId;
  }

  /** The graph a rule's conclusions are written to. */
  public static String ruleGraph(final String rule) {
    return STORE_GRAPHS + "inferred:" + rule;
  }

  /** The graph the RULES step writes its witnesses to; never visible to snapshot queries. */
  public static final String PROVENANCE = STORE_GRAPHS + "provenance";

  /**
   * Captures the input of a REFRESH into {@code directory}: the spool, one graphs file per release,
   * the rules' read list and the rules. The spool exists only if the capture succeeds.
   *
   * @param releases the request's {@code ontologyReleases}, possibly none
   * @param bundle the request's enabled rules
   */
  public static Result run(
      final DatasetGraph dataset,
      final List<Release> releases,
      final RuleBundle bundle,
      final Path directory,
      final Capture.Bounds bounds,
      final BooleanSupplier cancelled)
      throws Capture.Refused, Capture.Cancelled, IOException {
    final long start = System.nanoTime();
    if (releases.size() > MAX_CLOSURES) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.LIMIT_EXCEEDED,
                  releases.size() + " releases are active, more than " + MAX_CLOSURES)
              .withSubject("maxImports"));
    }
    final Path spool = directory.resolve(Plans.SPOOL);
    final Path partial = spool.resolveSibling(spool.getFileName() + ".tmp");
    final Path closureDirectory = Files.createDirectories(directory.resolve(CLOSURES));
    boolean complete = false;
    dataset.begin(TxnType.READ);
    try {
      final Set<String> read = new LinkedHashSet<>();
      final List<Closure> closures = new ArrayList<>();
      final List<String> catalogGraphs;
      final long catalogTriples;
      long triples = 0;
      final RulePredicates predicates =
          RulePredicates.of(bundle.rules().stream().map(RuleBundle.Rule::body).toList());
      try (Capture.CountingOutputStream file =
              new Capture.CountingOutputStream(Files.newOutputStream(partial));
          OutputStream out = new BufferedOutputStream(new Capture.FastGzip(file), 1 << 16)) {
        final StreamRDF writer = StreamRDFWriter.getWriterStream(out, RDFFormat.NQUADS_UTF8);
        writer.start();
        for (final Release release : releases) {
          final Capture.Copy copy = new Capture.Copy(bounds, cancelled);
          copy.file = file;
          closures.add(closure(dataset, release, closures.size(), writer, copy, closureDirectory));
          triples += copy.triples;
        }
        final Capture.Copy catalogCopy =
            new Capture.Copy(
                new Capture.Bounds(Long.MAX_VALUE, bounds.maxBytes(), bounds.deadlineNanos()),
                cancelled);
        catalogCopy.file = file;
        final Set<String> catalog = new LinkedHashSet<>();
        catalogTriples = catalog(dataset, predicates, writer, catalogCopy, catalog);
        catalogGraphs = List.copyOf(catalog);
        triples += catalogTriples;
        writer.finish();
        closures.forEach(closure -> read.add(Capture.ONTOLOGY_GRAPH_PREFIX + closure.ontologyId()));
        read.addAll(catalog);
      }
      if (read.size() > MAX_READ_GRAPHS) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "The releases and the catalog span more than " + MAX_READ_GRAPHS + " graphs")
                .withSubject("maxImports"));
      }
      closures.forEach(closure -> read.add(closure.entailments()));
      writeLines(directory.resolve(READ_FILE), read);
      Files.writeString(directory.resolve(RULES_FILE), JSON.toString(bundle.rulesJson()));
      final long bytes = Files.size(partial);
      if (bytes > bounds.maxBytes()) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "The captured input passed "
                        + bounds.maxBytes()
                        + " bytes, what the reasoning disk quota leaves for it")
                .withSubject("maxDiskBytes"));
      }
      Files.move(partial, spool, StandardCopyOption.REPLACE_EXISTING);
      complete = true;
      return new Result(
          bundle.rules().size(),
          List.copyOf(closures),
          catalogGraphs,
          catalogTriples,
          predicates.any(),
          predicates.predicates(),
          triples,
          bytes,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } finally {
      dataset.end();
      if (!complete) {
        Files.deleteIfExists(partial);
      }
    }
  }

  /**
   * Copies one release's graph into the spool, checking as it goes that the graph is the release
   * the request listed.
   */
  private static Closure closure(
      final DatasetGraph dataset,
      final Release release,
      final int index,
      final StreamRDF writer,
      final Capture.Copy copy,
      final Path closureDirectory)
      throws Capture.Refused, Capture.Cancelled, IOException {
    final Node graph = NodeFactory.createURI(Capture.ONTOLOGY_GRAPH_PREFIX + release.ontologyId());
    final Iterator<Quad> quads = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY);
    if (!quads.hasNext()) {
      throw Capture.changed(
          release.ontologyId(),
          "The serving graph holds no graph for ontology "
              + release.ontologyId()
              + ", so release "
              + release.releaseId()
              + " is no longer active");
    }
    final Capture.Header header = new Capture.Header("graph <" + graph.getURI() + ">");
    while (quads.hasNext()) {
      final Quad quad = quads.next();
      copy.count();
      header.see(quad.asTriple());
      writer.quad(quad);
    }
    copy.checkBytes(copy.file.count());
    final String versionIri = header.verify(release.ontologyId(), release.releaseId());
    final String file = CLOSURES + "/" + index + ".graphs";
    writeLines(closureDirectory.resolve(index + ".graphs"), List.of(graph.getURI()));
    return new Closure(
        index,
        release.ontologyId(),
        release.releaseId(),
        release.contentDigest(),
        header.ontology.getURI(),
        versionIri,
        copy.triples,
        file,
        entailmentGraph(release.releaseId()));
  }

  /**
   * Streams the catalog triples the rules can match: one scan per predicate on TDB2's
   * predicate-first index, or one scan of everything when a rule can match any predicate.
   */
  private static long catalog(
      final DatasetGraph dataset,
      final RulePredicates predicates,
      final StreamRDF writer,
      final Capture.Copy copy,
      final Set<String> graphs)
      throws Capture.Refused, Capture.Cancelled {
    final GraphDecisions decisions = new GraphDecisions();
    final List<Node> scans = new ArrayList<>();
    if (predicates.any()) {
      scans.add(Node.ANY);
    } else {
      predicates.predicates().forEach(iri -> scans.add(NodeFactory.createURI(iri)));
    }
    long copied = 0;
    long seen = 0;
    for (final Node predicate : scans) {
      final Iterator<Quad> quads = dataset.find(Node.ANY, Node.ANY, predicate, Node.ANY);
      while (quads.hasNext()) {
        final Quad quad = quads.next();
        if (++seen % CHECK_EVERY == 0) {
          copy.checkBytes(copy.file.count());
          copy.checkTime();
        }
        if (!decisions.include(quad.getGraph())) {
          continue;
        }
        final String name =
            Quad.isDefaultGraph(quad.getGraph())
                ? Quad.defaultGraphIRI.getURI()
                : quad.getGraph().getURI();
        if (graphs.add(name) && graphs.size() > MAX_CATALOG_GRAPHS) {
          throw new Capture.Refused(
              Problem.of(
                      Problem.Code.LIMIT_EXCEEDED,
                      "The catalog triples the rules read span more than "
                          + MAX_CATALOG_GRAPHS
                          + " graphs")
                  .withSubject("maxImports"));
        }
        writer.quad(quad);
        copied++;
      }
    }
    return copied;
  }

  /** Whether a graph's triples are catalog input, decided once per graph within a bounded cache. */
  static final class GraphDecisions {
    private final Map<Node, Boolean> decided =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<Node, Boolean> eldest) {
            return size() > GRAPH_DECISIONS;
          }
        };

    boolean include(final Node graph) {
      return decided.computeIfAbsent(graph, GraphDecisions::decide);
    }

    private static boolean decide(final Node graph) {
      if (Quad.isDefaultGraph(graph)) {
        return true;
      }
      if (!graph.isURI()) {
        return false;
      }
      final String iri = graph.getURI();
      if (iri.startsWith(STORE_GRAPHS)) {
        return false;
      }
      for (final String path : EXCLUDED_PATHS) {
        if (iri.contains(path)) {
          return false;
        }
      }
      return true;
    }
  }

  private static void writeLines(final Path file, final Iterable<String> lines) throws IOException {
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (final String line : lines) {
        out.write(line);
        out.write('\n');
      }
    }
  }
}
