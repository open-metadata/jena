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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One bounded justification per request, over the conformance fixtures. */
class ExplainStepTest {
  private static final String EXISTENTIAL = "http://example.org/el-intersection-existential#";
  private static final String FUNCTIONAL = "http://example.org/dl-functional-inconsistent#";

  @TempDir static Path job;

  @BeforeAll
  static void load() throws Exception {
    Conformance.prepare(job);
  }

  @Test
  void aSubsumptionIsJustifiedByTheAxiomsThatEntailIt() throws Exception {
    final Path step =
        explain(
            "subsumption",
            "el-intersection-existential",
            "SubClassOf(<" + EXISTENTIAL + "RegisteredCustomer> <" + EXISTENTIAL + "Party>)");
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    assertEquals(
        """
        OUTCOME EXPLAINED
        TARGET SubClassOf(<%1$sRegisteredCustomer> <%1$sParty>)
        JUSTIFICATION SubClassOf(<%1$sCustomer> <%1$sParty>)
        JUSTIFICATION SubClassOf(<%1$sRegisteredCustomer> ObjectIntersectionOf(<%1$sCustomer> \
        ObjectSomeValuesFrom(<%1$shasContact> <%1$sEmailAddress>)))
        """
            .formatted(EXISTENTIAL),
        Jobs.report(step));
  }

  @Test
  void anInconsistencyIsJustifiedByTheClashingAxioms() throws Exception {
    final Path step =
        Jobs.write(
            job,
            "explain-inconsistency",
            """
            {"protocol": 1, "stepId": "explain-inconsistency", "kind": "EXPLAIN",
             "dataset": "working", "timeoutMillis": 60000, "report": true,
             "closure": {"root": "http://example.org/dl-functional-inconsistent",
                         "graphs": ["urn:fixture:dl-functional-inconsistent"]},
             "explain": {"inconsistency": true}}
            """);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    assertEquals(
        """
        OUTCOME EXPLAINED
        TARGET INCONSISTENCY
        JUSTIFICATION ClassAssertion(<%1$sPerson> <%1$salice>)
        JUSTIFICATION ClassAssertion(<%1$sTeam> <%1$splatformTeam>)
        JUSTIFICATION DisjointClasses(<%1$sPerson> <%1$sTeam>)
        JUSTIFICATION FunctionalObjectProperty(<%1$sprimaryOwner>)
        JUSTIFICATION ObjectPropertyAssertion(<%1$sprimaryOwner> <%1$sdataset1> <%1$salice>)
        JUSTIFICATION ObjectPropertyAssertion(<%1$sprimaryOwner> <%1$sdataset1> <%1$splatformTeam>)
        """
            .formatted(FUNCTIONAL),
        Jobs.report(step));
  }

  @Test
  void aNonEntailmentIsReportedAsSuchNotAsARefutation() throws Exception {
    final Path step =
        explain(
            "not-entailed",
            "el-intersection-existential",
            "SubClassOf(<" + EXISTENTIAL + "Party> <" + EXISTENTIAL + "Customer>)");
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    assertEquals("NOT_ENTAILED", Jobs.outcome(step));
  }

  @Test
  void anEntailmentOfAnInconsistentClosureIsExplainedByTheInconsistency() throws Exception {
    final Path step =
        explain(
            "on-inconsistent",
            "dl-functional-inconsistent",
            "ClassAssertion(<" + FUNCTIONAL + "Team> <" + FUNCTIONAL + "alice>)");
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    assertEquals("INCONSISTENT", Jobs.outcome(step));
    assertEquals(6, Jobs.manifest(step).getNumber("justificationSize").intValue());
    assertTrue(
        Jobs.report(step)
            .contains(
                "JUSTIFICATION DisjointClasses(<%1$sPerson> <%1$sTeam>)".formatted(FUNCTIONAL)));
  }

  @Test
  void severalJustificationsAreBoundedByTheirCount() throws Exception {
    Jobs.file(
        job,
        "two-ways.trig",
        """
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX : <http://example.org/two#>
        GRAPH <urn:two> {
          <http://example.org/two> a owl:Ontology .
          :A a owl:Class ; rdfs:subClassOf :B , :D .
          :B a owl:Class ; rdfs:subClassOf :C .
          :D a owl:Class ; rdfs:subClassOf :C .
          :C a owl:Class .
        }
        """);
    assertEquals(
        ExitCodes.COMPLETED,
        Jobs.run(Jobs.write(job, "load-two", Jobs.load("two", "two-ways.trig"))));
    final String two = "http://example.org/two#";
    final Path all = several("several", 10, two);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(all), () -> Jobs.manifest(all).toString());
    assertEquals(
        """
        OUTCOME EXPLAINED
        TARGET SubClassOf(<%1$sA> <%1$sC>)
        JUSTIFICATION SubClassOf(<%1$sA> <%1$sB>)
        JUSTIFICATION SubClassOf(<%1$sB> <%1$sC>)
        JUSTIFICATION#2 SubClassOf(<%1$sA> <%1$sD>)
        JUSTIFICATION#2 SubClassOf(<%1$sD> <%1$sC>)
        """
            .formatted(two),
        Jobs.report(all));
    assertEquals(2, Jobs.manifest(all).get("justifications").getAsArray().size());

    final Path one = several("one", 1, two);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(one));
    assertEquals(1, Jobs.manifest(one).get("justifications").getAsArray().size());
  }

  private static Path several(final String id, final int max, final String namespace) {
    return Jobs.write(
        job,
        id,
        """
        {"protocol": 1, "stepId": "%s", "kind": "EXPLAIN", "dataset": "two",
         "timeoutMillis": 60000, "report": true,
         "closure": {"root": "http://example.org/two", "graphs": ["urn:two"]},
         "explain": {"axiom": "SubClassOf(<%sA> <%sC>)", "maxJustifications": %d}}
        """
            .formatted(id, namespace, namespace, max));
  }

  @Test
  void aJustificationNeedingMoreChecksThanItsLimitIsRejected() throws Exception {
    final Path subsumption =
        Jobs.write(
            job,
            "explain-capped",
            """
            {"protocol": 1, "stepId": "explain-capped", "kind": "EXPLAIN", "dataset": "working",
             "timeoutMillis": 60000, "limits": {"maxExplainChecks": 2},
             "closure": {"root": "http://example.org/el-intersection-existential",
                         "graphs": ["urn:fixture:el-intersection-existential"]},
             "explain": {"axiom": "SubClassOf(<%1$sRegisteredCustomer> <%1$sParty>)"}}
            """
                .formatted(EXISTENTIAL));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(subsumption));
    assertEquals("LIMIT_EXCEEDED", Jobs.outcome(subsumption));
    assertEquals("EXPLAIN_CHECKS_EXCEEDED", Jobs.firstProblem(subsumption));

    final Path inconsistency =
        Jobs.write(
            job,
            "explain-inconsistency-capped",
            """
            {"protocol": 1, "stepId": "explain-inconsistency-capped", "kind": "EXPLAIN",
             "dataset": "working", "timeoutMillis": 60000, "limits": {"maxExplainChecks": 2},
             "closure": {"root": "http://example.org/dl-functional-inconsistent",
                         "graphs": ["urn:fixture:dl-functional-inconsistent"]},
             "explain": {"inconsistency": true}}
            """);
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(inconsistency));
    assertEquals("EXPLAIN_CHECKS_EXCEEDED", Jobs.firstProblem(inconsistency));
  }

  @Test
  void aTargetOutsideTheClosureIsRejected() throws Exception {
    final Path step =
        explain(
            "unknown",
            "el-intersection-existential",
            "SubClassOf(<" + EXISTENTIAL + "Nobody> <" + EXISTENTIAL + "Party>)");
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step));
    assertEquals("UNKNOWN_ENTITY", Jobs.firstProblem(step));
  }

  private static Path explain(final String id, final String fixture, final String axiom) {
    return Jobs.write(
        job,
        id,
        """
        {"protocol": 1, "stepId": "%s", "kind": "EXPLAIN", "dataset": "working",
         "timeoutMillis": 60000, "report": true,
         "closure": {"root": "http://example.org/%s", "graphs": ["urn:fixture:%s"]},
         "explain": {"axiom": "%s"}}
        """
            .formatted(id, fixture, fixture, axiom));
  }
}
