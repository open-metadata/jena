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
package org.openmetadata.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import org.apache.jena.atlas.json.JSON;
import org.junit.jupiter.api.Test;

class LimitsTest {
  private static final long GIB = 1L << 30;

  @Test
  void theDefaultsForTheDefaultWorkerHeapFitWhatItWasMeasuredToHold() {
    final Limits limits = Limits.forHeap(2 * GIB);
    assertEquals(300_000, limits.maxClosureTriples());
    assertEquals(200_000, limits.maxLogicalAxioms());
    assertEquals(50_000, limits.maxIndividuals());
    assertEquals(1_000_000, limits.maxOutputTriples());
    assertEquals(1_000, limits.maxChecks());
    assertEquals(1_000, limits.maxExplainChecks());
    assertEquals(32, limits.maxExpressionDepth());
  }

  @Test
  void theDefaultsFollowTheConfiguredHeapNotTheUsableOne() {
    final long configured =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
            .getVMOption("MaxHeapSize")
            .getValue()
            .transform(Long::parseLong);
    assertEquals(configured, Limits.configuredHeap());
    assertTrue(Limits.configuredHeap() >= Runtime.getRuntime().maxMemory());
    assertEquals(Limits.forHeap(configured), Limits.defaults());
  }

  @Test
  void heapBoundLimitsGrowWithTheHeapAndTheOthersDoNot() {
    final Limits two = Limits.forHeap(2 * GIB);
    final Limits eight = Limits.forHeap(8 * GIB);
    assertEquals(4 * two.maxClosureTriples(), eight.maxClosureTriples());
    assertEquals(4 * two.maxLogicalAxioms(), eight.maxLogicalAxioms());
    assertEquals(4 * two.maxIndividuals(), eight.maxIndividuals());
    assertEquals(4 * two.maxOutputTriples(), eight.maxOutputTriples());
    assertEquals(two.maxInputBytes(), eight.maxInputBytes());
    assertEquals(two.maxExpressionDepth(), eight.maxExpressionDepth());
    assertEquals(two.maxChecks(), eight.maxChecks());
    assertTrue(Limits.forHeap(1).maxClosureTriples() >= 1);
  }

  @Test
  void aStepFileOverridesOnlyWhatItSets() throws Exception {
    final Limits limits = Limits.fromJson(JSON.parseAny("{\"maxChecks\": 7}"));
    assertEquals(7, limits.maxChecks());
    assertEquals(Limits.defaults().maxClosureTriples(), limits.maxClosureTriples());
    assertEquals(3, Limits.fromJson(JSON.parseAny("{\"maxChecks\": 3.0}")).maxChecks());
    assertEquals(3000, Limits.fromJson(JSON.parseAny("{\"maxChecks\": 3e3}")).maxChecks());
  }

  @Test
  void fractionalNegativeAndOutOfRangeLimitsAreInvalid() {
    for (final String value :
        new String[] {"1.5", "0", "-3", "9223372036854775808", "1e40", "\"10\"", "true"}) {
      final InvalidStepException error =
          assertThrows(
              InvalidStepException.class,
              () -> Limits.fromJson(JSON.parseAny("{\"maxOutputTriples\": " + value + "}")),
              value);
      assertTrue(error.getMessage().startsWith("maxOutputTriples"), error.getMessage());
    }
  }
}
