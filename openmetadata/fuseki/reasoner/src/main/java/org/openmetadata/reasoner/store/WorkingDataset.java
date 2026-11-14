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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.params.StoreParams;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.openmetadata.reasoner.InvalidStepException;

/**
 * The job's private TDB2 dataset. The worker never opens anything else: serving datasets stay in
 * the Fuseki JVM, and TDB2's single-process lock would refuse a second process anyway.
 *
 * <p>TDB2 bounds its node caches by entries, and a cached node measured about 220 bytes on a
 * catalog (IRIs and short literals; a long literal costs its length). The steps that read and write
 * the store with little else on the heap get caches sized from the heap, about a twentieth of it at
 * that size: {@value #HEAP_BYTES_PER_NODE} bytes of heap for each node the id-to-node cache may
 * hold, and four times that for each of the node-to-id cache, between TDB2's small store parameters
 * and its defaults. The steps that run an OWL engine keep the small caches, since their admission
 * limits were measured with them and the engine needs the heap.
 */
public final class WorkingDataset {
  private WorkingDataset() {}

  static final long HEAP_BYTES_PER_NODE = 4_096;
  static final int MIN_NODE_CACHE = 10_000;
  static final int MAX_ID_TO_NODE_CACHE = 1_000_000;
  static final int MAX_NODE_TO_ID_CACHE = 200_000;

  /**
   * Cache sizes are set explicitly: TDB2 records the creating process's in the dataset, and only an
   * explicit value overrides the recorded one when a later step opens it.
   */
  static StoreParams storeParams(final long heapBytes) {
    return StoreParams.builder("working", StoreParams.getSmallStoreParams())
        .nodeId2NodeCacheSize(entries(heapBytes / HEAP_BYTES_PER_NODE, MAX_ID_TO_NODE_CACHE))
        .node2NodeIdCacheSize(entries(heapBytes / (4 * HEAP_BYTES_PER_NODE), MAX_NODE_TO_ID_CACHE))
        .build();
  }

  private static int entries(final long wanted, final int most) {
    return (int) Math.max(MIN_NODE_CACHE, Math.min(most, wanted));
  }

  private static StoreParams heapSized() {
    return storeParams(Runtime.getRuntime().maxMemory());
  }

  /** Creates the dataset for a LOAD step, which must start from nothing. */
  public static DatasetGraph create(final Path location) throws InvalidStepException {
    if (Files.exists(location)) {
      if (!Files.isDirectory(location) || !isEmptyDirectory(location)) {
        throw new InvalidStepException(
            "LOAD needs a fresh dataset location, but " + location + " is not empty");
      }
    }
    try {
      Files.createDirectories(location);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return DatabaseMgr.connectDatasetGraph(Location.create(location), heapSized());
  }

  /** Opens an existing dataset; an absent one is an error, never silently created empty. */
  public static DatasetGraph open(final Path location) throws InvalidStepException {
    return open(location, heapSized());
  }

  /** Opens an existing dataset for a step that runs an OWL engine, with the small node caches. */
  public static DatasetGraph openForReasoner(final Path location) throws InvalidStepException {
    return open(location, storeParams(0));
  }

  private static DatasetGraph open(final Path location, final StoreParams params)
      throws InvalidStepException {
    if (!isDatabase(location)) {
      throw new InvalidStepException("No TDB2 dataset at " + location + "; run a LOAD step first");
    }
    return DatabaseMgr.connectDatasetGraph(Location.create(location), params);
  }

  /** Releases the dataset, so the process (or a test JVM) can open the location again. */
  public static void release(final DatasetGraph dataset) {
    TDBInternal.expel(dataset);
  }

  static boolean isDatabase(final Path location) {
    if (!Files.isDirectory(location)) {
      return false;
    }
    try (Stream<Path> entries = Files.list(location)) {
      return entries.anyMatch(
          entry -> Files.isDirectory(entry) && entry.getFileName().toString().startsWith("Data-"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static long sizeOnDisk(final Path location) {
    try (Stream<Path> files = Files.walk(location)) {
      return files
          .filter(Files::isRegularFile)
          .mapToLong(
              file -> {
                try {
                  return Files.size(file);
                } catch (final IOException e) {
                  return 0;
                }
              })
          .sum();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isEmptyDirectory(final Path location) {
    try (Stream<Path> entries = Files.list(location)) {
      return entries.findAny().isEmpty();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
