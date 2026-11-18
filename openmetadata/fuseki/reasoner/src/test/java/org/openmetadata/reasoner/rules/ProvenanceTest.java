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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.StepFile;

/**
 * The provenance file gives back, for every derived fact, the solution recorded for it and nothing
 * for any other fact, however many witnesses it holds; an unfinished or damaged file is never read
 * as a run.
 */
class ProvenanceTest {
  private static final Node OUT = NodeFactory.createURI("urn:out");
  private static final Node OTHER = NodeFactory.createURI("urn:other");
  private static final Node P = NodeFactory.createURI("urn:p");
  private static final Node Q = NodeFactory.createURI("urn:q");

  @TempDir Path job;

  @Test
  void everyWitnessIsFoundByItsFactAndGivesItsSolutionBack() throws Exception {
    final CompiledRule copy =
        rule(0, "copy", "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }");
    final CompiledRule pair =
        rule(
            1,
            "pair",
            "CONSTRUCT { ?a <urn:q> ?c . ?c <urn:q> ?a } WHERE { ?a <urn:p> ?b . ?b <urn:p> ?c }");
    final Path file = job.resolve("provenance");
    // More witnesses than one sorted run holds, so the index is merged from several.
    final int facts = 3 * RecordSorter.RUN_RECORDS + 17;
    final Node literal = NodeFactory.createLiteralLang("déjà vu", "fr");
    final Node blank = NodeFactory.createBlankNode("b0");
    try (Provenance.Writer writer = new Provenance.Writer(file, new SpoolBudget(Long.MAX_VALUE))) {
      for (int i = 0; i < facts; i++) {
        final Binding solution =
            BindingFactory.binding(Var.alloc("s"), subject(i), Var.alloc("o"), object(i));
        writer.witness(OUT, fact(i), writer.solution(copy, 1 + i % 7, solution));
      }
      final Binding shared =
          BindingFactory.builder()
              .add(Var.alloc("a"), blank)
              .add(Var.alloc("b"), NodeFactory.createLiteralDT("7", XSDDatatype.XSDinteger))
              .add(Var.alloc("c"), literal)
              .build();
      final long offset = writer.solution(pair, 9, shared);
      writer.witness(OTHER, Triple.create(blank, Q, literal), offset);
      writer.witness(OTHER, Triple.create(subject(0), Q, blank), offset);
      assertFalse(Files.exists(file), "the file has its name only once the run converged");
      writer.finish(List.of(NodeFactory.createURI("urn:read")), List.of(copy, pair), 9);
    }
    try (var files = Files.list(job)) {
      assertEquals(List.of(file), files.toList(), "no scratch file outlives the writer");
    }

    try (Provenance.Reader reader = Provenance.Reader.open(file)) {
      assertEquals(facts + 2, reader.witnesses());
      final Provenance.Run run = reader.run();
      assertEquals(9, run.passes());
      assertEquals(List.of(NodeFactory.createURI("urn:read")), run.read());
      assertEquals(List.of("copy", "pair"), List.copyOf(run.rules().keySet()));
      assertEquals(pair.body(), run.rules().get("pair").body());
      assertEquals(pair.digest(), run.rules().get("pair").digest());
      assertEquals(OUT, run.rules().get("copy").output());
      for (final int i : new int[] {0, 1, 12_345, RecordSorter.RUN_RECORDS, facts - 1}) {
        final Provenance.Solution solution = reader.solution(OUT, fact(i));
        assertEquals("copy", solution.rule());
        assertEquals(1 + i % 7, solution.pass());
        assertEquals(subject(i), solution.binding().get(Var.alloc("s")));
        assertEquals(object(i), solution.binding().get(Var.alloc("o")));
      }
      int found = 0;
      for (int i = 0; i < facts; i += 97) {
        found += reader.solution(OUT, fact(i)) == null ? 0 : 1;
      }
      assertEquals((facts + 96) / 97, found);
      final Provenance.Solution first = reader.solution(OTHER, Triple.create(blank, Q, literal));
      final Provenance.Solution second =
          reader.solution(OTHER, Triple.create(subject(0), Q, blank));
      assertEquals("pair", first.rule());
      assertEquals(9, first.pass());
      assertEquals(first.binding(), second.binding(), "facts of one solution share it");
      assertEquals(blank, first.binding().get(Var.alloc("a")), "a blank node keeps its label");
      assertEquals(literal, first.binding().get(Var.alloc("c")));
      assertNull(reader.solution(OTHER, fact(0)), "a witness belongs to its fact's graph");
      assertNull(reader.solution(OUT, Triple.create(subject(facts), Q, object(facts))));
    }
  }

  @Test
  void aRunThatDoesNotFinishLeavesNoFileAndAnyOtherFileIsRefused() throws Exception {
    final CompiledRule copy =
        rule(0, "copy", "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }");
    final Path file = job.resolve("provenance");
    final Binding solution =
        BindingFactory.binding(Var.alloc("s"), subject(1), Var.alloc("o"), object(1));
    try (Provenance.Writer writer = new Provenance.Writer(file, new SpoolBudget(Long.MAX_VALUE))) {
      writer.witness(OUT, fact(1), writer.solution(copy, 1, solution));
    }
    try (var files = Files.list(job)) {
      assertEquals(List.of(), files.toList());
    }
    assertNull(Provenance.Reader.open(file), "no file, no run");

    assertThrows(
        SpoolBudget.Full.class,
        () -> {
          try (Provenance.Writer writer = new Provenance.Writer(file, new SpoolBudget(64))) {
            for (int i = 0; i < 10; i++) {
              writer.witness(OUT, fact(i), writer.solution(copy, 1, solution));
            }
          }
        },
        "witnesses count against the step's spool budget");

    try (Provenance.Writer writer = new Provenance.Writer(file, new SpoolBudget(Long.MAX_VALUE))) {
      writer.witness(OUT, fact(1), writer.solution(copy, 1, solution));
      writer.finish(List.of(), List.of(copy), 2);
    }
    final byte[] whole = Files.readAllBytes(file);
    Files.write(file, Arrays.copyOf(whole, whole.length - 5));
    assertThrows(IOException.class, () -> Provenance.Reader.open(file), "cut short");
    Files.write(file, new byte[0]);
    assertThrows(IOException.class, () -> Provenance.Reader.open(file), "empty");
    Files.write(file, whole);
    try (Provenance.Reader reader = Provenance.Reader.open(file)) {
      assertTrue(reader.solution(OUT, fact(1)) != null);
    }
  }

  private static CompiledRule rule(final int index, final String name, final String body) {
    final RuleCompiler.Result result =
        RuleCompiler.compile(
            index,
            new StepFile.Rule(name, "CONSTRUCT", body, true, 0, OUT.getURI()),
            64,
            RuleCompiler.evaluationContext());
    assertEquals(List.of(), result.problems());
    return result.rule();
  }

  private static Triple fact(final int i) {
    return Triple.create(subject(i), Q, object(i));
  }

  private static Node subject(final int i) {
    return NodeFactory.createURI("urn:s:" + i);
  }

  private static Node object(final int i) {
    return i % 3 == 0
        ? NodeFactory.createLiteralString("value " + i)
        : NodeFactory.createURI("urn:o:" + i);
  }
}
