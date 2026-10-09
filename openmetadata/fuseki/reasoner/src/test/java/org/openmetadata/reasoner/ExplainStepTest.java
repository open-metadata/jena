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
  void anInconsistentClosureHasNoUsefulEntailmentsToExplain() throws Exception {
    final Path step =
        explain(
            "on-inconsistent",
            "dl-functional-inconsistent",
            "ClassAssertion(<" + FUNCTIONAL + "Team> <" + FUNCTIONAL + "alice>)");
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step));
    assertEquals("INCONSISTENT", Jobs.outcome(step));
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
