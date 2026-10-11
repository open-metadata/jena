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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher against a stand-in worker: a shell script in place of java whose behaviour each test
 * picks, so every exit path, the deadline's SIGTERM then SIGKILL, and the refusals before launch
 * are exercised with real processes.
 */
class WorkerLauncherTest {
  private static final String SUCCEED =
      "echo '{\"status\":\"SUCCEEDED\",\"outcome\":\"CLASSIFIED\"}' > step.result.json; exit 0";

  /** Variables a POSIX shell sets for itself, which env then prints. */
  private static final List<String> SHELL_VARIABLES = List.of("PWD", "SHLVL", "_", "OLDPWD");

  @TempDir Path root;
  private Path job;
  private Path fakeJava;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(root.resolve("sys/fs/cgroup"));
    Files.writeString(root.resolve("sys/fs/cgroup/memory.max"), Long.toString(64 * Sizes.GIB));
    Files.writeString(root.resolve("sys/fs/cgroup/memory.stat"), "anon 0\n");
    job = Files.createDirectories(root.resolve("fuseki/reasoning/jobs/job1"));
    fakeJava = root.resolve("fake-java");
    // The step file is the last argument; the behaviour is read from behaviour.sh next to it.
    Files.writeString(
        fakeJava,
        """
        #!/bin/sh
        for last; do :; done
        cd "$(dirname "$last")"
        . ./behaviour.sh
        """);
    Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  void theCommandIsTheDesignedLaunchCommand() {
    final WorkerLauncher launcher =
        new WorkerLauncher(settings(Map.of("OPENMETADATA_REASONER_CPUS", "2")), memory(), -1, true);
    final Path step = Path.of("/fuseki/reasoning/jobs/j/classify.json");
    final List<String> command = launcher.command(step);
    assertEquals(WorkerLauncher.PRIORITY, command.subList(0, WorkerLauncher.PRIORITY.size()));
    assertEquals(
        List.of(
            "-Xms2048m",
            "-Xmx2048m",
            "-Xss1m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:ReservedCodeCacheSize=128m",
            "-XX:MaxDirectMemorySize=64m",
            "-XX:ActiveProcessorCount=2",
            "-XX:+UseParallelGC",
            "-XX:+ExitOnOutOfMemoryError",
            "-Djava.io.tmpdir=/fuseki/reasoning/jobs/j",
            "-cp",
            root.resolve("jena-fuseki/fuseki-server.jar")
                + ":"
                + root.resolve("jena-fuseki/reasoner")
                + "/*",
            "org.openmetadata.reasoner.Main",
            step.toString()),
        command.subList(WorkerLauncher.PRIORITY.size() + 1, command.size()));
  }

  @Test
  void aCompletedStepReportsItsManifestOutcome() throws Exception {
    final StepOutcome outcome =
        run(
            """
            echo '{"status":"SUCCEEDED","outcome":"INCONSISTENT"}' > step.result.json
            exit 0
            """,
            60_000);
    assertEquals(StepOutcome.Status.SUCCEEDED, outcome.status());
    assertEquals("INCONSISTENT", outcome.reason());
    assertEquals(
        "SUCCEEDED", JSON.read(job.resolve("step.status.json").toString()).getString("status"));
  }

  @Test
  void exitStatusesMapToOutcomes() throws Exception {
    assertEquals(
        StepOutcome.Status.INCOMPLETE,
        run("echo 'Terminating due to OutOfMemoryError'; exit 3", 60_000).status());
    assertEquals("TIME_BUDGET", run("exit 5", 60_000).reason());
    assertEquals(StepOutcome.Status.FAILED, run("exit 2", 60_000).status());
    assertEquals(StepOutcome.Status.FAILED, run("exit 4", 60_000).status());
    final StepOutcome noManifest = run("exit 0", 60_000);
    assertEquals(StepOutcome.Status.FAILED, noManifest.status());
    assertTrue(noManifest.reason().startsWith("PROTOCOL_ERROR"));
    final StepOutcome hungUp = run("kill -HUP $$", 60_000);
    assertEquals(StepOutcome.Status.INTERRUPTED, hungUp.status());
    assertEquals("KILLED_BY_SIGNAL_1", hungUp.reason());
    assertTrue(Files.readString(job.resolve("step.log")).isEmpty());
  }

  @Test
  void aSigkillTheLauncherDidNotSendIsReportedAsTheMemoryBudget() throws Exception {
    final StepOutcome killed = run("kill -9 $$", 60_000);
    assertEquals(StepOutcome.Status.INCOMPLETE, killed.status());
    assertEquals(137, killed.exitCode());
    assertTrue(killed.reason().startsWith("MEMORY_BUDGET"), killed.reason());
    assertTrue(killed.reason().contains("out-of-memory killer"), killed.reason());
  }

  @Test
  void aWorkerPastItsDeadlineIsTerminated() throws Exception {
    final StepOutcome outcome = run("exec sleep 600", 200);
    assertEquals(StepOutcome.Status.INCOMPLETE, outcome.status());
    assertTrue(outcome.reason().startsWith("TIME_BUDGET"));
    assertTrue(outcome.terminationLatencyMillis() < 5_000, outcome.toJson().toString());
  }

  @Test
  void aWorkerIgnoringSigtermIsKilled() throws Exception {
    final long start = System.nanoTime();
    final StepOutcome outcome = run("trap '' TERM; while true; do sleep 1; done", 200);
    assertEquals(StepOutcome.Status.INCOMPLETE, outcome.status());
    assertTrue(outcome.terminationLatencyMillis() >= 1_000, outcome.toJson().toString());
    assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 30);
  }

  @Test
  void cancellationStopsTheWorkerAndIsReportedAsInterrupted() throws Exception {
    final WorkerLauncher launcher = launcher(settings(Map.of()));
    final Path step = step("touch started; exec sleep 600", 600_000);
    final CompletableFuture<StepOutcome> outcome = runAsync(launcher, step);
    awaitFile(job.resolve("started"));
    launcher.cancel();
    final StepOutcome cancelled = outcome.get(30, TimeUnit.SECONDS);
    assertEquals(StepOutcome.Status.INTERRUPTED, cancelled.status());
    assertEquals("CANCELLED", cancelled.reason());
    assertTrue(cancelled.terminationLatencyMillis() >= 0, cancelled.toJson().toString());
    assertTrue(cancelled.terminationLatencyMillis() < 5_000, cancelled.toJson().toString());
  }

  @Test
  void aWorkerIgnoringCancellationIsKilledAfterTheGracePeriod() throws Exception {
    final WorkerLauncher launcher = launcher(settings(Map.of()));
    final Path step = step("trap '' TERM; touch started; while true; do sleep 1; done", 600_000);
    final CompletableFuture<StepOutcome> outcome = runAsync(launcher, step);
    awaitFile(job.resolve("started"));
    launcher.cancel();
    final StepOutcome cancelled = outcome.get(30, TimeUnit.SECONDS);
    assertEquals("CANCELLED", cancelled.reason());
    assertEquals(137, cancelled.exitCode());
    assertTrue(cancelled.terminationLatencyMillis() >= 1_500, cancelled.toJson().toString());
  }

  @Test
  void aCancellationNeverOutlivesItsStep() throws Exception {
    final WorkerLauncher launcher = launcher(settings(Map.of()));
    launcher.cancel();
    assertEquals(
        StepOutcome.Status.SUCCEEDED,
        launcher.run(step(SUCCEED, 60_000)).status(),
        "a cancellation with no step running");

    final Path step = step("touch started; exec sleep 600", 600_000);
    final CompletableFuture<StepOutcome> first = runAsync(launcher, step);
    awaitFile(job.resolve("started"));
    launcher.cancel();
    assertEquals("CANCELLED", first.get(30, TimeUnit.SECONDS).reason());
    Files.delete(job.resolve("started"));
    assertEquals(
        StepOutcome.Status.SUCCEEDED,
        launcher.run(step(SUCCEED, 60_000)).status(),
        "the step after a cancelled one");
  }

  @Test
  void aStepWhoseCallerHasAlreadyCancelledNeverStartsAWorker() throws Exception {
    final StepOutcome outcome =
        launcher(settings(Map.of())).run(step("touch started; " + SUCCEED, 60_000), () -> true);
    assertEquals(StepOutcome.Status.INTERRUPTED, outcome.status());
    assertEquals("CANCELLED", outcome.reason());
    assertFalse(Files.exists(job.resolve("started")));
  }

  @Test
  void theWorkersPidIsRecordedWhileItRuns() throws Exception {
    final Path step =
        step("touch started; while [ ! -e release ]; do sleep 0.05; done; exit 0", 60_000);
    final CompletableFuture<StepOutcome> running = runAsync(launcher(settings(Map.of())), step);
    awaitFile(job.resolve("started"));
    final long pid = Long.parseLong(Files.readString(job.resolve("step.pid")).trim());
    assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    Files.writeString(job.resolve("release"), "");
    running.get(30, TimeUnit.SECONDS);
    assertFalse(Files.exists(job.resolve("step.pid")), "removed once the worker has exited");
  }

  @Test
  void interruptingTheCallerKillsTheWorkerBeforeTheLockIsReleased() throws Exception {
    final WorkerLauncher launcher = launcher(settings(Map.of()));
    final Path step = step("echo $$ > pid; trap '' TERM; exec sleep 600", 600_000);
    final CompletableFuture<Throwable> failure = new CompletableFuture<>();
    final Thread caller =
        new Thread(
            () -> {
              try {
                launcher.run(step);
                failure.complete(null);
              } catch (final Throwable e) {
                failure.complete(e);
              }
            });
    caller.start();
    awaitFile(job.resolve("pid"));
    final long pid = Long.parseLong(Files.readString(job.resolve("pid")).trim());
    caller.interrupt();
    final Throwable thrown = failure.get(30, TimeUnit.SECONDS);
    assertTrue(thrown instanceof IOException, String.valueOf(thrown));
    assertFalse(
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "the worker outlived run()");
    try (FileChannel channel =
            FileChannel.open(
                root.resolve("fuseki/reasoning/.worker.lock"), StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      assertTrue(lock != null, "the lock is free once run() returns");
    }
  }

  @Test
  void aStepThatDoesNotSucceedLeavesNoClosureOrTemporaryFiles() throws Exception {
    final String leftovers =
        "touch step-1.closure.nt step.result.json.tmp other.tmp; mkdir -p keep.tmp; ";
    for (final String ending : List.of("exit 3", "exec sleep 600", "exit 1")) {
      final StepOutcome outcome = run(leftovers + ending, 200);
      assertTrue(outcome.status() != StepOutcome.Status.SUCCEEDED, outcome.toJson().toString());
      assertFalse(Files.exists(job.resolve("step-1.closure.nt")), ending);
      assertFalse(Files.exists(job.resolve("step.result.json.tmp")), ending);
      assertFalse(Files.exists(job.resolve("other.tmp")), ending);
      assertTrue(Files.isDirectory(job.resolve("keep.tmp")), "only files are removed");
    }
  }

  @Test
  void aStaleStatusIsRemovedBeforeTheWorkerStarts() throws Exception {
    Files.writeString(job.resolve("step.status.json"), "{\"status\": \"SUCCEEDED\"}");
    final StepOutcome outcome = run("test -e step.status.json && exit 9; " + SUCCEED, 60_000);
    assertEquals(StepOutcome.Status.SUCCEEDED, outcome.status(), outcome.toJson().toString());
  }

  @Test
  void theWorkerInheritsOnlyTheAllowedEnvironment() throws Exception {
    final Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("ADMIN_PASSWORD", "secret");
    environment.put("JAVA_TOOL_OPTIONS", "-Xmx1t");
    environment.put("JVM_ARGS", "-Xmx8g");
    environment.put("LANG", "C.UTF-8");
    environment.put("TZ", "UTC");
    final WorkerLauncher launcher =
        new WorkerLauncher(
            settings(Map.of()),
            memory(),
            -1,
            true,
            List.of(),
            fakeJava.toString(),
            environment,
            300,
            1_500);
    assertEquals(
        StepOutcome.Status.SUCCEEDED,
        launcher.run(step("env > environment.txt; " + SUCCEED, 60_000)).status());
    final List<String> names =
        Files.readAllLines(job.resolve("environment.txt")).stream()
            .filter(line -> line.contains("="))
            .map(line -> line.substring(0, line.indexOf('=')))
            .toList();
    assertTrue(names.contains("PATH"), names.toString());
    assertTrue(names.contains("LANG"), names.toString());
    assertTrue(names.contains("TZ"), names.toString());
    for (final String name : names) {
      assertTrue(
          WorkerLauncher.ENVIRONMENT.contains(name) || SHELL_VARIABLES.contains(name),
          name + " reached the worker");
    }
  }

  @Test
  void aRunningWorkerMakesTheNextStepBusyNotNotReady() throws Exception {
    final WorkerLauncher launcher = launcher(settings(Map.of()));
    final Path step = step("touch started; exec sleep 600", 600_000);
    final CompletableFuture<StepOutcome> running = runAsync(launcher, step);
    awaitFile(job.resolve("started"));
    // The running worker's memory counts as anonymous memory of the container.
    Files.writeString(root.resolve("sys/fs/cgroup/memory.stat"), "anon " + 60 * Sizes.GIB + "\n");
    final Path other = Files.createDirectories(root.resolve("fuseki/reasoning/jobs/job2"));
    Files.writeString(
        other.resolve("step.json"),
        "{\"protocol\": 1, \"stepId\": \"other\", \"timeoutMillis\": 1000}");
    final StepOutcome second = launcher(settings(Map.of())).run(other.resolve("step.json"));
    assertEquals(StepOutcome.Status.BUSY, second.status(), second.toJson().toString());
    assertFalse(Files.exists(other.resolve("step.status.json")));
    launcher.cancel();
    running.get(30, TimeUnit.SECONDS);
  }

  @Test
  void theLogKeepsTheStartAndTheEndOfAnEndlessOutput() throws Exception {
    final StepOutcome outcome =
        run(
            "echo first-line; head -c 3000000 /dev/zero | tr '\\0' x; echo; echo last-line;"
                + " exit 1",
            60_000);
    assertEquals(StepOutcome.Status.FAILED, outcome.status());
    final Path log = job.resolve("step.log");
    assertTrue(
        Files.size(log) <= WorkerLauncher.LOG_HEAD_BYTES + WorkerLauncher.LOG_TAIL_BYTES + 100,
        Files.size(log) + " bytes");
    final String text = Files.readString(log);
    assertTrue(text.startsWith("first-line\n"));
    assertTrue(text.contains(" bytes of output omitted]"));
    assertTrue(text.endsWith("last-line\n"));
  }

  @Test
  void aDeadlineTheWorkerWouldReadDifferentlyIsRefused() throws Exception {
    for (final String timeout : List.of("1000.5", "-5", "0", "\"1000\"", "1e30")) {
      Files.writeString(
          job.resolve("step.json"),
          "{\"protocol\": 1, \"stepId\": \"step-1\", \"timeoutMillis\": " + timeout + "}");
      final StepOutcome outcome = launcher(settings(Map.of())).run(job.resolve("step.json"));
      assertEquals(StepOutcome.Status.FAILED, outcome.status(), timeout);
      assertTrue(outcome.reason().startsWith("INVALID_STEP: timeoutMillis"), outcome.reason());
    }
    Files.writeString(
        job.resolve("step.json"),
        "{\"stepId\": \"step-1\", \"timeoutMillis\": 1000, \"padding\": \""
            + "x".repeat((int) StepHeader.MAX_STEP_FILE_BYTES)
            + "\"}");
    final StepOutcome oversized = launcher(settings(Map.of())).run(job.resolve("step.json"));
    assertTrue(oversized.reason().contains("larger than"), oversized.reason());
    assertFalse(Files.exists(job.resolve("launched")));
  }

  @Test
  void anUndersizedContainerAdmitsNoWorker() throws Exception {
    Files.writeString(root.resolve("sys/fs/cgroup/memory.max"), Long.toString(Sizes.GIB));
    final StepOutcome outcome = run("touch launched; exit 0", 60_000);
    assertEquals(StepOutcome.Status.NOT_READY, outcome.status());
    assertTrue(outcome.reason().contains("is below the"));
    assertFalse(Files.exists(job.resolve("launched")));
    assertFalse(Files.exists(job.resolve("step.log")));
  }

  @Test
  void onlyOneWorkerRunsAtATime() throws Exception {
    try (FileChannel channel =
            FileChannel.open(
                root.resolve("fuseki/reasoning/.worker.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock held = channel.lock()) {
      assertEquals(StepOutcome.Status.BUSY, run("touch launched; exit 0", 60_000).status());
    }
    assertFalse(Files.exists(job.resolve("launched")));
  }

  @Test
  void stepFilesOutsideTheReasoningRootAreRefused() throws Exception {
    final Path outside = Files.createDirectories(root.resolve("elsewhere"));
    final Path step = outside.resolve("step.json");
    Files.writeString(step, "{\"timeoutMillis\": 1000}");
    final StepOutcome outcome = launcher(settings(Map.of())).run(step);
    assertEquals(StepOutcome.Status.FAILED, outcome.status());
    assertTrue(outcome.reason().startsWith("INVALID_STEP"));
    assertFalse(Files.exists(outside.resolve("step.status.json")));
  }

  private StepOutcome run(final String behaviour, final long timeoutMillis) throws Exception {
    return launcher(settings(Map.of())).run(step(behaviour, timeoutMillis));
  }

  private Path step(final String behaviour, final long timeoutMillis) throws IOException {
    Files.writeString(job.resolve("behaviour.sh"), behaviour + "\n");
    final Path step = job.resolve("step.json");
    Files.writeString(
        step,
        "{\"protocol\": 1, \"stepId\": \"step-1\", \"timeoutMillis\": " + timeoutMillis + "}");
    return step;
  }

  private WorkerLauncher launcher(final ReasoningSettings settings) {
    return new WorkerLauncher(
        settings, memory(), -1, true, List.of(), fakeJava.toString(), System.getenv(), 300, 1_500);
  }

  private static CompletableFuture<StepOutcome> runAsync(
      final WorkerLauncher launcher, final Path step) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return launcher.run(step);
          } catch (final IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static void awaitFile(final Path file) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, file + " never appeared");
      Thread.sleep(20);
    }
  }

  private ContainerMemory memory() {
    return new ContainerMemory(root);
  }

  private ReasoningSettings settings(final Map<String, String> extra) {
    final Map<String, String> environment = new HashMap<>(extra);
    environment.put("OPENMETADATA_REASONING_ENABLED", "true");
    environment.put("FUSEKI_HEAP", "1g");
    environment.put("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "0");
    environment.put("FUSEKI_BASE", root.resolve("fuseki").toString());
    environment.put("FUSEKI_HOME", root.resolve("jena-fuseki").toString());
    return ReasoningSettings.fromEnvironment(environment);
  }
}
