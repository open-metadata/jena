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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.jobs.Releases.Release;

/**
 * Capture of an uploaded release: what the spool holds, that the upload is accepted only as the
 * release the request named, and every bound. The uploads are real RDF-Thrift files.
 */
class CaptureTest {
  /** An axiom with its source member, as the release export keeps it. */
  private static final String MEMBERS =
      """
      dct:source a owl:AnnotationProperty .
      :Asset a owl:Class .
      :Liability a owl:Class .
      :Asset owl:disjointWith :Liability .
      [] a owl:Axiom ; owl:annotatedSource :Asset ; owl:annotatedProperty owl:disjointWith ;
         owl:annotatedTarget :Liability ; dct:source <urn:openmetadata:ontology-member:7> .
      """;

  @TempDir Path directory;
  private Path spool;
  private Path uploads;

  @BeforeEach
  void open() throws IOException {
    spool = directory.resolve("capture.nq.gz");
    uploads = Files.createDirectories(directory.resolve("inputs"));
  }

  @Test
  void aReleaseIsSpooledIntoOneGraphWithItsAxiomSourcesAndItsHeader() throws Exception {
    final Release release = Releases.of(MEMBERS);
    final Capture.Result captured = capture(release);

    assertEquals(Releases.ONTOLOGY_IRI, captured.root());
    assertEquals(Capture.RELEASE_IRI_PREFIX + release.releaseId(), captured.versionIri());
    final Node graph = NodeFactory.createURI(captured.graph());
    assertEquals(Capture.ONTOLOGY_GRAPH_PREFIX + release.ontologyId(), captured.graph());
    final DatasetGraph spooled = read(spool);
    assertEquals(11, captured.triples(), "header, members, and the axiom with its source");
    assertEquals(captured.triples(), spooled.getGraph(graph).size());
    assertEquals(1, spooled.stream().map(quad -> quad.getGraph()).distinct().count());
    assertTrue(
        spooled.contains(
            graph,
            Node.ANY,
            NodeFactory.createURI("http://purl.org/dc/terms/source"),
            NodeFactory.createURI("urn:openmetadata:ontology-member:7")),
        "an axiom's source member survives into the spool");
    assertEquals(Files.size(spool), captured.bytes());
    assertFalse(Files.exists(Path.of(spool + ".tmp")));
  }

  @Test
  void anUploadThatIsNotHeldFailsAsInputChangedNamingTheDigest() {
    final Release release = Releases.of(MEMBERS);
    final Capture.Refused refused = refused(release, release.byteSize());
    assertEquals(Problem.Code.INPUT_CHANGED, refused.problem().code());
    assertEquals(release.digest(), refused.problem().subject());
    assertNoSpool();
  }

  @Test
  void anUploadOfAnotherSizeOrWithOtherBytesFailsAsInputChanged() throws Exception {
    final Release release = Releases.of(MEMBERS);
    hold(release);
    final Capture.Refused size = refused(release, release.byteSize() + 1);
    assertEquals(Problem.Code.INPUT_CHANGED, size.problem().code());
    assertEquals(release.digest(), size.problem().subject());
    assertTrue(size.problem().message().contains("bytes"), size.problem().message());

    final byte[] corrupted = release.rdf().clone();
    corrupted[corrupted.length / 2] ^= 1;
    Files.write(path(release), corrupted);
    final Capture.Refused hash = refused(release, release.byteSize());
    assertEquals(Problem.Code.INPUT_CHANGED, hash.problem().code());
    assertEquals(release.digest(), hash.problem().subject());
    assertTrue(hash.problem().message().contains("now hashes to"), hash.problem().message());
    assertNoSpool();
  }

  @Test
  void aHeaderThatIsNotTheNamedReleasesFailsAsInputChangedNamingTheOntology() throws Exception {
    final UUID ontology = UUID.randomUUID();
    final UUID release = UUID.randomUUID();
    final String other = Releases.header(UUID.randomUUID());
    final String twice =
        Releases.header(release) + "<https://example.org/second> a owl:Ontology .\n";
    final String unversioned = "<" + Releases.ONTOLOGY_IRI + "> a owl:Ontology .\n";
    final String anonymous =
        "[] a owl:Ontology ; owl:versionIRI <" + Capture.RELEASE_IRI_PREFIX + release + "> .\n";
    final String versionedElsewhere =
        Releases.header(release)
            + "<https://example.org/second> owl:versionIRI <"
            + Capture.RELEASE_IRI_PREFIX
            + release
            + "> .\n";
    final String headerless = ":Asset a owl:Class .\n";
    for (final String header :
        new String[] {other, twice, unversioned, anonymous, versionedElsewhere, headerless}) {
      final Release upload = Releases.raw(ontology, release, header + MEMBERS);
      hold(upload);
      final Capture.Refused refused = refused(upload, upload.byteSize());
      assertEquals(Problem.Code.INPUT_CHANGED, refused.problem().code(), header);
      assertEquals(ontology.toString(), refused.problem().subject(), header);
      assertNoSpool();
    }
  }

  @Test
  void anImportCannotResolveBecauseNoOtherOntologyIsInTheInput() throws Exception {
    final Release release =
        Releases.of(MEMBERS + "<" + Releases.ONTOLOGY_IRI + "> owl:imports <urn:other> .\n");
    hold(release);
    final Capture.Refused refused = refused(release, release.byteSize());
    assertEquals(Problem.Code.MISSING_IMPORT, refused.problem().code());
    assertEquals("urn:other", refused.problem().subject());
    assertNoSpool();
  }

  @Test
  void bytesThatAreNotRdfThriftFailAsAnInvalidRequestNamingTheUpload() throws Exception {
    final byte[] text = "this is not RDF-Thrift".getBytes(StandardCharsets.UTF_8);
    final Release release = new Release(UUID.randomUUID(), UUID.randomUUID(), text);
    hold(release);
    final Capture.Refused refused = refused(release, release.byteSize());
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
    assertEquals(release.digest(), refused.problem().subject());
    assertNoSpool();
  }

  @Test
  void aTermLongerThanTheTermLimitIsRefusedWhileTheUploadIsReadAndLeavesNoSpool() throws Exception {
    final Release huge =
        Releases.of(":Asset rdfs:comment \"" + "x".repeat(Capture.MAX_TERM_BYTES + 1) + "\" .");
    hold(huge);
    final Capture.Refused refused = refused(huge, huge.byteSize());
    assertEquals(Problem.Code.LIMIT_EXCEEDED, refused.problem().code());
    assertEquals(huge.digest(), refused.problem().subject());
    assertNoSpool();

    final Release large =
        Releases.of(":Asset rdfs:comment \"" + "x".repeat(Capture.MAX_TERM_BYTES / 2) + "\" .");
    assertEquals(3, capture(large).triples());
  }

  @Test
  void manyOntologiesStatingVersionsAreStillRefusedAsNotTheNamedRelease() throws Exception {
    final StringBuilder others = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      others.append("<urn:other:%d> owl:versionIRI <urn:other:v%d> .\n".formatted(i, i));
    }
    final Release release = Releases.of(others.toString());
    hold(release);
    final Capture.Refused refused = refused(release, release.byteSize());
    assertEquals(Problem.Code.INPUT_CHANGED, refused.problem().code());
    assertNoSpool();
  }

  @Test
  void aReleaseOverItsTripleBoundIsRefusedAndLeavesNoSpool() throws Exception {
    final Release release = Releases.of(MEMBERS);
    hold(release);
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class,
            () -> capture(release, new Capture.Bounds(5, 1 << 30, deadline()), () -> false));
    assertEquals(Problem.Code.LIMIT_EXCEEDED, refused.problem().code());
    assertEquals("maxClosureAxioms", refused.problem().subject());
    assertNoSpool();
  }

  @Test
  void aSpoolOverItsByteBoundIsRefusedAndLeavesNoSpool() throws Exception {
    final StringBuilder many = new StringBuilder();
    for (int i = 0; i < 40_000; i++) {
      many.append(":i").append(i).append(" a :Asset ; rdfs:label \"individual ").append(i);
      many.append("\" .\n");
    }
    final Release release = Releases.of(MEMBERS + many);
    hold(release);
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class,
            () -> capture(release, new Capture.Bounds(1 << 30, 1_000, deadline()), () -> false));
    assertEquals("maxDiskBytes", refused.problem().subject());
    assertNoSpool();
  }

  @Test
  void aJobThatRunsOutOfTimeOrIsCancelledStopsTheCopy() throws Exception {
    final StringBuilder many = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      many.append(":i").append(i).append(" a :Asset .\n");
    }
    final Release release = Releases.of(MEMBERS + many);
    hold(release);
    final Capture.Refused late =
        assertThrows(
            Capture.Refused.class,
            () ->
                capture(
                    release,
                    new Capture.Bounds(1 << 30, 1 << 30, System.nanoTime() - 1),
                    () -> false));
    assertEquals("maxJobSeconds", late.problem().subject());
    assertNoSpool();
    assertThrows(
        Capture.Cancelled.class,
        () -> capture(release, new Capture.Bounds(1 << 30, 1 << 30, deadline()), () -> true));
    assertNoSpool();
  }

  private Capture.Result capture(final Release release) throws Exception {
    hold(release);
    return capture(release, new Capture.Bounds(1 << 30, 1 << 30, deadline()), () -> false);
  }

  private Capture.Result capture(
      final Release release,
      final Capture.Bounds bounds,
      final java.util.function.BooleanSupplier cancelled)
      throws Exception {
    return Capture.run(path(release), named(release, release.byteSize()), spool, bounds, cancelled);
  }

  private Capture.Refused refused(final Release release, final long byteSize) {
    return assertThrows(
        Capture.Refused.class,
        () ->
            Capture.run(
                path(release),
                named(release, byteSize),
                spool,
                new Capture.Bounds(1 << 30, 1 << 30, deadline()),
                () -> false));
  }

  private static Capture.Release named(final Release release, final long byteSize) {
    return new Capture.Release(
        release.ontologyId().toString(),
        release.releaseId().toString(),
        release.digest(),
        byteSize);
  }

  private void hold(final Release release) throws IOException {
    Files.write(path(release), release.rdf());
  }

  private Path path(final Release release) {
    return uploads.resolve(release.digest().substring("sha256:".length()));
  }

  private void assertNoSpool() {
    assertFalse(Files.exists(spool), "no spool unless the capture succeeded");
    assertFalse(Files.exists(Path.of(spool + ".tmp")), "no partial spool");
  }

  private static long deadline() {
    return System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
  }

  private static DatasetGraph read(final Path file) throws IOException {
    final DatasetGraph dataset = DatasetGraphFactory.createTxnMem();
    try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
      RDFParser.source(in).lang(Lang.NQUADS).parse(dataset);
    }
    return dataset;
  }
}
