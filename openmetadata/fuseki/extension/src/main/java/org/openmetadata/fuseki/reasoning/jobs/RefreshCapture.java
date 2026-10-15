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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import org.apache.jena.vocabulary.RDF;

/**
 * Captures what a REFRESH reasons over, inside one read transaction of the serving dataset, into
 * one gzip-compressed N-Quads spool in the job directory (the plan's refresh lifecycle, step 2):
 *
 * <ol>
 *   <li>the rule bundle the request names, verified against its digest ({@link RuleBundle});
 *   <li>every active Approved closure that {@code graph/ontology/activations} names, each manifest
 *       and pinned graph verified as a CHECK's capture verifies them, with the request's
 *       ontologyDigest checked against the activations ({@code om-activations-v1});
 *   <li>the catalog: every triple whose predicate a rule can match, from every graph that is not an
 *       ontology graph, a control graph or a derived graph, so a Draft axiom, a manifest or a
 *       previous run's conclusions never reach the rules.
 * </ol>
 *
 * <p>Everything is streamed: the server holds one pinned graph, one closure's graph names, the
 * catalog's graph names (at most {@value #MAX_CATALOG_GRAPHS}) and a bounded cache of graph
 * decisions, never the catalog. Every 4,096 quads the copy checks the disk the quota leaves, the
 * job's deadline and cancellation. A read transaction never blocks Fuseki's writer.
 */
public final class RefreshCapture {
  public static final String ACTIVATIONS_ALGORITHM = "om-activations-v1";

  /** Closures one refresh classifies, each in a worker step of its own. */
  public static final int MAX_CLOSURES = 1_000;

  /** Graphs the catalog part of a capture may span; OpenMetadata writes it into a few. */
  public static final int MAX_CATALOG_GRAPHS = 10_000;

  /**
   * Graphs the rules may read in all, closures and catalog together: the worker's limit on a RULES
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
   * Graphs of OpenMetadata's that are never rule input, by the path OpenMetadata gives them under
   * its base IRI: control graphs (manifests, activations, member records, rule bundles) and the
   * phase 0 materializer's derived graphs.
   */
  static final List<String> EXCLUDED_PATHS =
      List.of(
          "/graph/inferred/",
          "/graph/manifest/",
          "/graph/manifestStaging/",
          "/graph/ontology/",
          "/graph/rules/");

  private static final int CHECK_EVERY = 4096;
  private static final String ACTIVATIONS_PATH = "graph/ontology/activations";
  private static final Node TYPE = RDF.type.asNode();
  private static final Node ONTOLOGY_GRAPH = NodeFactory.createURI(Capture.OMR + "OntologyGraph");
  private static final Node ACTIVE_MANIFEST = NodeFactory.createURI(Capture.OMR + "activeManifest");
  private static final Node CLOSURE_DIGEST = NodeFactory.createURI(Capture.OMR + "closureDigest");

  /** One captured closure: what its classify step reads and where its entailments go. */
  public record Closure(
      int index,
      String glossary,
      String ontologyIri,
      String versionIri,
      String closureDigest,
      int manifests,
      int graphs,
      String graphsFile,
      String entailments) {
    JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("index", index);
      json.put("glossary", glossary);
      json.put("ontologyIri", ontologyIri);
      json.put("versionIri", versionIri);
      json.put("closureDigest", closureDigest);
      json.put("manifests", manifests);
      json.put("graphs", graphs);
      json.put("graphsFile", graphsFile);
      json.put("entailmentGraph", entailments);
      return json;
    }

    static Closure fromJson(final JsonObject json) {
      return new Closure(
          json.getNumber("index").intValue(),
          json.getString("glossary"),
          json.getString("ontologyIri"),
          json.getString("versionIri"),
          json.getString("closureDigest"),
          json.getNumber("manifests").intValue(),
          json.getNumber("graphs").intValue(),
          json.getString("graphsFile"),
          json.getString("entailmentGraph"));
    }
  }

  /** What was captured, as capture.json records it. */
  public record Result(
      RuleBundle bundle,
      String activationDigest,
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
      final JsonObject rules = new JsonObject();
      rules.put("digest", bundle.digest());
      if (bundle.graph() != null) {
        rules.put("graph", bundle.graph());
      }
      rules.put("count", bundle.rules().size());
      json.put("rules", rules);
      json.put("activationDigest", activationDigest);
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

  /** The graph a closure's entailments are written to in the working dataset and the snapshot. */
  public static String entailmentGraph(final String closureDigest) {
    return STORE_GRAPHS + "entailments:" + closureDigest.substring(GraphDigest.PREFIX.length());
  }

  /** The graph a rule's conclusions are written to. */
  public static String ruleGraph(final String rule) {
    return STORE_GRAPHS + "inferred:" + rule;
  }

  /** The graph the RULES step writes its witnesses to; never visible to snapshot queries. */
  public static final String PROVENANCE = STORE_GRAPHS + "provenance";

  /**
   * Captures the input of a REFRESH into {@code directory}: the spool, one graphs file per closure,
   * the rules' read list and the rules. The spool exists only if the capture succeeds.
   *
   * @param ruleBundleDigest the request's digest
   * @param inlineRules the request's rules, or null to read the published bundle
   * @param ontologyDigest the request's activation digest, or null to accept the activations as
   *     they are
   */
  public static Result run(
      final DatasetGraph dataset,
      final String ruleBundleDigest,
      final JsonArray inlineRules,
      final String ontologyDigest,
      final Path directory,
      final Capture.Bounds bounds,
      final BooleanSupplier cancelled)
      throws Capture.Refused, Capture.Cancelled, IOException {
    final long start = System.nanoTime();
    final Path spool = directory.resolve(Plans.SPOOL);
    final Path partial = spool.resolveSibling(spool.getFileName() + ".tmp");
    final Path closureDirectory = Files.createDirectories(directory.resolve(CLOSURES));
    boolean complete = false;
    dataset.begin(TxnType.READ);
    try {
      final RuleBundle bundle =
          inlineRules == null
              ? RuleBundle.published(dataset, ruleBundleDigest)
              : RuleBundle.inline(inlineRules, ruleBundleDigest);
      final TreeMap<String, Activation> activations = activations(dataset);
      final String activationDigest = activationDigest(activations);
      if (ontologyDigest != null && !ontologyDigest.equals(activationDigest)) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.GENERATION_MISMATCH,
                    "The active closures digest to "
                        + activationDigest
                        + " ("
                        + ACTIVATIONS_ALGORITHM
                        + "), not the request's ontologyDigest "
                        + ontologyDigest
                        + "; an ontology was activated since the request was made")
                .withSubject(ACTIVATIONS_PATH));
      }
      if (activations.size() > MAX_CLOSURES) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    activations.size() + " closures are active, more than " + MAX_CLOSURES)
                .withSubject("maxImports"));
      }
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
        for (final Activation activation : activations.values()) {
          final Capture.Copy copy = new Capture.Copy(bounds, cancelled);
          copy.file = file;
          final Closure closure =
              closure(dataset, activation, closures.size(), writer, copy, closureDirectory, read);
          closures.add(closure);
          triples += copy.triples;
        }
        final Capture.Copy catalogCopy =
            new Capture.Copy(
                new Capture.Bounds(
                    bounds.maxGraphs(), Long.MAX_VALUE, bounds.maxBytes(), bounds.deadlineNanos()),
                cancelled);
        catalogCopy.file = file;
        final Set<String> catalog = new LinkedHashSet<>();
        catalogTriples = catalog(dataset, predicates, writer, catalogCopy, catalog);
        catalogGraphs = List.copyOf(catalog);
        triples += catalogTriples;
        read.addAll(catalog);
        writer.finish();
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
          bundle,
          activationDigest,
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

  /** One glossary's activation: the manifest it names and the closure digest it records. */
  record Activation(String glossary, Node manifest, String closureDigest) {}

  /**
   * The activations, by glossary IRI, from every graph named {@code graph/ontology/activations}
   * under some base; a glossary with more than one active manifest is refused.
   */
  static TreeMap<String, Activation> activations(final DatasetGraph dataset)
      throws Capture.Refused {
    final TreeMap<String, Activation> activations = new TreeMap<>();
    final Iterator<Quad> quads = dataset.find(Node.ANY, Node.ANY, ACTIVE_MANIFEST, Node.ANY);
    while (quads.hasNext()) {
      final Quad quad = quads.next();
      if (!quad.getGraph().isURI()
          || !quad.getGraph().getURI().endsWith(ACTIVATIONS_PATH)
          || !quad.getSubject().isURI()
          || !quad.getObject().isURI()) {
        continue;
      }
      final Iterator<Quad> digests =
          dataset.find(quad.getGraph(), quad.getSubject(), CLOSURE_DIGEST, Node.ANY);
      final String digest =
          digests.hasNext() ? digests.next().getObject().getLiteralLexicalForm() : null;
      final String glossary = quad.getSubject().getURI();
      if (digest == null || activations.containsKey(glossary)) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.GENERATION_MISMATCH,
                    "The activation of <"
                        + glossary
                        + "> "
                        + (digest == null
                            ? "has no closure digest"
                            : "names more than one manifest"))
                .withSubject(quad.getGraph().getURI()));
      }
      activations.put(glossary, new Activation(glossary, quad.getObject(), digest));
    }
    return activations;
  }

  /**
   * The {@value #ACTIVATIONS_ALGORITHM} digest: SHA-256 over the line {@code om-activations-v1} and
   * one line {@code active <glossary IRI> <closure digest>} per activation in glossary-IRI order,
   * each ending in a newline (OpenMetadata's
   * ADR:2026-10-15-inference-runs-record-the-input-they-captured).
   */
  static String activationDigest(final Map<String, Activation> activations) {
    final MessageDigest sha256 = GraphDigest.sha256();
    sha256.update((ACTIVATIONS_ALGORITHM + "\n").getBytes(StandardCharsets.UTF_8));
    for (final Activation activation : new TreeMap<>(activations).values()) {
      sha256.update(
          ("active <" + activation.glossary() + "> " + activation.closureDigest() + "\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return GraphDigest.PREFIX + HexFormat.of().formatHex(sha256.digest());
  }

  private static Closure closure(
      final DatasetGraph dataset,
      final Activation activation,
      final int index,
      final StreamRDF writer,
      final Capture.Copy copy,
      final Path closureDirectory,
      final Set<String> read)
      throws Capture.Refused, Capture.Cancelled, IOException {
    final Capture.Manifest root = Capture.Manifest.read(dataset, activation.manifest());
    if (!root.closureDigest().equals(activation.closureDigest())
        || !"APPROVED".equals(root.selection())) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.GENERATION_MISMATCH,
                  "The activation of <"
                      + activation.glossary()
                      + "> names manifest <"
                      + activation.manifest().getURI()
                      + ">, which is a "
                      + root.selection()
                      + " closure with digest "
                      + root.closureDigest()
                      + ", not the APPROVED closure "
                      + activation.closureDigest())
              .withSubject(root.versionIri().getURI()));
    }
    final List<String> manifests = new ArrayList<>();
    final Set<String> graphs = new LinkedHashSet<>();
    Capture.copyClosure(dataset, activation.manifest(), root, writer, copy, manifests, graphs);
    final String file = CLOSURES + "/" + index + ".graphs";
    writeLines(closureDirectory.resolve(index + ".graphs"), graphs);
    read.addAll(graphs);
    if (read.size() > MAX_READ_GRAPHS) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.LIMIT_EXCEEDED,
                  "The active closures span more than " + MAX_READ_GRAPHS + " graphs")
              .withSubject("maxImports"));
    }
    return new Closure(
        index,
        activation.glossary(),
        root.ontologyIri(),
        root.versionIri().getURI(),
        root.closureDigest(),
        manifests.size(),
        graphs.size(),
        file,
        entailmentGraph(root.closureDigest()));
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
    final GraphDecisions decisions = new GraphDecisions(dataset);
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
    private final DatasetGraph dataset;
    private final Map<Node, Boolean> decided =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<Node, Boolean> eldest) {
            return size() > GRAPH_DECISIONS;
          }
        };

    GraphDecisions(final DatasetGraph dataset) {
      this.dataset = dataset;
    }

    boolean include(final Node graph) {
      return decided.computeIfAbsent(graph, this::decide);
    }

    private boolean decide(final Node graph) {
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
      // An ontology graph is read only as part of a closure some activation pins; any other, such
      // as a Draft axiom's, is no input at all.
      return !dataset.find(Node.ANY, graph, TYPE, ONTOLOGY_GRAPH).hasNext();
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
