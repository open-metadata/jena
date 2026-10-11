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

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches one reasoner worker step as a separate JVM and reports how it ended. The worker gets its
 * own JVM flags, never the server's: a fixed heap, capped metaspace, code cache and direct memory,
 * -XX:+ExitOnOutOfMemoryError, a CPU count, and the lowest CPU and I/O priority with the highest
 * OOM-killer score. It gets only the environment variables in {@link #ENVIRONMENT}, so nothing
 * meant for the server, such as ADMIN_PASSWORD, reaches it. At most one worker runs per container:
 * the launcher takes the container's worker lock before anything else, then checks the memory
 * budget.
 *
 * <p>The worker enforces the step's time budget itself and exits with status 5. If it does not exit
 * {@link #DEFAULT_TERMINATE_AFTER_MILLIS} later, it gets SIGTERM, and SIGKILL after {@link
 * #DEFAULT_KILL_AFTER_MILLIS} more.
 */
public final class WorkerLauncher {
  public static final long DEFAULT_TERMINATE_AFTER_MILLIS = 15_000;
  public static final long DEFAULT_KILL_AFTER_MILLIS = 10_000;
  public static final String WORKER_MAIN = "org.openmetadata.reasoner.Main";

  /** choom, nice and ionice ship in the base image; each executes the next command in place. */
  public static final List<String> PRIORITY =
      List.of("choom", "-n", "1000", "--", "nice", "-n", "10", "ionice", "-c2", "-n7");

  /**
   * The only variables a worker inherits: PATH to find the priority commands, and the locale, time
   * zone and home directory for file names and log timestamps. The JVM finds itself by its absolute
   * path, and the worker reads no other variable.
   */
  public static final List<String> ENVIRONMENT =
      List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "HOME");

  /** The worker log keeps this much of the start of a step's output and as much of its end. */
  static final int LOG_HEAD_BYTES = 512 * 1024;

  static final int LOG_TAIL_BYTES = 512 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(WorkerLauncher.class);

  private final ReasoningSettings settings;
  private final ContainerMemory memory;
  private final long fusekiHeap;
  private final boolean outsideFuseki;
  private final List<String> priority;
  private final String java;
  private final Map<String, String> environment;
  private final long terminateAfterMillis;
  private final long killAfterMillis;
  private volatile Run current;

  /**
   * @param fusekiHeap H_f as measured in the Fuseki JVM, or -1 outside it
   * @param outsideFuseki true for the command line, whose own memory is not Fuseki's
   */
  public WorkerLauncher(
      final ReasoningSettings settings,
      final ContainerMemory memory,
      final long fusekiHeap,
      final boolean outsideFuseki) {
    this(
        settings,
        memory,
        fusekiHeap,
        outsideFuseki,
        PRIORITY,
        ProcessHandle.current().info().command().orElse("java"),
        System.getenv(),
        DEFAULT_TERMINATE_AFTER_MILLIS,
        DEFAULT_KILL_AFTER_MILLIS);
  }

  WorkerLauncher(
      final ReasoningSettings settings,
      final ContainerMemory memory,
      final long fusekiHeap,
      final boolean outsideFuseki,
      final List<String> priority,
      final String java,
      final Map<String, String> environment,
      final long terminateAfterMillis,
      final long killAfterMillis) {
    this.settings = settings;
    this.memory = memory;
    this.fusekiHeap = fusekiHeap;
    this.outsideFuseki = outsideFuseki;
    this.priority = List.copyOf(priority);
    this.java = java;
    this.environment = Map.copyOf(environment);
    this.terminateAfterMillis = terminateAfterMillis;
    this.killAfterMillis = killAfterMillis;
  }

  /** The exact command a step runs with. */
  public List<String> command(final Path stepFile) {
    final List<String> command = new ArrayList<>(priority);
    command.add(java);
    final long heapMiB = settings.workerHeap() / Sizes.MIB;
    command.add("-Xms" + heapMiB + "m");
    command.add("-Xmx" + heapMiB + "m");
    command.add("-Xss1m");
    command.add("-XX:MaxMetaspaceSize=256m");
    command.add("-XX:ReservedCodeCacheSize=128m");
    command.add("-XX:MaxDirectMemorySize=64m");
    command.add("-XX:ActiveProcessorCount=" + settings.cpus());
    command.add("-XX:+UseParallelGC");
    command.add("-XX:+ExitOnOutOfMemoryError");
    // Temporary files belong on the data volume with the job, never on a memory-backed tmpfs.
    command.add("-Djava.io.tmpdir=" + stepFile.getParent());
    command.add("-cp");
    command.add(settings.workerClasspath());
    command.add(WORKER_MAIN);
    command.add(stepFile.toString());
    return command;
  }

  public Readiness readiness() {
    return Readiness.evaluate(settings, memory, fusekiHeap, true, outsideFuseki);
  }

  /**
   * Cancels the step running now, if there is one: the worker gets SIGTERM at once and SIGKILL
   * {@link #DEFAULT_KILL_AFTER_MILLIS} later if it is still alive, and the step ends INTERRUPTED.
   * It has no effect on a later step.
   */
  public void cancel() {
    final Run run = current;
    if (run != null) {
      run.cancel();
    }
  }

  /**
   * Runs one step and records its outcome in {@code <step>.status.json}, except when the step file
   * is outside the reasoning root or another worker was running (BUSY), whose step may be this very
   * file; the outcome is returned even when that file cannot be written, which is logged.
   * Interrupting the calling thread kills the worker, and this method returns only once the worker
   * has exited.
   */
  public StepOutcome run(final Path stepFilePath) throws IOException {
    return run(stepFilePath, () -> false);
  }

  /**
   * Runs one step as {@link #run(Path)} does, cancelled as by {@link #cancel()} if {@code
   * cancelled} is true when the step starts. A caller that sets its flag and then calls {@link
   * #cancel()} therefore cancels its step whether the step had already started or not, and never a
   * later step.
   */
  public StepOutcome run(final Path stepFilePath, final BooleanSupplier cancelled)
      throws IOException {
    final Path stepFile = stepFilePath.toAbsolutePath().normalize();
    final Path root = settings.reasoningRoot();
    if (!Files.isDirectory(root)
        || !Files.isRegularFile(stepFile)
        || !stepFile.toRealPath().startsWith(root.toRealPath())) {
      // Nothing is written next to a file outside the reasoning root.
      return StepOutcome.notLaunched(
          StepOutcome.Status.FAILED,
          "INVALID_STEP: step files must be under " + root + " on the data volume",
          null);
    }
    try (FileChannel lockFile =
            FileChannel.open(
                root.resolve(".worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = tryLock(lockFile)) {
      if (lock == null) {
        return StepOutcome.notLaunched(
            StepOutcome.Status.BUSY, "Another reasoner worker is running", null);
      }
      Files.deleteIfExists(sibling(stepFile, ".status.json"));
      return record(stepFile, launch(stepFile, cancelled));
    }
  }

  private StepOutcome launch(final Path stepFile, final BooleanSupplier cancelled)
      throws IOException {
    final StepHeader header;
    try {
      header = StepHeader.read(stepFile);
    } catch (final StepHeader.Invalid e) {
      return StepOutcome.notLaunched(
          StepOutcome.Status.FAILED, "INVALID_STEP: " + e.getMessage(), null);
    }
    final Readiness readiness = readiness();
    if (!readiness.admits()) {
      LOG.warn("Reasoner step {} not started: {}", stepFile.getFileName(), readiness.summary());
      return StepOutcome.notLaunched(
          StepOutcome.Status.NOT_READY, String.join("; ", readiness.reasons()), readiness);
    }
    final Run run = new Run();
    current = run;
    if (cancelled.getAsBoolean()) {
      run.cancel();
    }
    try {
      final StepOutcome outcome = execute(stepFile, header, readiness, run);
      if (outcome.status() != StepOutcome.Status.SUCCEEDED) {
        removeLeftovers(stepFile.getParent(), header.stepId());
      }
      return outcome;
    } catch (final IOException e) {
      removeLeftovers(stepFile.getParent(), header.stepId());
      throw e;
    } finally {
      current = null;
    }
  }

  private StepOutcome execute(
      final Path stepFile, final StepHeader header, final Readiness readiness, final Run run)
      throws IOException {
    final Path manifestFile = sibling(stepFile, ".result.json");
    Files.deleteIfExists(manifestFile);
    Files.deleteIfExists(sibling(stepFile, ".report.txt"));
    final ProcessBuilder builder = new ProcessBuilder(command(stepFile));
    builder.directory(stepFile.getParent().toFile());
    builder.redirectErrorStream(true);
    final Map<String, String> workerEnvironment = builder.environment();
    workerEnvironment.clear();
    for (final String name : ENVIRONMENT) {
      final String value = environment.get(name);
      if (value != null) {
        workerEnvironment.put(name, value);
      }
    }

    final long start = System.nanoTime();
    final Process process;
    synchronized (run) {
      if (run.cancelled) {
        return StepOutcome.notLaunched(StepOutcome.Status.INTERRUPTED, "CANCELLED", readiness);
      }
      try {
        process = builder.start();
      } catch (final IOException e) {
        return StepOutcome.notLaunched(
            StepOutcome.Status.FAILED, "LAUNCH_ERROR: " + e.getMessage(), readiness);
      }
      run.process = process;
    }
    // Read after a restart of the server, to kill a worker that outlived it. Best effort: the
    // worker is already running and must be awaited either way.
    final Path pidFile = sibling(stepFile, ".pid");
    try {
      AtomicFiles.write(pidFile, process.pid() + "\n");
    } catch (final IOException e) {
      LOG.warn("Cannot record the worker's pid in {}: {}", pidFile, e.toString());
    }
    try {
      return await(stepFile, header, readiness, run, process, start);
    } finally {
      Files.deleteIfExists(pidFile);
    }
  }

  private StepOutcome await(
      final Path stepFile,
      final StepHeader header,
      final Readiness readiness,
      final Run run,
      final Process process,
      final long start)
      throws IOException {
    final Path manifestFile = sibling(stepFile, ".result.json");
    final Thread logger = startLog(process.getInputStream(), sibling(stepFile, ".log"));
    final long terminateAt =
        start + TimeUnit.MILLISECONDS.toNanos(header.timeoutMillis() + terminateAfterMillis);
    final long killAfter = TimeUnit.MILLISECONDS.toNanos(killAfterMillis);
    boolean terminatedByLauncher = false;
    long signalled = 0;
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        final long now = System.nanoTime();
        if (signalled == 0 && run.cancelledAt != 0) {
          signalled = run.cancelledAt;
        }
        if (signalled == 0 && now - terminateAt >= 0) {
          terminatedByLauncher = true;
          signalled = now;
          process.destroy();
        }
        if (signalled != 0 && now - signalled >= killAfter) {
          process.destroyForcibly();
        }
      }
    } catch (final InterruptedException e) {
      process.destroyForcibly();
      awaitExit(process, killAfterMillis + 5_000);
      awaitLog(logger);
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for the reasoner worker", e);
    }
    final long ended = System.nanoTime();
    awaitLog(logger);
    // A cancellation that arrives after the worker exited does not change how it ended.
    final long cancelledAt = run.cancelledAt;
    final boolean cancelled = cancelledAt != 0 && cancelledAt - ended <= 0;
    if (signalled == 0 && cancelled) {
      signalled = cancelledAt;
    }
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(ended - start);
    final long terminationLatency =
        signalled == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(ended - signalled);
    final int exit = process.exitValue();
    final StepOutcome outcome =
        classify(
            exit,
            terminatedByLauncher,
            cancelled,
            readManifest(manifestFile),
            elapsed,
            terminationLatency,
            readiness);
    LOG.info(
        "Reasoner step {} ended {} ({}), exit {}, {} ms",
        stepFile.getFileName(),
        outcome.status(),
        outcome.reason(),
        exit,
        elapsed);
    return outcome;
  }

  static StepOutcome classify(
      final int exit,
      final boolean terminatedByLauncher,
      final boolean cancelled,
      final JsonObject manifest,
      final long elapsed,
      final long terminationLatency,
      final Readiness readiness) {
    final StepOutcome.Status status;
    final String reason;
    if (cancelled) {
      status = StepOutcome.Status.INTERRUPTED;
      reason = "CANCELLED";
    } else if (terminatedByLauncher) {
      status = StepOutcome.Status.INCOMPLETE;
      reason = "TIME_BUDGET: the worker did not exit after its deadline and was terminated";
    } else {
      switch (exit) {
        case WorkerExitCodes.COMPLETED -> {
          final boolean succeeded =
              manifest != null && "SUCCEEDED".equals(string(manifest, "status"));
          status = succeeded ? StepOutcome.Status.SUCCEEDED : StepOutcome.Status.FAILED;
          reason =
              succeeded ? string(manifest, "outcome") : "PROTOCOL_ERROR: no valid result manifest";
        }
        case WorkerExitCodes.INTERNAL_ERROR -> {
          status = StepOutcome.Status.FAILED;
          reason = "INTERNAL_ERROR";
        }
        case WorkerExitCodes.INVALID_STEP -> {
          status = StepOutcome.Status.FAILED;
          reason = "INVALID_STEP";
        }
        case WorkerExitCodes.OUT_OF_MEMORY -> {
          status = StepOutcome.Status.INCOMPLETE;
          reason = "MEMORY_BUDGET: the worker exhausted its heap and exited";
        }
        case WorkerExitCodes.INPUT_REJECTED -> {
          status = StepOutcome.Status.FAILED;
          reason = manifest == null ? "INPUT_REJECTED" : string(manifest, "outcome");
        }
        case WorkerExitCodes.TIME_BUDGET -> {
          status = StepOutcome.Status.INCOMPLETE;
          reason = "TIME_BUDGET";
        }
        case WorkerExitCodes.SIGKILL -> {
          // The launcher sends SIGKILL only after its own deadline or a cancellation, both
          // handled above, so this one came from elsewhere: in a container, almost always the
          // kernel's out-of-memory killer acting on the cgroup limit.
          status = StepOutcome.Status.INCOMPLETE;
          reason =
              "MEMORY_BUDGET: the worker was killed by SIGKILL, probably by the cgroup"
                  + " out-of-memory killer";
        }
        default -> {
          if (exit > WorkerExitCodes.SIGNALLED && exit <= WorkerExitCodes.SIGNALLED + 64) {
            status = StepOutcome.Status.INTERRUPTED;
            reason = "KILLED_BY_SIGNAL_" + (exit - WorkerExitCodes.SIGNALLED);
          } else {
            status = StepOutcome.Status.FAILED;
            reason = "UNEXPECTED_EXIT_" + exit;
          }
        }
      }
    }
    return new StepOutcome(status, reason, exit, elapsed, terminationLatency, manifest, readiness);
  }

  private StepOutcome record(final Path stepFile, final StepOutcome outcome) {
    final Path statusFile = sibling(stepFile, ".status.json");
    try {
      AtomicFiles.write(statusFile, JSON.toString(outcome.toJson()) + "\n");
    } catch (final IOException e) {
      LOG.error("Cannot write {}: {}", statusFile, e.toString());
    }
    return outcome;
  }

  /**
   * Removes what a step that did not succeed can leave in its job directory: the exported closure
   * and half-written files. Only one worker runs at a time and this runs under its lock, so none of
   * them belongs to a running step.
   */
  private static void removeLeftovers(final Path job, final String stepId) {
    try {
      Files.deleteIfExists(job.resolve(stepId + ".closure.nt"));
      try (DirectoryStream<Path> temporary = Files.newDirectoryStream(job, "*.tmp")) {
        for (final Path file : temporary) {
          if (Files.isRegularFile(file)) {
            Files.deleteIfExists(file);
          }
        }
      }
    } catch (final IOException e) {
      LOG.warn("Cannot remove the leftovers of step {} in {}: {}", stepId, job, e.toString());
    }
  }

  private static Thread startLog(final InputStream output, final Path file) {
    final Thread thread =
        new Thread(
            () -> {
              try (output) {
                new BoundedLog(file, LOG_HEAD_BYTES, LOG_TAIL_BYTES).copy(output);
              } catch (final IOException e) {
                LOG.warn("Cannot write the worker log {}: {}", file, e.toString());
              }
            },
            "reasoner-worker-log");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** The log ends when the worker's output closes, which its exit does. */
  private static void awaitLog(final Thread logger) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    boolean interrupted = false;
    while (logger.isAlive() && System.nanoTime() - deadline < 0) {
      try {
        logger.join(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 1);
      } catch (final InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for a killed worker to exit, ignoring interrupts, so the worker lock is never released
   * while it is still alive.
   */
  private static void awaitExit(final Process process, final long timeoutMillis) {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    boolean interrupted = false;
    while (process.isAlive() && System.nanoTime() - deadline < 0) {
      try {
        process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      } catch (final InterruptedException e) {
        interrupted = true;
      }
    }
    if (process.isAlive()) {
      LOG.error("Reasoner worker {} did not exit after SIGKILL", process.pid());
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static FileLock tryLock(final FileChannel channel) throws IOException {
    try {
      return channel.tryLock();
    } catch (final OverlappingFileLockException e) {
      return null;
    }
  }

  private static JsonObject readManifest(final Path manifest) {
    if (!Files.isRegularFile(manifest)) {
      return null;
    }
    try {
      return JSON.read(manifest.toString());
    } catch (final RuntimeException e) {
      return null;
    }
  }

  private static String string(final JsonObject json, final String key) {
    final JsonValue value = json.get(key);
    return value != null && value.isString() ? value.getAsString().value() : null;
  }

  /** The file the worker or launcher writes next to a step file, named as the worker names it. */
  static Path sibling(final Path stepFile, final String suffix) {
    final String name = stepFile.getFileName().toString();
    final String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    return stepFile.resolveSibling(base + suffix);
  }

  /** One step's worker, and whether it was cancelled; a cancellation never outlives its step. */
  private static final class Run {
    private Process process;
    private boolean cancelled;
    private volatile long cancelledAt;

    synchronized void cancel() {
      if (cancelled) {
        return;
      }
      cancelled = true;
      cancelledAt = System.nanoTime();
      if (process != null) {
        process.destroy();
      }
    }
  }
}
