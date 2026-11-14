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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitorBase;
import org.apache.jena.sparql.algebra.OpWalker;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpSlice;
import org.apache.jena.sparql.algebra.op.OpTriple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingRoot;
import org.apache.jena.sparql.path.P_Alt;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.P_OneOrMore1;
import org.apache.jena.sparql.path.P_OneOrMoreN;
import org.apache.jena.sparql.path.P_ReverseLink;
import org.apache.jena.sparql.path.P_ZeroOrMore1;
import org.apache.jena.sparql.path.P_ZeroOrMoreN;
import org.apache.jena.sparql.path.P_ZeroOrOne;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.sparql.util.Context;
import org.openmetadata.reasoner.StepFile;

/**
 * Explains a fact a converged RULES step derived, from its provenance graph: the rule and pass that
 * first derived it and its premises, each premise explained in turn, down to facts the rules read.
 * Premises of a derived fact were all present before the pass that derived it, so following them
 * always reaches asserted facts; the explanation is still bounded in depth and size, and marks
 * where it stopped. A property-path premise is reconstructed as one supporting chain of triples,
 * found breadth-first, so it is a shortest one; an EXISTS condition is answered with one match.
 * Read inside a read transaction on the working dataset or its published snapshot.
 */
public final class Explanations {
  /**
   * @param maxDepth premises below the fact explained before they are left unexplained
   * @param maxFacts facts one explanation lists, all levels together
   * @param maxPathLength triples one reconstructed path chain may have
   * @param maxPathNodes nodes a path reconstruction may visit
   */
  public record Bounds(int maxDepth, int maxFacts, int maxPathLength, int maxPathNodes) {
    public static final Bounds DEFAULT = new Bounds(16, 1_000, 256, 100_000);
  }

  /** How a fact in an explanation is known. */
  public enum Origin {
    /** In a graph the rules read. */
    ASSERTED,
    /** Derived by a rule; the explanation names the rule, the pass and the premises. */
    DERIVED,
    /** In neither: the snapshot does not hold it. */
    ABSENT
  }

  /** A premise of a derived fact. */
  public sealed interface Premise permits Fact, PathPremise, ConditionPremise {
    JsonObject toJson();
  }

  /**
   * A fact and how it is known. {@code truncated} means its premises were left out because a bound
   * was reached; {@code premises} is then empty.
   */
  public record Fact(
      Triple triple,
      Origin origin,
      Node graph,
      String rule,
      int pass,
      List<Premise> premises,
      boolean truncated)
      implements Premise {
    @Override
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("kind", "FACT");
      json.put("triple", NodeFmtLib.strNT(triple));
      json.put("origin", origin.name());
      if (graph != null) {
        json.put("graph", graph.getURI());
      }
      if (rule != null) {
        json.put("rule", rule);
        json.put("pass", pass);
      }
      final JsonArray array = new JsonArray();
      premises.forEach(premise -> array.add(premise.toJson()));
      json.put("premises", array);
      json.put("truncated", truncated);
      return json;
    }
  }

  /**
   * A property-path premise: its endpoints, the path, and one chain of triples that connects them.
   * {@code complete} is false when the chain was not found within the bounds or the path's shape is
   * not one the reconstruction follows; the endpoints are then the whole answer.
   */
  public record PathPremise(
      Node subject, String path, Node object, List<Triple> chain, boolean complete)
      implements Premise {
    @Override
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("kind", "PATH");
      json.put("subject", NodeFmtLib.strNT(subject));
      json.put("path", path);
      json.put("object", NodeFmtLib.strNT(object));
      final JsonArray array = new JsonArray();
      chain.forEach(triple -> array.add(NodeFmtLib.strNT(triple)));
      json.put("chain", array);
      json.put("complete", complete);
      return json;
    }
  }

  /** A FILTER EXISTS condition the solution satisfied, with the triples of one match. */
  public record ConditionPremise(String pattern, List<Triple> match) implements Premise {
    @Override
    public JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("kind", "EXISTS");
      json.put("pattern", pattern);
      final JsonArray array = new JsonArray();
      match.forEach(triple -> array.add(NodeFmtLib.strNT(triple)));
      json.put("match", array);
      return json;
    }
  }

  private final DatasetGraph dataset;
  private final Node provenance;
  private final Provenance.Run run;
  private final Bounds bounds;
  private final Context context = RuleCompiler.evaluationContext();
  private final Map<String, CompiledRule> rules = new HashMap<>();
  private final Set<Node> read = new HashSet<>();
  private final Set<Node> outputs = new LinkedHashSet<>();
  private final DatasetGraph evaluation;
  private int facts;

  private Explanations(
      final DatasetGraph dataset,
      final Node provenance,
      final Provenance.Run run,
      final Bounds bounds) {
    this.dataset = dataset;
    this.provenance = provenance;
    this.run = run;
    this.bounds = bounds;
    run.read().forEach(graph -> read.add(SelectedGraphs.normalize(graph)));
    run.rules().values().forEach(rule -> outputs.add(rule.output()));
    final Set<Node> selection = new HashSet<>(read);
    selection.addAll(outputs);
    this.evaluation = SelectedGraphs.evaluation(dataset, selection, null);
  }

  /**
   * Explains {@code fact}, or returns {@code null} when the provenance graph holds no converged
   * run, which is the case after a RULES step that did not finish.
   */
  public static Fact explain(
      final DatasetGraph dataset, final Node provenance, final Triple fact, final Bounds bounds) {
    final Provenance.Run run = Provenance.readRun(dataset, provenance);
    if (run == null) {
      return null;
    }
    return new Explanations(dataset, provenance, run, bounds).fact(fact, 0);
  }

  private Fact fact(final Triple triple, final int depth) {
    facts++;
    final SelectedGraphs lookup = new SelectedGraphs(dataset, Set.of());
    Node derivedIn = null;
    for (final Iterator<Node> graphs = lookup.graphsOf(triple); graphs.hasNext(); ) {
      final Node graph = graphs.next();
      if (read.contains(graph)) {
        return new Fact(triple, Origin.ASSERTED, graph, null, 0, List.of(), false);
      }
      if (derivedIn == null && outputs.contains(graph)) {
        derivedIn = graph;
      }
    }
    if (derivedIn == null) {
      return new Fact(triple, Origin.ABSENT, null, null, 0, List.of(), false);
    }
    final Provenance.Solution solution =
        Provenance.readSolution(dataset, provenance, Provenance.witness(derivedIn, triple));
    if (solution == null) {
      return new Fact(triple, Origin.DERIVED, derivedIn, null, 0, List.of(), true);
    }
    if (depth >= bounds.maxDepth() || facts >= bounds.maxFacts()) {
      return new Fact(
          triple, Origin.DERIVED, derivedIn, solution.rule(), solution.pass(), List.of(), true);
    }
    final CompiledRule rule = rule(solution.rule());
    final List<Premise> premises = new ArrayList<>();
    boolean truncated = false;
    for (final CompiledRule.Atom atom : rule.atoms()) {
      if (!atom.matchedBy(solution.binding())) {
        continue;
      }
      if (facts >= bounds.maxFacts()) {
        truncated = true;
        break;
      }
      if (atom.isPath()) {
        premises.add(path(atom.path(), solution.binding()));
      } else {
        premises.add(fact(Substitute.substitute(atom.triple(), solution.binding()), depth + 1));
      }
    }
    for (final CompiledRule.Condition condition : rule.conditions()) {
      if (condition.matchedBy(solution.binding())) {
        premises.add(condition(condition, solution.binding()));
      }
    }
    return new Fact(
        triple,
        Origin.DERIVED,
        derivedIn,
        solution.rule(),
        solution.pass(),
        List.copyOf(premises),
        truncated);
  }

  private CompiledRule rule(final String name) {
    return rules.computeIfAbsent(
        name,
        key -> {
          final Provenance.RuleRecord record = run.rules().get(key);
          final RuleCompiler.Result result =
              RuleCompiler.compile(
                  0,
                  new StepFile.Rule(
                      key, "CONSTRUCT", record.body(), true, 0, record.output().getURI()),
                  Long.MAX_VALUE,
                  context);
          if (result.rule() == null) {
            throw new IllegalStateException(
                "The recorded rule '" + key + "' no longer compiles: " + result.problems());
          }
          return result.rule();
        });
  }

  /**
   * One chain for a path premise, breadth-first from its subject. Paths that repeat one step, or a
   * choice of steps, any number of times are followed; any other shape is reported by endpoint.
   */
  private PathPremise path(final TriplePath pattern, final Binding binding) {
    final Node subject = value(pattern.getSubject(), binding);
    final Node object = value(pattern.getObject(), binding);
    final String text = pattern.getPath().toString();
    final List<Step> steps = new ArrayList<>();
    final Path inner = repeated(pattern.getPath());
    if (subject == null || object == null || inner == null || !steps(inner, steps)) {
      return new PathPremise(subject, text, object, List.of(), false);
    }
    if (subject.equals(object) && !(pattern.getPath() instanceof P_OneOrMore1)) {
      return new PathPremise(subject, text, object, List.of(), true);
    }
    final Map<Node, Triple> reachedBy = new HashMap<>();
    final Deque<Node> queue = new ArrayDeque<>();
    queue.add(subject);
    int visited = 0;
    while (!queue.isEmpty() && visited < bounds.maxPathNodes()) {
      final Node node = queue.poll();
      visited++;
      for (final Step step : steps) {
        final Iterator<Triple> edges =
            step.forward()
                ? evaluation.getDefaultGraph().find(node, step.predicate(), Node.ANY)
                : evaluation.getDefaultGraph().find(Node.ANY, step.predicate(), node);
        while (edges.hasNext()) {
          final Triple edge = edges.next();
          final Node next = step.forward() ? edge.getObject() : edge.getSubject();
          if (next.equals(subject) && !next.equals(object) || reachedBy.containsKey(next)) {
            continue;
          }
          reachedBy.put(next, edge);
          if (next.equals(object)) {
            return chain(subject, object, text, reachedBy, steps);
          }
          queue.add(next);
        }
      }
    }
    return new PathPremise(subject, text, object, List.of(), false);
  }

  private PathPremise chain(
      final Node subject,
      final Node object,
      final String text,
      final Map<Node, Triple> reachedBy,
      final List<Step> steps) {
    final Deque<Triple> chain = new ArrayDeque<>();
    Node node = object;
    while (chain.size() < bounds.maxPathLength() + 1) {
      final Triple edge = reachedBy.get(node);
      chain.addFirst(edge);
      node = edge.getSubject().equals(node) ? edge.getObject() : edge.getSubject();
      if (node.equals(subject)) {
        break;
      }
    }
    final boolean complete = chain.size() <= bounds.maxPathLength();
    final List<Triple> triples = new ArrayList<>(chain);
    return new PathPremise(
        subject,
        text,
        object,
        complete
            ? triples
            : triples.subList(triples.size() - bounds.maxPathLength(), triples.size()),
        complete);
  }

  private static Path repeated(final Path path) {
    if (path instanceof P_OneOrMore1 p) {
      return p.getSubPath();
    }
    if (path instanceof P_ZeroOrMore1 p) {
      return p.getSubPath();
    }
    if (path instanceof P_OneOrMoreN p) {
      return p.getSubPath();
    }
    if (path instanceof P_ZeroOrMoreN p) {
      return p.getSubPath();
    }
    if (path instanceof P_ZeroOrOne p) {
      return p.getSubPath();
    }
    return path;
  }

  private record Step(Node predicate, boolean forward) {}

  private static boolean steps(final Path path, final List<Step> steps) {
    if (path instanceof P_Link link) {
      steps.add(new Step(link.getNode(), true));
      return true;
    }
    if (path instanceof P_ReverseLink link) {
      steps.add(new Step(link.getNode(), false));
      return true;
    }
    if (path instanceof P_Alt alt) {
      return steps(alt.getLeft(), steps) && steps(alt.getRight(), steps);
    }
    return false;
  }

  /** One match of an EXISTS condition, with the solution's values substituted in. */
  private ConditionPremise condition(
      final CompiledRule.Condition condition, final Binding binding) {
    final Op pattern = Substitute.substitute(condition.pattern(), binding);
    final Op limited = new OpSlice(Algebra.optimize(pattern, context), 0, 1);
    final QueryIterator iterator =
        QueryEngineRegistry.findFactory(limited, evaluation, context)
            .create(limited, evaluation, BindingRoot.create(), context)
            .iterator();
    final List<Triple> match = new ArrayList<>();
    try {
      if (iterator.hasNext()) {
        final Binding found = iterator.nextBinding();
        for (final Triple triple : triples(pattern)) {
          final Triple ground = Substitute.substitute(triple, found);
          if (ground.isConcrete()) {
            match.add(ground);
          }
        }
      }
    } finally {
      iterator.close();
    }
    return new ConditionPremise(pattern.toString().strip(), List.copyOf(match));
  }

  private static List<Triple> triples(final Op op) {
    final List<Triple> triples = new ArrayList<>();
    OpWalker.walk(
        op,
        new OpVisitorBase() {
          @Override
          public void visit(final OpBGP bgp) {
            triples.addAll(bgp.getPattern().getList());
          }

          @Override
          public void visit(final OpTriple triple) {
            triples.add(triple.getTriple());
          }
        });
    return triples;
  }

  private static Node value(final Node node, final Binding binding) {
    if (!node.isVariable()) {
      return node;
    }
    return binding.get(Var.alloc(node));
  }
}
