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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * CHECK and EXPLAIN jobs end to end: a serving TDB2 dataset laid out as OpenMetadata writes
 * ontologies, one named graph per term and axiom pinned by a version manifest, and the job service
 * the Fuseki module runs, with the packaged worker started by the launcher's real command. Only the
 * graphs the manifest pins are captured; a Draft graph outside it that would make the closure
 * inconsistent leaves the answer alone until a candidate closure adds it.
 */
class OntologyJobsIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String ROOT = "https://open-metadata.org/ontology/finance";
  private static final String F = "https://open-metadata.org/finance#";
  private static final String GRAPH = "https://open-metadata.org/graph/";

  private static final String BASE = "https://open-metadata.org/";
  private static final List<String> MEMBERS =
      List.of(
          GRAPH + "glossaryTerm/asset",
          GRAPH + "glossaryTerm/liability",
          GRAPH + "glossaryTerm/hybrid",
          GRAPH + "axiom/disjoint",
          GRAPH + "asset/loan");

  private static final String LAYOUT =
      """
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX : <https://open-metadata.org/finance#>
      GRAPH <https://open-metadata.org/graph/glossaryTerm/asset> { :Asset a owl:Class . }
      GRAPH <https://open-metadata.org/graph/glossaryTerm/liability> { :Liability a owl:Class . }
      GRAPH <https://open-metadata.org/graph/glossaryTerm/hybrid> {
        :Hybrid a owl:Class ; rdfs:subClassOf :Asset , :Liability .
      }
      GRAPH <https://open-metadata.org/graph/axiom/disjoint> {
        [] a owl:AllDisjointClasses ; owl:members ( :Asset :Liability ) .
      }
      GRAPH <https://open-metadata.org/graph/asset/loan> { :loan a owl:NamedIndividual , :Asset . }
      GRAPH <https://open-metadata.org/graph/draft/loan> { :loan a :Liability . }
      """;

  @TempDir Path root;
  private ImageLayout image;
  private DatasetGraph serving;
  private JobService service;
  private ReasoningSettings settings;
  private Manifests.Published approved;

  @BeforeEach
  void start() throws IOException {
    image = ImageLayout.create(root, FUSEKI_SERVER, REASONER);
    serving = DatabaseMgr.connectDatasetGraph(Location.create(root.resolve("serving")));
    Txn.executeWrite(serving, () -> RDFParser.fromString(LAYOUT, Lang.TRIG).parse(serving));
    approved = publish("APPROVED", MEMBERS);
    settings = image.settings("512m");
    final JobSettings jobs = JobSettings.fromEnvironment(Map.of());
    final Operations operations = new Operations(settings, jobs);
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
  void aCheckCapturesOnlyItsClosureAndSeesADisjointnessViolationOnceItIsInIt() throws Exception {
    final JsonObject consistent = run(check("{\"kind\": \"CONSISTENCY\"}"));
    assertEquals("SUCCEEDED", consistent.getString("state"), consistent.toString());
    assertEquals("NOT_ENTAILED", consistent.getString("outcome"), "consistent");
    assertFalse(consistent.hasKey("problem"));
    final JsonObject engine =
        consistent.getObj("input").get("engines").getAsArray().get(0).getAsObject();
    assertTrue(List.of("HermiT", "ELK").contains(engine.getString("name")), engine.toString());

    final JsonObject capture = file(consistent, "capture.json");
    final List<String> graphs = strings(capture.get("graphs"));
    assertEquals(6, graphs.size(), graphs.toString());
    assertTrue(graphs.contains(approved.graph()));
    assertTrue(graphs.contains(GRAPH + "asset/loan"));
    assertFalse(graphs.contains(GRAPH + "draft/loan"), "a Draft graph outside the closure");
    assertEquals(approved.versionIri(), capture.getString("versionIri"));
    final JsonObject result = file(consistent, "result.json");
    assertEquals(List.of(F + "Hybrid"), strings(result.get("unsatisfiableClasses")));
    assertEquals(1, result.getNumber("unsatisfiableCount").intValue());
    assertTrue(
        Files.notExists(directory(consistent).resolve(Plans.SPOOL)), "the spool is discarded");
    assertTrue(Files.notExists(directory(consistent).resolve(Plans.WORKING)));

    final JsonObject satisfiable =
        run(
            check(
                """
                {"kind": "SATISFIABILITY",
                 "classExpression": {"kind": "NAMED_CLASS", "classIri": "%sHybrid"}}
                """
                    .formatted(F)));
    assertEquals("ENTAILED", satisfiable.getString("outcome"), "Hybrid is unsatisfiable");

    final Manifests.Published candidate =
        publish("CANDIDATE", concat(MEMBERS, GRAPH + "draft/loan"));
    final JsonObject inconsistent =
        run(check("{\"kind\": \"CONSISTENCY\"}", candidate, "CANDIDATE"));
    assertEquals("SUCCEEDED", inconsistent.getString("state"), inconsistent.toString());
    assertEquals("INCONSISTENT", inconsistent.getString("outcome"));
    assertEquals(
        "NOT_ENTAILED",
        run(check("{\"kind\": \"CONSISTENCY\"}")).getString("outcome"),
        "production is unchanged");

    addLiability();
    final JsonObject changed = run(check("{\"kind\": \"CONSISTENCY\"}"));
    assertEquals("FAILED", changed.getString("state"), changed.toString());
    assertEquals("GENERATION_MISMATCH", changed.getObj("problem").getString("code"));
    assertEquals(GRAPH + "asset/loan", changed.getObj("problem").getString("subject"));
    approved = publish("APPROVED", MEMBERS);
    assertEquals("INCONSISTENT", run(check("{\"kind\": \"CONSISTENCY\"}")).getString("outcome"));
  }

  @Test
  void anExplanationJustifiesAnEntailmentAndAnInconsistency() throws Exception {
    final JsonObject entailed =
        run(
            explain(
                """
                {"subjectIri": "%sHybrid",
                 "predicateIri": "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                 "objectKind": "IRI", "objectIri": "http://www.w3.org/2002/07/owl#Nothing"}
                """
                    .formatted(F)));
    assertEquals("SUCCEEDED", entailed.getString("state"), entailed.toString());
    assertEquals("ENTAILED", entailed.getString("outcome"));
    final List<String> justification =
        strings(file(entailed, "result.json").get("justifications").getAsArray().get(0));
    assertEquals(
        List.of(
            "DisjointClasses(<%1$sAsset> <%1$sLiability>)".formatted(F),
            "SubClassOf(<%1$sHybrid> <%1$sAsset>)".formatted(F),
            "SubClassOf(<%1$sHybrid> <%1$sLiability>)".formatted(F)),
        justification);

    final JsonObject notEntailed =
        run(
            explain(
                """
                {"subjectIri": "%1$sloan",
                 "predicateIri": "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                 "objectKind": "IRI", "objectIri": "%1$sLiability"}
                """
                    .formatted(F)));
    assertEquals("NOT_ENTAILED", notEntailed.getString("outcome"));

    final JsonObject unknown =
        run(
            explain(
                """
                {"subjectIri": "%1$sNobody",
                 "predicateIri": "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                 "objectKind": "IRI", "objectIri": "%1$sAsset"}
                """
                    .formatted(F)));
    assertEquals("FAILED", unknown.getString("state"));
    assertEquals("INVALID_REQUEST", unknown.getObj("problem").getString("code"));

    addLiability();
    approved = publish("APPROVED", MEMBERS);
    final JsonObject inconsistent =
        run(
            explain(
                """
                {"subjectIri": "%1$sloan",
                 "predicateIri": "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                 "objectKind": "IRI", "objectIri": "%1$sAsset"}
                """
                    .formatted(F)));
    assertEquals("INCONSISTENT", inconsistent.getString("outcome"), inconsistent.toString());
    assertEquals(
        List.of(
            "ClassAssertion(<%1$sAsset> <%1$sloan>)".formatted(F),
            "ClassAssertion(<%1$sLiability> <%1$sloan>)".formatted(F),
            "DisjointClasses(<%1$sAsset> <%1$sLiability>)".formatted(F)),
        strings(file(inconsistent, "result.json").get("justifications").getAsArray().get(0)));
  }

  @Test
  void aClosureOverItsLimitAnswersIncompleteAtCapture() throws Exception {
    Txn.executeWrite(
        serving,
        () -> {
          final StringBuilder many = new StringBuilder("GRAPH <" + GRAPH + "asset/loan> {\n");
          for (int i = 0; i < 100_000; i++) {
            many.append('<').append(F).append("a").append(i).append("> a <").append(F);
            many.append("Asset> .\n");
          }
          RDFParser.fromString(many.append("}\n").toString(), Lang.TRIG).parse(serving);
        });
    approved = publish("APPROVED", MEMBERS);
    final JsonObject incomplete = run(check("{\"kind\": \"CONSISTENCY\"}"));
    assertEquals("SUCCEEDED", incomplete.getString("state"), incomplete.toString());
    assertEquals("INCOMPLETE", incomplete.getString("outcome"));
    final JsonObject problem = incomplete.getObj("problem");
    assertEquals("LIMIT_EXCEEDED", problem.getString("code"));
    assertEquals("maxClosureAxioms", problem.getString("subject"), "75,000 triples in 512 MiB");
    assertEquals("capture", problem.getString("step"));
  }

  private JsonObject check(final String question) {
    return check(question, approved, "APPROVED");
  }

  private JsonObject check(
      final String question, final Manifests.Published closure, final String selection) {
    final JsonObject request = request("CHECK", closure, selection);
    request.put("check", JSON.parse(question));
    return request;
  }

  private JsonObject explain(final String statement) {
    final JsonObject request = request("EXPLAIN", approved, "APPROVED");
    final JsonObject explain = new JsonObject();
    explain.put("statement", JSON.parse(statement));
    request.put("explain", explain);
    return request;
  }

  private static JsonObject request(
      final String operation, final Manifests.Published closure, final String selection) {
    final JsonObject request = new JsonObject();
    request.put("requestId", UUID.randomUUID().toString());
    request.put("operation", operation);
    request.put(
        "sourceRevision",
        JSON.parse(
            "{\"datasetGeneration\": \"9d72cf3b-3657-4ee3-809c-5bc70b9308bd\","
                + " \"liveWriteWatermark\": 1}"));
    request.put("ontologySelection", selection);
    request.put("ontologyIri", ROOT);
    request.put("ontologyDigest", closure.digest());
    return request;
  }

  private Manifests.Published publish(final String selection, final List<String> members) {
    return Manifests.publish(
        serving, BASE, ROOT, BASE + "glossary/finance", selection, members, List.of());
  }

  private void addLiability() {
    Txn.executeWrite(
        serving,
        () ->
            RDFParser.fromString(
                    """
                    GRAPH <https://open-metadata.org/graph/asset/loan> {
                      <https://open-metadata.org/finance#loan>
                        a <https://open-metadata.org/finance#Liability> .
                    }
                    """,
                    Lang.TRIG)
                .parse(serving));
  }

  private static List<String> concat(final List<String> members, final String added) {
    final List<String> all = new ArrayList<>(members);
    all.add(added);
    return all;
  }

  private JsonObject run(final JsonObject request) throws Exception {
    final JobService.Submission submission =
        service.submit(JobRequest.validate(request, "openmetadata"));
    final UUID id = UUID.fromString(submission.job().getString("id"));
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
    while (true) {
      final JsonObject job = service.job(id).orElseThrow();
      if (!List.of("QUEUED", "RUNNING").contains(job.getString("state"))) {
        return job;
      }
      assertTrue(System.nanoTime() < deadline, "Timed out; last " + job);
      Thread.sleep(100);
    }
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
