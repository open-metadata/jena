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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
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
 * Capture from a real TDB2 dataset laid out as OpenMetadata writes it: one graph per term, axiom
 * and relationship type, and version manifests that pin them. What is copied, every check of the
 * pinned content, and every bound.
 */
class CaptureTest {
  private static final String BASE = "https://open-metadata.org/";
  private static final String ROOT = BASE + "ontology/finance";
  private static final String SHARED = BASE + "ontology/shared";
  private static final String TYPE = BASE + "graph/relationshipType/reportedIn";

  private static final String LAYOUT =
      """
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX : <https://open-metadata.org/finance#>
      GRAPH <https://open-metadata.org/graph/glossaryTerm/1> { :Revenue a owl:Class . }
      GRAPH <https://open-metadata.org/graph/axiom/2> {
        :Revenue rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :reportedIn ;
                                   owl:someValuesFrom :Currency ] .
      }
      GRAPH <https://open-metadata.org/graph/relationshipType/reportedIn> {
        :reportedIn a owl:ObjectProperty .
      }
      GRAPH <https://open-metadata.org/graph/glossaryTerm/7> { :Currency a owl:Class . }
      GRAPH <https://open-metadata.org/graph/glossaryTerm/99> { :Unrelated a owl:Class . }
      """;

  @TempDir Path directory;
  private DatasetGraph dataset;
  private Path spool;
  private Manifests.Published shared;
  private Manifests.Published finance;

  @BeforeEach
  void open() {
    dataset = DatabaseMgr.connectDatasetGraph(Location.create(directory.resolve("serving")));
    Txn.executeWrite(dataset, () -> RDFParser.fromString(LAYOUT, Lang.TRIG).parse(dataset));
    shared =
        Manifests.publish(
            dataset,
            BASE,
            SHARED,
            BASE + "glossary/shared",
            "APPROVED",
            List.of(BASE + "graph/glossaryTerm/7", TYPE),
            List.of());
    finance =
        Manifests.publish(
            dataset,
            BASE,
            ROOT,
            BASE + "glossary/finance",
            "APPROVED",
            List.of(BASE + "graph/glossaryTerm/1", BASE + "graph/axiom/2", TYPE),
            List.of(shared));
    spool = directory.resolve("capture.nq.gz");
  }

  @AfterEach
  void close() {
    TDBInternal.expel(dataset);
  }

  @Test
  void theClosureDigestIsTheDocumentedOne() {
    assertEquals(
        "sha256:14a01107832a95295a2b152ff2d607b774c892caad6b3d0ff642a3b772263eb3",
        Manifests.closureDigest(
            List.of(
                "om-closure-v2",
                "ontology <https://ex.org/o>",
                "glossary <https://ex.org/g>",
                "selection APPROVED",
                "member <https://ex.org/graph/a> sha256:"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "import <https://ex.org/i> sha256:" + "0".repeat(64))),
        "computed from the line format alone, in another language");
  }

  @Test
  void thePinnedGraphsAndManifestHeadersAreCopiedAndNothingElse() throws Exception {
    final Capture.Result result = capture(selection(finance), bounds());
    assertEquals(List.of(finance.graph(), shared.graph()), result.manifests());
    assertEquals(
        List.of(
            finance.graph(),
            BASE + "graph/axiom/2",
            BASE + "graph/glossaryTerm/1",
            TYPE,
            shared.graph(),
            BASE + "graph/glossaryTerm/7"),
        result.graphs());
    assertEquals(finance.versionIri(), result.versionIri());
    final DatasetGraph copied = DatasetGraphFactory.create();
    RDFDataMgr.read(copied, spool.toString());
    final Set<String> graphs = new TreeSet<>();
    copied.listGraphNodes().forEachRemaining(graph -> graphs.add(graph.getURI()));
    assertEquals(new TreeSet<>(result.graphs()), graphs);
    assertEquals(result.triples(), copied.stream().count());
    assertFalse(
        copied.stream().anyMatch(quad -> quad.getPredicate().getURI().startsWith(Manifests.OMR)),
        "control triples never reach the reasoner");
    assertTrue(
        copied.contains(
            NodeFactory.createURI(finance.graph()),
            NodeFactory.createURI(ROOT),
            NodeFactory.createURI("http://www.w3.org/2002/07/owl#imports"),
            NodeFactory.createURI(shared.versionIri())));
    final Node axiom = NodeFactory.createURI(BASE + "graph/axiom/2");
    assertEquals(
        Txn.calculateRead(dataset, () -> dataset.getGraph(axiom).size()),
        copied.getGraph(axiom).size(),
        "blank nodes survive the copy");
    assertEquals(Files.size(spool), result.bytes());

    Files.delete(spool);
    Txn.executeWrite(
        dataset,
        () ->
            RDFParser.fromString(
                    """
                    PREFIX omr: <https://open-metadata.org/reasoning/>
                    GRAPH <https://open-metadata.org/graph/ontology/activations> {
                      <https://open-metadata.org/glossary/finance>
                        omr:activeManifest <%s> ;
                        omr:ontology <https://open-metadata.org/ontology/finance> .
                    }
                    """
                        .formatted(finance.graph()),
                    Lang.TRIG)
                .parse(dataset));
    assertEquals(
        result.graphs(),
        capture(new Capture.Selection(ROOT, "APPROVED", null), bounds()).graphs(),
        "an Approved check without a digest uses the active manifest");
  }

  @Test
  void contentThatChangedSinceItWasPinnedIsRefused() throws Exception {
    final String term = BASE + "graph/glossaryTerm/7";
    Txn.executeWrite(
        dataset,
        () ->
            dataset.add(
                NodeFactory.createURI(term),
                NodeFactory.createURI(BASE + "finance#Currency"),
                NodeFactory.createURI("http://www.w3.org/2000/01/rdf-schema#label"),
                NodeFactory.createLiteralString("Currency")));
    assertRefused(selection(finance), bounds(), Problem.Code.GENERATION_MISMATCH, term);

    Txn.executeWrite(
        dataset, () -> dataset.removeGraph(NodeFactory.createURI(BASE + "graph/axiom/2")));
    assertRefused(
        selection(finance), bounds(), Problem.Code.GENERATION_MISMATCH, BASE + "graph/axiom/2");
  }

  @Test
  void aPinnedGraphTooSymmetricToDigestIsRefused() {
    final String term = BASE + "graph/glossaryTerm/7";
    final StringBuilder triangles = new StringBuilder("GRAPH <" + term + "> {\n");
    for (int cycle = 0; cycle < 8; cycle++) {
      for (int node = 0; node < 3; node++) {
        triangles
            .append("_:c")
            .append(cycle)
            .append('n')
            .append(node)
            .append(" <http://ex.org/p> _:c")
            .append(cycle)
            .append('n')
            .append((node + 1) % 3)
            .append(" .\n");
      }
    }
    Txn.executeWrite(
        dataset,
        () -> RDFParser.fromString(triangles.append("}\n").toString(), Lang.TRIG).parse(dataset));
    assertRefused(selection(finance), bounds(), Problem.Code.GENERATION_MISMATCH, term);
  }

  /**
   * OpenMetadata publishes no closure whose imports are missing or form a cycle, and its digest has
   * no line for either. A manifest that names a missing manifest is not a manifest the store can
   * check; one that imports itself cannot match its own digest.
   */
  @Test
  void anImportCycleCannotMatchItsDigest() {
    Txn.executeWrite(
        dataset,
        () ->
            dataset.add(
                NodeFactory.createURI(finance.graph()),
                NodeFactory.createURI(finance.versionIri()),
                NodeFactory.createURI(Manifests.OMR + "importsManifest"),
                NodeFactory.createURI(finance.graph())));
    assertRefused(
        selection(finance), bounds(), Problem.Code.GENERATION_MISMATCH, finance.versionIri());
  }

  @Test
  void aManifestOfAnEarlierDigestAlgorithmIsNotChecked() {
    Txn.executeWrite(
        dataset,
        () -> {
          final Node graph = NodeFactory.createURI(finance.graph());
          final Node version = NodeFactory.createURI(finance.versionIri());
          final Node algorithm = NodeFactory.createURI(Manifests.OMR + "digestAlgorithm");
          dataset.delete(
              graph, version, algorithm, NodeFactory.createLiteralString("om-closure-v2"));
          dataset.add(graph, version, algorithm, NodeFactory.createLiteralString("om-closure-v1"));
        });
    assertRefused(selection(finance), bounds(), Problem.Code.NOT_IMPLEMENTED, finance.graph());
  }

  @Test
  void aManifestWhoseMembersDoNotMatchItsDigestIsRefused() {
    Txn.executeWrite(
        dataset,
        () ->
            dataset.add(
                NodeFactory.createURI(finance.graph()),
                NodeFactory.createURI(BASE + "graph/glossaryTerm/99"),
                NodeFactory.createURI(Manifests.OMR + "pinnedDigest"),
                NodeFactory.createLiteralString("sha256:" + "0".repeat(64))));
    assertRefused(
        selection(finance), bounds(), Problem.Code.GENERATION_MISMATCH, finance.versionIri());
  }

  @Test
  void aClosureTheStoreDoesNotHoldIsMissing() {
    assertRefused(
        new Capture.Selection(ROOT, "APPROVED", "sha256:" + "1".repeat(64)),
        bounds(),
        Problem.Code.MISSING_IMPORT,
        ROOT);
    assertRefused(
        new Capture.Selection(ROOT, "CANDIDATE", finance.digest()),
        bounds(),
        Problem.Code.MISSING_IMPORT,
        ROOT);
    assertRefused(
        new Capture.Selection(ROOT, "CANDIDATE", null),
        bounds(),
        Problem.Code.INVALID_REQUEST,
        ROOT);
    assertRefused(
        new Capture.Selection(ROOT, "APPROVED", null), bounds(), Problem.Code.MISSING_IMPORT, ROOT);
    Txn.executeWrite(dataset, () -> dataset.removeGraph(NodeFactory.createURI(shared.graph())));
    assertRefused(selection(finance), bounds(), Problem.Code.MISSING_IMPORT, shared.graph());
  }

  @Test
  void everyBoundStopsTheCopyAndLeavesNoSpool() throws Exception {
    final long later = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    assertRefused(
        selection(finance),
        new Capture.Bounds(3, 1_000, 1 << 20, later),
        Problem.Code.LIMIT_EXCEEDED,
        "maxImports");
    assertRefused(
        selection(finance),
        new Capture.Bounds(100, 8, 1 << 20, later),
        Problem.Code.LIMIT_EXCEEDED,
        "maxClosureAxioms");
    assertRefused(
        selection(finance),
        new Capture.Bounds(100, 1_000, 10, later),
        Problem.Code.LIMIT_EXCEEDED,
        "maxDiskBytes");
    assertRefused(
        selection(finance),
        new Capture.Bounds(100, 1_000, 1 << 20, System.nanoTime() - 1),
        Problem.Code.LIMIT_EXCEEDED,
        "maxJobSeconds");
    assertThrows(
        Capture.Cancelled.class,
        () -> Capture.run(dataset, selection(finance), spool, bounds(), () -> true));
    assertNoSpool();
  }

  /**
   * The capture holds one read transaction, never the writer: a write to the serving dataset
   * commits while the capture is in the middle of its copy, and the capture still sees the dataset
   * as it was when it began, so the content it verified is the content it copied.
   */
  @Test
  void writersAreNotBlockedAndTheCaptureSeesOneVersion() throws Exception {
    final StringBuilder big = new StringBuilder("GRAPH <" + BASE + "graph/glossaryTerm/big> {\n");
    for (int i = 0; i < 20_000; i++) {
      big.append("<https://open-metadata.org/finance#c")
          .append(i)
          .append("> a <http://www.w3.org/2002/07/owl#Class> .\n");
    }
    Txn.executeWrite(
        dataset,
        () -> RDFParser.fromString(big.append("}\n").toString(), Lang.TRIG).parse(dataset));
    final Manifests.Published large =
        Manifests.publish(
            dataset,
            BASE,
            BASE + "ontology/large",
            BASE + "glossary/large",
            "APPROVED",
            List.of(BASE + "graph/glossaryTerm/big"),
            List.of());
    final AtomicBoolean wrote = new AtomicBoolean();
    final AtomicLong writeMillis = new AtomicLong(-1);
    final Capture.Result result =
        Capture.run(
            dataset,
            selection(large),
            spool,
            bounds(),
            () -> {
              if (wrote.compareAndSet(false, true)) {
                final Thread writer =
                    new Thread(
                        () -> {
                          final long start = System.nanoTime();
                          Txn.executeWrite(
                              dataset,
                              () ->
                                  dataset.add(
                                      NodeFactory.createURI(BASE + "graph/glossaryTerm/big"),
                                      NodeFactory.createURI(BASE + "finance#Late"),
                                      NodeFactory.createURI(
                                          "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                                      NodeFactory.createURI(
                                          "http://www.w3.org/2002/07/owl#Class")));
                          writeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                        });
                writer.start();
                try {
                  writer.join(10_000);
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              return false;
            });
    assertTrue(writeMillis.get() >= 0, "the write did not commit during the capture");
    assertTrue(writeMillis.get() < 5_000, writeMillis.get() + " ms");
    assertEquals(20_002, result.triples(), "two header triples; the write is not in it");
    final DatasetGraph copied = DatasetGraphFactory.create();
    RDFDataMgr.read(copied, spool.toString());
    assertFalse(
        copied.contains(
            Node.ANY, NodeFactory.createURI(BASE + "finance#Late"), Node.ANY, Node.ANY));
  }

  /** The image smoke test checks this closure; its manifest must keep matching its graphs. */
  @Test
  void theSmokeTestsClosureStillMatchesItsManifest() throws Exception {
    final Path fixture = Path.of("..", "test", "check-closure.trig");
    final String digest =
        Files.readAllLines(fixture).stream()
            .filter(line -> line.startsWith("# closure-digest "))
            .findFirst()
            .orElseThrow()
            .substring("# closure-digest ".length());
    final DatasetGraph smoke =
        DatabaseMgr.connectDatasetGraph(Location.create(directory.resolve("smoke")));
    try {
      Txn.executeWrite(smoke, () -> RDFDataMgr.read(smoke, fixture.toString()));
      final Capture.Result result =
          Capture.run(
              smoke, new Capture.Selection(ROOT, "APPROVED", digest), spool, bounds(), () -> false);
      assertEquals(6, result.graphs().size());
      assertFalse(result.graphs().contains(BASE + "graph/draft/loan"));
    } finally {
      TDBInternal.expel(smoke);
    }
  }

  private static Capture.Selection selection(final Manifests.Published published) {
    return new Capture.Selection(published.ontologyIri(), "APPROVED", published.digest());
  }

  private Capture.Bounds bounds() {
    return new Capture.Bounds(
        100, 1_000_000, 1L << 30, System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
  }

  private Capture.Result capture(final Capture.Selection selection, final Capture.Bounds bounds)
      throws Exception {
    return Capture.run(dataset, selection, spool, bounds, () -> false);
  }

  private void assertRefused(
      final Capture.Selection selection,
      final Capture.Bounds bounds,
      final Problem.Code code,
      final String subject) {
    final Capture.Refused refused =
        assertThrows(Capture.Refused.class, () -> capture(selection, bounds));
    assertEquals(code, refused.problem().code(), refused.getMessage());
    assertEquals(subject, refused.problem().subject(), refused.getMessage());
    assertNoSpool();
  }

  private void assertNoSpool() {
    assertFalse(Files.exists(spool));
    assertFalse(Files.exists(spool.resolveSibling("capture.nq.gz.tmp")));
  }
}
