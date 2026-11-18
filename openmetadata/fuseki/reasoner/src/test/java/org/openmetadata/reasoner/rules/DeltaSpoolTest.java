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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.StepFile;

/**
 * A delta is read back from its file exactly as the pass wrote it, by any pattern; and a rule gets
 * delta variants only where each reads that file once.
 */
class DeltaSpoolTest {
  private static final Node P = NodeFactory.createURI("urn:p");
  private static final Node Q = NodeFactory.createURI("urn:q");

  @TempDir Path job;

  @Test
  void aSpooledDeltaAnswersEveryPatternLikeTheFactsItHolds() throws Exception {
    final Path file = job.resolve("delta");
    final Node blank = NodeFactory.createBlankNode("b1");
    final Node french = NodeFactory.createLiteralLang("chat", "fr");
    final List<Triple> facts =
        List.of(
            Triple.create(uri("a"), P, uri("b")),
            Triple.create(uri("a"), Q, french),
            Triple.create(blank, P, uri("a")),
            Triple.create(uri("b"), P, uri("b")));
    final SpoolBudget budget = new SpoolBudget(Long.MAX_VALUE);
    final DeltaSpool.Writer writer = new DeltaSpool.Writer(file, budget);
    try (writer) {
      for (final Triple fact : facts) {
        writer.write(fact);
      }
    }
    assertEquals(Files.size(file), writer.bytes(), "the whole file counts against the budget");

    final Graph delta = DeltaSpool.graph(file);
    assertEquals(facts, delta.find().toList(), "in the order written");
    assertEquals(List.of(facts.get(0), facts.get(1)), delta.find(uri("a"), null, null).toList());
    assertEquals(
        List.of(facts.get(0), facts.get(2), facts.get(3)), delta.find(null, P, null).toList());
    assertEquals(List.of(facts.get(1)), delta.find(Node.ANY, Node.ANY, french).toList());
    assertEquals(List.of(facts.get(2)), delta.find(blank, P, uri("a")).toList());
    assertEquals(List.of(), delta.find(uri("b"), Q, null).toList());
    assertTrue(delta.contains(uri("b"), P, uri("b")));
    assertFalse(delta.contains(uri("b"), P, uri("a")));
    assertEquals(4, delta.size());

    // As a rule's variant reads it: the one named graph of the dataset a pass evaluates over.
    final DatasetGraph evaluation = DatasetGraphFactory.createGeneral();
    evaluation.addGraph(uri("delta"), delta);
    try (QueryExec exec =
        QueryExec.dataset(evaluation)
            .query("SELECT ?x ?z { GRAPH <urn:x:delta> { ?x <urn:p> ?y . ?y <urn:p> ?z } }")
            .build()) {
      final RowSet rows = exec.select();
      final Set<String> pairs = new java.util.TreeSet<>();
      rows.forEachRemaining(
          row -> pairs.add(row.get(Var.alloc("x")) + " " + row.get(Var.alloc("z"))));
      assertEquals(Set.of("_:b1 urn:x:b", "urn:x:a urn:x:b", "urn:x:b urn:x:b"), pairs);
    }

    assertThrows(
        SpoolBudget.Full.class,
        () -> {
          try (DeltaSpool.Writer small =
              new DeltaSpool.Writer(job.resolve("small"), new SpoolBudget(16))) {
            small.write(facts.get(0));
          }
        });
  }

  @Test
  void aRuleGetsDeltaVariantsOnlyWhereTheDeltaIsReadOnceAndFirst() {
    final Set<Node> p = Set.of(P);
    final Set<Node> q = Set.of(Q);
    assertTrue(rule("?x <urn:q> ?y . ?y <urn:p> ?z").deltaDrives(Set.of(P, Q)), "one block");
    assertTrue(
        rule("{ ?x <urn:p> ?y } UNION { ?x <urn:q> ?y . ?y <urn:p> ?z }").deltaDrives(p),
        "each branch is its own variant");
    assertTrue(
        rule("?x <urn:p> ?y FILTER(?x != ?y) BIND(STR(?y) AS ?label)").deltaDrives(p),
        "filters and assignments read their operand's solutions");
    final CompiledRule afterPath = rule("?x <urn:r>+ ?y . ?y <urn:q> ?z");
    assertFalse(afterPath.deltaDrives(q), "looked up once per solution of the path");
    final CompiledRule beforePath = rule("?y <urn:q> ?z . ?x <urn:r>+ ?y");
    assertTrue(beforePath.deltaDrives(q), "the first element of the sequence");
    final CompiledRule joined = rule("?x <urn:p> ?y { { ?y <urn:q> ?z } UNION { ?y <urn:r> ?z } }");
    assertTrue(joined.deltaDrives(p), "the left operand of the join");
    assertFalse(joined.deltaDrives(q), "the right operand is evaluated per solution of the left");
    assertFalse(rule("VALUES ?x { <urn:a> <urn:b> } ?x <urn:p> ?y").deltaDrives(p), "after VALUES");
    assertTrue(afterPath.deltaDrives(Set.of()), "no atom reads an empty delta");
  }

  private static CompiledRule rule(final String where) {
    final RuleCompiler.Result result =
        RuleCompiler.compile(
            0,
            new StepFile.Rule(
                "rule",
                "CONSTRUCT",
                "CONSTRUCT { ?x <urn:out> ?y } WHERE { " + where + " }",
                true,
                0,
                "urn:x:out"),
            64,
            RuleCompiler.evaluationContext());
    assertEquals(List.of(), result.problems());
    return result.rule();
  }

  private static Node uri(final String local) {
    return NodeFactory.createURI("urn:x:" + local);
  }
}
