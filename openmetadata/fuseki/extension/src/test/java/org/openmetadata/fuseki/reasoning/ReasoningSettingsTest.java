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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReasoningSettingsTest {
  @Test
  void defaultsLeaveReasoningOffWithTheDesignStartingValues() {
    final ReasoningSettings settings = ReasoningSettings.fromEnvironment(Map.of());
    assertFalse(settings.enabled());
    assertEquals(2 * Sizes.GIB, settings.workerHeap());
    assertEquals(512 * Sizes.MIB, settings.workerOffHeap());
    assertEquals(-1, settings.pageCacheFloorBytes());
    assertEquals(50, settings.pageCacheFloorPercent());
    assertEquals(4 * Sizes.GIB, settings.fusekiHeap());
    assertEquals(256 * Sizes.MIB, settings.fusekiDirectMemory());
    assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), settings.cpus());
    assertEquals("/fuseki/reasoning", settings.reasoningRoot().toString());
    assertEquals(
        "/jena-fuseki/fuseki-server.jar:/jena-fuseki/reasoner/*", settings.workerClasspath());
  }

  @Test
  void readsEveryVariable() {
    final ReasoningSettings settings =
        ReasoningSettings.fromEnvironment(
            Map.of(
                "OPENMETADATA_REASONING_ENABLED", "true",
                "OPENMETADATA_REASONER_HEAP", "1g",
                "OPENMETADATA_REASONER_OFF_HEAP", "768m",
                "OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "3g",
                "OPENMETADATA_REASONER_CPUS", "3",
                "FUSEKI_HEAP", "8g"));
    assertTrue(settings.enabled());
    assertEquals(Sizes.GIB, settings.workerHeap());
    assertEquals(768 * Sizes.MIB, settings.workerOffHeap());
    assertEquals(3 * Sizes.GIB, settings.pageCacheFloorBytes());
    assertEquals(3, settings.cpus());
    assertEquals(8 * Sizes.GIB, settings.fusekiHeap());
  }

  @Test
  void fusekiOffHeapIsItsDirectLimitPlusTheRestOfItsNativeMemory() {
    final long direct = ReasoningSettings.DEFAULT_FUSEKI_DIRECT_MEMORY;
    assertEquals(Sizes.GIB, ReasoningSettings.fusekiOffHeap(4 * Sizes.GIB, direct));
    assertEquals(3 * Sizes.GIB / 2, ReasoningSettings.fusekiOffHeap(8 * Sizes.GIB, direct));
    assertEquals(
        3 * Sizes.GIB / 4 + 4 * Sizes.GIB,
        ReasoningSettings.fusekiOffHeap(4 * Sizes.GIB, 0),
        "the JVM's default direct limit is the heap");
  }

  @Test
  void jvmArgsSetFusekiDirectMemoryAsTheEntrypointLeavesIt() {
    assertEquals(
        Sizes.GIB,
        ReasoningSettings.fromEnvironment(
                Map.of("JVM_ARGS", "-Xmx4g -XX:MaxDirectMemorySize=64m -XX:MaxDirectMemorySize=1g"))
            .fusekiDirectMemory());
    assertEquals(
        0,
        ReasoningSettings.fromEnvironment(Map.of("JVM_ARGS", "-XX:MaxDirectMemorySize=0"))
            .fusekiDirectMemory());
    assertEquals(
        ReasoningSettings.DEFAULT_FUSEKI_DIRECT_MEMORY,
        ReasoningSettings.fromEnvironment(Map.of("JVM_ARGS", "-Xmx4g")).fusekiDirectMemory());
  }

  @Test
  void pageCacheFloorAcceptsAPercentageOrZero() {
    assertEquals(
        25,
        ReasoningSettings.fromEnvironment(Map.of("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "25%"))
            .pageCacheFloorPercent());
    assertEquals(
        0,
        ReasoningSettings.fromEnvironment(Map.of("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "0"))
            .pageCacheFloorBytes());
  }

  @Test
  void jvmArgsReplaceFusekiHeapAsTheEntrypointDoes() {
    assertEquals(
        6 * Sizes.GIB,
        ReasoningSettings.fromEnvironment(
                Map.of("FUSEKI_HEAP", "2g", "JVM_ARGS", "-Xms1g -Xmx3g -Dx=y -Xmx6g"))
            .fusekiHeap());
    assertEquals(
        -1,
        ReasoningSettings.fromEnvironment(Map.of("JVM_ARGS", "-XX:+UseG1GC")).fusekiHeap(),
        "without -Xmx the JVM default applies, a quarter of the limit");
  }

  @Test
  void invalidValuesNameTheirVariable() {
    assertRejected("OPENMETADATA_REASONING_ENABLED", "yes");
    assertRejected("OPENMETADATA_REASONER_HEAP", "lots");
    assertRejected("OPENMETADATA_REASONER_HEAP", "8m");
    assertRejected("OPENMETADATA_REASONER_HEAP", "-1g");
    assertRejected("OPENMETADATA_REASONER_OFF_HEAP", "256m");
    assertRejected("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "150%");
    assertRejected("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "half");
    assertRejected("OPENMETADATA_REASONER_CPUS", "0");
    assertRejected("OPENMETADATA_REASONER_CPUS", "two");
  }

  private static void assertRejected(final String variable, final String value) {
    final IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ReasoningSettings.fromEnvironment(Map.of(variable, value)));
    assertTrue(error.getMessage().startsWith(variable), error.getMessage());
  }
}
