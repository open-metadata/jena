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

import java.io.ByteArrayInputStream;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.ImageLayout;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;
import org.openmetadata.fuseki.reasoning.jobs.Releases.Release;

/**
 * CHECK and EXPLAIN jobs end to end: an ontology release uploaded as RDF-Thrift, as OpenMetadata
 * sends it, and the job service the Fuseki module runs, with the packaged worker started by the
 * launcher's real command. Nothing is in a serving dataset: a release under review never enters
 * one.
 */
class OntologyJobsIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final Path FUSEKI_SERVER = Path.of(System.getProperty("reasoner.fusekiServerJar"));
  private static final String F = Releases.F;

  /** Hybrid is unsatisfiable: it is both of two disjoint classes. */
  private static final String MEMBERS =
      """
      dct:source a owl:AnnotationProperty .
      :Asset a owl:Class .
      :Liability a owl:Class .
      :Hybrid a owl:Class ; rdfs:subClassOf :Asset , :Liability .
      :Asset owl:disjointWith :Liability .
      [] a owl:Axiom ; owl:annotatedSource :Hybrid ; owl:annotatedProperty rdfs:subClassOf ;
         owl:annotatedTarget :Asset ; dct:source <urn:openmetadata:ontology-member:1> .
      [] a owl:Axiom ; owl:annotatedSource :Hybrid ; owl:annotatedProperty rdfs:subClassOf ;
         owl:annotatedTarget :Liability ; dct:source <urn:openmetadata:ontology-member:2> .
      [] a owl:Axiom ; owl:annotatedSource :Asset ; owl:annotatedProperty owl:disjointWith ;
         owl:annotatedTarget :Liability ; dct:source <urn:openmetadata:ontology-member:3> ,
         <urn:openmetadata:ontology-member:4> .
      :loan a owl:NamedIndividual , :Asset .
      """;

  @TempDir Path root;
  private JobService service;
  private ReasoningSettings settings;

  @BeforeEach
  void start() throws IOException {
    final ImageLayout image = ImageLayout.create(root, FUSEKI_SERVER, REASONER);
    settings = image.settings("512m");
    final JobSettings jobs = JobSettings.fromEnvironment(Map.of());
    final Operations operations = new Operations(settings, jobs);
    service =
        new JobService(
            operations,
            image.launcher(settings),
            image.launcher(settings)::readiness,
            Plans.current(operations, name -> null),
            System::currentTimeMillis);
    service.open();
    service.start();
  }

  @AfterEach
  void stop() {
    service.close();
  }

  @Test
  void aCheckSeesADisjointnessViolationOnlyWhenTheReleaseHasIt() throws Exception {
    final Release release = Releases.of(MEMBERS);
    final JsonObject consistent = run(check(release, "{\"kind\": \"CONSISTENCY\"}"), release);
    assertEquals("SUCCEEDED", consistent.getString("state"), consistent.toString());
    assertEquals("NOT_ENTAILED", consistent.getString("outcome"), "consistent");
    assertFalse(consistent.hasKey("problem"));
    final JsonObject engine =
        consistent.getObj("input").get("engines").getAsArray().get(0).getAsObject();
    assertTrue(List.of("HermiT", "ELK").contains(engine.getString("name")), engine.toString());
    assertEquals(
        release.digest(), consistent.getObj("input").getObj("releaseRdf").getString("digest"));
    assertEquals(
        release.releaseId().toString(),
        consistent
            .getObj("input")
            .get("ontologyReleases")
            .getAsArray()
            .get(0)
            .getAsObject()
            .getString("releaseId"));

    final JsonObject capture = file(consistent, "capture.json");
    assertEquals(Releases.ONTOLOGY_IRI, capture.getString("root"));
    assertEquals(Capture.RELEASE_IRI_PREFIX + release.releaseId(), capture.getString("versionIri"));
    final JsonObject result = file(consistent, "result.json");
    assertEquals(List.of(F + "Hybrid"), strings(result.get("unsatisfiableClasses")));
    assertEquals(1, result.getNumber("unsatisfiableCount").intValue());
    assertTrue(
        Files.notExists(directory(consistent).resolve(Plans.SPOOL)), "the spool is discarded");
    assertTrue(Files.notExists(directory(consistent).resolve(Plans.WORKING)));

    final Release satisfiable = Releases.of(MEMBERS);
    assertEquals(
        "ENTAILED",
        run(
                check(
                    satisfiable,
                    """
                    {"kind": "SATISFIABILITY",
                     "classExpression": {"kind": "NAMED_CLASS", "classIri": "%sHybrid"}}
                    """
                        .formatted(F)),
                satisfiable)
            .getString("outcome"),
        "Hybrid is unsatisfiable");

    final Release violating = Releases.of(MEMBERS + ":loan a :Liability .\n");
    final JsonObject inconsistent = run(check(violating, "{\"kind\": \"CONSISTENCY\"}"), violating);
    assertEquals("SUCCEEDED", inconsistent.getString("state"), inconsistent.toString());
    assertEquals("INCONSISTENT", inconsistent.getString("outcome"));
  }

  @Test
  void anExplanationJustifiesAnEntailmentAndAnInconsistency() throws Exception {
    final Release release = Releases.of(MEMBERS);
    final JsonObject entailed =
        run(
            explain(
                release,
                """
                {"subjectIri": "%sHybrid",
                 "predicateIri": "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                 "objectKind": "IRI", "objectIri": "http://www.w3.org/2002/07/owl#Nothing"}
                """
                    .formatted(F)),
            release);
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
    assertEquals(
        List.of(
            List.of("urn:openmetadata:ontology-member:3", "urn:openmetadata:ontology-member:4"),
            List.of("urn:openmetadata:ontology-member:1"),
            List.of("urn:openmetadata:ontology-member:2")),
        sourcesOf(file(entailed, "result.json"), 0),
        "each axiom of the justification names the members it came from");

    final JsonObject notEntailed =
        run(
            explain(
                release,
                """
                {"subjectIri": "%1$sloan",
                 "predicateIri": "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                 "objectKind": "IRI", "objectIri": "%1$sLiability"}
                """
                    .formatted(F)),
            release);
    assertEquals("NOT_ENTAILED", notEntailed.getString("outcome"));

    final JsonObject unknown =
        run(
            explain(
                release,
                """
                {"subjectIri": "%1$sNobody",
                 "predicateIri": "http://www.w3.org/2000/01/rdf-schema#subClassOf",
                 "objectKind": "IRI", "objectIri": "%1$sAsset"}
                """
                    .formatted(F)),
            release);
    assertEquals("FAILED", unknown.getString("state"));
    assertEquals("INVALID_REQUEST", unknown.getObj("problem").getString("code"));

    final Release violating = Releases.of(MEMBERS + ":loan a :Liability .\n");
    final JsonObject inconsistent =
        run(
            explain(
                violating,
                """
                {"subjectIri": "%1$sloan",
                 "predicateIri": "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                 "objectKind": "IRI", "objectIri": "%1$sAsset"}
                """
                    .formatted(F)),
            violating);
    assertEquals("INCONSISTENT", inconsistent.getString("outcome"), inconsistent.toString());
    assertEquals(
        List.of(
            "ClassAssertion(<%1$sAsset> <%1$sloan>)".formatted(F),
            "ClassAssertion(<%1$sLiability> <%1$sloan>)".formatted(F),
            "DisjointClasses(<%1$sAsset> <%1$sLiability>)".formatted(F)),
        strings(file(inconsistent, "result.json").get("justifications").getAsArray().get(0)));
  }

  @Test
  void aReleaseThatIsNotTheOneNamedOrThatImportsFailsBeforeAnyWorkerRuns() throws Exception {
    final Release imports =
        Releases.of(MEMBERS + "<" + Releases.ONTOLOGY_IRI + "> owl:imports <urn:other> .\n");
    final JsonObject missing = run(check(imports, "{\"kind\": \"CONSISTENCY\"}"), imports);
    assertEquals("FAILED", missing.getString("state"), missing.toString());
    assertEquals("MISSING_IMPORT", missing.getObj("problem").getString("code"));
    assertEquals("urn:other", missing.getObj("problem").getString("subject"));

    final Release real = Releases.of(MEMBERS);
    final Release elsewhere = new Release(real.ontologyId(), UUID.randomUUID(), real.rdf());
    final JsonObject changed = run(check(elsewhere, "{\"kind\": \"CONSISTENCY\"}"), elsewhere);
    assertEquals("FAILED", changed.getString("state"), changed.toString());
    assertEquals("INPUT_CHANGED", changed.getObj("problem").getString("code"));
    assertEquals(real.ontologyId().toString(), changed.getObj("problem").getString("subject"));
    assertEquals("capture", changed.getObj("problem").getString("step"));

    final Release never = Releases.of(MEMBERS);
    final JsonObject absent = run(check(never, "{\"kind\": \"CONSISTENCY\"}"));
    assertEquals("INPUT_CHANGED", absent.getObj("problem").getString("code"));
    assertEquals(never.digest(), absent.getObj("problem").getString("subject"));
  }

  @Test
  void aReleaseOverItsLimitAnswersIncompleteAtCapture() throws Exception {
    final StringBuilder many = new StringBuilder(MEMBERS);
    for (int i = 0; i < 100_000; i++) {
      many.append(":a").append(i).append(" a :Asset .\n");
    }
    final Release release = Releases.of(many.toString());
    final JsonObject incomplete = run(check(release, "{\"kind\": \"CONSISTENCY\"}"), release);
    assertEquals("SUCCEEDED", incomplete.getString("state"), incomplete.toString());
    assertEquals("INCOMPLETE", incomplete.getString("outcome"));
    final JsonObject problem = incomplete.getObj("problem");
    assertEquals("LIMIT_EXCEEDED", problem.getString("code"));
    assertEquals("maxClosureAxioms", problem.getString("subject"), "75,000 triples in 512 MiB");
    assertEquals("capture", problem.getString("step"));
  }

  private static JsonObject check(final Release release, final String question) {
    final JsonObject request = request("CHECK", release);
    request.put("check", JSON.parse(question));
    return request;
  }

  private static JsonObject explain(final Release release, final String statement) {
    final JsonObject request = request("EXPLAIN", release);
    final JsonObject explain = new JsonObject();
    explain.put("statement", JSON.parse(statement));
    request.put("explain", explain);
    return request;
  }

  private static JsonObject request(final String operation, final Release release) {
    final JsonObject request = new JsonObject();
    request.put("requestId", UUID.randomUUID().toString());
    request.put("operation", operation);
    request.put(
        "sourceRevision",
        JSON.parse(
            "{\"datasetGeneration\": \"9d72cf3b-3657-4ee3-809c-5bc70b9308bd\","
                + " \"liveWriteWatermark\": 1}"));
    return release.into(request);
  }

  /** Uploads each release first, as OpenMetadata does, then submits and waits for the job. */
  private JsonObject run(final JsonObject request, final Release... uploads) throws Exception {
    for (final Release release : uploads) {
      service.upload(
          release.digest().substring("sha256:".length()), new ByteArrayInputStream(release.rdf()));
    }
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

  private static List<List<String>> sourcesOf(final JsonObject result, final int justification) {
    final List<List<String>> sources = new ArrayList<>();
    result
        .get("justificationSources")
        .getAsArray()
        .get(justification)
        .getAsArray()
        .forEach(axiom -> sources.add(strings(axiom)));
    return sources;
  }

  private static List<String> strings(final JsonValue array) {
    final List<String> values = new ArrayList<>();
    array.getAsArray().forEach(value -> values.add(value.getAsString().value()));
    return values;
  }
}
