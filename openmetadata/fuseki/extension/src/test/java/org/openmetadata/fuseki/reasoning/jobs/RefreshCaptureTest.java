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
import java.util.UUID;
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
 * A REFRESH capture from a real TDB2 dataset laid out as OpenMetadata writes it: the listed
 * releases, each in its ontology's graph, and the catalog triples the rules read, without any other
 * ontology, staging or derived graph.
 */
class RefreshCaptureTest {
  private static final String BASE = "https://open-metadata.org/";
  private static final String GRAPH = BASE + "graph/";
  private static final String OM = BASE + "ontology/";
  private static final String RULE =
      "PREFIX om: <"
          + OM
          + "> CONSTRUCT { ?d om:hasTag ?t } WHERE { ?u om:hasTag ?t ."
          + " ?l om:fromColumn ?u ; om:toColumn ?d }";
  private static final String SECOND_ONTOLOGY_IRI = BASE + "ontology/risk";

  private static final String LAYOUT =
      """
PREFIX om: <https://open-metadata.org/ontology/>
GRAPH <https://open-metadata.org/graph/knowledge> {
  <urn:c:a> om:hasTag <urn:t:pii> ; om:description "not read by the rules" .
  <urn:l:1> om:fromColumn <urn:c:a> ; om:toColumn <urn:c:b> .
}
GRAPH <https://open-metadata.org/graph/lineage> { <urn:l:2> om:fromColumn <urn:c:b> . }
GRAPH <https://open-metadata.org/graph/inferred/old-rule> { <urn:c:z> om:hasTag <urn:t:pii> . }
GRAPH <urn:openmetadata:inferred:old-rule> { <urn:c:y> om:hasTag <urn:t:pii> . }
GRAPH <https://open-metadata.org/graph/staging/knowledge-7> { <urn:c:s> om:hasTag <urn:t:staged> . }
GRAPH <https://open-metadata.org/graph/ontology/00000000-0000-4000-8000-00000000dead> {
  <urn:c:u> om:hasTag <urn:t:unlisted> .
}
""";

  @TempDir Path root;
  private DatasetGraph serving;
  private Releases.Release finance;

  @BeforeEach
  void layOut() {
    serving = DatabaseMgr.connectDatasetGraph(Location.create(root.resolve("serving")));
    Txn.executeWrite(serving, () -> RDFParser.fromString(LAYOUT, Lang.TRIG).parse(serving));
    finance = Releases.of(":Asset a owl:Class .\n");
    activate(finance);
  }

  @AfterEach
  void close() {
    TDBInternal.expel(serving);
  }

  @Test
  void capturesTheListedReleasesAndOnlyTheCatalogTriplesTheRulesRead() throws Exception {
    final RefreshCapture.Result result = capture(List.of(release(finance)), Long.MAX_VALUE);
    assertEquals(1, result.closures().size());
    final RefreshCapture.Closure closure = result.closures().get(0);
    assertEquals(finance.releaseId().toString(), closure.releaseId());
    assertEquals(Releases.ONTOLOGY_IRI, closure.ontologyIri());
    assertEquals(Capture.RELEASE_IRI_PREFIX + finance.releaseId(), closure.versionIri());
    assertEquals(Set.of(OM + "fromColumn", OM + "hasTag", OM + "toColumn"), result.predicates());
    assertEquals(List.of(GRAPH + "knowledge", GRAPH + "lineage"), sorted(result.catalogGraphs()));
    assertEquals(4, result.catalogTriples());

    final DatasetGraph spool = spool();
    final Set<String> graphs = new TreeSet<>();
    spool.listGraphNodes().forEachRemaining(graph -> graphs.add(graph.getURI()));
    assertEquals(
        Set.of(releaseGraph(finance), GRAPH + "knowledge", GRAPH + "lineage"),
        graphs,
        "neither the unlisted ontology, a staging nor a derived graph");
    assertFalse(
        spool.contains(Node.ANY, Node.ANY, NodeFactory.createURI(OM + "description"), Node.ANY),
        "a predicate no rule reads");

    final List<String> read =
        Files.readAllLines(root.resolve("job").resolve(RefreshCapture.READ_FILE));
    assertTrue(read.contains(GRAPH + "knowledge"));
    assertTrue(read.contains(releaseGraph(finance)));
    assertTrue(read.contains(RefreshCapture.entailmentGraph(closure.releaseId())));
    final JsonArray rules =
        JSON.parseAny(Files.readString(root.resolve("job").resolve(RefreshCapture.RULES_FILE)))
            .getAsArray();
    assertEquals("pii", rules.get(0).getAsObject().getString("name"));
    assertEquals(
        List.of(releaseGraph(finance)),
        Files.readAllLines(root.resolve("job").resolve(closure.graphsFile())));
  }

  @Test
  void everyListedReleaseIsItsOwnClosure() throws Exception {
    final UUID releaseId = UUID.randomUUID();
    final Releases.Release second =
        Releases.raw(
            UUID.randomUUID(),
            releaseId,
            "<%s> a owl:Ontology ; owl:versionIRI <%s%s> .\n:Risk a owl:Class .\n"
                .formatted(SECOND_ONTOLOGY_IRI, Capture.RELEASE_IRI_PREFIX, releaseId));
    activate(second);
    final RefreshCapture.Result result =
        capture(List.of(release(finance), release(second)), Long.MAX_VALUE);
    assertEquals(
        List.of(Releases.ONTOLOGY_IRI, SECOND_ONTOLOGY_IRI),
        result.closures().stream().map(RefreshCapture.Closure::ontologyIri).toList());
    assertEquals(
        List.of(
            RefreshCapture.entailmentGraph(finance.releaseId().toString()),
            RefreshCapture.entailmentGraph(second.releaseId().toString())),
        result.closures().stream().map(RefreshCapture.Closure::entailments).toList());
    assertEquals(
        List.of(releaseGraph(second)),
        Files.readAllLines(root.resolve("job").resolve(result.closures().get(1).graphsFile())));
  }

  @Test
  void aRefreshWithoutActiveReleasesStillRunsTheRulesOverTheCatalog() throws Exception {
    final RefreshCapture.Result result = capture(List.of(), Long.MAX_VALUE);
    assertEquals(List.of(), result.closures());
    assertEquals(4, result.catalogTriples());
    assertEquals(
        List.of(GRAPH + "knowledge", GRAPH + "lineage"),
        Files.readAllLines(root.resolve("job").resolve(RefreshCapture.READ_FILE)).stream()
            .sorted()
            .toList());
  }

  @Test
  void aGraphThatStatesAnotherReleaseIsInputChanged() {
    final RefreshCapture.Release newer =
        new RefreshCapture.Release(
            finance.ontologyId().toString(), UUID.randomUUID().toString(), "0".repeat(64));
    final Capture.Refused refused =
        assertThrows(Capture.Refused.class, () -> capture(List.of(newer), Long.MAX_VALUE));
    assertEquals(Problem.Code.INPUT_CHANGED, refused.problem().code());
    assertEquals(finance.ontologyId().toString(), refused.problem().subject());
    assertFalse(Files.exists(root.resolve("job").resolve(Plans.SPOOL)));
  }

  @Test
  void aListedReleaseWhoseGraphIsMissingIsInputChanged() {
    final RefreshCapture.Release gone =
        new RefreshCapture.Release(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), "0".repeat(64));
    final Capture.Refused refused =
        assertThrows(Capture.Refused.class, () -> capture(List.of(gone), Long.MAX_VALUE));
    assertEquals(Problem.Code.INPUT_CHANGED, refused.problem().code());
    assertEquals(gone.ontologyId(), refused.problem().subject());
  }

  @Test
  void anImportIsMissingBecauseNoOtherOntologyIsInTheInput() {
    final UUID release = UUID.randomUUID();
    final Releases.Release importing =
        Releases.raw(
            UUID.randomUUID(),
            release,
            Releases.header(release)
                + "<%s> owl:imports <urn:other> .\n".formatted(Releases.ONTOLOGY_IRI));
    activate(importing);
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class, () -> capture(List.of(release(importing)), Long.MAX_VALUE));
    assertEquals(Problem.Code.MISSING_IMPORT, refused.problem().code());
    assertEquals("urn:other", refused.problem().subject());
  }

  @Test
  void theDiskQuotaBoundsTheSpool() {
    final Capture.Refused refused =
        assertThrows(Capture.Refused.class, () -> capture(List.of(release(finance)), 10));
    assertEquals("maxDiskBytes", refused.problem().subject());
    assertFalse(Files.exists(root.resolve("job").resolve(Plans.SPOOL)));
    assertFalse(Files.exists(root.resolve("job").resolve(Plans.SPOOL + ".tmp")));
  }

  private RefreshCapture.Result capture(
      final List<RefreshCapture.Release> releases, final long maxBytes) throws Exception {
    return RefreshCapture.run(
        serving,
        releases,
        RuleBundle.of(
            JSON.parseAny(
                    "[{\"name\": \"pii\", \"ruleBody\": \"" + RULE.replace("\"", "\\\"") + "\"}]")
                .getAsArray()),
        Files.createDirectories(root.resolve("job")),
        new Capture.Bounds(1_000_000, maxBytes, System.nanoTime() + TimeUnit.MINUTES.toNanos(1)),
        () -> false);
  }

  private static RefreshCapture.Release release(final Releases.Release release) {
    return new RefreshCapture.Release(
        release.ontologyId().toString(), release.releaseId().toString(), "0".repeat(64));
  }

  private static String releaseGraph(final Releases.Release release) {
    return Capture.ONTOLOGY_GRAPH_PREFIX + release.ontologyId();
  }

  private void activate(final Releases.Release release) {
    Releases.activate(serving, release);
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
}
