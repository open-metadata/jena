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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskUseTest {
  private static final long TTL = 1_000;

  @TempDir Path root;

  private final AtomicLong now = new AtomicLong(10_000);
  private final List<Runnable> pending = new ArrayList<>();

  @Test
  void countsEverythingButTheUploadsAndHoldsTheFigureUntilItIsStale() throws Exception {
    Files.write(Files.createDirectories(root.resolve("jobs/a")).resolve("spool"), new byte[100]);
    Files.write(Files.createDirectories(root.resolve("snapshots")).resolve("s"), new byte[20]);
    Files.write(
        Files.createDirectories(root.resolve(JobInputs.DIRECTORY)).resolve("a".repeat(64)),
        new byte[5_000]);
    final DiskUse use = new DiskUse(root, now::get, TTL, pending::add);
    use.measure();
    assertEquals(120, use.outsideUploads());

    Files.write(root.resolve("jobs/a/more"), new byte[30]);
    now.addAndGet(TTL - 1);
    assertEquals(120, use.outsideUploads(), "within the TTL nothing is re-measured");
    assertEquals(0, pending.size());

    now.addAndGet(1);
    assertEquals(120, use.outsideUploads(), "a stale read answers at once with the held figure");
    assertEquals(1, pending.size());
    assertEquals(120, use.outsideUploads());
    assertEquals(1, pending.size(), "one refresh at a time");

    pending.remove(0).run();
    assertEquals(150, use.outsideUploads());
    assertEquals(0, pending.size());
  }
}
