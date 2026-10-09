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
    final StepOutcome killed = run("kill -9 $$", 60_000);
    assertEquals(StepOutcome.Status.INTERRUPTED, killed.status());
    assertEquals("KILLED_BY_SIGNAL_9", killed.reason());
    assertTrue(Files.readString(job.resolve("step.log")).isEmpty());
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
    final Path step = step("exec sleep 600", 600_000);
    final CompletableFuture<StepOutcome> outcome =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return launcher.run(step);
              } catch (final IOException e) {
                throw new IllegalStateException(e);
              }
            });
    Thread.sleep(500);
    launcher.cancel();
    final StepOutcome cancelled = outcome.get(30, TimeUnit.SECONDS);
    assertEquals(StepOutcome.Status.INTERRUPTED, cancelled.status());
    assertEquals("CANCELLED", cancelled.reason());
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
  }

  private StepOutcome run(final String behaviour, final long timeoutMillis) throws Exception {
    return launcher(settings(Map.of())).run(step(behaviour, timeoutMillis));
  }

  private Path step(final String behaviour, final long timeoutMillis) throws IOException {
    Files.writeString(job.resolve("behaviour.sh"), behaviour + "\n");
    final Path step = job.resolve("step.json");
    Files.writeString(step, "{\"protocol\": 1, \"timeoutMillis\": " + timeoutMillis + "}");
    return step;
  }

  private WorkerLauncher launcher(final ReasoningSettings settings) {
    return new WorkerLauncher(
        settings, memory(), -1, true, List.of(), fakeJava.toString(), 300, 1_500);
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
