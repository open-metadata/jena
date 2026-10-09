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
package org.openmetadata.reasoner.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.system.progress.MonitorOutputs;
import org.apache.jena.tdb2.loader.DataLoader;
import org.apache.jena.tdb2.loader.LoaderFactory;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bulk-loads the job's exported quads into a fresh private dataset. One bulk load, not many small
 * write transactions, which would grow TDB2 by copying index pages. The dataset has no other reader
 * or writer. A load that is killed leaves a dataset nobody may use; the caller discards it.
 */
public final class LoadStep implements Step {
  private static final Logger LOG = LoggerFactory.getLogger(LoadStep.class);

  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final List<String> files = new ArrayList<>();
    long inputBytes = 0;
    for (final Path input : step.inputs()) {
      if (!Files.isRegularFile(input)) {
        throw InputRejectedException.of(
            "INVALID_INPUT", "INPUT_MISSING", "Input " + input.getFileName() + " does not exist");
      }
      final Lang lang = RDFLanguages.filenameToLang(input.toString());
      if (lang == null || !RDFLanguages.isQuads(lang)) {
        throw InputRejectedException.of(
            "INVALID_INPUT",
            "INPUT_FORMAT",
            "Input "
                + input.getFileName()
                + " must be N-Quads or TriG, optionally gzip-compressed (.nq, .nq.gz, .trig)");
      }
      inputBytes += Files.size(input);
      files.add(input.toString());
    }
    if (inputBytes > step.limits().maxInputBytes()) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "INPUT_TOO_LARGE",
          "Inputs total "
              + inputBytes
              + " bytes, over the limit of "
              + step.limits().maxInputBytes());
    }

    final DatasetGraph dataset = WorkingDataset.create(step.dataset());
    try {
      final DataLoader loader =
          LoaderFactory.phasedLoader(dataset, MonitorOutputs.outputToLog(LOG));
      loader.startBulk();
      try {
        loader.load(files);
        loader.finishBulk();
      } catch (final RuntimeException e) {
        loader.finishException(e);
        if (e instanceof RiotException) {
          throw InputRejectedException.of("INVALID_INPUT", "PARSE_ERROR", e.getMessage());
        }
        throw e;
      }
      final long quads = loader.countQuads();
      final long graphs = Txn.calculateRead(dataset, () -> Iter.count(dataset.listGraphNodes()));
      return StepResult.succeeded("LOADED")
          .detail("inputBytes", inputBytes)
          .detail("quads", quads)
          .detail("graphs", graphs)
          .detail("datasetBytes", WorkingDataset.sizeOnDisk(step.dataset()));
    } finally {
      WorkingDataset.release(dataset);
    }
  }
}
