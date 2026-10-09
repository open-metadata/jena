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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches one reasoner worker step as a separate JVM and reports how it ended. The worker gets its
 * own JVM flags, never the server's: a fixed heap, capped metaspace, code cache and direct memory,
 * -XX:+ExitOnOutOfMemoryError, a CPU count, and the lowest CPU and I/O priority with the highest
 * OOM-killer score. Before every step the memory budget is checked, and at most one worker runs per
 * container.
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

  /** Environment variables through which a JVM would pick up flags meant for another one. */
  private static final List<String> FOREIGN_JVM_OPTIONS =
      List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JVM_ARGS", "CLASSPATH");

  private static final Logger LOG = LoggerFactory.getLogger(WorkerLauncher.class);

  private final ReasoningSettings settings;
  private final ContainerMemory memory;
  private final long fusekiHeap;
  private final boolean outsideFuseki;
  private final List<String> priority;
  private final String java;
  private final long terminateAfterMillis;
  private final long killAfterMillis;
  private volatile Process running;
  private volatile boolean cancelled;

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
      final long terminateAfterMillis,
      final long killAfterMillis) {
    this.settings = settings;
    this.memory = memory;
    this.fusekiHeap = fusekiHeap;
    this.outsideFuseki = outsideFuseki;
    this.priority = List.copyOf(priority);
    this.java = java;
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

  /** Requests cancellation of the running step: SIGTERM, then SIGKILL after the grace period. */
  public void cancel() {
    cancelled = true;
    final Process process = running;
    if (process != null) {
      process.destroy();
    }
  }

  public StepOutcome run(final Path stepFilePath) throws IOException {
    final Path stepFile = stepFilePath.toAbsolutePath().normalize();
    final Path statusFile = sibling(stepFile, ".status.json");
    final StepOutcome outcome = launch(stepFile);
    writeAtomically(statusFile, JSON.toString(outcome.toJson()) + "\n");
    return outcome;
  }

  private StepOutcome launch(final Path stepFile) throws IOException {
    final Path root = settings.reasoningRoot();
    if (!Files.isDirectory(root)
        || !Files.isRegularFile(stepFile)
        || !stepFile.toRealPath().startsWith(root.toRealPath())) {
      return StepOutcome.notLaunched(
          StepOutcome.Status.FAILED,
          "INVALID_STEP: step files must be under " + root + " on the data volume",
          null);
    }
    final long timeoutMillis = timeoutMillis(stepFile);
    if (timeoutMillis <= 0) {
      return StepOutcome.notLaunched(
          StepOutcome.Status.FAILED,
          "INVALID_STEP: timeoutMillis must be a positive integer",
          null);
    }
    final Readiness readiness = readiness();
    if (!readiness.admits()) {
      LOG.warn("Reasoner step {} not started: {}", stepFile.getFileName(), readiness.summary());
      return StepOutcome.notLaunched(
          StepOutcome.Status.NOT_READY, String.join("; ", readiness.reasons()), readiness);
    }
    try (FileChannel lockFile =
            FileChannel.open(
                root.resolve(".worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = tryLock(lockFile)) {
      if (lock == null) {
        return StepOutcome.notLaunched(
            StepOutcome.Status.BUSY, "Another reasoner worker is running", readiness);
      }
      return execute(stepFile, timeoutMillis, readiness);
    }
  }

  private StepOutcome execute(
      final Path stepFile, final long timeoutMillis, final Readiness readiness) throws IOException {
    final Path manifestFile = sibling(stepFile, ".result.json");
    Files.deleteIfExists(manifestFile);
    Files.deleteIfExists(sibling(stepFile, ".report.txt"));
    final ProcessBuilder builder = new ProcessBuilder(command(stepFile));
    builder.directory(stepFile.getParent().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.to(sibling(stepFile, ".log").toFile()));
    final Map<String, String> environment = builder.environment();
    FOREIGN_JVM_OPTIONS.forEach(environment::remove);

    final long start = System.nanoTime();
    final Process process;
    try {
      process = builder.start();
    } catch (final IOException e) {
      return StepOutcome.notLaunched(
          StepOutcome.Status.FAILED, "LAUNCH_ERROR: " + e.getMessage(), readiness);
    }
    running = process;
    if (cancelled) {
      process.destroy();
    }
    boolean terminatedByLauncher = false;
    long terminationLatency = -1;
    try {
      if (!process.waitFor(timeoutMillis + terminateAfterMillis, TimeUnit.MILLISECONDS)) {
        terminatedByLauncher = true;
        final long signalled = System.nanoTime();
        process.destroy();
        if (!process.waitFor(killAfterMillis, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor();
        }
        terminationLatency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - signalled);
      } else if (cancelled) {
        terminationLatency = 0;
      }
    } catch (final InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for the reasoner worker", e);
    } finally {
      running = null;
    }
    final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    final int exit = process.exitValue();
    final JsonObject manifest = readManifest(manifestFile);
    final StepOutcome outcome =
        classify(
            exit,
            terminatedByLauncher,
            cancelled,
            manifest,
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
        case 0 -> {
          final boolean succeeded =
              manifest != null && "SUCCEEDED".equals(string(manifest, "status"));
          status = succeeded ? StepOutcome.Status.SUCCEEDED : StepOutcome.Status.FAILED;
          reason =
              succeeded ? string(manifest, "outcome") : "PROTOCOL_ERROR: no valid result manifest";
        }
        case 1 -> {
          status = StepOutcome.Status.FAILED;
          reason = "INTERNAL_ERROR";
        }
        case 2 -> {
          status = StepOutcome.Status.FAILED;
          reason = "INVALID_STEP";
        }
        case 3 -> {
          status = StepOutcome.Status.INCOMPLETE;
          reason = "MEMORY_BUDGET: the worker exhausted its heap and exited";
        }
        case 4 -> {
          status = StepOutcome.Status.FAILED;
          reason = manifest == null ? "INPUT_REJECTED" : string(manifest, "outcome");
        }
        case 5 -> {
          status = StepOutcome.Status.INCOMPLETE;
          reason = "TIME_BUDGET";
        }
        default -> {
          if (exit > 128 && exit <= 128 + 64) {
            status = StepOutcome.Status.INTERRUPTED;
            reason = "KILLED_BY_SIGNAL_" + (exit - 128);
          } else {
            status = StepOutcome.Status.FAILED;
            reason = "UNEXPECTED_EXIT_" + exit;
          }
        }
      }
    }
    return new StepOutcome(status, reason, exit, elapsed, terminationLatency, manifest, readiness);
  }

  private static FileLock tryLock(final FileChannel channel) throws IOException {
    try {
      return channel.tryLock();
    } catch (final OverlappingFileLockException e) {
      return null;
    }
  }

  private static long timeoutMillis(final Path stepFile) {
    try {
      final JsonValue value = JSON.read(stepFile.toString()).get("timeoutMillis");
      return value != null && value.isNumber() ? value.getAsNumber().value().longValue() : -1;
    } catch (final RuntimeException e) {
      return -1;
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

  static Path sibling(final Path stepFile, final String suffix) {
    final String name = stepFile.getFileName().toString();
    final String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    return stepFile.resolveSibling(base + suffix);
  }

  private static void writeAtomically(final Path path, final String content) throws IOException {
    final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      final ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    Files.move(
        temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
