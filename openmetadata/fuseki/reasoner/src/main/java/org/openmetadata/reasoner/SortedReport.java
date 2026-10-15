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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The canonical report's lines in order, keeping only the first {@code limit}: a report lists every
 * fact for tests and diagnosis, so it would otherwise repeat an output graph that lives on disk.
 */
public final class SortedReport {
  private final int limit;
  private final TreeSet<String> lines = new TreeSet<>();
  private long omitted;

  public SortedReport(final int limit) {
    this.limit = limit;
  }

  public void add(final String line) {
    lines.add(line);
    if (lines.size() > limit) {
      lines.pollLast();
      omitted++;
    }
  }

  public List<String> lines(final String outcome) {
    final List<String> report = new ArrayList<>(lines.size() + 2);
    report.add(outcome);
    report.addAll(lines);
    if (omitted > 0) {
      report.add("OMITTED " + omitted + " lines beyond the first " + limit);
    }
    return report;
  }
}
