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
package org.openmetadata.reasoner.owl;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.store.WorkingDataset;

/**
 * Classifies one import closure: consistency, unsatisfiable classes, the direct named class
 * hierarchy and equivalences, direct types and object property values of named individuals and,
 * with HermiT, their equalities; plus the requested entailment checks. The entailments replace the
 * contents of the output graph in the working dataset, in one write transaction that commits only
 * when classification completes, so a failed or interrupted step leaves the graph as it was; an
 * inconsistent closure leaves that graph empty. Entailments go to the graph as they are found and
 * are never all held in memory.
 */
public final class ClassifyStep implements Step {
  /**
   * The canonical report lists every fact for tests and diagnosis, so it would repeat the output
   * graph on disk; beyond this many lines it keeps the first ones in order and says how many it
   * left out.
   */
  static final int MAX_REPORT_LINES = 100_000;

  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      final ClosureInput input = ClosureInput.read(dataset, step);
      final List<Classifier.Check> checks =
          step.checks() == null
              ? List.of()
              : input.checks(step.checks(), step.limits().maxChecks());
      final PropertyValues values = PropertyValues.analyse(input.ontology());
      final EngineRouter.Route route = EngineRouter.route(input.ontology(), step.engine(), values);
      final Report report = step.report() ? new Report(MAX_REPORT_LINES) : null;
      final GraphWriter writer =
          step.outputGraph() == null ? null : new GraphWriter(dataset, step.outputGraph());
      final Classifier.Outcome outcome;
      try {
        outcome =
            Classifier.run(
                input.ontology(),
                route,
                values,
                checks,
                watchdog,
                step.limits(),
                fact -> {
                  if (writer != null) {
                    writer.write(fact);
                  }
                  if (report != null) {
                    report.add(fact.line());
                  }
                });
        if (writer != null) {
          writer.commit();
        }
      } catch (final Classification.OutputTooLarge e) {
        throw InputRejectedException.of("LIMIT_EXCEEDED", "OUTPUT_TOO_LARGE", e.getMessage());
      } finally {
        if (writer != null) {
          writer.close();
        }
      }
      final Classification classification = outcome.classification();
      final String result = classification.isConsistent() ? "CLASSIFIED" : "INCONSISTENT";

      final JsonObject routing = route.toJson();
      routing.put("engineUsed", outcome.engine().displayName());
      if (outcome.fallback() != null) {
        routing.put("fallback", outcome.fallback());
      }
      final JsonArray checkResults = new JsonArray();
      for (final Classifier.CheckResult check : outcome.checks()) {
        final JsonObject json = new JsonObject();
        json.put("axiom", check.rendering());
        json.put("outcome", check.outcome().name());
        if (check.engine() != null) {
          json.put("engine", check.engine());
        }
        checkResults.add(json);
        if (report != null) {
          report.add(check.line());
        }
      }
      final JsonObject counts = classification.countsJson();
      counts.put("outputTriples", writer == null ? 0 : writer.written());
      final StepResult stepResult =
          StepResult.succeeded(result)
              .detail("engine", outcome.engine().toJson())
              .detail("routing", routing)
              .detail("closure", input.closureJson())
              .detail("admission", input.admission().toJson())
              .detail("entailments", counts)
              .detail("checks", checkResults);
      return report == null ? stepResult : stepResult.report(report.lines("OUTCOME " + result));
    } finally {
      WorkingDataset.release(dataset);
    }
  }

  /**
   * Replaces the output graph inside one write transaction, opened before the first fact so the old
   * contents are dropped in the same transaction.
   */
  private static final class GraphWriter implements AutoCloseable {
    private final DatasetGraph dataset;
    private final Node graph;
    private long written;

    GraphWriter(final DatasetGraph dataset, final String graphIri) {
      this.dataset = dataset;
      this.graph = NodeFactory.createURI(graphIri);
      dataset.begin(TxnType.WRITE);
      dataset.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
    }

    void write(final Classification.Fact fact) {
      fact.triples(
          triple ->
              dataset.add(graph, triple.getSubject(), triple.getPredicate(), triple.getObject()));
      written += fact.tripleCount();
    }

    long written() {
      return written;
    }

    void commit() {
      dataset.commit();
    }

    @Override
    public void close() {
      if (dataset.isInTransaction()) {
        dataset.abort();
      }
      dataset.end();
    }
  }

  /** The report's fact and check lines in order, keeping only the first {@code limit}. */
  static final class Report {
    private final int limit;
    private final TreeSet<String> lines = new TreeSet<>();
    private long omitted;

    Report(final int limit) {
      this.limit = limit;
    }

    void add(final String line) {
      lines.add(line);
      if (lines.size() > limit) {
        lines.pollLast();
        omitted++;
      }
    }

    List<String> lines(final String outcome) {
      final List<String> report = new ArrayList<>(lines.size() + 2);
      report.add(outcome);
      report.addAll(lines);
      if (omitted > 0) {
        report.add("OMITTED " + omitted + " lines beyond the first " + limit);
      }
      return report;
    }
  }
}
