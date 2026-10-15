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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;

/**
 * The governance rules a REFRESH runs: the bundle OpenMetadata published under the request's {@code
 * ruleBundleDigest} (OpenMetadata's docs/rdf-rule-bundle-contract.md and
 * ADR:2026-10-15-governance-rules-are-published-as-a-content-addressed-bundle). The bundle graph
 * {@code {base}graph/rules/bundle/<hex>} is read inside the capture's read transaction, its {@code
 * om-rules-v1} digest recomputed from the rules it names, and anything else refused, as capture
 * does for closure manifests. A request that carries its rules inline is used only when its enabled
 * rules digest to {@code ruleBundleDigest}.
 *
 * <p>Only {@code name}, {@code ruleType} and {@code ruleBody} belong to a bundle; the rules' order
 * does not, since the RULES step's fixed point is the same for every order.
 */
public final class RuleBundle {
  public static final String ALGORITHM = "om-rules-v1";

  /** The store's maxRules: rules one RULES step runs. */
  public static final int MAX_RULES = 256;

  /** A rule body, as OpenMetadata admits it. */
  public static final int MAX_BODY_CHARACTERS = 16_384;

  private static final String OMR = Capture.OMR;
  private static final String BUNDLE_PATH = "graph/rules/bundle/";
  private static final Node BUNDLE_DIGEST = NodeFactory.createURI(OMR + "bundleDigest");
  private static final Node DIGEST_ALGORITHM = NodeFactory.createURI(OMR + "digestAlgorithm");
  private static final Node RULE = NodeFactory.createURI(OMR + "rule");
  private static final Node RULE_NAME = NodeFactory.createURI(OMR + "ruleName");
  private static final Node RULE_TYPE = NodeFactory.createURI(OMR + "ruleType");
  private static final Node RULE_BODY = NodeFactory.createURI(OMR + "ruleBody");
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}[a-z0-9]");

  /** One rule of a bundle. */
  public record Rule(String name, String ruleType, String body) {
    JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("name", name);
      json.put("ruleType", ruleType);
      json.put("ruleBody", body);
      return json;
    }
  }

  private final String digest;
  private final String graph;
  private final List<Rule> rules;

  private RuleBundle(final String digest, final String graph, final List<Rule> rules) {
    this.digest = digest;
    this.graph = graph;
    this.rules = rules;
  }

  /** The bundle digest, {@code sha256:<hex>}. */
  public String digest() {
    return digest;
  }

  /** The bundle graph the rules were read from, or null for rules the request carried. */
  public String graph() {
    return graph;
  }

  /** The rules in name order. */
  public List<Rule> rules() {
    return rules;
  }

  public JsonArray rulesJson() {
    final JsonArray array = new JsonArray();
    rules.forEach(rule -> array.add(rule.toJson()));
    return array;
  }

  /**
   * The {@value #ALGORITHM} digest: SHA-256 over the line {@code om-rules-v1} and one line {@code
   * rule <name> <ruleType> sha256:<SHA-256 of the UTF-8 body>} per rule in name order, each ending
   * in a newline.
   */
  public static String digest(final List<Rule> rules) {
    final MessageDigest sha256 = GraphDigest.sha256();
    sha256.update((ALGORITHM + "\n").getBytes(StandardCharsets.UTF_8));
    for (final Rule rule : sorted(rules)) {
      final String body =
          HexFormat.of()
              .formatHex(GraphDigest.sha256().digest(rule.body().getBytes(StandardCharsets.UTF_8)));
      sha256.update(
          ("rule " + rule.name() + " " + rule.ruleType() + " sha256:" + body + "\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return GraphDigest.PREFIX + HexFormat.of().formatHex(sha256.digest());
  }

  /**
   * The enabled rules a request carries, which must digest to its ruleBundleDigest.
   *
   * @throws Capture.Refused INVALID_REQUEST when they do not, or a rule is malformed
   */
  static RuleBundle inline(final JsonArray rules, final String expectedDigest)
      throws Capture.Refused {
    final List<Rule> enabled = new ArrayList<>();
    for (final JsonValue value : rules) {
      final JsonObject rule = value.getAsObject();
      if (rule.hasKey("enabled") && !rule.getBoolean("enabled")) {
        continue;
      }
      enabled.add(
          new Rule(
              rule.getString("name"),
              rule.hasKey("ruleType") ? rule.getString("ruleType") : "CONSTRUCT",
              rule.getString("ruleBody")));
    }
    final List<Rule> checked = checked(enabled, expectedDigest, Problem.Code.INVALID_REQUEST);
    final String actual = digest(checked);
    if (!actual.equals(expectedDigest)) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.INVALID_REQUEST,
                  "The request's enabled rules digest to "
                      + actual
                      + ", not its ruleBundleDigest "
                      + expectedDigest)
              .withSubject(expectedDigest));
    }
    return new RuleBundle(actual, null, checked);
  }

  /**
   * Reads and verifies the bundle graph published under {@code expectedDigest}; the caller holds a
   * read transaction on {@code dataset}.
   */
  static RuleBundle published(final DatasetGraph dataset, final String expectedDigest)
      throws Capture.Refused {
    final String hex = expectedDigest.substring(GraphDigest.PREFIX.length());
    final Node graph = bundleGraph(dataset, expectedDigest, hex);
    final Iterator<Quad> algorithm = dataset.find(graph, graph, DIGEST_ALGORITHM, Node.ANY);
    final String name =
        algorithm.hasNext() ? algorithm.next().getObject().getLiteralLexicalForm() : "";
    if (!ALGORITHM.equals(name)) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.NOT_IMPLEMENTED,
                  "Rule bundle <"
                      + graph.getURI()
                      + "> uses the digest "
                      + (name.isEmpty() ? "(none)" : name)
                      + ", which this build cannot check")
              .withSubject(expectedDigest));
    }
    final List<Rule> rules = new ArrayList<>();
    final Iterator<Quad> members = dataset.find(graph, graph, RULE, Node.ANY);
    while (members.hasNext()) {
      final Node rule = members.next().getObject();
      if (rules.size() >= MAX_RULES) {
        throw new Capture.Refused(
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    "Rule bundle <"
                        + graph.getURI()
                        + "> names more than "
                        + MAX_RULES
                        + " rules, the store's maxRules")
                .withSubject(expectedDigest));
      }
      rules.add(
          new Rule(
              literal(dataset, graph, rule, RULE_NAME),
              literal(dataset, graph, rule, RULE_TYPE),
              literal(dataset, graph, rule, RULE_BODY)));
    }
    final List<Rule> checked = checked(rules, expectedDigest, Problem.Code.GENERATION_MISMATCH);
    final String actual = digest(checked);
    if (!actual.equals(expectedDigest)) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.GENERATION_MISMATCH,
                  "Rule bundle <"
                      + graph.getURI()
                      + "> holds rules whose digest is "
                      + actual
                      + ", not "
                      + expectedDigest)
              .withSubject(expectedDigest));
    }
    return new RuleBundle(actual, graph.getURI(), checked);
  }

  private static Node bundleGraph(final DatasetGraph dataset, final String digest, final String hex)
      throws Capture.Refused {
    final Iterator<Quad> found =
        dataset.find(Node.ANY, Node.ANY, BUNDLE_DIGEST, NodeFactory.createLiteralString(digest));
    while (found.hasNext()) {
      final Quad quad = found.next();
      if (quad.getGraph().equals(quad.getSubject())
          && quad.getGraph().isURI()
          && quad.getGraph().getURI().endsWith(BUNDLE_PATH + hex)) {
        return quad.getGraph();
      }
    }
    throw new Capture.Refused(
        Problem.of(
                Problem.Code.RULE_FAILED,
                "No rule bundle with digest "
                    + digest
                    + " is published; it was never published or has been dropped, so submit the"
                    + " refresh again with the current bundle")
            .withSubject(digest));
  }

  /** Rules in name order, each well formed; one name twice or a malformed rule is refused. */
  private static List<Rule> checked(
      final List<Rule> rules, final String digest, final Problem.Code code) throws Capture.Refused {
    final List<Rule> sorted = sorted(rules);
    for (int i = 0; i < sorted.size(); i++) {
      final Rule rule = sorted.get(i);
      String problem = null;
      if (rule.name() == null || !NAME.matcher(rule.name()).matches()) {
        problem = "a rule name must match " + NAME.pattern();
      } else if (i > 0 && sorted.get(i - 1).name().equals(rule.name())) {
        problem = "the bundle names rule '" + rule.name() + "' twice";
      } else if (rule.ruleType() == null) {
        problem = "rule '" + rule.name() + "' has no ruleType";
      } else if (rule.body() == null || rule.body().length() > MAX_BODY_CHARACTERS) {
        problem =
            "rule '" + rule.name() + "' has no body or one over " + MAX_BODY_CHARACTERS + " chars";
      }
      if (problem != null) {
        throw new Capture.Refused(
            Problem.of(code, "The rule bundle is malformed: " + problem).withSubject(digest));
      }
    }
    if (sorted.size() > MAX_RULES) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.LIMIT_EXCEEDED,
                  "The bundle has " + sorted.size() + " rules, over the store's maxRules of 256")
              .withSubject(digest));
    }
    return List.copyOf(sorted);
  }

  private static List<Rule> sorted(final List<Rule> rules) {
    final List<Rule> sorted = new ArrayList<>(rules);
    sorted.sort(Comparator.comparing(Rule::name, Comparator.nullsFirst(String::compareTo)));
    return sorted;
  }

  private static String literal(
      final DatasetGraph dataset, final Node graph, final Node subject, final Node predicate)
      throws Capture.Refused {
    final Iterator<Quad> quads = dataset.find(graph, subject, predicate, Node.ANY);
    if (!quads.hasNext()) {
      return null;
    }
    final Node value = quads.next().getObject();
    if (!value.isLiteral() || quads.hasNext()) {
      throw new Capture.Refused(
          Problem.of(
                  Problem.Code.GENERATION_MISMATCH,
                  "Rule <"
                      + subject
                      + "> of bundle <"
                      + graph.getURI()
                      + "> has a malformed "
                      + predicate.getURI())
              .withSubject(graph.getURI()));
    }
    return value.getLiteralLexicalForm();
  }
}
