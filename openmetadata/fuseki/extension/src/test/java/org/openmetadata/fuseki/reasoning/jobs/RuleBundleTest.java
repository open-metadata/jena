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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The om-rules-v1 digest against OpenMetadata's vectors, and the capture's verification of a
 * published bundle graph and of rules a request carries.
 */
class RuleBundleTest {
  private static final String OMR = Capture.OMR;
  private static final String BASE = "https://open-metadata.org/";

  @TestFactory
  Stream<DynamicTest> theDigestMatchesOpenMetadatasVectors() throws IOException {
    final List<String> lines = new ArrayList<>();
    try (InputStream in = resource("expected.txt")) {
      new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .forEach(lines::add);
    }
    return lines.stream()
        .map(
            line -> {
              final String[] fields = line.split(" ");
              return DynamicTest.dynamicTest(
                  fields[0],
                  () -> {
                    final JsonArray rules;
                    try (InputStream in = resource(fields[0])) {
                      rules = JSON.parseAny(in).getAsArray();
                    }
                    assertEquals(fields[1], RuleBundle.digest(rules(rules)));
                  });
            });
  }

  @Test
  void aPublishedBundleIsVerifiedAgainstItsDigest() throws Exception {
    final List<RuleBundle.Rule> rules =
        List.of(
            new RuleBundle.Rule(
                "b-rule", "CONSTRUCT", "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }"),
            new RuleBundle.Rule(
                "a-rule", "CONSTRUCT", "CONSTRUCT { ?s <urn:r> ?o } WHERE { ?s <urn:q> ?o }"));
    final String digest = RuleBundle.digest(rules);
    final DatasetGraph dataset = DatasetGraphFactory.createTxnMem();
    final Node graph = publish(dataset, digest, rules, "om-rules-v1");
    final RuleBundle bundle = Txn.calculateRead(dataset, () -> published(dataset, digest));
    assertEquals(graph.getURI(), bundle.graph());
    assertEquals(
        List.of("a-rule", "b-rule"), bundle.rules().stream().map(RuleBundle.Rule::name).toList());

    assertEquals(
        Problem.Code.RULE_FAILED,
        refused(dataset, RuleBundle.digest(List.of())).code(),
        "an unpublished digest");
    Txn.executeWrite(
        dataset,
        () ->
            dataset.add(
                graph,
                NodeFactory.createURI(graph.getURI() + "/rule/a-rule"),
                NodeFactory.createURI(OMR + "ruleBody"),
                NodeFactory.createLiteralString(
                    "CONSTRUCT { ?s <urn:x> ?o } WHERE { ?s <urn:q> ?o }")));
    assertEquals(Problem.Code.GENERATION_MISMATCH, refused(dataset, digest).code(), "two bodies");

    final DatasetGraph other = DatasetGraphFactory.createTxnMem();
    publish(other, digest, rules, "om-rules-v0");
    assertEquals(Problem.Code.NOT_IMPLEMENTED, refused(other, digest).code());

    final DatasetGraph changed = DatasetGraphFactory.createTxnMem();
    publish(changed, digest, List.of(rules.get(0)), "om-rules-v1");
    final Problem mismatch = refused(changed, digest);
    assertEquals(Problem.Code.GENERATION_MISMATCH, mismatch.code());
    assertEquals(digest, mismatch.subject());
  }

  /** The smoke test's fixture is a bundle the capture accepts, beside the closure it activates. */
  @Test
  void theSmokeTestsRefreshFixtureVerifies() throws Exception {
    final java.nio.file.Path image = java.nio.file.Path.of("..").toAbsolutePath().normalize();
    final DatasetGraph dataset = DatasetGraphFactory.createTxnMem();
    for (final String fixture : List.of("check-closure.trig", "refresh-catalog.trig")) {
      org.apache.jena.riot.RDFParser.source(image.resolve("test").resolve(fixture))
          .lang(org.apache.jena.riot.Lang.TRIG)
          .parse(dataset);
    }
    final String digest =
        java.nio.file.Files.readAllLines(image.resolve("test/refresh-catalog.trig")).stream()
            .filter(line -> line.startsWith("# rule-bundle-digest "))
            .findFirst()
            .orElseThrow()
            .substring("# rule-bundle-digest ".length());
    final RuleBundle bundle = Txn.calculateRead(dataset, () -> published(dataset, digest));
    assertEquals(
        List.of("pii-propagation-via-lineage"),
        bundle.rules().stream().map(RuleBundle.Rule::name).toList());
    assertEquals(
        1,
        Txn.calculateRead(
            dataset,
            () -> {
              try {
                return RefreshCapture.activations(dataset).size();
              } catch (final Capture.Refused e) {
                throw new IllegalStateException(e);
              }
            }));
  }

  @Test
  void inlineRulesMustBeTheBundleTheRequestNames() throws Exception {
    final JsonArray rules =
        JSON.parseAny(
                """
[{"name": "kept-rule", "ruleBody": "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }"},
 {"name": "off-rule", "enabled": false, "ruleBody": "CONSTRUCT { ?s <urn:z> ?o } WHERE { ?s <urn:p> ?o }"}]
""")
            .getAsArray();
    final String digest =
        RuleBundle.digest(
            List.of(
                new RuleBundle.Rule(
                    "kept-rule",
                    "CONSTRUCT",
                    "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }")));
    final RuleBundle bundle = RuleBundle.inline(rules, digest);
    assertEquals(1, bundle.rules().size(), "disabled rules take no part");
    assertNull(bundle.graph());
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class, () -> RuleBundle.inline(rules, RuleBundle.digest(List.of())));
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
  }

  private static RuleBundle published(final DatasetGraph dataset, final String digest) {
    try {
      return RuleBundle.published(dataset, digest);
    } catch (final Capture.Refused e) {
      throw new IllegalStateException(e);
    }
  }

  private static Problem refused(final DatasetGraph dataset, final String digest) {
    return Txn.calculateRead(
        dataset,
        () -> {
          try {
            RuleBundle.published(dataset, digest);
            throw new AssertionError("accepted");
          } catch (final Capture.Refused e) {
            return e.problem();
          }
        });
  }

  private static Node publish(
      final DatasetGraph dataset,
      final String digest,
      final List<RuleBundle.Rule> rules,
      final String algorithm) {
    final Node graph =
        NodeFactory.createURI(BASE + "graph/rules/bundle/" + digest.substring("sha256:".length()));
    Txn.executeWrite(
        dataset,
        () -> {
          dataset.add(graph, graph, iri("bundleDigest"), NodeFactory.createLiteralString(digest));
          dataset.add(
              graph, graph, iri("digestAlgorithm"), NodeFactory.createLiteralString(algorithm));
          for (final RuleBundle.Rule rule : rules) {
            final Node node = NodeFactory.createURI(graph.getURI() + "/rule/" + rule.name());
            dataset.add(graph, graph, iri("rule"), node);
            dataset.add(graph, node, iri("ruleName"), NodeFactory.createLiteralString(rule.name()));
            dataset.add(
                graph, node, iri("ruleType"), NodeFactory.createLiteralString(rule.ruleType()));
            dataset.add(graph, node, iri("ruleBody"), NodeFactory.createLiteralString(rule.body()));
          }
        });
    return graph;
  }

  private static Node iri(final String name) {
    return NodeFactory.createURI(OMR + name);
  }

  private static List<RuleBundle.Rule> rules(final JsonArray array) {
    final List<RuleBundle.Rule> rules = new ArrayList<>();
    for (final JsonValue value : array) {
      final JsonObject rule = value.getAsObject();
      rules.add(
          new RuleBundle.Rule(
              rule.getString("name"), rule.getString("ruleType"), rule.getString("ruleBody")));
    }
    return rules;
  }

  private static InputStream resource(final String name) {
    return RuleBundleTest.class.getResourceAsStream("/rule-bundle-vectors/" + name);
  }
}
