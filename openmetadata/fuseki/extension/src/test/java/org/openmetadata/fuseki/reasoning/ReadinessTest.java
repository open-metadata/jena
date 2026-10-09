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
package org.openmetadata.fuseki.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The memory budget against cgroup v2, cgroup v1 and physical-memory files under a fake root. */
class ReadinessTest {
  private static final long GIB = Sizes.GIB;
  private static final long MIB = Sizes.MIB;
  @TempDir Path root;

  @Test
  void readyWhenTheLimitHoldsTheBudgetWithItsMargin() throws IOException {
    cgroupV2(Long.toString(8 * GIB), 0);
    // H_f 2g + O_f 1g + H_w 2g + O_w 512m + C_min 0 = 5.5g; / 0.9 = 6.11g.
    final Readiness readiness = evaluate(settings("2g", "0"), false);
    assertEquals(Readiness.Status.READY, readiness.status(), readiness.summary());
    assertEquals(Math.ceilDiv(11 * GIB / 2 * 10, 9), readiness.requiredBytes());
    assertEquals(8 * GIB, readiness.availableBytes());
    assertEquals("cgroup v2 memory.max", readiness.limitSource());
    assertEquals(GIB, readiness.terms().get("fusekiOffHeap"));
  }

  @Test
  void notReadyReportsRequiredAndAvailableBytes() throws IOException {
    cgroupV2(Long.toString(4 * GIB), 0);
    final Readiness readiness = evaluate(settings("2g", "0"), false);
    assertEquals(Readiness.Status.NOT_READY, readiness.status());
    assertFalse(readiness.admits());
    assertEquals(4 * GIB, readiness.availableBytes());
    assertTrue(readiness.requiredBytes() > readiness.availableBytes());
    assertTrue(readiness.reasons().get(0).contains("is below the"), readiness.reasons().toString());
    assertEquals("NOT_READY", readiness.toJson().getString("status"));
  }

  @Test
  void pageCacheFloorDefaultsToHalfTheServingDatasetsOnDisk() throws IOException {
    cgroupV2(Long.toString(64 * GIB), 0);
    final Path databases = root.resolve("fuseki/databases/openmetadata/Data-0001");
    Files.createDirectories(databases);
    Files.write(databases.resolve("nodes.dat"), new byte[4 * 1024 * 1024]);
    final Readiness readiness = evaluate(settings("2g", null), false);
    assertEquals(2 * MIB, readiness.terms().get("pageCacheFloor"));
  }

  @Test
  void anUnlimitedCgroupFallsBackToPhysicalMemory() throws IOException {
    cgroupV2("max", 0);
    meminfo(16 * GIB);
    final Readiness readiness = evaluate(settings("2g", "0"), false);
    assertEquals(16 * GIB, readiness.availableBytes());
    assertEquals("physical memory (no container limit)", readiness.limitSource());
  }

  @Test
  void cgroupV1IsReadWhenV2IsAbsent() throws IOException {
    final Path v1 = Files.createDirectories(root.resolve("sys/fs/cgroup/memory"));
    Files.writeString(v1.resolve("memory.limit_in_bytes"), Long.toString(12 * GIB));
    Files.writeString(v1.resolve("memory.stat"), "cache 1\nrss 5\ntotal_rss " + GIB + "\n");
    final Readiness readiness = evaluate(settings("2g", "0"), true);
    assertEquals("cgroup v1 memory.limit_in_bytes", readiness.limitSource());
    assertEquals(GIB, readiness.anonymousBytes());

    Files.writeString(v1.resolve("memory.limit_in_bytes"), "9223372036854771712");
    meminfo(32 * GIB);
    assertEquals(32 * GIB, evaluate(settings("2g", "0"), false).availableBytes());
  }

  @Test
  void beforeAStepFusekiMemoryBeyondItsBudgetBlocksTheWorker() throws IOException {
    cgroupV2(Long.toString(64 * GIB), 4 * GIB);
    final Readiness readiness = evaluate(settings("2g", "0"), true);
    assertEquals(Readiness.Status.NOT_READY, readiness.status());
    assertTrue(readiness.reasons().get(0).contains("off-heap has outgrown O_f"));

    cgroupV2(Long.toString(64 * GIB), 2 * GIB);
    assertTrue(evaluate(settings("2g", "0"), true).admits());
  }

  @Test
  void theCommandLineDoesNotCountItsOwnMemoryAsFusekis() throws IOException {
    cgroupV2(Long.toString(64 * GIB), 3 * GIB + 100 * MIB);
    Files.createDirectories(root.resolve("proc/self"));
    Files.writeString(root.resolve("proc/self/status"), "Name:\tjava\nRssAnon:\t  204800 kB\n");
    final ReasoningSettings settings = settings("2g", "0");
    assertFalse(Readiness.evaluate(settings, memory(), -1, true, false).admits());
    assertTrue(Readiness.evaluate(settings, memory(), -1, true, true).admits());
  }

  @Test
  void disabledReasoningIsReportedAsSuch() throws IOException {
    cgroupV2(Long.toString(64 * GIB), 0);
    final Map<String, String> environment = new HashMap<>();
    environment.put("FUSEKI_BASE", root.resolve("fuseki").toString());
    final Readiness readiness =
        Readiness.evaluate(
            ReasoningSettings.fromEnvironment(environment), memory(), -1, false, false);
    assertEquals(Readiness.Status.DISABLED, readiness.status());
    assertFalse(readiness.admits());
  }

  private Readiness evaluate(final ReasoningSettings settings, final boolean beforeStep) {
    return Readiness.evaluate(settings, memory(), -1, beforeStep, false);
  }

  private ContainerMemory memory() {
    return new ContainerMemory(root);
  }

  private ReasoningSettings settings(final String fusekiHeap, final String floor) {
    final Map<String, String> environment = new HashMap<>();
    environment.put("OPENMETADATA_REASONING_ENABLED", "true");
    environment.put("FUSEKI_HEAP", fusekiHeap);
    environment.put("FUSEKI_BASE", root.resolve("fuseki").toString());
    if (floor != null) {
      environment.put("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", floor);
    }
    return ReasoningSettings.fromEnvironment(environment);
  }

  private void cgroupV2(final String max, final long anon) throws IOException {
    final Path cgroup = Files.createDirectories(root.resolve("sys/fs/cgroup"));
    Files.writeString(cgroup.resolve("memory.max"), max + "\n");
    Files.writeString(cgroup.resolve("memory.stat"), "anon " + anon + "\nfile 123\n");
  }

  private void meminfo(final long bytes) throws IOException {
    Files.createDirectories(root.resolve("proc"));
    Files.writeString(
        root.resolve("proc/meminfo"), "MemTotal:       " + bytes / 1024 + " kB\nMemFree: 1 kB\n");
  }
}
