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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.fuseki.reasoning.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The steps each operation takes. CHECK and EXPLAIN capture the request's import closure from the
 * serving dataset, bulk-load it into a private working dataset in a worker, and classify or explain
 * there; the answer goes to the job's outcome, with its detail in {@code result.json} in the job
 * directory, which the RESULTS operation will serve. REFRESH needs snapshots and the governance
 * rules step, so it still fails at capture with NOT_IMPLEMENTED.
 *
 * <p>A job has {@link JobSettings#maxJobSeconds()} from its start: each worker step gets what is
 * left as its deadline. A check that reaches a limit or its time answers INCOMPLETE with the
 * problem, as the protocol defines; one whose input is invalid fails.
 */
final class Plans {
  static final String CAPTURE = "capture";
  static final String SPOOL = "capture.nq.gz";
  static final String RESULT = "result.json";
  static final String WORKING = "working";
  static final String CHECK_FILE = "check.ofn";

  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private Plans() {}

  static Stage.Planner current(final Operations operations, final ServingDatasets datasets) {
    return (job, request) -> {
      final long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(operations.jobSettings().maxJobSeconds());
      return switch (job.operation()) {
        case REFRESH ->
            List.of(
                Stage.unavailable(CAPTURE, Operations.REFRESH_MISSING),
                Stage.unavailable("load", Operations.REFRESH_MISSING),
                Stage.unavailable("classify", Operations.REFRESH_MISSING),
                Stage.unavailable("rules", Operations.REFRESH_MISSING),
                Stage.unavailable("compact", Operations.REFRESH_MISSING),
                Stage.unavailable("publish", Operations.REFRESH_MISSING));
        case CHECK ->
            List.of(
                capture(operations, datasets, deadline),
                load(operations, deadline),
                classify(operations, deadline));
        case EXPLAIN ->
            List.of(
                capture(operations, datasets, deadline),
                load(operations, deadline),
                explain(operations, deadline));
      };
    };
  }

  private static Stage capture(
      final Operations operations, final ServingDatasets datasets, final long deadline) {
    return stage(
        CAPTURE,
        context -> {
          final DatasetGraph dataset = datasets.get(context.job().dataset());
          if (dataset == null) {
            return Stage.Result.failed(
                Problem.of(
                        Problem.Code.INVALID_REQUEST,
                        "Dataset '" + context.job().dataset() + "' is no longer served")
                    .atStep(CAPTURE));
          }
          final long left = diskLeft(operations);
          final Capture.Bounds bounds =
              new Capture.Bounds(
                  Operations.MAX_IMPORTS,
                  operations.workerLimits().maxClosureTriples(),
                  left,
                  deadline);
          final JsonObject request = context.request();
          try {
            final Capture.Result captured =
                Capture.run(
                    dataset,
                    new Capture.Selection(
                        request.getString("ontologyIri"),
                        request.getString("ontologySelection"),
                        request.hasKey("ontologyDigest")
                            ? request.getString("ontologyDigest")
                            : null),
                    context.directory().resolve(SPOOL),
                    bounds,
                    context.cancelled());
            AtomicFiles.write(
                context.directory().resolve(CAPTURE + ".json"), JSON.toString(captured.toJson()));
            LOG.info(
                "Reasoning job {} captured {} manifests and {} graphs, {} triples, {} bytes in {}"
                    + " ms",
                context.job().id(),
                captured.manifests().size(),
                captured.graphs().size(),
                captured.triples(),
                captured.bytes(),
                captured.millis());
            return Stage.Result.completed();
          } catch (final Capture.Cancelled e) {
            return Stage.Result.stopped();
          } catch (final Capture.Refused e) {
            return answerOrFail(e.problem().atStep(CAPTURE));
          }
        });
  }

  private static Stage load(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "load",
            context -> {
              final JsonObject step = step("LOAD", deadline);
              final JsonArray inputs = new JsonArray();
              inputs.add(SPOOL);
              step.put("inputs", inputs);
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "load",
        context -> {
          final Stage.Result loaded = bounded(worker, context, deadline);
          if (loaded.problem() != null || loaded.cancelled()) {
            return loaded;
          }
          // A working dataset is far larger than its compressed spool (TDB2 allocates its files
          // in segments: 254 MB for a 1.7 MB spool of 225,000 quads), so the quota is checked
          // again once it exists, before anything reasons over it; the job's end removes it.
          final long quota =
              operations.jobSettings().diskQuota(operations.settings().reasoningRoot());
          final long used = JobStore.sizeOnDisk(operations.settings().reasoningRoot());
          if (used > quota) {
            return Stage.Result.answered(
                "INCOMPLETE",
                Problem.of(
                        Problem.Code.LIMIT_EXCEEDED,
                        "The working dataset brought the reasoning data to "
                            + used
                            + " bytes, over its quota of "
                            + quota)
                    .withSubject("maxDiskBytes")
                    .atStep("load"));
          }
          return loaded;
        });
  }

  private static Stage classify(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "classify",
            context -> {
              final JsonObject step = step("CLASSIFY", deadline);
              step.put("closure", closure(context));
              if (Files.exists(context.directory().resolve(CHECK_FILE))) {
                step.put("checks", CHECK_FILE);
              }
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "classify",
        context -> {
          final JsonObject check = context.request().getObj("check");
          final String axiom;
          try {
            axiom = FunctionalSyntax.check(check);
          } catch (final FunctionalSyntax.Inexpressible e) {
            return Stage.Result.failed(
                Problem.of(Problem.Code.INVALID_REQUEST, e.getMessage()).atStep("classify"));
          }
          if (axiom != null) {
            AtomicFiles.write(context.directory().resolve(CHECK_FILE), "Ontology(" + axiom + ")\n");
          }
          final Stage.Result ran = bounded(worker, context, deadline);
          if (ran.problem() != null || ran.cancelled()) {
            return ran;
          }
          return checkAnswer(context, check.getString("kind"));
        });
  }

  private static Stage explain(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "explain",
            context -> {
              final JsonObject step = step("EXPLAIN", deadline);
              step.put("closure", closure(context));
              final JsonObject request = context.request().getObj("explain");
              final JsonObject explain = new JsonObject();
              try {
                explain.put("axiom", FunctionalSyntax.statement(request.getObj("statement")));
              } catch (final FunctionalSyntax.Inexpressible e) {
                // JobRequest refuses such a request before it is queued.
                throw new IllegalStateException(e);
              }
              explain.put(
                  "maxJustifications",
                  request.hasKey("maxJustifications")
                      ? request.getNumber("maxJustifications").intValue()
                      : 1);
              step.put("explain", explain);
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "explain",
        context -> {
          final Stage.Result ran = bounded(worker, context, deadline);
          if (ran.problem() != null || ran.cancelled()) {
            return ran;
          }
          return explainAnswer(context);
        });
  }

  /** The job's answer to a check, from the classify step's manifest. */
  private static Stage.Result checkAnswer(final Stage.Context context, final String kind)
      throws IOException {
    final JsonObject manifest = manifest(context, "classify");
    final boolean consistent = manifest.getObj("entailments").getBoolean("consistent");
    final JsonObject result = new JsonObject();
    result.put("operation", "CHECK");
    result.put("kind", kind);
    result.put("consistent", consistent);
    result.put("unsatisfiableCount", manifest.getObj("entailments").get("unsatisfiable"));
    result.put("unsatisfiableClasses", manifest.get("unsatisfiable"));
    final String outcome;
    if (kind.equals("CONSISTENCY")) {
      outcome = consistent ? "NOT_ENTAILED" : "INCONSISTENT";
    } else {
      final JsonObject answer = manifest.get("checks").getAsArray().get(0).getAsObject();
      result.put("check", answer);
      if (answer.getString("outcome").equals("INVALID")) {
        return Stage.Result.failed(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "The check cannot be asked of this closure: " + answer.getString("reason"))
                .atStep("classify"));
      }
      outcome = answer.getString("outcome");
    }
    result.put("outcome", outcome);
    copy(manifest, result, "engine", "routing", "closure", "admission");
    AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
    return Stage.Result.answered(outcome, null).withEngines(engines(manifest));
  }

  /** The job's answer to an explanation, from the explain step's manifest. */
  private static Stage.Result explainAnswer(final Stage.Context context) throws IOException {
    final JsonObject manifest = manifest(context, "explain");
    final String outcome =
        switch (manifest.getString("outcome")) {
          case "EXPLAINED" -> "ENTAILED";
          case "INCONSISTENT" -> "INCONSISTENT";
          default -> "NOT_ENTAILED";
        };
    final JsonObject result = new JsonObject();
    result.put("operation", "EXPLAIN");
    result.put("outcome", outcome);
    copy(
        manifest,
        result,
        "target",
        "justifications",
        "justificationsIncomplete",
        "consistencyChecks",
        "engine",
        "closure",
        "admission");
    AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
    return Stage.Result.answered(outcome, null).withEngines(engines(manifest));
  }

  /**
   * Runs a worker step unless the job's time is gone, and reads a limit, a deadline or the worker's
   * heap running out as the INCOMPLETE answer the protocol gives a check, not as a failure.
   */
  private static Stage.Result bounded(
      final WorkerStage worker, final Stage.Context context, final long deadline)
      throws IOException, InterruptedException {
    if (System.nanoTime() - deadline >= 0) {
      return answerOrFail(outOfTime().atStep(worker.name()));
    }
    final Stage.Result result = worker.run(context);
    return result.problem() == null ? result : answerOrFail(result.problem());
  }

  private static Stage.Result answerOrFail(final Problem problem) {
    return switch (problem.code()) {
      case LIMIT_EXCEEDED, MEMORY_BUDGET_EXHAUSTED -> Stage.Result.answered("INCOMPLETE", problem);
      default -> Stage.Result.failed(problem);
    };
  }

  private static Problem outOfTime() {
    return Problem.of(Problem.Code.LIMIT_EXCEEDED, "The job ran out of time")
        .withSubject("maxJobSeconds");
  }

  private static JsonObject step(final String kind, final long deadline) {
    final JsonObject step = new JsonObject();
    step.put("protocol", 1);
    step.put("kind", kind);
    step.put("dataset", WORKING);
    step.put(
        "timeoutMillis", Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    return step;
  }

  /** The working dataset holds exactly the captured closure, so the closure is all of it. */
  private static JsonObject closure(final Stage.Context context) {
    final JsonObject closure = new JsonObject();
    closure.put("root", context.request().getString("ontologyIri"));
    closure.put("allGraphs", true);
    return closure;
  }

  private static JsonObject limits(final Operations operations, final Stage.Context context) {
    long spool;
    try {
      spool = Files.size(context.directory().resolve(SPOOL));
    } catch (final IOException e) {
      spool = 1;
    }
    return operations.workerLimits().toJson(spool);
  }

  private static long diskLeft(final Operations operations) throws IOException {
    final Path root = operations.settings().reasoningRoot();
    return operations.jobSettings().diskQuota(root) - JobStore.sizeOnDisk(root);
  }

  private static JsonObject manifest(final Stage.Context context, final String step) {
    return JSON.read(context.directory().resolve(step + ".result.json").toString());
  }

  private static JsonArray engines(final JsonObject manifest) {
    final JsonArray engines = new JsonArray();
    final JsonValue engine = manifest.get("engine");
    if (engine != null && engine.isObject()) {
      engines.add(
          Operations.engine(
              engine.getAsObject().getString("name"), engine.getAsObject().getString("version")));
    }
    return engines;
  }

  private static void copy(final JsonObject from, final JsonObject to, final String... keys) {
    for (final String key : keys) {
      if (from.hasKey(key)) {
        to.put(key, from.get(key));
      }
    }
  }

  @FunctionalInterface
  private interface Body {
    Stage.Result run(Stage.Context context) throws IOException, InterruptedException;
  }

  private static Stage stage(final String name, final Body body) {
    return new Stage() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Result run(final Context context) throws IOException, InterruptedException {
        return body.run(context);
      }
    };
  }
}
