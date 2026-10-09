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
package org.openmetadata.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The step-file contract: what a caller may ask, and how every refusal is reported. */
class StepContractTest {
  private static final String ONTOLOGY =
      """
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX : <http://example.org/c#>
GRAPH <urn:c> {
  <http://example.org/c> a owl:Ontology .
  :p a owl:ObjectProperty .
  :A a owl:Class ; rdfs:subClassOf :B .
  :C a owl:Class ; rdfs:subClassOf :A .
  :B a owl:Class ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ;
      owl:someValuesFrom [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :A ] ] .
}
""";

  @TempDir Path job;

  @BeforeEach
  void load() {
    Jobs.file(job, "input.trig", ONTOLOGY);
    assertEquals(
        ExitCodes.COMPLETED, Jobs.run(Jobs.write(job, "load", Jobs.load("working", "input.trig"))));
  }

  @Test
  void anUnreadableOrUnknownStepIsInvalid() {
    assertInvalidStep("{ not json");
    assertInvalidStep(
        "{\"protocol\": 2, \"stepId\": \"s\", \"kind\": \"COMPACT\", \"dataset\": \"working\","
            + " \"timeoutMillis\": 1000}");
    assertInvalidStep(
        "{\"protocol\": 1, \"stepId\": \"s\", \"kind\": \"COMPACT\", \"dataset\": \"working\","
            + " \"timeoutMillis\": 1000, \"surprise\": true}");
    assertInvalidStep(
        "{\"protocol\": 1, \"stepId\": \"s\", \"kind\": \"REASON\", \"dataset\": \"working\","
            + " \"timeoutMillis\": 1000}");
    assertInvalidStep(
        "{\"protocol\": 1, \"stepId\": \"s\", \"kind\": \"COMPACT\", \"dataset\": \"working\","
            + " \"timeoutMillis\": 0}");
  }

  @Test
  void pathsCannotLeaveTheJobDirectory() throws Exception {
    assertInvalidStep(Jobs.load("../elsewhere", "input.trig"));
    assertInvalidStep(Jobs.load("/fuseki/databases/openmetadata", "input.trig"));
    assertInvalidStep(Jobs.load("fresh", "../input.trig"));
    final Path outside = Files.createTempDirectory("outside");
    Files.createSymbolicLink(job.resolve("link"), outside);
    assertInvalidStep(Jobs.load("link/dataset", "input.trig"));
  }

  @Test
  void loadNeedsAFreshDatasetAndQuads() {
    assertInvalidStep(Jobs.load("working", "input.trig"));
    final Path missing = Jobs.write(job, "missing", Jobs.load("fresh1", "nothing.nq"));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(missing));
    assertEquals("INPUT_MISSING", Jobs.firstProblem(missing));
    Jobs.file(job, "triples.ttl", "<urn:s> <urn:p> <urn:o> .\n");
    final Path triples = Jobs.write(job, "triples", Jobs.load("fresh2", "triples.ttl"));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(triples));
    assertEquals("INPUT_FORMAT", Jobs.firstProblem(triples));
    Jobs.file(job, "broken.nq", "<urn:s> <urn:p> \"unterminated <urn:g> .\n");
    final Path broken = Jobs.write(job, "broken", Jobs.load("fresh3", "broken.nq"));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(broken));
    assertEquals("PARSE_ERROR", Jobs.firstProblem(broken));
  }

  @Test
  void compressedNQuadsLoad() throws Exception {
    try (GZIPOutputStream out =
        new GZIPOutputStream(Files.newOutputStream(job.resolve("export.nq.gz")))) {
      out.write(
          "<urn:s> <urn:p> <urn:o> <urn:g> .\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    final Path load = Jobs.write(job, "gz", Jobs.load("gz-dataset", "export.nq.gz"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(load));
    assertEquals(1, Jobs.manifest(load).getNumber("quads").longValue());
  }

  @Test
  void classifyNeedsALoadedDatasetAndAKnownClosure() {
    assertInvalidStep(
        Jobs.classify("c1", "http://example.org/c", "urn:c", "").replace("working", "absent"));
    assertInvalidStep(
        Jobs.classify("c2", "http://example.org/c", "urn:c", ", \"output\": \"urn:c\""));
    final Path missingGraph =
        Jobs.write(job, "c3", Jobs.classify("c3", "http://example.org/c", "urn:absent", ""));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(missingGraph));
    assertEquals("MISSING_GRAPH", Jobs.firstProblem(missingGraph));
    final Path wrongRoot =
        Jobs.write(job, "c4", Jobs.classify("c4", "http://example.org/other", "urn:c", ""));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(wrongRoot));
    assertEquals("ROOT_NOT_IN_CLOSURE", Jobs.firstProblem(wrongRoot));
  }

  @Test
  void admissionLimitsRejectRatherThanTruncate() {
    assertLimit("{\"maxClosureTriples\": 5}", "CLOSURE_TOO_LARGE");
    assertLimit("{\"maxLogicalAxioms\": 1}", "TOO_MANY_AXIOMS");
    assertLimit("{\"maxExpressionDepth\": 1}", "EXPRESSION_TOO_DEEP");
    assertLimit("{\"maxOutputTriples\": 1}", "OUTPUT_TOO_LARGE");
  }

  @Test
  void aCheckAboutAnUnknownEntityIsInvalidNotNotEntailed() throws Exception {
    Jobs.file(
        job,
        "checks.ofn",
        "Ontology(SubClassOf(<http://example.org/c#A> <http://example.org/c#Typo>)"
            + " SubClassOf(<http://example.org/c#A> <http://example.org/c#B>))");
    final Path step =
        Jobs.write(
            job,
            "checks",
            Jobs.classify(
                "checks", "http://example.org/c", "urn:c", ", \"checks\": \"checks.ofn\""));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    final String report = Jobs.report(step);
    assertTrue(report.contains("<http://example.org/c#Typo>) INVALID"), report);
    assertTrue(report.contains("<http://example.org/c#B>) ENTAILED"), report);
  }

  @Test
  void aStaleResultIsNeverMistakenForANewOne() throws Exception {
    final Path step =
        Jobs.write(job, "stale", Jobs.classify("stale", "http://example.org/c", "urn:c", ""));
    Files.writeString(StepFile.resultPath(step), "{\"status\": \"SUCCEEDED\"}");
    Files.writeString(step, "{ broken");
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(step));
    assertEquals("INVALID_STEP", Jobs.outcome(step));
  }

  private void assertLimit(final String limits, final String code) {
    final String id = "limit-" + code.toLowerCase(java.util.Locale.ROOT);
    final Path step =
        Jobs.write(
            job, id, Jobs.classify(id, "http://example.org/c", "urn:c", ", \"limits\": " + limits));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step), code);
    assertEquals("LIMIT_EXCEEDED", Jobs.outcome(step));
    assertEquals(code, Jobs.firstProblem(step));
  }

  private void assertInvalidStep(final String json) {
    final Path step = Jobs.write(job, "invalid", json);
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(step), json);
    assertEquals("INVALID_STEP", Jobs.outcome(step));
    assertFalse(Files.exists(job.resolve("elsewhere")));
  }
}
