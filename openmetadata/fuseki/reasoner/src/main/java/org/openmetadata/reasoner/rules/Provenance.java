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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;

/**
 * The provenance graph of a RULES step: one witness per derived fact and, once the step has
 * converged, a record of the run. It is a named graph of the working dataset that the rules never
 * read and that the publishing step keeps out of the serving union; only explanations read it.
 *
 * <p>A fact's witness is found from the fact alone: its IRI is a digest of the output graph and the
 * triple. The witness names the solution that first derived the fact, and the solution records the
 * rule, the pass and the value of each of the rule's variables. Those values are terms the dataset
 * already holds, so a witness costs index entries and no new node data however long its literals
 * are; the premises are the rule's matched patterns instantiated with them. Facts derived from one
 * solution share it.
 *
 * <pre>
 * &lt;witness:H(graph, s, p, o)&gt; :solution &lt;solution:H(rule, values)&gt; .
 * &lt;solution:...&gt; :rule &lt;rule:NAME&gt; ; :pass 3 ; &lt;binding:VAR&gt; value ...
 * &lt;rule:NAME&gt; :name "NAME" ; :body "..." ; :digest "sha-256" ; :output &lt;graph&gt; .
 * &lt;run&gt; :status "CONVERGED" ; :passes 4 ; :read &lt;graph&gt; ... ; :rule &lt;rule:NAME&gt; ...
 * </pre>
 */
public final class Provenance {
  static final String PREFIX = "urn:openmetadata:reasoner:";
  static final String VOCABULARY = PREFIX + "provenance:";
  static final Node RUN = NodeFactory.createURI(PREFIX + "run");
  static final Node SOLUTION = term("solution");
  static final Node RULE = term("rule");
  static final Node PASS = term("pass");
  static final Node NAME = term("name");
  static final Node BODY = term("body");
  static final Node DIGEST = term("digest");
  static final Node OUTPUT = term("output");
  static final Node READ = term("read");
  static final Node STATUS = term("status");
  static final Node PASSES = term("passes");
  static final String CONVERGED = "CONVERGED";
  private static final String BINDING = PREFIX + "binding:";

  private Provenance() {}

  private static Node term(final String name) {
    return NodeFactory.createURI(VOCABULARY + name);
  }

  static Node deltaGraph(final int pass) {
    return NodeFactory.createURI(PREFIX + "delta:" + pass);
  }

  static Node ruleNode(final String name) {
    return NodeFactory.createURI(PREFIX + "rule:" + name);
  }

  /** The witness of a derived fact in an output graph. */
  public static Node witness(final Node graph, final Triple fact) {
    return NodeFactory.createURI(
        PREFIX
            + "witness:"
            + digest(graph, fact.getSubject(), fact.getPredicate(), fact.getObject()));
  }

  static Node solution(final CompiledRule rule, final Binding solution) {
    final Node[] values = new Node[rule.witnessVars().size() + 1];
    values[0] = NodeFactory.createLiteralString(rule.name());
    for (int i = 0; i < rule.witnessVars().size(); i++) {
      values[i + 1] = solution.get(rule.witnessVars().get(i));
    }
    return NodeFactory.createURI(PREFIX + "solution:" + digest(values));
  }

  static Node bindingPredicate(final Var var) {
    return NodeFactory.createURI(
        BINDING + URLEncoder.encode(var.getVarName(), StandardCharsets.UTF_8));
  }

  /** Writes the solution a newly derived fact came from, the first time it is needed. */
  static void writeSolution(
      final DatasetGraph dataset,
      final Node graph,
      final Node solutionNode,
      final CompiledRule rule,
      final int pass,
      final Binding solution) {
    dataset.add(graph, solutionNode, RULE, ruleNode(rule.name()));
    dataset.add(graph, solutionNode, PASS, integer(pass));
    for (final Var var : rule.witnessVars()) {
      final Node value = solution.get(var);
      if (value != null) {
        dataset.add(graph, solutionNode, bindingPredicate(var), value);
      }
    }
  }

  /** Records a converged run: the graphs the rules read and every enabled rule's definition. */
  static void writeRun(
      final DatasetGraph dataset,
      final Node graph,
      final List<Node> read,
      final List<CompiledRule> rules,
      final int passes) {
    dataset.add(graph, RUN, STATUS, NodeFactory.createLiteralString(CONVERGED));
    dataset.add(graph, RUN, PASSES, integer(passes));
    read.forEach(node -> dataset.add(graph, RUN, READ, node));
    for (final CompiledRule rule : rules) {
      final Node node = ruleNode(rule.name());
      dataset.add(graph, RUN, RULE, node);
      dataset.add(graph, node, NAME, NodeFactory.createLiteralString(rule.name()));
      dataset.add(graph, node, BODY, NodeFactory.createLiteralString(rule.body()));
      dataset.add(graph, node, DIGEST, NodeFactory.createLiteralString(rule.digest()));
      dataset.add(graph, node, OUTPUT, rule.output());
    }
  }

  /** A run as its record describes it; read inside a read transaction. */
  record Run(int passes, List<Node> read, Map<String, RuleRecord> rules) {}

  record RuleRecord(String name, String body, String digest, Node output) {}

  /** The record of the converged run in this provenance graph, or {@code null} if there is none. */
  static Run readRun(final DatasetGraph dataset, final Node graph) {
    final Node status = object(dataset, graph, RUN, STATUS);
    if (status == null || !CONVERGED.equals(status.getLiteralLexicalForm())) {
      return null;
    }
    final List<Node> read = objects(dataset, graph, RUN, READ);
    final Map<String, RuleRecord> rules = new LinkedHashMap<>();
    for (final Node rule : objects(dataset, graph, RUN, RULE)) {
      final String name = object(dataset, graph, rule, NAME).getLiteralLexicalForm();
      rules.put(
          name,
          new RuleRecord(
              name,
              object(dataset, graph, rule, BODY).getLiteralLexicalForm(),
              object(dataset, graph, rule, DIGEST).getLiteralLexicalForm(),
              object(dataset, graph, rule, OUTPUT)));
    }
    final Node passes = object(dataset, graph, RUN, PASSES);
    return new Run(Integer.parseInt(passes.getLiteralLexicalForm()), read, rules);
  }

  /** A solution as its witness records it: the rule's name, the pass and the variables' values. */
  record Solution(String rule, int pass, Binding binding) {}

  static Solution readSolution(final DatasetGraph dataset, final Node graph, final Node witness) {
    final Node solution = object(dataset, graph, witness, SOLUTION);
    if (solution == null) {
      return null;
    }
    final Node rule = object(dataset, graph, solution, RULE);
    final Node pass = object(dataset, graph, solution, PASS);
    final BindingBuilder binding = BindingBuilder.create();
    final Iterator<Quad> quads = dataset.find(graph, solution, Node.ANY, Node.ANY);
    while (quads.hasNext()) {
      final Quad quad = quads.next();
      final String predicate = quad.getPredicate().getURI();
      if (predicate.startsWith(BINDING)) {
        binding.add(
            Var.alloc(
                URLDecoder.decode(predicate.substring(BINDING.length()), StandardCharsets.UTF_8)),
            quad.getObject());
      }
    }
    return new Solution(
        rule.getURI().substring((PREFIX + "rule:").length()),
        Integer.parseInt(pass.getLiteralLexicalForm()),
        binding.build());
  }

  private static Node object(
      final DatasetGraph dataset, final Node graph, final Node subject, final Node predicate) {
    final Iterator<Quad> quads = dataset.find(graph, subject, predicate, Node.ANY);
    return quads.hasNext() ? quads.next().getObject() : null;
  }

  private static List<Node> objects(
      final DatasetGraph dataset, final Node graph, final Node subject, final Node predicate) {
    final List<Node> objects = new ArrayList<>();
    dataset
        .find(graph, subject, predicate, Node.ANY)
        .forEachRemaining(q -> objects.add(q.getObject()));
    return objects;
  }

  private static Node integer(final long value) {
    return NodeFactory.createLiteralDT(Long.toString(value), XSDDatatype.XSDinteger);
  }

  /** 128 bits of SHA-256 over the terms' exact encoding, as hex. */
  private static String digest(final Node... nodes) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DataOutputStream out =
          new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
        for (final Node node : nodes) {
          NodeCodec.write(out, node);
        }
      }
      return HexFormat.of().formatHex(digest.digest(), 0, 16);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
