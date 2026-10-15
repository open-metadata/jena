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
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.fuseki.reasoning.WorkerLauncher;

/**
 * One step of a job: work in the Fuseki JVM such as capturing input, or a worker step run through
 * the launcher. A stage that can stop early checks {@link Context#cancelled()}.
 */
public interface Stage {
  String name();

  Result run(Context context) throws IOException, InterruptedException;

  /** What a stage may use: its job's directory and request, and the container's launcher. */
  record Context(
      Job job,
      Path directory,
      JsonObject request,
      WorkerLauncher launcher,
      BooleanSupplier cancelled) {}

  /**
   * How a stage ended: completed, possibly with the job's outcome; answered, which ends the job
   * SUCCEEDED with its outcome now, possibly INCOMPLETE with the problem that stopped it; failed
   * with a problem; or stopped after a cancellation. {@code engines} are the engine builds that
   * computed the result, recorded with the job's input; null when the stage ran none. {@code
   * snapshotId} is the snapshot a refresh's publish stage made current.
   */
  record Result(
      String outcome,
      Problem problem,
      boolean cancelled,
      boolean answered,
      JsonArray engines,
      UUID snapshotId) {
    public static Result completed() {
      return new Result(null, null, false, false, null, null);
    }

    public static Result completed(final String outcome) {
      return new Result(outcome, null, false, false, null, null);
    }

    public static Result answered(final String outcome, final Problem problem) {
      return new Result(outcome, problem, false, true, null, null);
    }

    public static Result failed(final Problem problem) {
      return new Result(null, problem, false, false, null, null);
    }

    public static Result stopped() {
      return new Result(null, null, true, false, null, null);
    }

    public Result withEngines(final JsonArray computedBy) {
      return new Result(outcome, problem, cancelled, answered, computedBy, snapshotId);
    }

    public Result withSnapshot(final UUID published) {
      return new Result(outcome, problem, cancelled, answered, engines, published);
    }
  }

  /** Plans the stages of a job from its request. */
  @FunctionalInterface
  interface Planner {
    List<Stage> plan(Job job, JsonObject request);
  }

  /**
   * A stage this build does not implement yet. It fails the job with NOT_IMPLEMENTED rather than
   * NOT_READY, because retrying cannot help until the store is upgraded, and says what is missing.
   */
  static Stage unavailable(final String name, final String missing) {
    return new Stage() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Result run(final Context context) {
        return Result.failed(Problem.of(Problem.Code.NOT_IMPLEMENTED, missing).atStep(name));
      }
    };
  }
}
