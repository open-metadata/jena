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
package org.openmetadata.fuseki.reasoning.jobs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bytes the reasoning root uses outside the uploads directory (jobs, working datasets and
 * snapshots), measured by walking the tree.
 *
 * <p>Those bytes change under writers this class cannot see, such as a TDB2 dataset growing, so the
 * figure is a cache with bounded staleness: it is measured once at {@link #measure()} and then
 * re-measured on {@code executor} when a read finds it older than {@code ttlMillis}. A read always
 * returns the held figure at once, so an upload never waits for the walk. A figure can therefore
 * miss up to {@code ttlMillis} of change; uploads are the only readers, and they add their own
 * exactly counted bytes on top.
 */
final class DiskUse {
  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private final Path root;
  private final LongSupplier clock;
  private final long ttlMillis;
  private final Executor executor;
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private volatile long bytes;
  private volatile long measuredAt = Long.MIN_VALUE;

  DiskUse(
      final Path root, final LongSupplier clock, final long ttlMillis, final Executor executor) {
    this.root = root;
    this.clock = clock;
    this.ttlMillis = ttlMillis;
    this.executor = executor;
  }

  /** Walks the root now, on the caller's thread. */
  void measure() throws IOException {
    final long at = clock.getAsLong();
    bytes = JobStore.sizeOnDisk(root, JobInputs.DIRECTORY);
    measuredAt = at;
  }

  /** The held figure, which a stale read also refreshes in the background. */
  long outsideUploads() {
    if (clock.getAsLong() - measuredAt >= ttlMillis && refreshing.compareAndSet(false, true)) {
      try {
        executor.execute(this::refresh);
      } catch (final RuntimeException e) {
        refreshing.set(false);
        throw e;
      }
    }
    return bytes;
  }

  private void refresh() {
    try {
      measure();
    } catch (final IOException | RuntimeException e) {
      LOG.warn("Cannot re-measure the reasoning data: {}", e.toString());
    } finally {
      refreshing.set(false);
    }
  }
}
