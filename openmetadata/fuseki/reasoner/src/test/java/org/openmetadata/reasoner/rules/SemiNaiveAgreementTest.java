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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.ExitCodes;
import org.openmetadata.reasoner.Jobs;

/**
 * Semi-naive evaluation derives exactly what naive evaluation does: every rule run over everything
 * until nothing changes, here with Jena's own CONSTRUCT over an in-memory graph. The rules join
 * derived predicates, use UNION, a variable predicate, VALUES, BIND, a property path and an EXISTS
 * over derived predicates, on random graphs.
 */
class SemiNaiveAgreementTest {
  private static final String PREFIXES = "PREFIX : <http://example.org/> ";
  private static final List<String> RULES =
      List.of(
          "CONSTRUCT { ?x :q ?z } WHERE { ?x :p ?y . ?y :p ?z }",
          "CONSTRUCT { ?y :r ?x } WHERE { { ?x :q ?y } UNION { ?x :r ?y . ?y :p ?x } }",
          "CONSTRUCT { ?x :s ?y } WHERE { ?x :r+ ?y FILTER(?x != ?y) }",
          "CONSTRUCT { ?x :t ?y } WHERE { ?x :s ?y FILTER(EXISTS { ?y :q ?w } && ?x != ?w) }",
          "CONSTRUCT { ?y :u ?k } WHERE { VALUES ?k { :n0 :n1 :n2 } ?k :t ?y BIND(STR(?y) AS"
              + " ?label) FILTER(STRLEN(?label) > 3) }",
          "CONSTRUCT { ?x :p ?y } WHERE { ?x ?pred ?y . ?pred :sub :p }",
          "CONSTRUCT { ?x :e ?y } WHERE { ?x :u ?y }");

  @TempDir Path job;

  @Test
  void semiNaiveAndNaiveEvaluationDeriveTheSameFacts() throws Exception {
    for (final long seed : new long[] {1, 2, 3, 4, 5}) {
      final String data = graph(seed, 18, 40);
      final Path run = Files.createDirectories(job.resolve("seed" + seed));
      Jobs.file(
          run,
          "catalog.trig",
          "PREFIX : <http://example.org/>\nGRAPH <"
              + RuleFixtures.ASSERTED
              + "> {\n"
              + data
              + "}\n");
      final Path load = Jobs.write(run, "load", Jobs.load("working", "catalog.trig"));
      assertEquals(ExitCodes.COMPLETED, Jobs.run(load), () -> Jobs.manifest(load).toString());
      final List<JsonObject> bundle = bundle();
      final Path step = Jobs.write(run, "rules", RuleFixtures.step("rules", bundle, null, false));
      assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
      final Set<String> expected = naive(data);
      assertEquals(expected, RuleFixtures.derived(run, bundle), "seed " + seed);
      assertTrue(expected.size() > 20, "seed " + seed + " derived only " + expected.size());
      final JsonObject manifest = Jobs.manifest(step);
      assertTrue(
          manifest
              .getArray("perPass")
              .skip(1)
              .anyMatch(pass -> pass.getAsObject().getNumber("variants").longValue() > 0),
          manifest.toString());
    }
  }

  private static List<JsonObject> bundle() {
    final List<JsonObject> rules = new java.util.ArrayList<>();
    for (int i = 0; i < RULES.size(); i++) {
      rules.add(RuleFixtures.rule("rule-" + i, PREFIXES + RULES.get(i)));
    }
    return rules;
  }

  /** Every rule over everything, again and again, until the graph stops growing. */
  private static Set<String> naive(final String data) {
    final Graph graph = GraphFactory.createDefaultGraph();
    RDFParser.fromString("PREFIX : <http://example.org/>\n" + data, Lang.TURTLE).parse(graph);
    final Set<String> asserted = strings(graph);
    final List<Query> queries =
        RULES.stream().map(rule -> QueryFactory.create(PREFIXES + rule)).toList();
    int size = -1;
    while (graph.size() != size) {
      size = graph.size();
      for (final Query query : queries) {
        final Graph derived = QueryExec.graph(graph).query(query).construct();
        derived.find().forEachRemaining(graph::add);
      }
    }
    final Set<String> derived = strings(graph);
    derived.removeAll(asserted);
    return derived;
  }

  private static Set<String> strings(final Graph graph) {
    final Set<String> triples = new TreeSet<>();
    graph.find().forEachRemaining((Triple triple) -> triples.add(NodeFmtLib.strNT(triple)));
    return triples;
  }

  /** {@code edges} random edges among {@code nodes} nodes, with :p, :r and :link, :link :sub :p. */
  private static String graph(final long seed, final int nodes, final int edges) {
    final java.util.Random random = new java.util.Random(seed);
    final String[] predicates = {":p", ":r", ":link"};
    final StringBuilder data = new StringBuilder(":link :sub :p .\n");
    for (int i = 0; i < edges; i++) {
      data.append(":n")
          .append(random.nextInt(nodes))
          .append(' ')
          .append(predicates[random.nextInt(predicates.length)])
          .append(" :n")
          .append(random.nextInt(nodes))
          .append(" .\n");
    }
    return data.toString();
  }
}
