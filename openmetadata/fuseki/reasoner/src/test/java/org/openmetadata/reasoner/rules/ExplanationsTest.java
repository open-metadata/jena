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
package org.openmetadata.reasoner.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.out.NodeFmtLib;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.ExitCodes;
import org.openmetadata.reasoner.Jobs;
import org.openmetadata.reasoner.rules.Explanations.ConditionPremise;
import org.openmetadata.reasoner.rules.Explanations.Fact;
import org.openmetadata.reasoner.rules.Explanations.Origin;
import org.openmetadata.reasoner.rules.Explanations.PathPremise;

/**
 * Explanations follow witnesses from a derived fact down to the facts the rules read, through every
 * rule that contributed, reconstruct property paths as chains, and say where they stopped.
 */
class ExplanationsTest {
  private static final String C = "http://example.org/catalog/";
  private static final String CATALOG =
      """
      PREFIX om: <https://open-metadata.org/ontology/>
      PREFIX : <http://example.org/catalog/>
      PREFIX prov: <http://www.w3.org/ns/prov#>
      GRAPH <urn:catalog:asserted> {
        :pii om:tagFQN "PII.Sensitive" .
        :schema a om:DatabaseSchema ; om:hasTag :pii .
        :orders a om:Table ; om:belongsToSchema :schema ; om:hasColumn :orders_email .
        :l1 om:fromColumn :orders_email ; om:toColumn :report_email .
        :l2 om:fromColumn :report_email ; om:toColumn :export_email .
        :t1 prov:wasDerivedFrom :t0 . :t2 prov:wasDerivedFrom :t1 . :t3 prov:wasDerivedFrom :t2 .
        :t4 prov:wasDerivedFrom :t3 . :t3 prov:wasDerivedFrom :t1 .
        :export_email :reviewedBy :alice .
      }
      """;

  @TempDir Path job;

  @Test
  void aFactDerivedAcrossRulesIsExplainedDownToAssertedFacts() throws Exception {
    run();
    final Fact fact = explain(tag("export_email"), Explanations.Bounds.DEFAULT);
    assertEquals(Origin.DERIVED, fact.origin());
    assertEquals("pii-propagation-via-lineage", fact.rule());
    assertEquals(4, fact.pass());
    assertFalse(fact.truncated());
    assertEquals(4, fact.premises().size());
    final Fact upstream = premise(fact, tag("report_email"));
    assertEquals("pii-propagation-via-lineage", upstream.rule());
    final Fact column = premise(upstream, tag("orders_email"));
    assertEquals("schema-tag-inheritance", column.rule());
    assertEquals(2, column.pass());
    assertEquals(3, column.premises().size());
    // The table's tag came from the schema, through the other UNION branch of the same rule.
    final Fact table = premise(column, tag("orders"));
    assertEquals("schema-tag-inheritance", table.rule());
    assertEquals(1, table.pass());
    assertEquals(3, table.premises().size());
    for (final Explanations.Premise premise : table.premises()) {
      assertEquals(Origin.ASSERTED, ((Fact) premise).origin());
    }
    final JsonObject json = fact.toJson();
    assertEquals("DERIVED", json.getString("origin"));
    assertEquals(
        List.of(
            "<" + C + "export_email> <https://open-metadata.org/ontology/hasTag> <" + C + "pii> .",
            "ASSERTED"),
        List.of(
            json.getString("triple"),
            explain(
                    Triple.create(uri("schema"), om("hasTag"), uri("pii")),
                    Explanations.Bounds.DEFAULT)
                .origin()
                .name()));
  }

  @Test
  void explanationsStopAtTheirBoundsAndSaySo() throws Exception {
    run();
    final Fact shallow =
        explain(tag("export_email"), new Explanations.Bounds(1, 1_000, 256, 1_000));
    final Fact upstream = premise(shallow, tag("report_email"));
    assertEquals(Origin.DERIVED, upstream.origin());
    assertTrue(upstream.truncated());
    assertTrue(upstream.premises().isEmpty());
    final Fact small = explain(tag("export_email"), new Explanations.Bounds(16, 2, 256, 1_000));
    assertTrue(
        small.truncated() || small.premises().stream().anyMatch(p -> ((Fact) p).truncated()));
    assertEquals(Origin.ABSENT, explain(tag("orders_id"), Explanations.Bounds.DEFAULT).origin());
  }

  @Test
  void aPathPremiseIsReconstructedAsAShortestChain() throws Exception {
    run();
    final Triple closure = Triple.create(uri("t4"), om("transitivelyDerivedFrom"), uri("t0"));
    final Fact fact = explain(closure, Explanations.Bounds.DEFAULT);
    assertEquals("transitive-lineage-closure", fact.rule());
    final PathPremise path = (PathPremise) fact.premises().get(0);
    assertTrue(path.complete());
    assertEquals(
        List.of(
            "<" + C + "t1> <http://www.w3.org/ns/prov#wasDerivedFrom> <" + C + "t0> .",
            "<" + C + "t3> <http://www.w3.org/ns/prov#wasDerivedFrom> <" + C + "t1> .",
            "<" + C + "t4> <http://www.w3.org/ns/prov#wasDerivedFrom> <" + C + "t3> ."),
        path.chain().stream().map(NodeFmtLib::strNT).toList().reversed());
    final PathPremise cut =
        (PathPremise)
            explain(closure, new Explanations.Bounds(16, 1_000, 2, 1_000)).premises().get(0);
    assertFalse(cut.complete());
    assertEquals(2, cut.chain().size());
  }

  @Test
  void anExistsConditionIsAnsweredWithOneMatch() throws Exception {
    run();
    final Fact fact =
        explain(
            Triple.create(uri("export_email"), om("reviewedPii"), uri("alice")),
            Explanations.Bounds.DEFAULT);
    assertEquals("reviewed-pii", fact.rule());
    final ConditionPremise condition = (ConditionPremise) fact.premises().get(1);
    assertEquals(List.of(tag("export_email")), condition.match());
  }

  private void run() throws Exception {
    Jobs.file(job, "catalog.trig", CATALOG);
    assertEquals(
        ExitCodes.COMPLETED,
        Jobs.run(Jobs.write(job, "load", Jobs.load("working", "catalog.trig"))));
    final List<JsonObject> bundle = new java.util.ArrayList<>(RuleFixtures.starterBundle());
    bundle.get(3).put("enabled", true);
    bundle.add(
        RuleFixtures.rule(
            "reviewed-pii",
            """
            PREFIX om: <https://open-metadata.org/ontology/>
            PREFIX : <http://example.org/catalog/>
            CONSTRUCT { ?column om:reviewedPii ?who }
            WHERE { ?column :reviewedBy ?who FILTER EXISTS { ?column om:hasTag ?tag } }
            """));
    final Path step = Jobs.write(job, "rules", RuleFixtures.step("rules", bundle, null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
  }

  private static Fact premise(final Fact fact, final Triple triple) {
    return fact.premises().stream()
        .map(Fact.class::cast)
        .filter(premise -> premise.triple().equals(triple))
        .findFirst()
        .orElseThrow(() -> new AssertionError(triple + " is not a premise of " + fact));
  }

  private Fact explain(final Triple fact, final Explanations.Bounds bounds) {
    return RuleFixtures.read(
        job,
        dataset ->
            Explanations.explain(
                dataset, NodeFactory.createURI(RuleFixtures.PROVENANCE), fact, bounds));
  }

  private static Triple tag(final String column) {
    return Triple.create(uri(column), om("hasTag"), uri("pii"));
  }

  private static Node uri(final String local) {
    return NodeFactory.createURI(C + local);
  }

  private static Node om(final String local) {
    return NodeFactory.createURI(RuleFixtures.OM + local);
  }
}
