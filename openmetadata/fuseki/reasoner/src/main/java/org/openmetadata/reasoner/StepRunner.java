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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.openmetadata.reasoner.owl.ClassifyStep;
import org.openmetadata.reasoner.owl.ExplainStep;
import org.openmetadata.reasoner.store.CompactStep;
import org.openmetadata.reasoner.store.LoadStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one step file: reads it, runs the step under its deadline, and writes the result manifest
 * atomically, so a reader never sees a partial one. Whatever happens, the returned exit status
 * agrees with the manifest's status.
 */
public final class StepRunner {
  private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);

  private final boolean haltOnTimeBudget;

  /**
   * @param haltOnTimeBudget true in the worker process: a step that does not unwind after its
   *     deadline ends the JVM. Tests that run steps in their own JVM pass false.
   */
  public StepRunner(final boolean haltOnTimeBudget) {
    this.haltOnTimeBudget = haltOnTimeBudget;
  }

  public int run(final Path stepFilePath) {
    final long start = System.nanoTime();
    final Path stepFile = stepFilePath.toAbsolutePath().normalize();
    final Manifest manifest = new Manifest(StepFile.resultPath(stepFile), start);
    try {
      Files.deleteIfExists(StepFile.resultPath(stepFile));
      Files.deleteIfExists(StepFile.reportPath(stepFile));
    } catch (final IOException e) {
      LOG.error("Cannot clear the previous result of {}", stepFile, e);
      return ExitCodes.INTERNAL_ERROR;
    }
    final StepFile step;
    try {
      step = StepFile.read(stepFile);
    } catch (final InvalidStepException e) {
      LOG.error("Invalid step file {}: {}", stepFile, e.getMessage());
      manifest.write(StepResult.invalidStep(e.getMessage()));
      return ExitCodes.INVALID_STEP;
    }
    manifest.identify(step.stepId(), step.kind().name());
    LOG.info("Step {} ({}) started", step.stepId(), step.kind());

    try (Watchdog watchdog =
        new Watchdog(
            step.timeoutMillis(),
            expired -> {
              LOG.error("Step {} exceeded its {} ms budget", step.stepId(), step.timeoutMillis());
              manifest.write(
                  StepResult.timeBudget(step.timeoutMillis())
                      .detail("worker", expired.workerJson()));
              if (haltOnTimeBudget) {
                Runtime.getRuntime().halt(ExitCodes.TIME_BUDGET);
              }
            })) {
      StepResult result;
      try {
        result = step(step.kind()).run(step, watchdog);
      } catch (final InvalidStepException e) {
        result = StepResult.invalidStep(e.getMessage());
      } catch (final InputRejectedException e) {
        result = StepResult.rejected(e);
      } catch (final Throwable e) {
        result =
            watchdog.expired()
                ? StepResult.timeBudget(step.timeoutMillis())
                : StepResult.internalError(e);
        if (!watchdog.expired()) {
          LOG.error("Step {} failed", step.stepId(), e);
        }
      }
      if (watchdog.expired()) {
        result =
            StepResult.timeBudget(step.timeoutMillis())
                .detail("interruptLatencyMillis", watchdog.millisSinceExpiry());
      }
      watchdog.finish();
      result.detail("worker", watchdog.workerJson());
      if (step.report()) {
        writeReport(
            StepFile.reportPath(stepFile),
            result.report() != null ? result.report() : problemReport(result));
      }
      if (!manifest.write(result)) {
        return ExitCodes.TIME_BUDGET;
      }
      LOG.info(
          "Step {} finished: {} {} in {} ms",
          step.stepId(),
          result.status(),
          result.outcome(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      return result.exitCode();
    } catch (final IOException e) {
      LOG.error("Cannot write the report of step {}", step.stepId(), e);
      manifest.write(StepResult.internalError(e));
      return ExitCodes.INTERNAL_ERROR;
    }
  }

  private static Step step(final StepKind kind) {
    return switch (kind) {
      case LOAD -> new LoadStep();
      case CLASSIFY -> new ClassifyStep();
      case EXPLAIN -> new ExplainStep();
      case COMPACT -> new CompactStep();
    };
  }

  /** The report of a step that produced no answer: its outcome and the distinct problem codes. */
  private static List<String> problemReport(final StepResult result) {
    final List<String> lines = new ArrayList<>();
    lines.add("OUTCOME " + result.outcome());
    result.problems().stream()
        .map(problem -> "PROBLEM " + problem.code())
        .distinct()
        .sorted()
        .forEach(lines::add);
    return lines;
  }

  private static void writeReport(final Path path, final List<String> lines) throws IOException {
    final StringBuilder text = new StringBuilder();
    lines.forEach(line -> text.append(line).append('\n'));
    writeAtomically(path, text.toString().getBytes(StandardCharsets.UTF_8));
  }

  static void writeAtomically(final Path path, final byte[] content) throws IOException {
    final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      final ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    Files.move(
        temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** The result manifest. The first write wins, so the watchdog and the step never interleave. */
  private static final class Manifest {
    private final Path path;
    private final long startNanos;
    private volatile String stepId;
    private volatile String kind;
    private boolean written;

    Manifest(final Path path, final long startNanos) {
      this.path = path;
      this.startNanos = startNanos;
    }

    void identify(final String stepId, final String kind) {
      this.stepId = stepId;
      this.kind = kind;
    }

    synchronized boolean write(final StepResult result) {
      if (written) {
        return false;
      }
      written = true;
      final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      try {
        final String json = JSON.toString(result.toJson(stepId, kind, elapsed)) + "\n";
        writeAtomically(path, json.getBytes(StandardCharsets.UTF_8));
      } catch (final IOException | RuntimeException e) {
        LOG.error("Cannot write the result manifest {}", path, e);
      }
      return true;
    }
  }
}
