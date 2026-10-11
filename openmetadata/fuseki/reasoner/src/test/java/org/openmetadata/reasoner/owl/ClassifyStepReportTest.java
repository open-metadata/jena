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
package org.openmetadata.reasoner.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The canonical report never grows past its line limit, whatever the order facts arrive in. */
class ClassifyStepReportTest {
  @Test
  void theReportKeepsTheFirstLinesInOrderAndCountsTheRest() {
    final ClassifyStep.Report report = new ClassifyStep.Report(3);
    for (final String line : List.of("TYPE e", "SUBCLASS b", "VALUE f", "SUBCLASS a", "TYPE c")) {
      report.add(line);
    }
    assertEquals(
        List.of(
            "OUTCOME CLASSIFIED",
            "SUBCLASS a",
            "SUBCLASS b",
            "TYPE c",
            "OMITTED 2 lines beyond the first 3"),
        report.lines("OUTCOME CLASSIFIED"));
  }

  @Test
  void aReportWithinItsLimitIsComplete() {
    final ClassifyStep.Report report = new ClassifyStep.Report(3);
    report.add("TYPE b");
    report.add("SUBCLASS a");
    assertEquals(
        List.of("OUTCOME CLASSIFIED", "SUBCLASS a", "TYPE b"), report.lines("OUTCOME CLASSIFIED"));
  }
}
