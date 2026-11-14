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
package org.openmetadata.reasoner.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.ExitCodes;
import org.openmetadata.reasoner.Jobs;

/**
 * COMPACT replaces the working dataset with one built from its index records, not from its quads,
 * so these tests read the result back through every index and write to it again.
 */
class CompactStepTest {
  private static final String EX = "http://example.org/";
  private static final int SUBJECTS = 30_000;
  private static final List<Node> GRAPHS =
      List.of(
          Quad.defaultGraphIRI, iri("graph/asserted"), iri("graph/inferred"), iri("graph/rules"));

  private static final String COMPACT =
      """
      {"protocol": 1, "stepId": "compact", "kind": "COMPACT", "dataset": "working",
       "timeoutMillis": 60000}
      """;

  @TempDir Path job;

  @Test
  void theCompactedDatasetAnswersEveryPatternAsBeforeInFewerBytes() throws Exception {
    final Path location = job.resolve("working");
    final Set<Quad> expected = fill(location);
    final long before = WorkingDataset.sizeOnDisk(location);

    compact();

    assertTrue(
        WorkingDataset.sizeOnDisk(location) < before,
        () -> WorkingDataset.sizeOnDisk(location) + " bytes, from " + before);
    assertFalse(Files.exists(job.resolve("compact.packed.tmp")));
    read(
        location,
        dataset -> {
          assertEquals(expected, Iter.toSet(dataset.find()));
          assertEquals(EX, dataset.prefixes().get("ex"));
          for (int i = 0; i < SUBJECTS; i += 997) {
            for (final Node graph : GRAPHS) {
              final Quad quad = Quad.create(graph, subject(i), predicate(i), object(i));
              if (expected.contains(quad)) {
                assertEveryPatternOf(quad, expected, dataset);
              }
            }
          }
          return null;
        });
  }

  @Test
  void theCompactedDatasetTakesWritesLikeAnyOther() throws Exception {
    final Path location = job.resolve("working");
    final Set<Quad> expected = fill(location);
    compact();

    final DatasetGraph dataset = WorkingDataset.open(location);
    try {
      Txn.executeWrite(
          dataset,
          () -> {
            for (int i = 0; i < SUBJECTS; i += 3) {
              final Quad added = Quad.create(GRAPHS.get(3), subject(i), iri("added"), object(i));
              dataset.add(added);
              expected.add(added);
              final Quad removed = Quad.create(GRAPHS.get(1), subject(i), predicate(i), object(i));
              dataset.delete(removed);
              expected.remove(removed);
            }
          });
    } finally {
      WorkingDataset.release(dataset);
    }
    read(
        location,
        reopened -> {
          assertEquals(expected, Iter.toSet(reopened.find()));
          return null;
        });
  }

  @Test
  void anEmptyDatasetStaysOne() throws Exception {
    final Path location = job.resolve("working");
    WorkingDataset.release(WorkingDataset.create(location));
    compact();
    read(
        location,
        dataset -> {
          assertFalse(dataset.find().hasNext());
          return null;
        });
  }

  /**
   * The copy takes each index from the same index of the source, so one that lost its records would
   * be published as it is; TDB2's own compaction rebuilt every index from the first.
   */
  @Test
  void aDatasetWhoseIndexesDisagreeIsNotCompacted() throws Exception {
    final Path location = job.resolve("working");
    fill(location);
    final DatasetGraph dataset = WorkingDataset.open(location);
    try {
      Txn.executeWrite(dataset, () -> TDBInternal.findIndex(dataset, "OSPG").clear());
    } finally {
      WorkingDataset.release(dataset);
    }
    final Path step = Jobs.write(job, "compact", COMPACT);
    assertEquals(ExitCodes.INTERNAL_ERROR, Jobs.run(step), () -> Jobs.manifest(step).toString());
    assertFalse(Files.exists(job.resolve("compact.packed.tmp")));
  }

  private void compact() {
    final Path step = Jobs.write(job, "compact", COMPACT);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(step), () -> Jobs.manifest(step).toString());
    assertEquals("COMPACTED", Jobs.outcome(step));
  }

  /**
   * Writes quads in several transactions that each touch every part of every index, then deletes
   * some, so the dataset holds superseded blocks and half-empty ones as a job's does.
   */
  private static Set<Quad> fill(final Path location) throws Exception {
    final Set<Quad> quads = new HashSet<>();
    final DatasetGraph dataset = WorkingDataset.create(location);
    try {
      Txn.executeWrite(dataset, () -> dataset.prefixes().add("ex", EX));
      for (int round = 0; round < GRAPHS.size(); round++) {
        final Node graph = GRAPHS.get(round);
        Txn.executeWrite(
            dataset,
            () -> {
              for (int i = 0; i < SUBJECTS; i++) {
                final Quad quad = Quad.create(graph, subject(i), predicate(i), object(i));
                dataset.add(quad);
                quads.add(quad);
              }
            });
      }
      Txn.executeWrite(
          dataset,
          () -> {
            for (int i = 0; i < SUBJECTS; i += 5) {
              final Quad quad = Quad.create(GRAPHS.get(2), subject(i), predicate(i), object(i));
              dataset.delete(quad);
              quads.remove(quad);
            }
          });
    } finally {
      WorkingDataset.release(dataset);
    }
    return quads;
  }

  /** Each pattern below is answered from a different index of the quad or the triple table. */
  private static void assertEveryPatternOf(
      final Quad quad, final Set<Quad> expected, final DatasetGraph dataset) {
    final Node g = quad.getGraph();
    final Node s = quad.getSubject();
    final Node p = quad.getPredicate();
    final Node o = quad.getObject();
    final Node any = Node.ANY;
    final List<Quad> patterns =
        List.of(
            Quad.create(g, s, any, any),
            Quad.create(g, any, p, o),
            Quad.create(g, any, any, o),
            Quad.create(any, s, p, any),
            Quad.create(any, any, p, o),
            Quad.create(any, any, any, o));
    for (final Quad pattern : patterns) {
      final Set<Quad> matching =
          expected.stream()
              .filter(
                  candidate ->
                      pattern.matches(
                          candidate.getGraph(),
                          candidate.getSubject(),
                          candidate.getPredicate(),
                          candidate.getObject()))
              .collect(Collectors.toSet());
      assertEquals(matching, Iter.toSet(dataset.find(pattern)), pattern::toString);
    }
  }

  private static <T> T read(final Path location, final Function<DatasetGraph, T> reader)
      throws Exception {
    final DatasetGraph dataset = WorkingDataset.open(location);
    try {
      return Txn.calculateRead(dataset, () -> reader.apply(dataset));
    } finally {
      WorkingDataset.release(dataset);
    }
  }

  private static Node subject(final int i) {
    return i % 11 == 0
        ? NodeFactory.createBlankNode("b" + i)
        : iri("entity/" + Integer.toString(i * 7919 % SUBJECTS, 36));
  }

  private static Node predicate(final int i) {
    return iri("p" + i % 7);
  }

  /** IRIs, strings of several lengths and languages, and numbers and dates TDB2 keeps inline. */
  private static Node object(final int i) {
    return switch (i % 6) {
      case 0 -> iri("entity/" + Integer.toString(i * 104_729 % SUBJECTS, 36));
      case 1 -> NodeFactory.createLiteralString("name " + i);
      case 2 -> NodeFactory.createLiteralLang("nom " + i % 100, "fr");
      case 3 -> NodeFactory.createLiteralDT(Integer.toString(i), XSDDatatype.XSDinteger);
      case 4 ->
          NodeFactory.createLiteralDT(
              "2026-01-%02dT00:00:00Z".formatted(1 + i % 28), XSDDatatype.XSDdateTime);
      default -> NodeFactory.createLiteralString(("description " + i + " ").repeat(40));
    };
  }

  private static Node iri(final String local) {
    return NodeFactory.createURI(EX + local);
  }
}
