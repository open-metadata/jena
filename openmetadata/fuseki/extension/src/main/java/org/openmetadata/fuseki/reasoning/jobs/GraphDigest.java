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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.out.NodeFmtLib;

/**
 * The content digest OpenMetadata pins for each ontology graph in a version manifest ({@code
 * omr:pinnedDigest}), recomputed from the triples the store holds: {@code sha256:} and the SHA-256
 * of the graph's N-Triples, blank nodes replaced by IRIs derived from their neighbourhood, one
 * triple per line, lines sorted in Java string order, each ending in a newline.
 *
 * <p>This is OpenMetadata's {@code OntologyGraphDigest} over its {@code RdfBlankNodeCanonicalizer}
 * (ADR:2026-10-15-reasoning-closures-are-approved-graphs-pinned-by-a-content-manifest in
 * OpenMetadata), and must produce the same digest for the same triples; {@code GraphDigestTest}
 * pins vectors computed by OpenMetadata's classes. A blank node's hash starts from the triples it
 * occurs in, its neighbours written {@code _:blank}, and is refined from its neighbours' hashes
 * until it stops changing, at most 16 rounds; the nodes, ordered by hash and then by label, become
 * {@code urn:openmetadata:annex:<sha256(hash:index)>}. It differs from OpenMetadata's code only in
 * cost: each node's incident triples are indexed once, rather than every triple scanned for every
 * node in every round, so a graph is digested in time linear in its size per round.
 *
 * <p>Blank nodes whose hashes stay equal are ordered by their labels, which the store assigns
 * differently from OpenMetadata. That changes nothing when they are interchangeable, as in every
 * tree the OWL 2 RDF mapping writes; for graphs the refinement cannot tell apart that are not
 * symmetric, the digests can differ and the capture reports the graph as changed, never the
 * reverse.
 */
public final class GraphDigest {
  public static final String PREFIX = "sha256:";

  private static final int HASH_REFINEMENT_ROUNDS = 16;
  private static final String SKOLEM_PREFIX = "urn:openmetadata:annex:";
  private static final HexFormat HEX = HexFormat.of();

  private GraphDigest() {}

  public static String of(final Collection<Triple> triples) {
    final Map<Node, List<Triple>> incident = new LinkedHashMap<>();
    for (final Triple triple : triples) {
      final Set<Node> blanks = new LinkedHashSet<>();
      addBlanks(blanks, triple);
      for (final Node blank : blanks) {
        incident.computeIfAbsent(blank, node -> new ArrayList<>()).add(triple);
      }
    }
    final Map<Node, String> hashes = refine(incident);
    final List<Node> ordered =
        incident.keySet().stream()
            .sorted(
                Comparator.<Node, String>comparing(hashes::get)
                    .thenComparing(Node::getBlankNodeLabel))
            .toList();
    final Map<Node, Node> skolem = new HashMap<>();
    for (int index = 0; index < ordered.size(); index++) {
      final Node blank = ordered.get(index);
      skolem.put(
          blank, NodeFactory.createURI(SKOLEM_PREFIX + sha256Hex(hashes.get(blank) + ':' + index)));
    }
    final List<String> lines = new ArrayList<>(triples.size());
    for (final Triple triple : triples) {
      lines.add(
          NodeFmtLib.strNT(replace(triple.getSubject(), skolem))
              + ' '
              + NodeFmtLib.strNT(replace(triple.getPredicate(), skolem))
              + ' '
              + NodeFmtLib.strNT(replace(triple.getObject(), skolem))
              + " .");
    }
    lines.sort(String::compareTo);
    final MessageDigest digest = sha256();
    for (final String line : lines) {
      digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return PREFIX + HEX.formatHex(digest.digest());
  }

  private static Map<Node, String> refine(final Map<Node, List<Triple>> incident) {
    Map<Node, String> hashes = round(incident, Map.of());
    for (int round = 0; round < HASH_REFINEMENT_ROUNDS; round++) {
      final Map<Node, String> refined = round(incident, hashes);
      if (refined.equals(hashes)) {
        break;
      }
      hashes = refined;
    }
    return hashes;
  }

  private static Map<Node, String> round(
      final Map<Node, List<Triple>> incident, final Map<Node, String> neighbours) {
    final Map<Node, String> hashes = new HashMap<>();
    incident.forEach(
        (blank, triples) -> {
          final List<String> signatures = new ArrayList<>(triples.size());
          for (final Triple triple : triples) {
            signatures.add(signature(blank, triple, neighbours));
          }
          signatures.sort(String::compareTo);
          hashes.put(blank, sha256Hex(String.join("|", signatures)));
        });
    return hashes;
  }

  private static String signature(
      final Node blank, final Triple triple, final Map<Node, String> neighbours) {
    return signatureNode(blank, triple.getSubject(), neighbours)
        + ' '
        + signatureNode(blank, triple.getPredicate(), neighbours)
        + ' '
        + signatureNode(blank, triple.getObject(), neighbours);
  }

  private static String signatureNode(
      final Node blank, final Node node, final Map<Node, String> neighbours) {
    if (node.equals(blank)) {
      return "_:self";
    }
    if (node.isBlank()) {
      return "_:" + neighbours.getOrDefault(node, "blank");
    }
    if (node.isTripleTerm()) {
      return "<<( " + signature(blank, node.getTriple(), neighbours) + " )>>";
    }
    return NodeFmtLib.strNT(node);
  }

  private static void addBlanks(final Set<Node> blanks, final Triple triple) {
    addBlanks(blanks, triple.getSubject());
    addBlanks(blanks, triple.getPredicate());
    addBlanks(blanks, triple.getObject());
  }

  private static void addBlanks(final Set<Node> blanks, final Node node) {
    if (node.isBlank()) {
      blanks.add(node);
    } else if (node.isTripleTerm()) {
      addBlanks(blanks, node.getTriple());
    }
  }

  private static Node replace(final Node node, final Map<Node, Node> skolem) {
    if (node.isBlank()) {
      return skolem.get(node);
    }
    if (node.isTripleTerm()) {
      final Triple triple = node.getTriple();
      return NodeFactory.createTripleTerm(
          replace(triple.getSubject(), skolem),
          replace(triple.getPredicate(), skolem),
          replace(triple.getObject(), skolem));
    }
    return node;
  }

  static String sha256Hex(final String text) {
    return HEX.formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
