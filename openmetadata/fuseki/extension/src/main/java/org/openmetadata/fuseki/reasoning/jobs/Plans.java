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
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;

/**
 * The steps each operation takes, and what capabilities report this build can perform. Every plan
 * starts by capturing its input from the serving dataset, which this build does not implement yet,
 * so every job fails at that step with NOT_IMPLEMENTED; the later steps are listed so progress
 * reports the whole plan. Capture, the worker steps that consume it, and snapshot publication
 * replace these placeholders in the following changes, and each operation becomes AVAILABLE in
 * {@link #operations()} once it runs and its limits are enforced.
 */
final class Plans {
  static final String CAPTURE_MISSING =
      "Capturing input from the serving dataset is not available in this build; it arrives with"
          + " reasoning snapshots.";
  static final String RESULTS_MISSING =
      "Snapshot results are not available in this build; they arrive with reasoning snapshots.";
  static final String QUERY_MISSING =
      "Snapshot queries are not available in this build; they arrive with reasoning snapshots.";

  private Plans() {}

  static Stage.Planner current() {
    return (job, request) ->
        switch (job.operation()) {
          case REFRESH -> stages("capture", "load", "classify", "rules", "compact", "publish");
          case CHECK -> stages("capture", "load", "classify");
          case EXPLAIN -> stages("capture", "load", "explain");
        };
  }

  /**
   * The capabilities' operations list for this build. OpenMetadata reads an operation that is not
   * AVAILABLE as one it must not use, so nothing is claimed before it runs.
   */
  static JsonArray operations() {
    final JsonArray operations = new JsonArray();
    for (final JobRequest.Operation operation : JobRequest.Operation.values()) {
      operations.add(notImplemented(operation.name(), CAPTURE_MISSING));
    }
    operations.add(notImplemented("RESULTS", RESULTS_MISSING));
    operations.add(notImplemented("QUERY", QUERY_MISSING));
    return operations;
  }

  private static JsonObject notImplemented(final String operation, final String reason) {
    final JsonObject support = new JsonObject();
    support.put("operation", operation);
    support.put("availability", "NOT_IMPLEMENTED");
    support.put("reason", reason);
    return support;
  }

  private static List<Stage> stages(final String... names) {
    return Arrays.stream(names)
        .map(
            name ->
                Stage.unavailable(
                    name,
                    name.equals("capture")
                        ? CAPTURE_MISSING
                        : "The " + name + " step is not available in this build."))
        .toList();
  }
}
