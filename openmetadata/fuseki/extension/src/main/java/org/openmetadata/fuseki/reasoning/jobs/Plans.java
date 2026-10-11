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

import java.util.Arrays;
import java.util.List;

/**
 * The steps each operation takes. Every plan starts by capturing its input from the serving
 * dataset, which this build does not implement yet, so every job fails at that step with a problem
 * that says so; the later steps are listed so progress reports the whole plan. Capture, the worker
 * steps that consume it, and snapshot publication replace these placeholders in the following
 * changes.
 */
final class Plans {
  static final String CAPTURE_MISSING =
      "capturing input from the serving dataset is not available in this build; it arrives with"
          + " reasoning snapshots";

  private Plans() {}

  static Stage.Planner current() {
    return (job, request) ->
        switch (job.operation()) {
          case REFRESH -> stages("capture", "load", "classify", "rules", "compact", "publish");
          case CHECK -> stages("capture", "load", "classify");
          case EXPLAIN -> stages("capture", "load", "explain");
        };
  }

  private static List<Stage> stages(final String... names) {
    return Arrays.stream(names)
        .map(
            name ->
                Stage.unavailable(
                    name,
                    name.equals("capture")
                        ? CAPTURE_MISSING
                        : "the " + name + " step is not available in this build"))
        .toList();
  }
}
