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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Substitute;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingRoot;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.system.Txn;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Problem;
import org.openmetadata.reasoner.SortedReport;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.store.WorkingDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a bundle of governance rules to a fixed point over the working dataset, semi-naively, and
 * writes each rule's conclusions into its output graph with one witness per derived fact.
 *
 * <p>The step first empties its output graphs and removes its provenance file, so conclusions are
 * always recomputed from the graphs it reads and never seeded with an earlier run's. Each pass then
 * has two phases. First it evaluates the rules and spools their solutions to a bounded file: pass 1
 * evaluates every rule in full; later passes evaluate, for each rule atom that can match the
 * previous pass's new facts, the variant where that atom matches only them (see {@link
 * CompiledRule}). No evaluation is open when it then reads the spool back and applies the
 * conclusions: a conclusion already in a graph the rules read is not derived; one already in its
 * rule's graph is a duplicate; otherwise it is added there with its witness, and if no output graph
 * had it, it is a new fact and goes into the next delta. The deltas and the witnesses are files of
 * the step, never graphs of the dataset ({@link DeltaSpool}, {@link Provenance}). The step
 * converges when a pass adds no new fact. Running out of passes, new facts or spool ends it
 * INCOMPLETE; a step that does not converge leaves its graphs partial and no provenance file, and
 * its dataset must not be published.
 *
 * <p>All passes share one write transaction, committed when the step converges or runs out of a
 * budget. Nothing reads the working dataset while a step runs and a failed step's dataset is thrown
 * away, so a commit per pass bought no safety: it cost a sync of every index file, and TDB2 copies
 * a block again in each transaction that changes it, which grew the files with every pass.
 */
public final class RulesStep implements Step {
  private static final Logger LOG = LoggerFactory.getLogger(RulesStep.class);

  /**
   * Predicates the step tracks in a delta; a delta with more counts as having every predicate, so
   * every variant and every opaque rule runs, which costs only time.
   */
  static final int MAX_DELTA_PREDICATES = 4_096;

  static final int MAX_REPORT_LINES = 100_000;

  /**
   * The name a variant's delta pattern reads the previous pass's new facts by. It names a graph of
   * the dataset a pass evaluates over, never one of the working dataset.
   */
  private static final Node DELTA = NodeFactory.createURI(StepFile.RESERVED_GRAPH_PREFIX + "delta");

  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final StepFile.Governance governance = step.governance();
    final Limits limits = step.limits();
    if (governance.rules().size() > limits.maxRules()) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "TOO_MANY_RULES",
          "The bundle has "
              + governance.rules().size()
              + " rules, over the limit of "
              + limits.maxRules()
              + " (maxRules)");
    }
    final Context context = context();
    final List<CompiledRule> rules = compile(governance.rules(), limits, context);
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      return new Run(step, watchdog, dataset, rules, context).execute();
    } finally {
      WorkingDataset.release(dataset);
    }
  }

  /** Rules run with property functions off, whatever is registered: they run code by IRI. */
  static Context context() {
    return RuleCompiler.evaluationContext();
  }

  /** Every rule, enabled or not, must be valid; the enabled ones are returned in bundle order. */
  static List<CompiledRule> compile(
      final List<StepFile.Rule> specs, final Limits limits, final Context context)
      throws InputRejectedException {
    final List<Problem> problems = new ArrayList<>();
    final List<CompiledRule> enabled = new ArrayList<>();
    for (final StepFile.Rule spec : specs) {
      final RuleCompiler.Result result =
          RuleCompiler.compile(enabled.size(), spec, limits.maxRulePatterns(), context);
      problems.addAll(result.problems());
      if (result.rule() != null && spec.enabled()) {
        enabled.add(result.rule());
      }
    }
    if (!problems.isEmpty()) {
      throw new InputRejectedException(
          "INVALID_RULES", problems.stream().limit(Problem.MAX_REPORTED).toList(), problems.size());
    }
    return List.copyOf(enabled);
  }

  /** One execution of the step. */
  private static final class Run {
    private final StepFile step;
    private final Watchdog watchdog;
    private final DatasetGraph dataset;
    private final List<CompiledRule> rules;
    private final Context context;
    private final Limits limits;
    private final Set<Node> read = new LinkedHashSet<>();
    private final Set<Node> outputs = new LinkedHashSet<>();
    private final Path provenance;
    private final Path spool;
    private final Path deltaFile;
    private final Path nextDeltaFile;
    private final SpoolBudget budget;
    private final SortedReport report;
    private final Map<String, long[]> perRule = new LinkedHashMap<>();
    private final JsonArray passes = new JsonArray();
    private long newFacts;
    private long derived;
    private long solutions;
    private long peakSpoolBytes;
    private int pass;
    private Provenance.Writer witnesses;

    Run(
        final StepFile step,
        final Watchdog watchdog,
        final DatasetGraph dataset,
        final List<CompiledRule> rules,
        final Context context) {
      this.step = step;
      this.watchdog = watchdog;
      this.dataset = dataset;
      this.rules = rules;
      this.context = context;
      this.limits = step.limits();
      step.governance().read().forEach(iri -> read.add(graph(iri)));
      rules.forEach(rule -> outputs.add(rule.output()));
      rules.forEach(rule -> perRule.put(rule.name(), new long[2]));
      this.provenance = step.governance().provenance();
      this.spool = step.path().resolveSibling(step.stepId() + ".spool.tmp");
      this.deltaFile = step.path().resolveSibling(step.stepId() + ".delta.tmp");
      this.nextDeltaFile = step.path().resolveSibling(step.stepId() + ".delta.next.tmp");
      this.budget = new SpoolBudget(limits.maxSpoolBytes());
      this.report = step.report() ? new SortedReport(MAX_REPORT_LINES) : null;
    }

    StepResult execute() throws Exception {
      final List<String> emptyReadGraphs = clear();
      try (Provenance.Writer writer = new Provenance.Writer(provenance, budget)) {
        witnesses = writer;
        final StepResult stopped = inOneTransaction();
        if (stopped != null) {
          return stopped;
        }
        writer.finish(List.copyOf(read), rules, pass);
      } finally {
        Files.deleteIfExists(spool);
        Files.deleteIfExists(deltaFile);
        Files.deleteIfExists(nextDeltaFile);
      }
      final StepResult result =
          details(StepResult.succeeded("CONVERGED"))
              .detail("emptyReadGraphs", strings(emptyReadGraphs));
      return report == null ? result : result.report(report.lines("OUTCOME CONVERGED"));
    }

    /**
     * Runs the passes and commits what they derived, also when a budget stopped them; a failure
     * leaves the output graphs empty.
     *
     * @return the budget that stopped the passes, or null if they reached a fixed point
     */
    private StepResult inOneTransaction() throws IOException {
      dataset.begin(TxnType.WRITE);
      try {
        final StepResult stopped = passes();
        dataset.commit();
        return stopped;
      } catch (final IOException | RuntimeException e) {
        dataset.abort();
        throw e;
      } finally {
        dataset.end();
      }
    }

    private StepResult passes() throws IOException {
      Set<Node> deltaPredicates = Set.of();
      long deltaBytes = 0;
      while (true) {
        pass++;
        final long started = System.nanoTime();
        final Pass result;
        try {
          result = pass(deltaPredicates);
        } catch (final SpoolBudget.Full e) {
          return budget("SPOOL_BUDGET", "maxSpoolBytes", e.getMessage() + " in pass " + pass);
        } catch (final FactBudget e) {
          return budget(
              "FACT_BUDGET",
              "maxNewFacts",
              "The rules derived more than "
                  + limits.maxNewFacts()
                  + " facts (maxNewFacts) by pass "
                  + pass);
        }
        passes.add(result.toJson(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        LOG.info(
            "Pass {}: {} solutions, {} derived, {} new facts",
            pass,
            result.solutions,
            result.derived,
            result.newFacts);
        if (result.newFacts == 0) {
          return null;
        }
        if (pass >= limits.maxPasses()) {
          return budget(
              "PASS_BUDGET",
              "maxPasses",
              "The rules did not reach a fixed point within "
                  + limits.maxPasses()
                  + " passes (maxPasses); the last pass derived "
                  + result.newFacts
                  + " new facts");
        }
        Files.move(nextDeltaFile, deltaFile, StandardCopyOption.REPLACE_EXISTING);
        budget.release(deltaBytes);
        deltaBytes = result.deltaBytes;
        deltaPredicates = result.predicates;
      }
    }

    private StepResult budget(final String outcome, final String limit, final String message) {
      LOG.warn("Step {} stopped: {}", step.stepId(), message);
      return details(StepResult.budget(outcome, limit, message));
    }

    private StepResult details(final StepResult result) {
      final JsonArray perRuleJson = new JsonArray();
      for (final CompiledRule rule : rules) {
        final JsonObject json = new JsonObject();
        json.put("name", rule.name());
        json.put("digest", rule.digest());
        json.put("output", rule.output().getURI());
        json.put("solutions", perRule.get(rule.name())[0]);
        json.put("derived", perRule.get(rule.name())[1]);
        perRuleJson.add(json);
      }
      return result
          .detail("passes", pass)
          .detail("newFacts", newFacts)
          .detail("derived", derived)
          .detail("solutions", solutions)
          .detail("peakSpoolBytes", peakSpoolBytes)
          .detail("provenanceBytes", witnesses.bytes())
          .detail("rules", perRuleJson)
          .detail("perPass", passes);
    }

    /**
     * Empties the graphs this step writes and removes its provenance file, so nothing an earlier
     * run derived seeds this one or is explained from its witnesses, and names the graphs to read
     * that hold nothing.
     */
    private List<String> clear() throws IOException {
      Files.deleteIfExists(provenance);
      final List<String> empty = new ArrayList<>();
      Txn.executeWrite(
          dataset,
          () -> {
            for (final Node output : outputs) {
              dataset.deleteAny(output, Node.ANY, Node.ANY, Node.ANY);
            }
            for (final Node graph : read) {
              if (!dataset.find(graph, Node.ANY, Node.ANY, Node.ANY).hasNext()) {
                empty.add(graph.getURI());
              }
            }
          });
      return empty;
    }

    /** One pass: reads the delta the previous pass left in {@link #deltaFile}, writes the next. */
    private Pass pass(final Set<Node> deltaPredicates) throws IOException {
      final Pass result = new Pass(pass);
      final Set<Node> selection = new HashSet<>(read);
      selection.addAll(outputs);
      final SolutionSpool.Writer writer = new SolutionSpool.Writer(spool, budget);
      try (writer) {
        final DatasetGraph evaluation =
            SelectedGraphs.evaluation(
                dataset, selection, DELTA, pass == 1 ? null : DeltaSpool.graph(deltaFile));
        for (final CompiledRule rule : rules) {
          for (final Op op : plan(rule, deltaPredicates, result)) {
            evaluate(rule, op, evaluation, writer, result);
          }
        }
        result.spoolBytes = writer.bytes();
      }
      peakSpoolBytes = Math.max(peakSpoolBytes, result.spoolBytes);
      apply(result);
      budget.release(writer.bytes());
      return result;
    }

    /**
     * What to evaluate for a rule in this pass: its pattern in full, or its delta variants. A
     * variant scans the whole delta wherever it reads it, so a rule gets variants only if each
     * reads the delta once; otherwise it is evaluated in full, which finds the same facts.
     */
    private List<Op> plan(
        final CompiledRule rule, final Set<Node> deltaPredicates, final Pass result) {
      if (pass == 1
          || rule.opaquePartsRead(deltaPredicates)
          || !rule.deltaDrives(deltaPredicates)) {
        result.fullEvaluations++;
        return List.of(rule.pattern());
      }
      final List<Op> variants = new ArrayList<>();
      for (final CompiledRule.Atom atom : rule.deltaAtoms(deltaPredicates)) {
        variants.add(rule.variant(atom.index(), DELTA));
      }
      result.variants += variants.size();
      return variants;
    }

    private void evaluate(
        final CompiledRule rule,
        final Op op,
        final DatasetGraph evaluation,
        final SolutionSpool.Writer writer,
        final Pass result)
        throws IOException {
      final Op optimized = Algebra.optimize(op, context);
      final QueryIterator iterator =
          QueryEngineRegistry.findFactory(optimized, evaluation, context)
              .create(optimized, evaluation, BindingRoot.create(), context)
              .iterator();
      watchdog.guard(iterator::cancel);
      try {
        while (iterator.hasNext()) {
          writer.write(rule, iterator.nextBinding());
          result.solutions++;
          solutions++;
          perRule.get(rule.name())[0]++;
        }
      } finally {
        watchdog.guard(null);
        iterator.close();
      }
    }

    /**
     * Applies the spooled solutions: instantiates each rule's template and keeps the conclusions
     * that are derived and not duplicates, with their witnesses, and spools the new facts as the
     * next pass's delta.
     */
    private void apply(final Pass result) throws IOException {
      final Set<Node> predicates = new HashSet<>();
      final SelectedGraphs lookup = new SelectedGraphs(dataset, Set.of());
      final DeltaSpool.Writer nextDelta = new DeltaSpool.Writer(nextDeltaFile, budget);
      try (nextDelta;
          SolutionSpool.Reader reader = new SolutionSpool.Reader(spool, rules)) {
        for (SolutionSpool.Record record = reader.next(); record != null; record = reader.next()) {
          if (watchdog.expired()) {
            throw new IllegalStateException("The step's deadline passed");
          }
          applyOne(record, nextDelta, lookup, predicates, result);
        }
      }
      result.deltaBytes = nextDelta.bytes();
      result.predicates =
          predicates.size() > MAX_DELTA_PREDICATES
              ? Set.of(CompiledRule.ANY)
              : Set.copyOf(predicates);
    }

    private void applyOne(
        final SolutionSpool.Record record,
        final DeltaSpool.Writer nextDelta,
        final SelectedGraphs lookup,
        final Set<Node> predicates,
        final Pass result)
        throws IOException {
      final CompiledRule rule = record.rule();
      final Binding solution = record.solution();
      long solutionOffset = -1;
      for (final Triple template : rule.template()) {
        final Triple fact = Substitute.substitute(template, solution);
        if (!fact.isConcrete() || fact.getSubject().isLiteral() || !fact.getPredicate().isURI()) {
          continue;
        }
        boolean inRead = false;
        boolean inOwn = false;
        boolean inOutput = false;
        final Iterator<Node> graphs = lookup.graphsOf(fact);
        while (graphs.hasNext()) {
          final Node graph = graphs.next();
          inRead |= read.contains(graph);
          inOwn |= graph.equals(rule.output());
          inOutput |= outputs.contains(graph);
        }
        if (inRead || inOwn) {
          continue;
        }
        if (derived >= limits.maxNewFacts()) {
          throw new FactBudget();
        }
        dataset.add(rule.output(), fact.getSubject(), fact.getPredicate(), fact.getObject());
        derived++;
        result.derived++;
        perRule.get(rule.name())[1]++;
        if (solutionOffset < 0) {
          solutionOffset = witnesses.solution(rule, pass, solution);
        }
        witnesses.witness(rule.output(), fact, solutionOffset);
        if (!inOutput) {
          nextDelta.write(fact);
          newFacts++;
          result.newFacts++;
          if (predicates.size() <= MAX_DELTA_PREDICATES) {
            predicates.add(fact.getPredicate());
          }
        }
        if (report != null) {
          report.add(
              "FACT "
                  + NodeFmtLib.strNT(rule.output())
                  + " "
                  + NodeFmtLib.strNT(fact.getSubject())
                  + " "
                  + NodeFmtLib.strNT(fact.getPredicate())
                  + " "
                  + NodeFmtLib.strNT(fact.getObject()));
        }
      }
    }

    private static Node graph(final String iri) {
      return SelectedGraphs.normalize(NodeFactory.createURI(iri));
    }

    private static JsonArray strings(final List<String> values) {
      final JsonArray array = new JsonArray();
      values.forEach(array::add);
      return array;
    }
  }

  /** The new-fact budget ran out while a pass was applied. */
  private static final class FactBudget extends RuntimeException {
    FactBudget() {
      super(null, null, false, false);
    }
  }

  /** What one pass did. */
  private static final class Pass {
    private final int number;
    private long fullEvaluations;
    private long variants;
    private long solutions;
    private long spoolBytes;
    private long derived;
    private long newFacts;
    private long deltaBytes;
    private Set<Node> predicates = Set.of();

    Pass(final int number) {
      this.number = number;
    }

    JsonObject toJson(final long millis) {
      final JsonObject json = new JsonObject();
      json.put("pass", number);
      json.put("fullEvaluations", fullEvaluations);
      json.put("variants", variants);
      json.put("solutions", solutions);
      json.put("spoolBytes", spoolBytes);
      json.put("derived", derived);
      json.put("newFacts", newFacts);
      json.put("millis", millis);
      return json;
    }
  }
}
