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
package org.openmetadata.fuseki.reasoning.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store recomputes the digests OpenMetadata pins. The vectors in resources/digest-vectors were
 * computed by OpenMetadata's own classes; the store must match them from a parse of the same file
 * and from the same triples read back from TDB2, whose blank node labels differ.
 */
class GraphDigestTest {
  private static final String REFUSED = "refused";

  @TempDir Path directory;

  @Test
  void theStoreMatchesOpenMetadatasDigests() throws Exception {
    final Path vectors = resource("digest-vectors");
    final DatasetGraph store = DatabaseMgr.connectDatasetGraph(Location.create(directory));
    try {
      for (final String line : Files.readAllLines(vectors.resolve("expected.txt"))) {
        if (line.startsWith("#") || line.isBlank()) {
          continue;
        }
        final String[] parts = line.split(" ");
        final Graph parsed = turtle(Files.readString(vectors.resolve(parts[0])));
        final boolean refused = REFUSED.equals(parts[1]);
        if (refused) {
          assertThrows(GraphDigest.TooSymmetricException.class, () -> digest(parsed), parts[0]);
        } else {
          assertEquals(parts[1], digest(parsed), parts[0]);
        }

        final Node name = NodeFactory.createURI("urn:vector:" + parts[0]);
        Txn.executeWrite(
            store,
            () ->
                parsed
                    .find()
                    .forEachRemaining(
                        t -> store.add(name, t.getSubject(), t.getPredicate(), t.getObject())));
        final List<Triple> stored =
            Txn.calculateRead(store, () -> store.getGraph(name).find().toList());
        Collections.reverse(stored);
        if (refused) {
          assertThrows(
              GraphDigest.TooSymmetricException.class,
              () -> GraphDigest.of(stored),
              parts[0] + " read back from TDB2");
        } else {
          assertEquals(parts[1], GraphDigest.of(stored), parts[0] + " read back from TDB2");
        }
      }
    } finally {
      TDBInternal.expel(store);
    }
  }

  @Test
  void contentChangesTheDigestAndBlankNodeLabelsDoNot() throws Exception {
    final String text = Files.readString(resource("digest-vectors").resolve("axiom.ttl"));
    assertEquals(digest(turtle(text)), digest(turtle(text.replace("[]", "_:renamed"))));
    assertNotEquals(digest(turtle(text)), digest(turtle(text.replace("ex.org/p", "ex.org/q"))));
    assertNotEquals(digest(turtle(text)), digest(turtle(text.replace("\"5\"", "\"05\""))));
  }

  /**
   * Ties are where a label could leak into the digest: the nested twins and the cycles vectors have
   * blank nodes refinement cannot tell apart, and must digest equally however they are labelled and
   * ordered.
   */
  @Test
  void tiedBlankNodesDigestEquallyWhateverTheirLabels() throws Exception {
    final Random random = new Random(20261015L);
    for (final String name : List.of("nested-twins.ttl", "cycles.ttl", "list.ttl", "axiom.ttl")) {
      final List<Triple> triples =
          turtle(Files.readString(resource("digest-vectors").resolve(name))).find().toList();
      final String expected = GraphDigest.of(triples);
      for (int attempt = 0; attempt < 25; attempt++) {
        assertEquals(expected, GraphDigest.of(relabel(triples, random)), name + " " + attempt);
      }
    }
  }

  private static List<Triple> relabel(final List<Triple> triples, final Random random) {
    final Map<Node, Node> names = new HashMap<>();
    final List<Triple> relabelled = new ArrayList<>();
    for (final Triple triple : triples) {
      relabelled.add(
          Triple.create(
              rename(triple.getSubject(), names, random),
              rename(triple.getPredicate(), names, random),
              rename(triple.getObject(), names, random)));
    }
    Collections.shuffle(relabelled, random);
    return relabelled;
  }

  private static Node rename(final Node node, final Map<Node, Node> names, final Random random) {
    return node.isBlank()
        ? names.computeIfAbsent(
            node, blank -> NodeFactory.createBlankNode(Long.toHexString(random.nextLong())))
        : node;
  }

  @Test
  void aGraphWithManyBlankNodesIsDigestedInLinearTime() {
    final StringBuilder list = new StringBuilder("<http://ex.org/s> <http://ex.org/p> (");
    for (int i = 0; i < 50_000; i++) {
      list.append(" <http://ex.org/m").append(i).append('>');
    }
    final Graph graph = turtle(list.append(" ) .").toString());
    assertEquals(
        71, assertTimeoutPreemptively(Duration.ofSeconds(60), () -> digest(graph)).length());
  }

  private static String digest(final Graph graph) {
    return GraphDigest.of(new ArrayList<>(graph.find().toList()));
  }

  private static Graph turtle(final String text) {
    final Graph graph = GraphFactory.createDefaultGraph();
    RDFParser.fromString(text, Lang.TURTLE).parse(graph);
    return graph;
  }

  private static Path resource(final String name) throws URISyntaxException {
    return Path.of(GraphDigestTest.class.getResource("/" + name).toURI());
  }
}
