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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.params.StoreParams;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingDatasetTest {
  private static final long MIB = 1L << 20;

  @TempDir Path directory;

  @Test
  void nodeCachesGrowWithTheHeapBetweenTdbsSmallAndDefaultSizes() {
    final StoreParams defaultHeap = WorkingDataset.storeParams(1024 * MIB);
    assertEquals(262_144, defaultHeap.getNodeId2NodeCacheSize());
    assertEquals(65_536, defaultHeap.getNode2NodeIdCacheSize());
    final StoreParams smallest = WorkingDataset.storeParams(16 * MIB);
    assertEquals(10_000, smallest.getNodeId2NodeCacheSize());
    assertEquals(10_000, smallest.getNode2NodeIdCacheSize());
    final StoreParams largest = WorkingDataset.storeParams(64 * 1024 * MIB);
    assertEquals(1_000_000, largest.getNodeId2NodeCacheSize());
    assertEquals(200_000, largest.getNode2NodeIdCacheSize());
    assertEquals(
        StoreParams.getSmallStoreParams().getBlockReadCacheSize(),
        largest.getBlockReadCacheSize(),
        "everything else stays TDB2's small store parameters");
  }

  @Test
  void aStepOpensTheDatasetWithItsOwnCachesWhateverTheLoadRecorded() throws Exception {
    final Path location = directory.resolve("working");
    final int heapSized =
        WorkingDataset.storeParams(Runtime.getRuntime().maxMemory()).getNodeId2NodeCacheSize();
    assertEquals(heapSized, nodeCache(WorkingDataset.create(location)));
    assertEquals(10_000, nodeCache(WorkingDataset.openForReasoner(location)));
    assertEquals(heapSized, nodeCache(WorkingDataset.open(location)));
  }

  private static int nodeCache(final DatasetGraph dataset) {
    try {
      return TDBInternal.getDatasetGraphTDB(dataset).getStoreParams().getNodeId2NodeCacheSize();
    } finally {
      WorkingDataset.release(dataset);
    }
  }
}
