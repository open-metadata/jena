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

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;

/**
 * Compacts the working dataset, dropping the old generation, as the last step before the Fuseki
 * module opens it read-only as a snapshot. The module opens it only after this worker has exited.
 */
public final class CompactStep implements Step {
  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final DatasetGraph dataset = WorkingDataset.open(step.dataset());
    try {
      final long before = WorkingDataset.sizeOnDisk(step.dataset());
      DatabaseMgr.compact(dataset, true);
      return StepResult.succeeded("COMPACTED")
          .detail("bytesBefore", before)
          .detail("bytesAfter", WorkingDataset.sizeOnDisk(step.dataset()));
    } finally {
      WorkingDataset.release(dataset);
    }
  }
}
