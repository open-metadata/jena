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
import java.nio.file.StandardCopyOption;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.reasoner.Step;
import org.openmetadata.reasoner.StepFile;
import org.openmetadata.reasoner.StepResult;
import org.openmetadata.reasoner.Watchdog;

/**
 * Compacts the working dataset as the last step before the Fuseki module opens it read-only as a
 * snapshot, which it does only after this worker has exited. The steps before it leave B+tree
 * blocks half full and, for every transaction that changed a block, the block's earlier copy; the
 * step replaces the dataset with a {@link PackedCopy} of it, built beside it in the job directory.
 * A step that fails may leave no dataset behind: the job has failed and its directory goes.
 */
public final class CompactStep implements Step {
  @Override
  public StepResult run(final StepFile step, final Watchdog watchdog) throws Exception {
    final Path location = step.dataset();
    final Path packed = step.path().resolveSibling(step.stepId() + ".packed.tmp");
    final long before = WorkingDataset.sizeOnDisk(location);
    WorkingDataset.delete(packed);
    try {
      final DatasetGraph dataset = WorkingDataset.open(location);
      try {
        final DatasetGraph copy = WorkingDataset.create(packed);
        try {
          PackedCopy.copy(dataset, copy);
        } finally {
          WorkingDataset.release(copy);
        }
      } finally {
        WorkingDataset.release(dataset);
      }
      WorkingDataset.delete(location);
      Files.move(packed, location, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      WorkingDataset.delete(packed);
    }
    return StepResult.succeeded("COMPACTED")
        .detail("bytesBefore", before)
        .detail("bytesAfter", WorkingDataset.sizeOnDisk(location));
  }
}
