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
package org.openmetadata.reasoner;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.concurrent.TimeUnit;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;

class WatchdogTest {
  /**
   * HermiT clears its interrupt flag when a task starts, so an interrupt sent once, between two
   * tasks, used to be lost: an explanation that passed its deadline between two consistency checks
   * ran the next check to its end, which on a hard closure takes minutes. The watchdog now repeats
   * the interrupt until the step unwinds. Without that this test runs into its timeout.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void anInterruptSentBeforeTheEnginesNextTaskStillStopsThatTask() throws Exception {
    final OWLOntology hard = hardOntology();
    final Reasoner hermit = new Reasoner(new Configuration(), hard);
    try (Watchdog watchdog = new Watchdog(50, expired -> {})) {
      watchdog.guard(hermit::interrupt);
      // The deadline passes while HermiT is idle; the task below starts by clearing the interrupt.
      while (!watchdog.expired()) {
        Thread.sleep(10);
      }
      final long start = System.nanoTime();
      assertThrows(
          ReasonerInterruptedException.class,
          () -> hermit.precomputeInferences(InferenceType.CLASS_HIERARCHY));
      final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsed < Watchdog.UNWIND_GRACE_MILLIS, elapsed + " ms");
    } finally {
      hermit.dispose();
    }
  }

  private static OWLOntology hardOntology() throws Exception {
    final DatasetGraph dataset = DatasetGraphFactory.create();
    RDFParser.fromString(Jobs.hardOntology(200), Lang.TRIG).parse(dataset);
    final StringWriter turtle = new StringWriter();
    RDFDataMgr.write(turtle, dataset.getGraph(NodeFactory.createURI("urn:hard")), Lang.TURTLE);
    return OWLManager.createOWLOntologyManager()
        .loadOntologyFromOntologyDocument(new StringDocumentSource(turtle.toString()));
  }
}
