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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A REFRESH capture from a real TDB2 dataset laid out as OpenMetadata writes it: the active
 * closures, verified, and the catalog triples the rules read, without Draft, control or derived
 * graphs.
 */
class RefreshCaptureTest {
  private static final String BASE = "https://open-metadata.org/";
  private static final String GRAPH = BASE + "graph/";
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String ROOT = BASE + "ontology/finance";
  private static final String GLOSSARY = BASE + "glossary/finance";
  private static final String RULE =
      "PREFIX om: <"
          + OM
          + "> CONSTRUCT { ?d om:hasTag ?t } WHERE { ?u om:hasTag ?t ."
          + " ?l om:fromColumn ?u ; om:toColumn ?d }";

  private static final String LAYOUT =
      """
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX om: <https://open-metadata.org/ontology/>
PREFIX omr: <https://open-metadata.org/reasoning/>
GRAPH <https://open-metadata.org/graph/glossaryTerm/asset> {
  <https://open-metadata.org/finance#Asset> a owl:Class .
}
GRAPH <https://open-metadata.org/graph/axiom/draft> { <urn:c:x> om:hasTag <urn:t:draft> . }
GRAPH <https://open-metadata.org/graph/ontology/members> {
  <https://open-metadata.org/graph/glossaryTerm/asset> a omr:OntologyGraph .
  <https://open-metadata.org/graph/axiom/draft> a omr:OntologyGraph .
}
GRAPH <https://open-metadata.org/graph/knowledge> {
  <urn:c:a> om:hasTag <urn:t:pii> ; om:description "not read by the rules" .
  <urn:l:1> om:fromColumn <urn:c:a> ; om:toColumn <urn:c:b> .
}
GRAPH <https://open-metadata.org/graph/lineage> { <urn:l:2> om:fromColumn <urn:c:b> . }
GRAPH <https://open-metadata.org/graph/inferred/old-rule> { <urn:c:z> om:hasTag <urn:t:pii> . }
GRAPH <urn:openmetadata:inferred:old-rule> { <urn:c:y> om:hasTag <urn:t:pii> . }
""";

  @TempDir Path root;
  private DatasetGraph serving;
  private Manifests.Published closure;
  private String bundle;

  @BeforeEach
  void layOut() {
    serving = DatabaseMgr.connectDatasetGraph(Location.create(root.resolve("serving")));
    Txn.executeWrite(serving, () -> RDFParser.fromString(LAYOUT, Lang.TRIG).parse(serving));
    closure =
        Manifests.publish(
            serving,
            BASE,
            ROOT,
            GLOSSARY,
            "APPROVED",
            List.of(GRAPH + "glossaryTerm/asset"),
            List.of());
    activate(closure.digest());
    final List<RuleBundle.Rule> rules = List.of(new RuleBundle.Rule("pii", "CONSTRUCT", RULE));
    bundle = RuleBundle.digest(rules);
    final Node graph = NodeFactory.createURI(GRAPH + "rules/bundle/" + bundle.substring(7));
    final Node rule = NodeFactory.createURI(graph.getURI() + "/rule/pii");
    Txn.executeWrite(
        serving,
        () -> {
          serving.add(graph, graph, omr("bundleDigest"), NodeFactory.createLiteralString(bundle));
          serving.add(
              graph, graph, omr("digestAlgorithm"), NodeFactory.createLiteralString("om-rules-v1"));
          serving.add(graph, graph, omr("rule"), rule);
          serving.add(graph, rule, omr("ruleName"), NodeFactory.createLiteralString("pii"));
          serving.add(graph, rule, omr("ruleType"), NodeFactory.createLiteralString("CONSTRUCT"));
          serving.add(graph, rule, omr("ruleBody"), NodeFactory.createLiteralString(RULE));
        });
  }

  @AfterEach
  void close() {
    TDBInternal.expel(serving);
  }

  @Test
  void capturesTheClosuresAndOnlyTheCatalogTriplesTheRulesRead() throws Exception {
    final RefreshCapture.Result result = capture(null, Long.MAX_VALUE);
    assertEquals(1, result.closures().size());
    assertEquals(closure.digest(), result.closures().get(0).closureDigest());
    assertEquals(Set.of(OM + "fromColumn", OM + "hasTag", OM + "toColumn"), result.predicates());
    assertEquals(List.of(GRAPH + "knowledge", GRAPH + "lineage"), sorted(result.catalogGraphs()));
    assertEquals(4, result.catalogTriples());

    final DatasetGraph spool = spool();
    final Set<String> graphs = new TreeSet<>();
    spool.listGraphNodes().forEachRemaining(graph -> graphs.add(graph.getURI()));
    assertEquals(
        Set.of(
            closure.graph(), GRAPH + "glossaryTerm/asset", GRAPH + "knowledge", GRAPH + "lineage"),
        graphs,
        "no Draft, member, activation, bundle or derived graph");
    assertFalse(
        spool.contains(Node.ANY, Node.ANY, NodeFactory.createURI(OM + "description"), Node.ANY),
        "a predicate no rule reads");

    final List<String> read =
        Files.readAllLines(root.resolve("job").resolve(RefreshCapture.READ_FILE));
    assertTrue(read.contains(GRAPH + "knowledge"));
    assertTrue(read.contains(GRAPH + "glossaryTerm/asset"));
    assertTrue(read.contains(RefreshCapture.entailmentGraph(closure.digest())));
    final JsonArray rules =
        JSON.parseAny(Files.readString(root.resolve("job").resolve(RefreshCapture.RULES_FILE)))
            .getAsArray();
    assertEquals("pii", rules.get(0).getAsObject().getString("name"));
    assertEquals(
        List.of(GRAPH + "glossaryTerm/asset", closure.graph()),
        sorted(
            Files.readAllLines(
                root.resolve("job").resolve(result.closures().get(0).graphsFile()))));
  }

  @Test
  void theActivationDigestPinsTheActiveClosures() throws Exception {
    final String digest = RefreshCapture.activationDigest(RefreshCapture.activations(readOnly()));
    assertEquals(digest, capture(digest, Long.MAX_VALUE).activationDigest());
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class, () -> capture("sha256:" + "1".repeat(64), Long.MAX_VALUE));
    assertEquals(Problem.Code.GENERATION_MISMATCH, refused.problem().code());

    activate("sha256:" + "2".repeat(64));
    final Capture.Refused stale =
        assertThrows(Capture.Refused.class, () -> capture(null, Long.MAX_VALUE));
    assertEquals(
        Problem.Code.GENERATION_MISMATCH,
        stale.problem().code(),
        "activation and manifest disagree");
  }

  @Test
  void theDiskQuotaBoundsTheSpool() {
    final Capture.Refused refused = assertThrows(Capture.Refused.class, () -> capture(null, 10));
    assertEquals("maxDiskBytes", refused.problem().subject());
    assertFalse(Files.exists(root.resolve("job").resolve(Plans.SPOOL)));
    assertFalse(Files.exists(root.resolve("job").resolve(Plans.SPOOL + ".tmp")));
  }

  private RefreshCapture.Result capture(final String ontologyDigest, final long maxBytes)
      throws Exception {
    return RefreshCapture.run(
        serving,
        bundle,
        null,
        ontologyDigest,
        Files.createDirectories(root.resolve("job")),
        new Capture.Bounds(
            Operations.MAX_IMPORTS,
            1_000_000,
            maxBytes,
            System.nanoTime() + TimeUnit.MINUTES.toNanos(1)),
        () -> false);
  }

  private DatasetGraph readOnly() {
    final DatasetGraph copy = DatasetGraphFactory.createTxnMem();
    Txn.executeRead(serving, () -> serving.find().forEachRemaining(copy::add));
    return copy;
  }

  private void activate(final String digest) {
    final Node graph = NodeFactory.createURI(GRAPH + "ontology/activations");
    final Node glossary = NodeFactory.createURI(GLOSSARY);
    Txn.executeWrite(
        serving,
        () -> {
          serving.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
          serving.add(
              graph, glossary, omr("activeManifest"), NodeFactory.createURI(closure.graph()));
          serving.add(
              graph, glossary, omr("closureDigest"), NodeFactory.createLiteralString(digest));
        });
  }

  private DatasetGraph spool() throws Exception {
    final DatasetGraph spool = DatasetGraphFactory.createTxnMem();
    try (InputStream in =
        new GZIPInputStream(Files.newInputStream(root.resolve("job").resolve(Plans.SPOOL)))) {
      RDFParser.source(in).lang(Lang.NQUADS).parse(spool);
    }
    return spool;
  }

  private static List<String> sorted(final List<String> values) {
    return values.stream().sorted().toList();
  }

  private static Node omr(final String name) {
    return NodeFactory.createURI(Capture.OMR + name);
  }
}
