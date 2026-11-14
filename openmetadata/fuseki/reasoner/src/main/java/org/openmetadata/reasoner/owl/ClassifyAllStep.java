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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.StepRunner;
import org.openmetadata.reasoner.Watchdog;
import org.openmetadata.reasoner.store.WorkingDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies the closures of a refresh one after another in this one process, each exactly as a
 * {@link ClassifyStep} of its own would: its own closure and admission, its own engine, its own
 * output graph, and its own result manifest {@code <closure step id>.result.json}, written as soon
 * as the closure is done. Starting a JVM per closure cost about a second each, which a refresh of a
 * thousand small releases paid a thousand times.
 *
 * <p>Every closure is read and written inside one write transaction, which commits when the last
 * one is classified: a commit forces a dozen files to disk, far longer than a small closure takes
 * to classify, and TDB2 grows with every commit. So the step writes every output graph or none. It
 * stops at the first closure that is rejected or inconsistent, as a caller running a step per
 * closure would have, and then leaves the dataset as it was; so does a step its deadline, the heap
 * or a kill ends.
 *
 * <p>The manifests say how far the step got: a closure that stopped it has a manifest that says
 * why, closures after it have none, and neither has one cut short by the deadline, the heap or a
 * kill, so the first closure without a SUCCEEDED manifest is always the one that ended the step.
 *
 * <p>The closures share the step's deadline, as steps of their own shared the job's, and {@code
 * maxNewFacts} bounds the triples they write together: each gets what the earlier ones left of it,
 * up to {@code maxOutputTriples}.
 */
public final class ClassifyAllStep implements Step {
  private static final Logger LOG = LoggerFactory.getLogger(ClassifyAllStep.class);

  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    for (final StepFile.BatchClosure closure : step.closures()) {
      // A manifest an earlier run of this step file left would read as this run's.
      Files.deleteIfExists(StepFile.resultPath(part(step, closure)));
    }
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      dataset.begin(TxnType.WRITE);
      try {
        final StepResult result = classifyAll(dataset, step, watchdog);
        if (result.detail("stoppedAt") == null) {
          dataset.commit();
        }
        return result;
      } finally {
        if (dataset.isInTransaction()) {
          dataset.abort();
        }
        dataset.end();
      }
    } finally {
      WorkingDataset.release(dataset);
    }
  }

  private static StepResult classifyAll(
      final DatasetGraph dataset, final StepFile step, final Watchdog watchdog) throws Exception {
    // One exported closure at a time, under this step's id, which is the file the launcher
    // removes after a step that did not succeed.
    final Path document = step.jobDirectory().resolve(step.stepId() + ".closure.nt");
    long written = 0;
    int classified = 0;
    for (final StepFile.BatchClosure closure : step.closures()) {
      Classifier.checkDeadline(watchdog);
      final long start = System.nanoTime();
      final long left = step.limits().maxNewFacts() - written;
      final StepFile one =
          step.forClosure(closure, Math.max(1, Math.min(step.limits().maxOutputTriples(), left)));
      final StepResult result;
      try {
        result = ClassifyStep.classify(dataset, one, document, watchdog);
      } catch (final InputRejectedException e) {
        StepRunner.writePartResult(one, StepResult.rejected(e), start, watchdog);
        return summary(StepResult.rejected(e), step, classified, written)
            .detail("stoppedAt", closure.stepId());
      }
      StepRunner.writePartResult(one, result, start, watchdog);
      final JsonObject entailments = result.detail("entailments").getAsObject();
      LOG.info(
          "Closure {} <{}>: {} in {} ms",
          closure.stepId(),
          closure.closure().root(),
          result.outcome(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      if (!entailments.getBoolean("consistent")) {
        return summary(StepResult.succeeded(result.outcome()), step, classified, written)
            .detail("stoppedAt", closure.stepId());
      }
      written += entailments.getNumber("outputTriples").longValue();
      classified++;
    }
    return summary(StepResult.succeeded("CLASSIFIED"), step, classified, written);
  }

  private static Path part(final StepFile step, final StepFile.BatchClosure closure) {
    return step.jobDirectory().resolve(closure.stepId() + ".json");
  }

  private static StepResult summary(
      final StepResult result, final StepFile step, final int classified, final long written) {
    final JsonObject closures = new JsonObject();
    closures.put("total", step.closures().size());
    closures.put("classified", classified);
    return result.detail("closures", closures).detail("outputTriples", written);
  }
}
