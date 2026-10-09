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
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.store.WorkingDataset;

/**
 * Classifies one import closure: consistency, unsatisfiable classes, the direct named class
 * hierarchy and equivalences, direct types of named individuals and, with HermiT, their property
 * values and equalities; plus the requested entailment checks. The entailments replace the contents
 * of the output graph in the working dataset, in one write transaction after reasoning; an
 * inconsistent closure leaves that graph empty.
 */
public final class ClassifyStep implements Step {
  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      final ClosureInput input = ClosureInput.read(dataset, step);
      final List<Classifier.Check> checks =
          step.checks() == null
              ? List.of()
              : input.checks(step.checks(), step.limits().maxChecks());
      final EngineRouter.Route route = EngineRouter.route(input.ontology(), step.engine());
      final Classifier.Outcome outcome;
      try {
        outcome = Classifier.run(input.ontology(), route, checks, watchdog, step.limits());
      } catch (final Classifier.OutputTooLarge e) {
        throw InputRejectedException.of("LIMIT_EXCEEDED", "OUTPUT_TOO_LARGE", e.getMessage());
      }
      final Classification classification = outcome.classification();
      final long written =
          step.outputGraph() == null ? 0 : write(dataset, step.outputGraph(), classification);

      final List<String> lines = new ArrayList<>(classification.lines());
      outcome.checks().forEach(check -> lines.add(check.line()));
      final List<String> report = new ArrayList<>();
      report.add("OUTCOME " + (classification.isConsistent() ? "CLASSIFIED" : "INCONSISTENT"));
      report.addAll(lines.stream().sorted().toList());

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
      }
      final JsonObject counts = classification.countsJson();
      counts.put("outputTriples", written);
      return StepResult.succeeded(classification.isConsistent() ? "CLASSIFIED" : "INCONSISTENT")
          .detail("engine", outcome.engine().toJson())
          .detail("routing", routing)
          .detail("closure", input.closureJson())
          .detail("admission", input.admission().toJson())
          .detail("entailments", counts)
          .detail("checks", checkResults)
          .report(report);
    } finally {
      WorkingDataset.release(dataset);
    }
  }

  private static long write(
      final DatasetGraph dataset, final String graphIri, final Classification classification) {
    final Node graph = NodeFactory.createURI(graphIri);
    final List<Triple> triples =
        classification.isConsistent() ? classification.triples() : List.of();
    Txn.executeWrite(
        dataset,
        () -> {
          dataset.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
          for (final Triple triple : triples) {
            dataset.add(graph, triple.getSubject(), triple.getPredicate(), triple.getObject());
          }
        });
    return triples.size();
  }
}
