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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.ImageLayout;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;

/**
 * REFRESH, QUERY, RESULTS and governance EXPLAIN end to end, with the packaged worker started by
 * the launcher's real command, over a serving TDB2 dataset laid out as OpenMetadata writes it: an
 * activated closure of per-term graphs, a Draft graph outside it, a catalog in the knowledge graph,
 * a legacy inferred graph, and a published rule bundle. A refresh publishes a snapshot that a query
 * reads; a write that removes a premise and a second refresh retract what it supported and advance
 * the generation, while a leased older snapshot survives until its reader is done; an older refresh
 * cannot replace the newer snapshot.
 */
class RefreshJobsIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String BASE = "https://open-metadata.org/";
  private static final String GRAPH = BASE + "graph/";
  private static final String ROOT = BASE + "ontology/finance";
  private static final String GLOSSARY = BASE + "glossary/finance";
  private static final String F = "https://open-metadata.org/finance#";
  private static final String C = "https://open-metadata.org/catalog#";
  private static final String OM = "https://open-metadata.org/ontology/";
  private static final String GENERATION = "9d72cf3b-3657-4ee3-809c-5bc70b9308bd";
  private static final String DATASET = "openmetadata";
  private static final List<String> MEMBERS =
      List.of(GRAPH + "glossaryTerm/asset", GRAPH + "glossaryTerm/loan", GRAPH + "asset/loan1");
  private static final String DRAFT = GRAPH + "axiom/draft";

  private static final String LAYOUT =
      """
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX : <https://open-metadata.org/finance#>
PREFIX c: <https://open-metadata.org/catalog#>
PREFIX om: <https://open-metadata.org/ontology/>
GRAPH <https://open-metadata.org/graph/glossaryTerm/asset> { :Asset a owl:Class . }
GRAPH <https://open-metadata.org/graph/glossaryTerm/loan> {
  :Loan a owl:Class ; rdfs:subClassOf :Asset .
}
GRAPH <https://open-metadata.org/graph/asset/loan1> { :loan1 a owl:NamedIndividual , :Loan . }
GRAPH <https://open-metadata.org/graph/axiom/draft> { :Loan owl:disjointWith :Asset . }
GRAPH <https://open-metadata.org/graph/axiom/draftTag> { :loan1 om:hasTag c:draftOnly . }
GRAPH <https://open-metadata.org/graph/knowledge> {
  c:pii om:tagFQN "PII.Sensitive" .
  c:c1 om:hasTag c:pii .
  c:l1 om:fromColumn c:c1 ; om:toColumn c:c2 .
  c:l2 om:fromColumn c:c2 ; om:toColumn c:c3 .
  c:t1 a om:Table ; om:hasTag c:gold ; om:hasColumn c:c4 .
}
GRAPH <https://open-metadata.org/graph/inferred/pii-propagation-via-lineage> {
  c:c9 om:hasTag c:pii .
}
""";

  @TempDir Path root;
  private ImageLayout image;
  private DatasetGraph serving;
  private ReasoningSettings settings;
  private Operations operations;
  private JobService service;
  private String bundle;
  private Manifests.Published approved;

  @BeforeEach
  void start() throws Exception {
    image = ImageLayout.create(root, FUSEKI_SERVER, REASONER);
    serving = DatabaseMgr.connectDatasetGraph(Location.create(root.resolve("serving")));
    Txn.executeWrite(serving, () -> RDFParser.fromString(LAYOUT, Lang.TRIG).parse(serving));
    final List<String> ontologyGraphs = new ArrayList<>(MEMBERS);
    ontologyGraphs.add(DRAFT);
    ontologyGraphs.add(GRAPH + "axiom/draftTag");
    Bundles.members(serving, BASE, ontologyGraphs);
    approved = publishClosure(MEMBERS);
    Bundles.activate(serving, BASE, Map.of(GLOSSARY, approved));
    bundle =
        Bundles.publish(
            serving,
            BASE,
            List.of(
                Bundles.starter("pii-propagation-via-lineage"),
                Bundles.starter("schema-tag-inheritance")));
    settings = image.settings("512m");
    startService();
  }

  private void startService() throws Exception {
    operations = new Operations(settings, JobSettings.fromEnvironment(Map.of()));
    service =
        new JobService(
            operations,
            image.launcher(settings),
            image.launcher(settings)::readiness,
            Plans.current(operations, name -> serving),
            System::currentTimeMillis);
    service.open();
    service.start();
  }

  @AfterEach
  void stop() {
    service.close();
    TDBInternal.expel(serving);
  }

  @Test
  void refreshQueryWriteAndRefreshAgain() throws Exception {
    assertTrue(operations.ruleCode().isPresent(), "the packaged worker's rule code loads");
    final JsonObject first = run(refresh(10, bundle));
    assertEquals("SUCCEEDED", first.getString("state"), first.toString());
    assertTrue(first.hasKey("snapshotId"), first.toString());
    assertEquals(6, first.getObj("progress").getNumber("totalSteps").intValue());
    final Set<String> engines = new TreeSet<>();
    first
        .getObj("input")
        .get("engines")
        .getAsArray()
        .forEach(engine -> engines.add(engine.getAsObject().getString("name")));
    assertTrue(engines.contains("governance-rules"), engines.toString());
    assertEquals(1, operations.snapshots().generation(DATASET));

    final JsonObject capture = file(first, "capture.json");
    assertFalse(
        capture.getObj("catalog").getBoolean("anyPredicate"), "the starter rules name predicates");
    assertEquals(List.of(GRAPH + "knowledge"), strings(capture.getObj("catalog").get("graphs")));
    assertTrue(Files.notExists(directory(first).resolve(Plans.WORKING)), "moved into the snapshot");

    assertEquals(Set.of(C + "c1", C + "c2", C + "c3"), piiColumns(), "no legacy inferred fact");
    assertEquals(
        Set.of(C + "c4"),
        select(
            "SELECT ?c WHERE { ?c <"
                + OM
                + "hasTag> <"
                + C
                + "gold> . FILTER(?c != <"
                + C
                + "t1>) }"));
    final String entailments = RefreshCapture.entailmentGraph(approved.digest());
    assertTrue(
        ask(
            "ASK { GRAPH <"
                + entailments
                + "> { <"
                + F
                + "Loan> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <"
                + F
                + "Asset> } }"),
        "the closure's classified hierarchy");
    assertTrue(
        ask("ASK { GRAPH <" + entailments + "> { <" + F + "loan1> a <" + F + "Loan> } }"),
        "a direct type");
    assertFalse(ask("ASK { GRAPH <" + RefreshCapture.PROVENANCE + "> { ?s ?p ?o } }"));
    assertFalse(ask("ASK { ?s ?p ?o . FILTER(?o = <" + C + "draftOnly>) }"), "no Draft graph");
    assertFalse(ask("ASK { GRAPH <" + DRAFT + "> { ?s ?p ?o } }"));
    final JsonObject answer = query("SELECT ?s WHERE { ?s ?p ?o }", 2);
    assertTrue(answer.getBoolean("truncated"));
    assertEquals(2, answer.getNumber("rows").intValue());
    assertEquals(first.getString("snapshotId"), answer.getObj("snapshot").getString("id"));

    final JsonObject page = service.results(id(first), 0, 10);
    assertEquals("closures", page.getObj("page").getString("items"));
    assertEquals(1, page.getObj("page").getNumber("total").intValue());
    assertEquals(
        RefreshCapture.entailmentGraph(approved.digest()),
        page.getObj("result")
            .get("closures")
            .getAsArray()
            .get(0)
            .getAsObject()
            .getString("entailmentGraph"));

    final JsonObject explained = run(explain(C + "c3", OM + "hasTag", C + "pii"));
    assertEquals("ENTAILED", explained.getString("outcome"), explained.toString());
    final JsonObject explanation = file(explained, "result.json").getObj("explanation");
    assertEquals("DERIVED", explanation.getString("origin"));
    assertEquals("pii-propagation-via-lineage", explanation.getString("rule"));
    final Set<String> asserted = new TreeSet<>();
    leaves(explanation, asserted);
    assertTrue(
        asserted.contains("<" + C + "l1> <" + OM + "fromColumn> <" + C + "c1>"),
        "explained back to the asserted lineage: " + asserted);

    try (SnapshotStore.Lease reader = operations.snapshots().lease(DATASET).orElseThrow()) {
      final Path leased =
          settings
              .reasoningRoot()
              .resolve("snapshots")
              .resolve(DATASET)
              .resolve(reader.id().toString());
      removeEdge();
      final JsonObject second = run(refresh(20, bundle));
      assertEquals("SUCCEEDED", second.getString("state"), second.toString());
      assertEquals(2, operations.snapshots().generation(DATASET));
      assertTrue(Files.isDirectory(leased), "a leased snapshot survives its replacement");
      assertEquals(1, operations.snapshots().retired(DATASET));
      assertEquals(Set.of(C + "c1"), piiColumns(), "removing a premise retracts its conclusions");
    }
    assertEquals(0, operations.snapshots().retired(DATASET), "released, then removed");

    final JsonObject older = run(refresh(15, bundle));
    assertEquals("FAILED", older.getString("state"), older.toString());
    assertEquals("SUPERSEDED", older.getObj("problem").getString("code"));
    assertEquals(2, operations.snapshots().generation(DATASET));

    service.close();
    startService();
    assertEquals(2, operations.snapshots().generation(DATASET), "the pointer survives a restart");
    assertEquals(Set.of(C + "c1"), piiColumns());
  }

  @Test
  void anInvalidBundleOrAnInconsistentClosurePublishesNothing() throws Exception {
    final String invalid =
        Bundles.publish(
            serving,
            BASE,
            List.of(
                new RuleBundle.Rule(
                    "optional-rule",
                    "CONSTRUCT",
                    "PREFIX om: <"
                        + OM
                        + "> CONSTRUCT { ?s om:x ?o } WHERE { ?s om:hasTag ?t"
                        + " OPTIONAL { ?t om:tagFQN ?o } }")));
    final JsonObject refused = run(refresh(1, invalid));
    assertEquals("FAILED", refused.getString("state"), refused.toString());
    assertEquals("RULE_FAILED", refused.getObj("problem").getString("code"));
    assertEquals("optional-rule", refused.getObj("problem").getString("subject"));
    assertEquals("capture", refused.getObj("problem").getString("step"));

    final JsonObject missing = run(refresh(1, "sha256:" + "0".repeat(64)));
    assertEquals("RULE_FAILED", missing.getObj("problem").getString("code"), missing.toString());

    final List<String> withDraft = new ArrayList<>(MEMBERS);
    withDraft.add(DRAFT);
    Bundles.activate(serving, BASE, Map.of(GLOSSARY, publishClosure(withDraft)));
    final JsonObject inconsistent = run(refresh(1, bundle));
    assertEquals("FAILED", inconsistent.getString("state"), inconsistent.toString());
    assertEquals("INCONSISTENT_ONTOLOGY", inconsistent.getObj("problem").getString("code"));
    assertEquals(ROOT, inconsistent.getObj("problem").getString("subject"));
    assertEquals(0, operations.snapshots().generation(DATASET), "nothing was published");
  }

  private Manifests.Published publishClosure(final List<String> members) {
    return Manifests.publish(serving, BASE, ROOT, GLOSSARY, "APPROVED", members, List.of());
  }

  private void removeEdge() {
    Txn.executeWrite(
        serving,
        () ->
            serving.deleteAny(
                org.apache.jena.graph.NodeFactory.createURI(GRAPH + "knowledge"),
                org.apache.jena.graph.NodeFactory.createURI(C + "l1"),
                org.apache.jena.graph.Node.ANY,
                org.apache.jena.graph.Node.ANY));
  }

  private Set<String> piiColumns() throws Exception {
    return select("SELECT ?c WHERE { ?c <" + OM + "hasTag> <" + C + "pii> }");
  }

  private Set<String> select(final String sparql) throws Exception {
    final Set<String> values = new TreeSet<>();
    query(sparql, null)
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

  private boolean ask(final String sparql) throws Exception {
    return query(sparql, null).getObj("results").getBoolean("boolean");
  }

  private JsonObject query(final String sparql, final Integer maxRows) throws Exception {
    final JsonObject request = new JsonObject();
    request.put("query", sparql);
    if (maxRows != null) {
      request.put("maxRows", maxRows);
    }
    final byte[] body =
        operations
            .queries()
            .run(DATASET, JSON.toString(request).getBytes(StandardCharsets.UTF_8))
            .body();
    return JSON.parse(new String(body, StandardCharsets.UTF_8));
  }

  private static void leaves(final JsonObject fact, final Set<String> asserted) {
    if ("ASSERTED".equals(fact.getString("origin"))) {
      asserted.add(fact.getString("triple").replaceAll("\\s*\\.\\s*$", ""));
    }
    for (final JsonValue premise : fact.get("premises").getAsArray()) {
      if ("FACT".equals(premise.getAsObject().getString("kind"))) {
        leaves(premise.getAsObject(), asserted);
      }
    }
  }

  private JsonObject refresh(final long watermark, final String digest) {
    final JsonObject request = new JsonObject();
    request.put("requestId", UUID.randomUUID().toString());
    request.put("operation", "REFRESH");
    request.put(
        "sourceRevision",
        JSON.parse(
            "{\"datasetGeneration\": \""
                + GENERATION
                + "\", \"liveWriteWatermark\": "
                + watermark
                + "}"));
    request.put("ontologySelection", "APPROVED");
    request.put("ruleBundleDigest", digest);
    return request;
  }

  private JsonObject explain(final String subject, final String predicate, final String object) {
    final JsonObject request = new JsonObject();
    request.put("requestId", UUID.randomUUID().toString());
    request.put("operation", "EXPLAIN");
    request.put(
        "sourceRevision",
        JSON.parse("{\"datasetGeneration\": \"" + GENERATION + "\", \"liveWriteWatermark\": 10}"));
    request.put("ontologySelection", "APPROVED");
    request.put("ontologyIri", ROOT);
    final JsonObject explain = new JsonObject();
    explain.put(
        "statement",
        JSON.parse(
            "{\"subjectIri\": \"%s\", \"predicateIri\": \"%s\", \"objectKind\": \"IRI\","
                    .formatted(subject, predicate)
                + " \"objectIri\": \""
                + object
                + "\"}"));
    request.put("explain", explain);
    return request;
  }

  private JsonObject run(final JsonObject request) throws Exception {
    final UUID id =
        UUID.fromString(
            service.submit(JobRequest.validate(request, DATASET)).job().getString("id"));
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    while (true) {
      final JsonObject job = service.job(id).orElseThrow();
      if (!List.of("QUEUED", "RUNNING").contains(job.getString("state"))) {
        return job;
      }
      assertTrue(System.nanoTime() < deadline, "Timed out; last " + job);
      Thread.sleep(100);
    }
  }

  private static UUID id(final JsonObject job) {
    return UUID.fromString(job.getString("id"));
  }

  private Path directory(final JsonObject job) {
    return settings.reasoningRoot().resolve("jobs").resolve(job.getString("id"));
  }

  private JsonObject file(final JsonObject job, final String name) {
    return JSON.read(directory(job).resolve(name).toString());
  }

  private static List<String> strings(final JsonValue array) {
    final List<String> values = new ArrayList<>();
    array.getAsArray().forEach(value -> values.add(value.getAsString().value()));
    return values;
  }
}
