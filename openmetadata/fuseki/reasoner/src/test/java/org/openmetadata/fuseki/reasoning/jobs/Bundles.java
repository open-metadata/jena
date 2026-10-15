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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;

/**
 * Writes what OpenMetadata publishes for a REFRESH: a rule bundle graph (its
 * docs/rdf-rule-bundle-contract.md) and the activations graph naming each glossary's active
 * Approved manifest, each replaced whole as OpenMetadata's single SPARQL update does.
 */
final class Bundles {
  private static final String OMR = Manifests.OMR;

  private Bundles() {}

  /** A starter rule's name and body, from the reasoner's copy of OpenMetadata's rules. */
  static RuleBundle.Rule starter(final String name) {
    try (InputStream in = Bundles.class.getResourceAsStream("/rules/" + name + ".json")) {
      final JsonObject rule = JSON.parse(in);
      return new RuleBundle.Rule(name, "CONSTRUCT", rule.getString("ruleBody"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Publishes the bundle graph and returns its digest, computed as OpenMetadata computes it. */
  static String publish(
      final DatasetGraph dataset, final String base, final List<RuleBundle.Rule> rules) {
    final String digest = digest(rules);
    final String hex = digest.substring("sha256:".length());
    final Node graph = NodeFactory.createURI(base + "graph/rules/bundle/" + hex);
    Txn.executeWrite(
        dataset,
        () -> {
          dataset.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
          add(dataset, graph, graph, "bundleDigest", NodeFactory.createLiteralString(digest));
          add(
              dataset,
              graph,
              graph,
              "digestAlgorithm",
              NodeFactory.createLiteralString("om-rules-v1"));
          for (final RuleBundle.Rule rule : rules) {
            final Node node = NodeFactory.createURI(graph.getURI() + "/rule/" + rule.name());
            dataset.add(graph, graph, iri("rule"), node);
            add(dataset, graph, node, "ruleName", NodeFactory.createLiteralString(rule.name()));
            add(dataset, graph, node, "ruleType", NodeFactory.createLiteralString(rule.ruleType()));
            add(dataset, graph, node, "ruleBody", NodeFactory.createLiteralString(rule.body()));
          }
        });
    return digest;
  }

  /** Activates these closures, by glossary IRI, replacing every earlier activation. */
  static void activate(
      final DatasetGraph dataset,
      final String base,
      final Map<String, Manifests.Published> active) {
    final Node graph = NodeFactory.createURI(base + "graph/ontology/activations");
    Txn.executeWrite(
        dataset,
        () -> {
          dataset.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
          active.forEach(
              (glossary, closure) -> {
                final Node subject = NodeFactory.createURI(glossary);
                dataset.add(
                    graph, subject, iri("activeManifest"), NodeFactory.createURI(closure.graph()));
                dataset.add(
                    graph,
                    subject,
                    iri("activeVersion"),
                    NodeFactory.createURI(closure.versionIri()));
                dataset.add(
                    graph, subject, iri("ontology"), NodeFactory.createURI(closure.ontologyIri()));
                add(
                    dataset,
                    graph,
                    subject,
                    "closureDigest",
                    NodeFactory.createLiteralString(closure.digest()));
              });
        });
  }

  /** Records graphs as ontology graphs, as OpenMetadata's member records do. */
  static void members(final DatasetGraph dataset, final String base, final List<String> graphs) {
    final Node members = NodeFactory.createURI(base + "graph/ontology/members");
    Txn.executeWrite(
        dataset,
        () ->
            graphs.forEach(
                graph ->
                    dataset.add(
                        members,
                        NodeFactory.createURI(graph),
                        NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        iri("OntologyGraph"))));
  }

  /** OpenMetadata's om-rules-v1 digest, written out independently of the store's. */
  static String digest(final List<RuleBundle.Rule> rules) {
    try {
      final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      sha256.update("om-rules-v1\n".getBytes(StandardCharsets.UTF_8));
      rules.stream()
          .sorted(java.util.Comparator.comparing(RuleBundle.Rule::name))
          .forEach(
              rule -> {
                try {
                  final String body =
                      HexFormat.of()
                          .formatHex(
                              MessageDigest.getInstance("SHA-256")
                                  .digest(rule.body().getBytes(StandardCharsets.UTF_8)));
                  sha256.update(
                      ("rule " + rule.name() + " " + rule.ruleType() + " sha256:" + body + "\n")
                          .getBytes(StandardCharsets.UTF_8));
                } catch (final java.security.NoSuchAlgorithmException e) {
                  throw new IllegalStateException(e);
                }
              });
      return "sha256:" + HexFormat.of().formatHex(sha256.digest());
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void add(
      final DatasetGraph dataset,
      final Node graph,
      final Node subject,
      final String predicate,
      final Node object) {
    dataset.add(graph, subject, iri(predicate), object);
  }

  private static Node iri(final String name) {
    return NodeFactory.createURI(OMR + name);
  }
}
