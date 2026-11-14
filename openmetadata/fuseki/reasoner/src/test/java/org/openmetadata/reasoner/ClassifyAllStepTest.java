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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLASSIFY_ALL classifies several closures in one process and keeps them apart as steps of their
 * own were: each closure has its own entailment graph and its own result manifest, and the first
 * closure that is inconsistent, rejected, over the shared output budget or past the deadline ends
 * the step, leaving the closures before it complete and the ones after it untouched.
 */
class ClassifyAllStepTest {
  private static final String PREFIXES =
      """
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      """;

  @TempDir Path job;

  @Test
  void eachClosureGetsItsOwnEntailmentGraphAndManifest() throws Exception {
    load(hierarchy("a", 3) + hierarchy("b", 5));
    final Path step = step(60_000, "", closure("a"), closure("b"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());

    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("CLASSIFY_ALL", manifest.getString("kind"));
    assertEquals("CLASSIFIED", manifest.getString("outcome"));
    assertEquals(2, manifest.getObj("closures").getNumber("total").intValue());
    assertEquals(2, manifest.getObj("closures").getNumber("classified").intValue());
    assertEquals(2 + 4, manifest.getNumber("outputTriples").longValue());
    assertFalse(manifest.hasKey("stoppedAt"));

    final JsonObject first = part("all-0");
    assertEquals("all-0", first.getString("stepId"));
    assertEquals("CLASSIFY", first.getString("kind"));
    assertEquals("SUCCEEDED", first.getString("status"));
    assertEquals("CLASSIFIED", first.getString("outcome"));
    assertEquals(2, first.getObj("entailments").getNumber("outputTriples").longValue());
    assertEquals("http://example.org/a", first.getObj("closure").getString("root"));
    assertTrue(first.getObj("worker").hasKey("heapPeakAfterGcBytes"), first.toString());
    assertEquals(4, part("all-1").getObj("entailments").getNumber("outputTriples").longValue());

    assertEquals(
        Set.of("http://example.org/a#C1", "http://example.org/a#C2"),
        subjects("urn:a:entailments"));
    assertEquals(4, subjects("urn:b:entailments").size());
    assertFalse(Files.exists(job.resolve("all.closure.nt")));
  }

  @Test
  void anInconsistentClosureStopsTheStepAndIsNamed() throws Exception {
    load(
        hierarchy("a", 3)
            + """
              GRAPH <urn:bad> {
                <http://example.org/bad> a owl:Ontology .
                <http://example.org/bad#A> a owl:Class .
                <http://example.org/bad#B> a owl:Class ;
                    owl:disjointWith <http://example.org/bad#A> .
                <http://example.org/bad#i> a owl:NamedIndividual ,
                    <http://example.org/bad#A> , <http://example.org/bad#B> .
              }
              """
            + hierarchy("c", 3));
    final Path step = step(60_000, "", closure("a"), closure("bad"), closure("c"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());

    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("INCONSISTENT", manifest.getString("outcome"));
    assertEquals("all-1", manifest.getString("stoppedAt"));
    assertEquals(1, manifest.getObj("closures").getNumber("classified").intValue());
    assertTrue(part("all-0").getObj("entailments").getBoolean("consistent"));
    assertEquals("INCONSISTENT", part("all-1").getString("outcome"));
    assertFalse(part("all-1").getObj("entailments").getBoolean("consistent"));
    assertFalse(Files.exists(job.resolve("all-2.result.json")), "a later closure never ran");
    assertEquals(2, subjects("urn:a:entailments").size(), "the closure before it is complete");
    assertEquals(Set.of(), subjects("urn:bad:entailments"));
    assertEquals(Set.of(), subjects("urn:c:entailments"));
  }

  @Test
  void aRejectedClosureFailsTheStepWithItsProblems() throws Exception {
    load(
        hierarchy("a", 3)
            + """
              GRAPH <urn:imports> {
                <http://example.org/imports> a owl:Ontology ;
                    owl:imports <http://example.org/elsewhere> .
                <http://example.org/imports#A> a owl:Class .
              }
              """);
    final Path step = step(60_000, "", closure("a"), closure("imports"));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step), () -> Jobs.manifest(step).toString());

    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("FAILED", manifest.getString("status"));
    assertEquals("INVALID_INPUT", manifest.getString("outcome"));
    assertEquals("MISSING_IMPORT", Jobs.firstProblem(step));
    assertEquals("all-1", manifest.getString("stoppedAt"));
    final JsonObject rejected = part("all-1");
    assertEquals("FAILED", rejected.getString("status"));
    assertEquals(
        "MISSING_IMPORT",
        rejected.get("problems").getAsArray().get(0).getAsObject().getString("code"));
    assertEquals("SUCCEEDED", part("all-0").getString("status"));
    assertEquals(2, subjects("urn:a:entailments").size());
  }

  @Test
  void theClosuresShareOneOutputBudget() throws Exception {
    load(hierarchy("a", 3) + hierarchy("b", 5));
    final Path step =
        step(60_000, ", \"limits\": {\"maxNewFacts\": 5}", closure("a"), closure("b"));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step), () -> Jobs.manifest(step).toString());

    assertEquals("LIMIT_EXCEEDED", Jobs.outcome(step));
    assertEquals("OUTPUT_TOO_LARGE", Jobs.firstProblem(step));
    assertEquals("all-1", Jobs.manifest(step).getString("stoppedAt"));
    assertEquals(5, part("all-0").getObj("limits").getNumber("maxOutputTriples").longValue());
    assertEquals(
        3,
        part("all-1").getObj("limits").getNumber("maxOutputTriples").longValue(),
        "what the first closure left of the five");
    assertEquals(Set.of(), subjects("urn:b:entailments"), "nothing of a closure over the budget");
  }

  @Test
  void aClosurePastTheDeadlineEndsTheStepAndKeepsTheOnesBeforeIt() throws Exception {
    load(hierarchy("a", 3) + Jobs.hardOntology(200));
    final Path step =
        step(
            3_000,
            "",
            closure("a"),
            """
            {"root": "http://example.org/hard", "graphs": ["urn:hard"],
             "output": "urn:hard:entailments"}""");
    final long start = System.nanoTime();
    assertEquals(ExitCodes.TIME_BUDGET, Jobs.run(step));
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertEquals("INCOMPLETE", Jobs.manifest(step).getString("status"));
    assertEquals("TIME_BUDGET", Jobs.outcome(step));
    assertTrue(
        elapsed < 3_000 + Watchdog.UNWIND_GRACE_MILLIS + 2_000, "elapsed " + elapsed + " ms");
    assertEquals("SUCCEEDED", part("all-0").getString("status"));
    assertFalse(Files.exists(job.resolve("all-1.result.json")), "the closure it interrupted");
    assertEquals(2, subjects("urn:a:entailments").size());
    assertEquals(Set.of(), subjects("urn:hard:entailments"));
    assertFalse(Files.exists(job.resolve("all.closure.nt")));
  }

  @Test
  void aSecondRunReplacesTheResultsOfTheFirst() throws Exception {
    load(hierarchy("a", 3) + hierarchy("b", 5));
    final Path both = step(60_000, "", closure("a"), closure("b"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(both));
    assertTrue(Files.exists(job.resolve("all-1.result.json")));
    final Path one = step(60_000, "", closure("b"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(one));
    assertEquals("http://example.org/b", part("all-0").getObj("closure").getString("root"));
  }

  @Test
  void theClosuresFileIsValidatedBeforeAnythingRuns() throws Exception {
    load(hierarchy("a", 3) + hierarchy("b", 3));
    assertInvalid("an absent file", null);
    assertInvalid("no closures", "[]");
    assertInvalid("not an array", "{}");
    assertInvalid("no output", "[{\"root\": \"http://example.org/a\", \"graphs\": [\"urn:a\"]}]");
    assertInvalid("one output twice", "[" + closure("a") + "," + closure("a") + "]");
    assertInvalid(
        "an unknown key",
        "[" + closure("a").replace("\"root\"", "\"engine\": \"ELK\", \"root\"") + "]");
    assertInvalid(
        "all graphs, which would take in the outputs",
        "[{\"root\": \"http://example.org/a\", \"allGraphs\": true,"
            + " \"output\": \"urn:a:entailments\"}]");
    assertInvalid(
        "an output another closure reads",
        "["
            + closure("a")
            + ", {\"root\": \"http://example.org/b\", \"graphs\": [\"urn:b\","
            + " \"urn:a:entailments\"], \"output\": \"urn:b:entailments\"}]");
    final StringBuilder many = new StringBuilder("[");
    for (int i = 0; i <= StepFile.MAX_BATCH_CLOSURES; i++) {
      many.append(i == 0 ? "" : ",")
          .append(closure("a").replace("urn:a:entailments", "urn:a:entailments:" + i));
    }
    assertInvalid("too many closures", many.append("]").toString());
    assertEquals(Set.of(), subjects("urn:a:entailments"), "nothing ran");

    final Path report = Jobs.write(job, "report", stepJson("report", 60_000, ", \"report\": true"));
    Jobs.file(job, "closures.json", "[" + closure("a") + "]");
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(report));
    final Path elsewhere =
        Jobs.write(
            job,
            "elsewhere",
            stepJson("elsewhere", 60_000, "").replace("closures.json", "../closures.json"));
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(elsewhere));
    final Path classify =
        Jobs.write(
            job,
            "classify",
            Jobs.classify("classify", "http://example.org/a", "urn:a", "")
                .replace("\"report\": true", "\"closuresFile\": \"closures.json\""));
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(classify), "only CLASSIFY_ALL takes closures");
  }

  private void assertInvalid(final String what, final String closures) throws Exception {
    if (closures == null) {
      Files.deleteIfExists(job.resolve("closures.json"));
    } else {
      Jobs.file(job, "closures.json", closures);
    }
    final Path step = Jobs.write(job, "all", stepJson("all", 60_000, ""));
    assertEquals(ExitCodes.INVALID_STEP, Jobs.run(step), what);
    assertEquals("INVALID_STEP", Jobs.firstProblem(step), what);
  }

  private void load(final String graphs) {
    Jobs.file(job, "input.trig", PREFIXES + graphs);
    assertEquals(
        ExitCodes.COMPLETED, Jobs.run(Jobs.write(job, "load", Jobs.load("working", "input.trig"))));
  }

  /** A chain of {@code classes} classes in graph urn:name, entailing classes - 1 subclass facts. */
  private static String hierarchy(final String name, final int classes) {
    final String ontology = "http://example.org/" + name;
    final StringBuilder trig = new StringBuilder("GRAPH <urn:" + name + "> {\n");
    trig.append('<').append(ontology).append("> a owl:Ontology .\n");
    for (int i = 0; i < classes; i++) {
      trig.append('<').append(ontology).append("#C").append(i).append("> a owl:Class");
      if (i > 0) {
        trig.append(" ; rdfs:subClassOf <").append(ontology).append("#C").append(i - 1).append('>');
      }
      trig.append(" .\n");
    }
    return trig.append("}\n").toString();
  }

  private static String closure(final String name) {
    return """
           {"root": "http://example.org/%s", "graphs": ["urn:%s"],
            "output": "urn:%s:entailments"}"""
        .formatted(name, name, name);
  }

  private Path step(final long timeoutMillis, final String extra, final String... closures) {
    Jobs.file(job, "closures.json", "[" + String.join(",", closures) + "]");
    return Jobs.write(job, "all", stepJson("all", timeoutMillis, extra));
  }

  private static String stepJson(final String id, final long timeoutMillis, final String extra) {
    return """
           {"protocol": 1, "stepId": "%s", "kind": "CLASSIFY_ALL", "dataset": "working",
            "timeoutMillis": %d, "closuresFile": "closures.json"%s}
           """
        .formatted(id, timeoutMillis, extra);
  }

  private JsonObject part(final String stepId) {
    return Jobs.manifest(job.resolve(stepId + ".json"));
  }

  private Set<String> subjects(final String graph) {
    final DatasetGraph dataset =
        DatabaseMgr.connectDatasetGraph(Location.create(job.resolve("working")));
    try {
      return Txn.calculateRead(
          dataset,
          () -> {
            final Set<String> subjects = new TreeSet<>();
            dataset
                .find(NodeFactory.createURI(graph), Node.ANY, Node.ANY, Node.ANY)
                .forEachRemaining(quad -> subjects.add(quad.getSubject().getURI()));
            return subjects;
          });
    } finally {
      TDBInternal.expel(dataset);
    }
  }
}
