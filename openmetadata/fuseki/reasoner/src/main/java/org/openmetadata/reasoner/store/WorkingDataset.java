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
 * the Fuseki JVM, and TDB2's single-process lock would refuse a second process anyway. Node caches
 * are small, so the dataset's heap share stays small next to the reasoner's.
 */
public final class WorkingDataset {
  private WorkingDataset() {}

  static StoreParams storeParams() {
    return StoreParams.getSmallStoreParams();
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
    return DatabaseMgr.connectDatasetGraph(Location.create(location), storeParams());
  }

  /** Opens an existing dataset; an absent one is an error, never silently created empty. */
  public static DatasetGraph open(final Path location) throws InvalidStepException {
    if (!isDatabase(location)) {
      throw new InvalidStepException("No TDB2 dataset at " + location + "; run a LOAD step first");
    }
    return DatabaseMgr.connectDatasetGraph(Location.create(location), storeParams());
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
