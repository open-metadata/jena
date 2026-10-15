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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Snapshots on real TDB2 databases: atomic publication, the generation compare-and-set, reader
 * leases against retention, restart cleanup, and queries that never see the provenance graph.
 */
class SnapshotStoreTest {
  private static final String DATASET = "openmetadata";
  private static final String GENERATION = "9d72cf3b-3657-4ee3-809c-5bc70b9308bd";

  @TempDir Path root;
  private SnapshotStore store;

  @AfterEach
  void close() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  void aPublicationBecomesCurrentAtOnceAndQueriesSeeItsGraphsButNotItsProvenance()
      throws Exception {
    store = open();
    assertEquals(0, store.generation(DATASET));
    assertTrue(store.lease(DATASET).isEmpty());
    final JsonObject published =
        store.publish(DATASET, 0, candidate(10, 1), working("one", "urn:x:a"), 1L);
    assertEquals(1, store.generation(DATASET));
    assertTrue(Files.notExists(root.resolve("jobs/one/working")), "moved, not copied");

    final SnapshotQueries queries = queries(Map.of());
    final JsonObject answer = query(queries, "SELECT ?s WHERE { ?s ?p ?o }");
    assertEquals(published.getString("id"), answer.getObj("snapshot").getString("id"));
    assertEquals(Set.of("urn:x:a", "urn:x:d"), values(answer));
    assertFalse(
        query(queries, "ASK { GRAPH <" + RefreshCapture.PROVENANCE + "> { ?s ?p ?o } }")
            .getObj("results")
            .getBoolean("boolean"));
    assertFalse(
        query(queries, "ASK { ?s ?p <urn:x:witness> }").getObj("results").getBoolean("boolean"));
    assertEquals(
        Set.of("urn:x:asserted", RefreshCapture.ruleGraph("r")),
        values(query(queries, "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")));
    assertEquals(
        java.util.List.of(),
        Contract.violations(Contract.SNAPSHOT, published),
        "rdfReasoningSnapshot");
  }

  @Test
  void anOlderRefreshNeverReplacesANewerSnapshot() throws Exception {
    store = open();
    store.publish(DATASET, 0, candidate(20, 2), working("one", "urn:x:a"), 1L);
    assertThrows(
        SnapshotStore.Superseded.class,
        () -> store.publish(DATASET, 0, candidate(30, 3), working("two", "urn:x:b"), 2L),
        "it captured before generation 1 was published");
    assertThrows(
        SnapshotStore.Superseded.class,
        () -> store.publish(DATASET, 1, candidate(15, 4), working("three", "urn:x:c"), 3L),
        "a lower watermark of the same dataset generation");
    assertThrows(
        SnapshotStore.Superseded.class,
        () -> store.publish(DATASET, 1, candidate(25, 1), working("four", "urn:x:c"), 4L),
        "a job submitted before the current snapshot's");
    assertEquals(1, store.generation(DATASET));
    assertEquals(
        Set.of("urn:x:a", "urn:x:d"), values(query(queries(Map.of()), "SELECT ?s { ?s ?p ?o }")));
    assertTrue(
        Files.isDirectory(root.resolve("jobs/two/working")), "a refused one stays the job's");
    store.publish(DATASET, 1, candidate(20, 5), working("five", "urn:x:e"), 5L);
    assertEquals(2, store.generation(DATASET), "an equal watermark with newer inputs publishes");
  }

  @Test
  void aLeasedSnapshotSurvivesItsReplacementUntilItsReaderIsDone() throws Exception {
    store = open();
    store.publish(DATASET, 0, candidate(1, 1), working("one", "urn:x:a"), 1L);
    final SnapshotStore.Lease reader = store.lease(DATASET).orElseThrow();
    final Path leased = root.resolve("snapshots").resolve(DATASET).resolve(reader.id().toString());
    store.publish(DATASET, 1, candidate(2, 2), working("two", "urn:x:b"), 2L);
    assertTrue(Files.isDirectory(leased));
    assertEquals(1, store.retired(DATASET));
    Txn.executeRead(
        reader.view(),
        () -> assertTrue(reader.view().getDefaultGraph().contains(node("urn:x:a"), null, null)));
    reader.close();
    assertFalse(Files.exists(leased), "deleted once its last reader is done");
    assertEquals(0, store.retired(DATASET));
    try (SnapshotStore.Lease current = store.lease(DATASET).orElseThrow()) {
      assertEquals(2, current.generation());
    }
  }

  @Test
  void aRestartKeepsOnlyEachCurrentSnapshot() throws Exception {
    store = open();
    store.publish(DATASET, 0, candidate(1, 1), working("one", "urn:x:a"), 1L);
    final SnapshotStore.Lease reader = store.lease(DATASET).orElseThrow();
    store.publish(DATASET, 1, candidate(2, 2), working("two", "urn:x:b"), 2L);
    reader.close();
    store.close();
    final Path lineage = root.resolve("snapshots").resolve(DATASET);
    Files.createDirectories(lineage.resolve(UUID.randomUUID().toString()).resolve("tdb"));
    store = open();
    assertEquals(2, store.generation(DATASET));
    try (var entries = Files.list(lineage)) {
      assertEquals(2, entries.count(), "the pointer and the current snapshot");
    }
    assertEquals(
        Set.of("urn:x:b", "urn:x:d"), values(query(queries(Map.of()), "SELECT ?s { ?s ?p ?o }")));
  }

  @Test
  void queriesAreBounded() throws Exception {
    store = open();
    store.publish(DATASET, 0, candidate(1, 1), working("one", "urn:x:a"), 1L);
    final SnapshotQueries queries = queries(Map.of(JobSettings.MAX_RESULT_ROWS, "1"));
    final JsonObject cut = query(queries, "SELECT ?s { ?s ?p ?o }");
    assertTrue(cut.getBoolean("truncated"));
    assertEquals(1, cut.getNumber("rows").intValue());
    for (final String refused :
        java.util.List.of(
            "{\"query\": \"CONSTRUCT WHERE { ?s ?p ?o }\"}",
            "{\"query\": \"SELECT * FROM <urn:x:asserted> { ?s ?p ?o }\"}",
            "{\"query\": \"SELECT * { SERVICE <http://example.org/> { ?s ?p ?o } }\"}",
            "{\"query\": \"SELECT * { ?s ?p ?o }\", \"maxRows\": 2}",
            "{\"query\": \"not sparql\"}",
            "{\"other\": 1}")) {
      final JobService.Refused e =
          assertThrows(
              JobService.Refused.class,
              () -> queries.run(DATASET, refused.getBytes(StandardCharsets.UTF_8)),
              refused);
      assertEquals(400, e.status(), refused);
    }
    final JobService.Refused none =
        assertThrows(
            JobService.Refused.class,
            () -> queries.run("other", "{\"query\": \"ASK {}\"}".getBytes(StandardCharsets.UTF_8)));
    assertEquals(503, none.status());
    assertEquals(Problem.Code.NOT_READY, none.problem().code());
  }

  private SnapshotStore open() throws Exception {
    final SnapshotStore opened = new SnapshotStore(root, 1_000);
    opened.open();
    return opened;
  }

  private SnapshotQueries queries(final Map<String, String> environment) {
    return new SnapshotQueries(store, JobSettings.fromEnvironment(environment));
  }

  private static JsonObject query(final SnapshotQueries queries, final String sparql)
      throws Exception {
    final JsonObject request = new JsonObject();
    request.put("query", sparql);
    final byte[] body =
        queries.run(DATASET, JSON.toString(request).getBytes(StandardCharsets.UTF_8)).body();
    return JSON.parse(new String(body, StandardCharsets.UTF_8));
  }

  private static Set<String> values(final JsonObject answer) {
    final Set<String> values = new TreeSet<>();
    answer
        .getObj("results")
        .getObj("results")
        .get("bindings")
        .getAsArray()
        .forEach(
            binding ->
                binding
                    .getAsObject()
                    .keys()
                    .forEach(
                        key -> values.add(binding.getAsObject().getObj(key).getString("value"))));
    return values;
  }

  /** A working dataset as a refresh leaves it: asserted, derived and provenance graphs. */
  private Path working(final String job, final String subject) throws Exception {
    final Path location =
        Files.createDirectories(root.resolve("jobs").resolve(job)).resolve("working");
    final DatasetGraph dataset = DatabaseMgr.connectDatasetGraph(Location.create(location));
    final String trig =
        "GRAPH <urn:x:asserted> { <"
            + subject
            + "> <urn:x:p> <urn:x:o> }\n"
            + "GRAPH <"
            + RefreshCapture.ruleGraph("r")
            + "> { <urn:x:d> <urn:x:q> <urn:x:o> }\n"
            + "GRAPH <"
            + RefreshCapture.PROVENANCE
            + "> { <urn:x:witness> <urn:x:solution> <urn:x:witness> }\n";
    Txn.executeWrite(dataset, () -> RDFParser.fromString(trig, Lang.TRIG).parse(dataset));
    TDBInternal.expel(dataset);
    return location;
  }

  private static SnapshotStore.Candidate candidate(final long watermark, final long sequence) {
    final JsonObject input =
        JSON.parse(
            "{\"sourceRevision\": {\"datasetGeneration\": \""
                + GENERATION
                + "\", \"liveWriteWatermark\": "
                + watermark
                + "}, \"ontologySelection\": \"APPROVED\", \"ruleBundleDigest\": \"sha256:"
                + "0".repeat(64)
                + "\"}");
    return new SnapshotStore.Candidate(
        UUID.randomUUID(), sequence, GENERATION, watermark, input, new JsonObject());
  }

  private static org.apache.jena.graph.Node node(final String iri) {
    return org.apache.jena.graph.NodeFactory.createURI(iri);
  }
}
