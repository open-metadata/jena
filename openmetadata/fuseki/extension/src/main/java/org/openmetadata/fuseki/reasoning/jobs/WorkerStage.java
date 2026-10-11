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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.openmetadata.fuseki.reasoning.AtomicFiles;
import org.openmetadata.fuseki.reasoning.StepOutcome;

/**
 * A worker step: writes the step file the planner builds into the job directory, runs it through
 * the launcher, and turns its outcome into the protocol's terms. While another worker holds the
 * container's lock, such as a step run by hand with bin/reasoner, it waits, up to a bound.
 */
public final class WorkerStage implements Stage {
  static final long BUSY_RETRY_MILLIS = 1_000;
  static final long DEFAULT_MAX_BUSY_WAIT_MILLIS = TimeUnit.MINUTES.toMillis(10);

  private final String name;
  private final Function<Context, JsonObject> stepFile;
  private final long maxBusyWaitMillis;

  /**
   * @param name the step's id and file name in the job directory
   * @param stepFile builds the step file; its stepId is set to {@code name}
   */
  public WorkerStage(final String name, final Function<Context, JsonObject> stepFile) {
    this(name, stepFile, DEFAULT_MAX_BUSY_WAIT_MILLIS);
  }

  WorkerStage(
      final String name,
      final Function<Context, JsonObject> stepFile,
      final long maxBusyWaitMillis) {
    this.name = name;
    this.stepFile = stepFile;
    this.maxBusyWaitMillis = maxBusyWaitMillis;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Result run(final Context context) throws IOException, InterruptedException {
    final JsonObject step = stepFile.apply(context);
    step.put("stepId", name);
    final Path file = context.directory().resolve(name + ".json");
    AtomicFiles.write(file, JSON.toString(step));
    final long waitingSince = System.nanoTime();
    while (true) {
      if (context.cancelled().getAsBoolean()) {
        return Result.stopped();
      }
      final StepOutcome outcome = context.launcher().run(file, context.cancelled());
      if (outcome.status() != StepOutcome.Status.BUSY) {
        return result(name, outcome);
      }
      if (System.nanoTime() - waitingSince > TimeUnit.MILLISECONDS.toNanos(maxBusyWaitMillis)) {
        return Result.failed(
            Problem.of(
                    Problem.Code.NOT_READY,
                    "Another reasoner worker held the container's worker lock for more than "
                        + maxBusyWaitMillis
                        + " ms")
                .atStep(name));
      }
      Thread.sleep(Math.min(BUSY_RETRY_MILLIS, maxBusyWaitMillis));
    }
  }

  /** The protocol's reading of a step's outcome. */
  static Result result(final String step, final StepOutcome outcome) {
    final String reason = outcome.reason() == null ? "" : outcome.reason();
    final Problem problem =
        switch (outcome.status()) {
          case SUCCEEDED -> null;
          case NOT_READY -> Problem.of(Problem.Code.NOT_READY, reason);
          case BUSY -> Problem.of(Problem.Code.NOT_READY, reason);
          case INCOMPLETE ->
              reason.startsWith("MEMORY_BUDGET")
                  ? Problem.of(Problem.Code.MEMORY_BUDGET_EXHAUSTED, reason)
                  : Problem.of(Problem.Code.LIMIT_EXCEEDED, reason).withSubject("maxJobSeconds");
          case INTERRUPTED ->
              reason.equals("CANCELLED") ? null : Problem.of(Problem.Code.WORKER_LOST, reason);
          case FAILED -> failure(reason, outcome.manifest());
        };
    if (outcome.status() == StepOutcome.Status.SUCCEEDED) {
      // The step's own outcome, such as CLASSIFIED, is not the job's: the plan reads the step's
      // result manifest from the job directory and decides what it answers.
      return Result.completed();
    }
    if (problem == null) {
      return Result.stopped();
    }
    return Result.failed(problem.atStep(step));
  }

  /** A rejected input keeps the worker's first problem as the message. */
  private static Problem failure(final String reason, final JsonObject manifest) {
    final JsonObject first = firstProblem(manifest);
    final String detail = first == null ? reason : reason + ": " + first.getString("message");
    if (reason.startsWith("NOT_OWL2_DL")) {
      return Problem.of(Problem.Code.PROFILE_VIOLATION, detail);
    }
    if (reason.startsWith("LIMIT_EXCEEDED")) {
      return Problem.of(Problem.Code.LIMIT_EXCEEDED, detail);
    }
    if (reason.startsWith("INVALID_INPUT")) {
      final boolean missingImport =
          first != null && "MISSING_IMPORT".equals(first.getString("code"));
      return Problem.of(
          missingImport ? Problem.Code.MISSING_IMPORT : Problem.Code.PROFILE_VIOLATION, detail);
    }
    if (reason.startsWith("PROTOCOL_ERROR")) {
      return Problem.of(Problem.Code.WORKER_LOST, detail);
    }
    return Problem.of(Problem.Code.INTERNAL, detail);
  }

  private static JsonObject firstProblem(final JsonObject manifest) {
    if (manifest == null) {
      return null;
    }
    final JsonValue problems = manifest.get("problems");
    if (problems == null || !problems.isArray() || problems.getAsArray().isEmpty()) {
      return null;
    }
    final JsonValue first = problems.getAsArray().get(0);
    return first.isObject() ? first.getAsObject() : null;
  }
}
