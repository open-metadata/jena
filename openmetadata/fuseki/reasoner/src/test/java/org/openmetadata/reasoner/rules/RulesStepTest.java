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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openmetadata.reasoner.rules.RuleFixtures.ASSERTED;
import static org.openmetadata.reasoner.rules.RuleFixtures.om;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import org.openmetadata.reasoner.ExitCodes;
import org.openmetadata.reasoner.Jobs;

/**
 * A RULES step over a real working dataset, run by the step runner: OpenMetadata's starter rules
 * keep their intended meaning, the fixed point does not depend on rule order or on facts derived
 * twice, a refresh retracts what lost its support, and every budget ends the step INCOMPLETE.
 */
class RulesStepTest {
  private static final String PREFIXES =
      """
      PREFIX om: <https://open-metadata.org/ontology/>
      PREFIX : <http://example.org/catalog/>
      PREFIX prov: <http://www.w3.org/ns/prov#>
      """;

  /**
   * A schema tagged PII.Sensitive holding a table in the sales domain with two columns; the first
   * column feeds a column of a second, Tier-tagged table, which feeds a third column.
   */
  private static final String CATALOG =
      PREFIXES
          + """
            GRAPH <urn:catalog:asserted> {
              :pii om:tagFQN "PII.Sensitive" .
              :tier om:tagFQN "Tier.Tier1" .
              :schema a om:DatabaseSchema ; om:hasTag :pii .
              :orders a om:Table ; om:belongsToSchema :schema ; om:belongsToDomain :sales ;
                om:hasColumn :orders_email , :orders_id .
              :report a om:Table ; om:hasTag :tier ; om:hasColumn :report_email .
              :l1 om:fromColumn :orders_email ; om:toColumn :report_email .
              :l2 om:fromColumn :report_email ; om:toColumn :export_email .
            }
            """;

  @TempDir Path job;

  @Test
  void theStarterRulesInheritTagsAndDomainsAndPropagatePiiAlongLineage() throws Exception {
    load(CATALOG);
    final Set<String> asserted = RuleFixtures.triples(job, ASSERTED);
    final List<JsonObject> bundle = RuleFixtures.starterBundle();
    final Path step = Jobs.write(job, "rules", RuleFixtures.step("rules", bundle, null, true));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("CONVERGED", manifest.getString("outcome"));

    final String c = "<http://example.org/catalog/";
    assertEquals(
        Set.of(
            c + "orders_email> " + om("belongsToDomain") + " " + c + "sales> .",
            c + "orders_id> " + om("belongsToDomain") + " " + c + "sales> ."),
        RuleFixtures.triples(job, RuleFixtures.output("domain-membership-inheritance")));
    assertEquals(
        Set.of(
            c + "orders> " + om("hasTag") + " " + c + "pii> .",
            c + "orders> " + om("inferredTagSource") + " " + c + "schema> .",
            c + "orders_email> " + om("hasTag") + " " + c + "pii> .",
            c + "orders_email> " + om("inferredTagSource") + " " + c + "orders> .",
            c + "orders_id> " + om("hasTag") + " " + c + "pii> .",
            c + "orders_id> " + om("inferredTagSource") + " " + c + "orders> .",
            c + "report_email> " + om("hasTag") + " " + c + "tier> .",
            c + "report_email> " + om("inferredTagSource") + " " + c + "report> ."),
        RuleFixtures.triples(job, RuleFixtures.output("schema-tag-inheritance")));
    // Two hops of lineage from a column whose PII tag is itself inherited; the Tier tag is not PII.
    assertEquals(
        Set.of(
            c + "report_email> " + om("hasTag") + " " + c + "pii> .",
            c + "report_email> " + om("inferredTagSource") + " " + c + "orders_email> .",
            c + "export_email> " + om("hasTag") + " " + c + "pii> .",
            c + "export_email> " + om("inferredTagSource") + " " + c + "report_email> ."),
        RuleFixtures.triples(job, RuleFixtures.output("pii-propagation-via-lineage")));
    assertEquals(
        Set.of(), RuleFixtures.triples(job, RuleFixtures.output("transitive-lineage-closure")));
    assertEquals(asserted, RuleFixtures.triples(job, ASSERTED), "the asserted graph is unchanged");
    assertEquals(5, manifest.getNumber("passes").longValue(), manifest.toString());
    assertTrue(Jobs.report(step).startsWith("OUTCOME CONVERGED\nFACT "), Jobs.report(step));
    assertFalse(Files.exists(job.resolve("rules.spool.tmp")));
  }

  /** a -p-> b gives q, q gives r, r gives s: each rule reads the previous rule's conclusions. */
  @Test
  void aThreeRuleChainConvergesToTheSameFactsInEveryOrder() throws Exception {
    final StringBuilder chain = new StringBuilder(PREFIXES + "GRAPH <urn:catalog:asserted> {\n");
    for (int i = 0; i < 20; i++) {
      chain.append(":n").append(i).append(" :p :n").append(i + 1).append(" .\n");
    }
    load(chain.append("}\n").toString());
    final List<JsonObject> rules =
        List.of(
            RuleFixtures.rule("to-q", PREFIXES + "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y }"),
            RuleFixtures.rule(
                "to-r", PREFIXES + "CONSTRUCT { ?x :r ?z } WHERE { ?x :q ?y . ?y :q ?z }"),
            RuleFixtures.rule("to-s", PREFIXES + "CONSTRUCT { ?z :s ?x } WHERE { ?x :r ?z }"));
    Set<String> first = null;
    long passes = -1;
    int order = 0;
    for (final List<JsonObject> permutation : permutations(rules)) {
      final String id = "order" + order++;
      final Path step = Jobs.write(job, id, RuleFixtures.step(id, permutation, null, false));
      assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
      final Set<String> derived = RuleFixtures.derived(job, rules);
      final long stepPasses = Jobs.manifest(step).getNumber("passes").longValue();
      if (first == null) {
        first = derived;
        passes = stepPasses;
      }
      assertEquals(first, derived, id);
      assertEquals(passes, stepPasses, id);
    }
    assertEquals(20 + 19 + 19, first.size());
    assertEquals(4, passes);
  }

  /**
   * A symmetric rule derives every fact again in the next pass, two rules derive the same facts,
   * and one rule derives facts that are asserted: the step still converges, and only the derived
   * facts that are not asserted are stored, once per rule graph.
   */
  @Test
  void factsDerivedTwiceOrAlreadyAssertedDoNotPreventTermination() throws Exception {
    load(
        PREFIXES
            + """
GRAPH <urn:catalog:asserted> { :a :knows :b . :b :knows :c . :c :knows :a . :b :friend :a . }
""");
    final List<JsonObject> rules =
        List.of(
            RuleFixtures.rule(
                "symmetric", PREFIXES + "CONSTRUCT { ?y :knows ?x } WHERE { ?x :knows ?y }"),
            RuleFixtures.rule(
                "friends", PREFIXES + "CONSTRUCT { ?x :friend ?y } WHERE { ?x :knows ?y }"),
            RuleFixtures.rule(
                "friends-again", PREFIXES + "CONSTRUCT { ?y :friend ?x } WHERE { ?x :knows ?y }"));
    final Path step = Jobs.write(job, "rules", RuleFixtures.step("rules", rules, null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
    assertEquals(3, RuleFixtures.triples(job, RuleFixtures.output("symmetric")).size());
    // Six friend pairs among a, b and c in both directions, less the asserted b friend a.
    assertEquals(5, RuleFixtures.triples(job, RuleFixtures.output("friends")).size());
    assertEquals(5, RuleFixtures.triples(job, RuleFixtures.output("friends-again")).size());
    final JsonObject manifest = Jobs.manifest(step);
    assertEquals(8, manifest.getNumber("newFacts").longValue(), manifest.toString());
    assertEquals(13, manifest.getNumber("derived").longValue(), manifest.toString());
  }

  /**
   * Conclusions held up only by each other, around a lineage cycle, disappear when the premise that
   * started them is deleted and the rules are recomputed; one with a second support stays.
   */
  @Test
  void deletingAPremiseRetractsWhatLostItsSupportIncludingCyclesButKeepsOtherSupports()
      throws Exception {
    final String lineage =
        """
        :pii om:tagFQN "PII.Email" .
        :c1 om:hasTag :pii .
        :c5 om:hasTag :pii .
        :l12 om:fromColumn :c1 ; om:toColumn :c2 .
        :l23 om:fromColumn :c2 ; om:toColumn :c3 .
        :l32 om:fromColumn :c3 ; om:toColumn :c2 .
        :l34 om:fromColumn :c3 ; om:toColumn :c4 .
        :l54 om:fromColumn :c5 ; om:toColumn :c4 .
        """;
    load(PREFIXES + "GRAPH <urn:catalog:asserted> {\n" + lineage + "}\n");
    final List<JsonObject> bundle = List.of(RuleFixtures.starter("pii-propagation-via-lineage"));
    final String graph = RuleFixtures.output("pii-propagation-via-lineage");
    final Path first = Jobs.write(job, "first", RuleFixtures.step("first", bundle, null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(first), () -> Jobs.manifest(first).toString());
    assertEquals(Set.of("c2", "c3", "c4"), tagged(graph));

    // Delete the premise in place and run again: the step empties its graphs first, so the
    // cycle c2 <-> c3 cannot keep itself.
    final DatasetGraph dataset =
        DatabaseMgr.connectDatasetGraph(Location.create(job.resolve("working")));
    try {
      Txn.executeWrite(
          dataset,
          () ->
              dataset.delete(
                  NodeFactory.createURI(ASSERTED),
                  NodeFactory.createURI("http://example.org/catalog/c1"),
                  NodeFactory.createURI(RuleFixtures.OM + "hasTag"),
                  NodeFactory.createURI("http://example.org/catalog/pii")));
    } finally {
      TDBInternal.expel(dataset);
    }
    final Path second = Jobs.write(job, "second", RuleFixtures.step("second", bundle, null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(second), () -> Jobs.manifest(second).toString());
    assertEquals(Set.of("c4"), tagged(graph));
    assertEquals(
        Set.of(
            "<http://example.org/catalog/c4> "
                + om("inferredTagSource")
                + " <http://example.org/catalog/c5> ."),
        sources(graph));
  }

  @Test
  void runningOutOfPassesFactsOrSpoolEndsTheStepIncomplete() throws Exception {
    final StringBuilder chain = new StringBuilder(PREFIXES + "GRAPH <urn:catalog:asserted> {\n");
    chain.append(":pii om:tagFQN \"PII.Name\" .\n:c0 om:hasTag :pii .\n");
    for (int i = 0; i < 30; i++) {
      chain.append(":l").append(i).append(" om:fromColumn :c").append(i);
      chain.append(" ; om:toColumn :c").append(i + 1).append(" .\n");
    }
    load(chain.append("}\n").toString());
    final List<JsonObject> bundle = List.of(RuleFixtures.starter("pii-propagation-via-lineage"));
    assertBudget("passes", bundle, "{\"maxPasses\": 5}", "PASS_BUDGET", "maxPasses");
    assertBudget("facts", bundle, "{\"maxNewFacts\": 7}", "FACT_BUDGET", "maxNewFacts");
    assertBudget("spool", bundle, "{\"maxSpoolBytes\": 64}", "SPOOL_BUDGET", "maxSpoolBytes");
    final Path enough =
        Jobs.write(
            job, "enough", RuleFixtures.step("enough", bundle, "{\"maxPasses\": 31}", false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(enough), () -> Jobs.manifest(enough).toString());
    assertEquals(30, tagged(RuleFixtures.output("pii-propagation-via-lineage")).size());
  }

  /** The disabled all-pairs closure stays empty however long the lineage is, until enabled. */
  @Test
  void theAllPairsLineageClosureIsOffUntilEnabled() throws Exception {
    final StringBuilder chain = new StringBuilder(PREFIXES + "GRAPH <urn:catalog:asserted> {\n");
    for (int i = 0; i < 10; i++) {
      chain.append(":t").append(i + 1).append(" prov:wasDerivedFrom :t").append(i).append(" .\n");
    }
    load(chain.append("}\n").toString());
    final JsonObject closure = RuleFixtures.starter("transitive-lineage-closure");
    final String graph = RuleFixtures.output("transitive-lineage-closure");
    final Path off =
        Jobs.write(job, "off", RuleFixtures.step("off", List.of(closure), null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(off), () -> Jobs.manifest(off).toString());
    assertEquals(Set.of(), RuleFixtures.triples(job, graph));
    closure.put("enabled", true);
    final Path on = Jobs.write(job, "on", RuleFixtures.step("on", List.of(closure), null, false));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(on), () -> Jobs.manifest(on).toString());
    assertEquals(55, RuleFixtures.triples(job, graph).size());
  }

  @Test
  void anInvalidRuleRejectsTheBundleWithEveryProblem() throws Exception {
    load(CATALOG);
    final List<JsonObject> bundle = new ArrayList<>(RuleFixtures.starterBundle());
    bundle.add(
        RuleFixtures.rule(
            "optional-rule",
            PREFIXES + "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y OPTIONAL { ?y :r ?z } }"));
    final JsonObject disabled =
        RuleFixtures.rule(
            "bound-rule", PREFIXES + "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y FILTER(BOUND(?y)) }");
    disabled.put("enabled", false);
    bundle.add(disabled);
    final Path step = Jobs.write(job, "rules", RuleFixtures.step("rules", bundle, null, false));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step));
    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("INVALID_RULES", manifest.getString("outcome"));
    assertEquals(2, manifest.getNumber("problemCount").longValue(), manifest.toString());
    final Path many =
        Jobs.write(
            job,
            "many",
            RuleFixtures.step("many", RuleFixtures.starterBundle(), "{\"maxRules\": 3}", false));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(many));
    assertEquals("TOO_MANY_RULES", Jobs.firstProblem(many));
  }

  @Test
  void theDefaultGraphCanBeRead() throws Exception {
    load(PREFIXES + ":a :p :b .\nGRAPH <urn:other> { :b :p :c . }\n");
    final List<JsonObject> rules =
        List.of(RuleFixtures.rule("to-q", PREFIXES + "CONSTRUCT { ?x :q ?y } WHERE { ?x :p ?y }"));
    final Path step =
        Jobs.write(
            job,
            "rules",
            RuleFixtures.step("rules", rules, null, false)
                .replace(ASSERTED, "urn:x-arq:DefaultGraph"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
    assertEquals(
        Set.of(
            "<http://example.org/catalog/a> <http://example.org/catalog/q>"
                + " <http://example.org/catalog/b> ."),
        RuleFixtures.triples(job, RuleFixtures.output("to-q")));
  }

  private void assertBudget(
      final String id,
      final List<JsonObject> bundle,
      final String limits,
      final String outcome,
      final String limit) {
    final Path step = Jobs.write(job, id, RuleFixtures.step(id, bundle, limits, true));
    assertEquals(ExitCodes.BUDGET, Jobs.run(step), () -> Jobs.manifest(step).toString());
    final JsonObject manifest = Jobs.manifest(step);
    assertEquals("INCOMPLETE", manifest.getString("status"), id);
    assertEquals(outcome, manifest.getString("outcome"), id);
    assertEquals(limit, manifest.getString("limit"), id);
    assertEquals(outcome, Jobs.firstProblem(step), id);
    assertFalse(Files.exists(job.resolve(id + ".spool.tmp")), id);
    assertNull(
        RuleFixtures.read(
            job,
            dataset ->
                Explanations.explain(
                    dataset,
                    NodeFactory.createURI(RuleFixtures.PROVENANCE),
                    org.apache.jena.graph.Triple.create(Node.ANY, Node.ANY, Node.ANY),
                    Explanations.Bounds.DEFAULT)),
        "an incomplete run records no run that explanations would trust");
  }

  private Set<String> tagged(final String graph) {
    final Set<String> columns = new TreeSet<>();
    for (final String triple : RuleFixtures.triples(job, graph)) {
      if (triple.contains(om("hasTag"))) {
        columns.add(triple.substring("<http://example.org/catalog/".length(), triple.indexOf('>')));
      }
    }
    return columns;
  }

  private Set<String> sources(final String graph) {
    final Set<String> sources = new TreeSet<>();
    for (final String triple : RuleFixtures.triples(job, graph)) {
      if (triple.contains(om("inferredTagSource"))) {
        sources.add(triple);
      }
    }
    return sources;
  }

  private void load(final String trig) {
    Jobs.file(job, "catalog.trig", trig);
    final Path load = Jobs.write(job, "load", Jobs.load("working", "catalog.trig"));
    assertEquals(ExitCodes.COMPLETED, Jobs.run(load), () -> Jobs.manifest(load).toString());
  }

  private static <T> List<List<T>> permutations(final List<T> items) {
    if (items.isEmpty()) {
      return List.of(List.of());
    }
    final List<List<T>> all = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      final List<T> rest = new ArrayList<>(items);
      final T head = rest.remove(i);
      for (final List<T> tail : permutations(rest)) {
        final List<T> permutation = new ArrayList<>();
        permutation.add(head);
        permutation.addAll(tail);
        all.add(permutation);
      }
    }
    return all;
  }
}
