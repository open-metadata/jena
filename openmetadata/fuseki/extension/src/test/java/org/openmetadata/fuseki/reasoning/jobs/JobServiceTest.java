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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.FakeWorkers;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;
import org.openmetadata.fuseki.reasoning.WorkerLauncher;

/**
 * The job lifecycle with real worker processes: a shell script stands in for the worker JVM, so
 * cancellation, restart recovery and the worker lock act on real processes and real job
 * directories. Every job document the service returns is checked against OpenMetadata's schema.
 */
class JobServiceTest {
  private static final String SUCCEED =
      "echo '{\"status\":\"SUCCEEDED\",\"outcome\":\"CLASSIFIED\"}' > work.result.json; exit 0";

  /** Ignores nothing: on SIGTERM it takes a second to stop, then records that it stopped. */
  private static final String SLOW_TO_STOP =
      "trap 'sleep 1; touch stopped; exit 143' TERM; touch started;"
          + " while :; do sleep 0.1; done";

  @TempDir Path root;
  private FakeWorkers workers;
  private ReasoningSettings settings;
  private final AtomicLong now = new AtomicLong(1_791_497_100_000L);
  private final List<JobService> services = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    workers = new FakeWorkers(root);
    settings = workers.settings(Map.of());
  }

  @AfterEach
  void tearDown() {
    services.forEach(JobService::close);
  }

  @Test
  void aJobRunsItsWorkerStepsAndReportsTheOutcome() throws Exception {
    final JobService service = service(worker(SUCCEED), Map.of());
    final JobService.Submission submission = service.submit(request("check-subsumption-request"));
    assertTrue(submission.created());
    assertEquals("QUEUED", submission.job().getString("state"));
    assertConforms(submission.job());

    final JsonObject done = await(service, id(submission), state("SUCCEEDED"));
    assertFalse(done.hasKey("outcome"), "a step's own outcome is not the job's answer");
    assertEquals(
        JSON.parse("{\"step\":\"work\",\"completedSteps\":1,\"totalSteps\":1}"),
        done.get("progress"));
    assertConforms(done);
    final Path directory = jobDirectory(id(submission));
    assertEquals(
        "SUCCEEDED",
        JSON.read(directory.resolve("job.json").toString()).getObj("job").getString("state"));
    assertTrue(Files.isRegularFile(directory.resolve("work.status.json")));
    assertFalse(Files.exists(directory.resolve("working")), "unpublished work is discarded");
  }

  @Test
  void theSameRequestIdReturnsTheSameJobAndADifferentRequestUnderItIsRefused() throws Exception {
    final JobService service = service(worker(SUCCEED), Map.of());
    final JobService.Submission first = service.submit(request("refresh-request"));
    final JobService.Submission again = service.submit(request("refresh-request"));
    assertFalse(again.created());
    assertEquals(id(first), id(again));

    final JsonObject changed = Contract.example("refresh-request");
    changed.getObj("sourceRevision").put("liveWriteWatermark", 99);
    final JobService.Refused refused =
        assertThrows(
            JobService.Refused.class,
            () -> service.submit(JobRequest.validate(changed, "openmetadata")));
    assertEquals(409, refused.status());
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
  }

  @Test
  void jobsRunOneAtATimeInOrderAndTheQueueIsBounded() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final List<String> order = Collections.synchronizedList(new ArrayList<>());
    final Stage.Planner planner =
        (job, request) ->
            List.of(
                stage(
                    "hold",
                    context -> {
                      order.add(job.requestId().toString());
                      release.await();
                      return Stage.Result.completed();
                    }));
    final JobService service = service(planner, Map.of(JobSettings.MAX_QUEUED_JOBS, "2"));
    final UUID running = id(service.submit(request(UUID.randomUUID())));
    await(service, running, state("RUNNING"));
    final UUID second = id(service.submit(request(UUID.randomUUID())));
    final UUID third = id(service.submit(request(UUID.randomUUID())));

    final JobService.Refused full =
        assertThrows(JobService.Refused.class, () -> service.submit(request(UUID.randomUUID())));
    assertEquals(429, full.status());
    assertEquals(Problem.Code.LIMIT_EXCEEDED, full.problem().code());
    assertEquals("maxQueuedJobs", full.problem().subject());
    assertEquals("QUEUED", service.job(second).orElseThrow().getString("state"));

    release.countDown();
    await(service, third, state("SUCCEEDED"));
    assertEquals(
        List.of(
            requestIdOf(service, running),
            requestIdOf(service, second),
            requestIdOf(service, third)),
        order);
  }

  @Test
  void aQueuedJobIsCancelledAtOnceAndNeverRuns() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final List<UUID> ran = Collections.synchronizedList(new ArrayList<>());
    final Stage.Planner planner =
        (job, request) ->
            List.of(
                stage(
                    "hold",
                    context -> {
                      ran.add(job.id());
                      release.await();
                      return Stage.Result.completed();
                    }));
    final JobService service = service(planner, Map.of());
    final UUID first = id(service.submit(request(UUID.randomUUID())));
    await(service, first, state("RUNNING"));
    final UUID queued = id(service.submit(request(UUID.randomUUID())));

    final JsonObject cancelled = service.cancel(queued).orElseThrow();
    assertEquals("CANCELLED", cancelled.getString("state"));
    assertTrue(cancelled.getBoolean("cancelRequested"));
    assertConforms(cancelled);

    release.countDown();
    await(service, first, state("SUCCEEDED"));
    assertEquals(List.of(first), ran);
    assertEquals("CANCELLED", service.job(queued).orElseThrow().getString("state"));
  }

  @Test
  void aRunningJobIsReportedCancelledOnlyAfterItsWorkerHasStopped() throws Exception {
    final JobService service = service(worker(SLOW_TO_STOP), Map.of());
    final UUID id = id(service.submit(request(UUID.randomUUID())));
    final Path directory = jobDirectory(id);
    awaitFile(directory.resolve("started"));

    final JsonObject requested = service.cancel(id).orElseThrow();
    assertEquals("RUNNING", requested.getString("state"));
    assertTrue(requested.getBoolean("cancelRequested"));
    assertConforms(requested);
    assertFalse(Files.exists(directory.resolve("stopped")), "the worker takes a second to stop");

    final JsonObject cancelled = await(service, id, state("CANCELLED"));
    assertTrue(Files.exists(directory.resolve("stopped")), "CANCELLED only once the worker exited");
    assertFalse(Files.exists(directory.resolve("work.pid")));
    assertFalse(cancelled.hasKey("problem"));
    assertConforms(cancelled);
  }

  @Test
  void aCancellationThatArrivesBetweenStepsStopsBeforeTheNextOne() throws Exception {
    final CountDownLatch inFirst = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final Stage.Planner planner =
        (job, request) ->
            List.of(
                stage(
                    "first",
                    context -> {
                      inFirst.countDown();
                      release.await();
                      return Stage.Result.completed();
                    }),
                new WorkerStage(
                    "work",
                    context -> {
                      behaviour(context, "touch second-ran; " + SUCCEED);
                      return step();
                    }));
    final JobService service = service(planner, Map.of());
    final UUID id = id(service.submit(request(UUID.randomUUID())));
    inFirst.await(10, TimeUnit.SECONDS);
    service.cancel(id);
    release.countDown();
    final JsonObject cancelled = await(service, id, state("CANCELLED"));
    assertEquals("first", cancelled.getObj("progress").getString("step"));
    assertFalse(Files.exists(jobDirectory(id).resolve("second-ran")));
  }

  @Test
  void aStepWaitsWhileAnotherWorkerHoldsTheLockAndCanBeCancelledMeanwhile() throws Exception {
    final Path lockFile = Files.createDirectories(settings.reasoningRoot()).resolve(".worker.lock");
    try (FileChannel channel =
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock held = channel.lock()) {
      final JobService waiting = service(worker(SUCCEED, 60_000), Map.of());
      final UUID id = id(waiting.submit(request(UUID.randomUUID())));
      await(waiting, id, state("RUNNING"));
      Thread.sleep(1_500);
      assertEquals("RUNNING", waiting.job(id).orElseThrow().getString("state"));
      waiting.cancel(id);
      await(waiting, id, state("CANCELLED"));

      final JobService impatient = service(worker(SUCCEED, 200), Map.of());
      final JsonObject failed =
          await(impatient, id(impatient.submit(request(UUID.randomUUID()))), state("FAILED"));
      assertEquals("NOT_READY", failed.getObj("problem").getString("code"));
      assertEquals("work", failed.getObj("problem").getString("step"));
    }
  }

  @Test
  void workerFailuresBecomeTheProtocolsProblems() throws Exception {
    final JobService service = service(worker("exit 3"), Map.of());
    final JsonObject failed =
        await(service, id(service.submit(request(UUID.randomUUID()))), state("FAILED"));
    assertEquals("MEMORY_BUDGET_EXHAUSTED", failed.getObj("problem").getString("code"));
    assertEquals("work", failed.getObj("problem").getString("step"));
    assertConforms(failed);
  }

  @Test
  void aRefreshFailsAtCaptureUntilSnapshotsExist() throws Exception {
    final JobService service = service(plans(name -> null), Map.of());
    final JsonObject failed =
        await(service, id(service.submit(request("refresh-request"))), state("FAILED"));
    final JsonObject problem = failed.getObj("problem");
    assertEquals("NOT_IMPLEMENTED", problem.getString("code"));
    assertEquals("capture", problem.getString("step"));
    assertEquals(Operations.REFRESH_MISSING, problem.getString("message"));
    assertEquals(0, failed.getObj("progress").getNumber("completedSteps").intValue());
    assertConforms(failed);
  }

  @Test
  void aCheckOfAnOntologyTheDatasetDoesNotHoldFailsAtCapture() throws Exception {
    final DatasetGraph empty = DatasetGraphFactory.createTxnMem();
    final JobService service = service(plans(name -> empty), Map.of());
    // The candidate names no closure digest; the Approved explanation has no active closure.
    for (final Map.Entry<String, String> example :
        Map.of(
                "check-subsumption-request", "INVALID_REQUEST",
                "explain-request", "MISSING_IMPORT")
            .entrySet()) {
      final JsonObject failed =
          await(service, id(service.submit(request(example.getKey()))), state("FAILED"));
      final JsonObject problem = failed.getObj("problem");
      assertEquals(example.getValue(), problem.getString("code"), failed.toString());
      assertEquals("capture", problem.getString("step"));
      assertEquals(
          Contract.example(example.getKey()).getString("ontologyIri"),
          problem.getString("subject"));
      assertConforms(failed);
    }
    final JobService gone = service(plans(name -> null), Map.of());
    final JsonObject retried = Contract.example("check-subsumption-request");
    retried.put("requestId", UUID.randomUUID().toString());
    final JsonObject failed =
        await(gone, id(gone.submit(JobRequest.validate(retried, "openmetadata"))), state("FAILED"));
    assertEquals("INVALID_REQUEST", failed.getObj("problem").getString("code"));
  }

  @Test
  void aRestartInterruptsUnfinishedJobsKillsTheirWorkersAndDiscardsTheirWork() throws Exception {
    final Path jobs = Files.createDirectories(settings.reasoningRoot().resolve("jobs"));
    final JsonObject runningJob = manifest("cancel-requested-job");
    final String retriedRequestId = runningJob.getObj("job").getString("requestId");
    runningJob.put(
        "requestDigest",
        JobRequest.validate(requestFor(retriedRequestId), "openmetadata").digest());
    final UUID runningId = UUID.fromString(runningJob.getObj("job").getString("id"));
    final Path running = Files.createDirectories(jobs.resolve(runningId.toString()));
    Files.writeString(running.resolve("job.json"), JSON.toString(runningJob));
    Files.createDirectories(running.resolve("working/Data-0001"));
    Files.writeString(running.resolve("capture.nq.gz"), "partial");
    Files.writeString(running.resolve("load.json"), "{}");
    final Process orphan =
        new ProcessBuilder(
                "sh",
                "-c",
                "sleep 30; :",
                WorkerLauncher.WORKER_MAIN,
                running.resolve("load.json").toString())
            .start();
    Files.writeString(running.resolve("load.pid"), orphan.pid() + "\n");
    final Process unrelated = new ProcessBuilder("sh", "-c", "sleep 30; :").start();
    final JsonObject queuedJob = manifest("cancel-requested-job");
    final UUID queuedId = UUID.randomUUID();
    queuedJob.getObj("job").put("id", queuedId.toString());
    queuedJob.getObj("job").put("requestId", UUID.randomUUID().toString());
    queuedJob.getObj("job").put("state", "QUEUED");
    queuedJob.getObj("job").put("cancelRequested", false);
    final Path queued = Files.createDirectories(jobs.resolve(queuedId.toString()));
    Files.writeString(queued.resolve("job.json"), JSON.toString(queuedJob));
    Files.writeString(queued.resolve("other.pid"), unrelated.pid() + "\n");
    final Path byHand = Files.createDirectories(jobs.resolve("conformance/working"));
    try {
      final JobService service = service(worker(SUCCEED), Map.of());

      final JsonObject interrupted = service.job(runningId).orElseThrow();
      assertEquals("INTERRUPTED", interrupted.getString("state"));
      assertEquals(now.get(), interrupted.getNumber("completedAt").longValue());
      assertConforms(interrupted);
      assertEquals("INTERRUPTED", service.job(queuedId).orElseThrow().getString("state"));
      assertFalse(Files.exists(running.resolve("working")));
      assertFalse(Files.exists(running.resolve("capture.nq.gz")));
      assertTrue(Files.exists(running.resolve("load.json")), "step files stay for diagnosis");
      assertTrue(Files.isDirectory(byHand), "directories without a manifest are left alone");
      assertTrue(orphan.waitFor(15, TimeUnit.SECONDS), "the recorded worker is killed");
      assertTrue(unrelated.isAlive(), "a pid that is not a worker of the job is never killed");

      final JobService.Submission retry =
          service.submit(JobRequest.validate(requestFor(retriedRequestId), "openmetadata"));
      assertFalse(retry.created(), "a retried request ID finds the interrupted job");
      assertEquals(runningId.toString(), retry.job().getString("id"));
    } finally {
      orphan.destroyForcibly();
      unrelated.destroyForcibly();
    }
  }

  @Test
  void finishedJobsAreKeptUpToTheHistoryCountAndAge() throws Exception {
    final JobService service =
        service(
            worker(SUCCEED),
            Map.of(JobSettings.JOB_HISTORY, "2", JobSettings.RETENTION_HOURS, "1"));
    final List<UUID> ids = new ArrayList<>();
    final List<JobRequest> requests = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      requests.add(request(UUID.randomUUID()));
      ids.add(id(service.submit(requests.get(i))));
      await(service, ids.get(i), state("SUCCEEDED"));
      now.addAndGet(1_000);
    }
    assertTrue(service.job(ids.get(0)).isEmpty(), "the oldest of three is evicted");
    assertFalse(Files.exists(jobDirectory(ids.get(0))));
    assertTrue(service.submit(requests.get(0)).created(), "its request ID is free again");
    assertTrue(service.job(ids.get(2)).isPresent());

    now.addAndGet(TimeUnit.HOURS.toMillis(2));
    service.submit(request(UUID.randomUUID()));
    assertTrue(service.job(ids.get(2)).isEmpty(), "jobs older than the retention are evicted");
  }

  @Test
  void noJobIsAdmittedOverTheDiskQuotaOrWhileNotReady() throws Exception {
    final JobService full = service(worker(SUCCEED), Map.of(JobSettings.DISK_QUOTA, "1k"));
    Files.write(
        Files.createDirectories(settings.reasoningRoot().resolve("jobs/conformance"))
            .resolve("big.nq"),
        new byte[2048]);
    final JobService.Refused overQuota =
        assertThrows(JobService.Refused.class, () -> full.submit(request(UUID.randomUUID())));
    assertEquals(503, overQuota.status());
    assertEquals("maxDiskBytes", overQuota.problem().subject());

    settings = workers.settings(Map.of("OPENMETADATA_REASONER_HEAP", "1t"));
    final JobService small = service(worker(SUCCEED), Map.of());
    final JobService.Refused notReady =
        assertThrows(JobService.Refused.class, () -> small.submit(request(UUID.randomUUID())));
    assertEquals(503, notReady.status());
    assertEquals(Problem.Code.NOT_READY, notReady.problem().code());
    assertEquals("NOT_READY", small.capabilities().getString("readiness"));
  }

  @Test
  void aDisabledServiceAnswersWithoutTouchingTheDisk() throws Exception {
    settings = workers.settings(Map.of("OPENMETADATA_REASONING_ENABLED", "false"));
    final JobService service = service(worker(SUCCEED), Map.of());
    final JobService.Refused refused =
        assertThrows(JobService.Refused.class, () -> service.submit(request(UUID.randomUUID())));
    assertEquals(503, refused.status());
    final JsonObject capabilities = service.capabilities();
    assertEquals("DISABLED", capabilities.getString("readiness"));
    assertEquals(List.of(), Contract.violations(Contract.CAPABILITIES, capabilities));
    assertFalse(Files.exists(settings.reasoningRoot()));
  }

  @Test
  void capabilitiesConformAndReportTheBudgetAndLimits() throws Exception {
    final JobService service = service(worker(SUCCEED), Map.of(JobSettings.DISK_QUOTA, "3g"));
    final JsonObject capabilities = service.capabilities();
    assertEquals(List.of(), Contract.violations(Contract.CAPABILITIES, capabilities));
    assertEquals("1", capabilities.getString("protocolVersion"));
    assertEquals("READY", capabilities.getString("readiness"));
    assertEquals(
        64L << 30,
        capabilities.getObj("memoryBudget").getNumber("containerLimitBytes").longValue());
    assertEquals(16, capabilities.getObj("limits").getNumber("maxQueuedJobs").intValue());
    assertEquals(3L << 30, capabilities.getObj("limits").getNumber("maxDiskBytes").longValue());
    final JsonObject limits = capabilities.getObj("limits");
    assertEquals(3600, limits.getNumber("maxJobSeconds").intValue());
    assertEquals(Operations.MAX_IMPORTS, limits.getNumber("maxImports").intValue());
    assertEquals(32, limits.getNumber("maxExpressionDepth").intValue());
    assertEquals(200_000, limits.getNumber("maxClosureAxioms").longValue(), "2 GiB worker heap");
    assertEquals(50_000, limits.getNumber("maxIndividuals").longValue());
    assertTrue(capabilities.get("engines").getAsArray().isEmpty(), "no worker jar in this test");
    final Map<String, String> availability = new java.util.LinkedHashMap<>();
    capabilities
        .get("operations")
        .getAsArray()
        .forEach(
            support ->
                availability.put(
                    support.getAsObject().getString("operation"),
                    support.getAsObject().getString("availability")));
    assertEquals(
        Contract.enumeration(Contract.CAPABILITIES, "operation"),
        new ArrayList<>(availability.keySet()));
    assertEquals(
        Map.of(
            "REFRESH", "NOT_IMPLEMENTED",
            "CHECK", "AVAILABLE",
            "EXPLAIN", "AVAILABLE",
            "RESULTS", "NOT_IMPLEMENTED",
            "QUERY", "NOT_IMPLEMENTED"),
        availability);
  }

  @Test
  void capabilitiesCannotClaimAnOperationWithoutTheLimitsItNeeds() throws Exception {
    final JobService service = service(worker(SUCCEED), Map.of());
    for (final String operation : List.of("REFRESH", "RESULTS", "QUERY")) {
      final JsonObject claimed = service.capabilities();
      claimed
          .get("operations")
          .getAsArray()
          .forEach(
              support -> {
                if (support.getAsObject().getString("operation").equals(operation)) {
                  support.getAsObject().put("availability", "AVAILABLE");
                }
              });
      assertTrue(
          Contract.violations(Contract.CAPABILITIES, claimed).stream()
              .anyMatch(violation -> violation.startsWith("$.limits.")),
          operation + " was accepted as AVAILABLE without its limits");
    }
    final JsonObject withoutJobSeconds = service.capabilities();
    withoutJobSeconds.getObj("limits").remove("maxJobSeconds");
    assertTrue(
        Contract.violations(Contract.CAPABILITIES, withoutJobSeconds).stream()
            .anyMatch(violation -> violation.startsWith("$.limits.")),
        "CHECK was accepted as AVAILABLE without maxJobSeconds");
    assertEquals(
        List.of(),
        Contract.violations(Contract.CAPABILITIES, Contract.example("ready-capabilities")));
  }

  @Test
  void stoppingTheServerInterruptsTheRunningJob() throws Exception {
    final JobService service = service(worker(SLOW_TO_STOP), Map.of());
    final UUID id = id(service.submit(request(UUID.randomUUID())));
    awaitFile(jobDirectory(id).resolve("started"));
    service.close();
    final JsonObject manifest =
        JSON.read(jobDirectory(id).resolve("job.json").toString()).getObj("job");
    assertEquals("INTERRUPTED", manifest.getString("state"));
  }

  @Test
  void openMetadataJobExamplesSurviveTheManifestUnchanged() {
    for (final String example :
        List.of(
            "succeeded-refresh-job",
            "incomplete-check-job",
            "generation-mismatch-job",
            "cancel-requested-job",
            "interrupted-job",
            "not-implemented-job")) {
      final JsonObject json = Contract.example(example);
      assertEquals(json, Job.fromManifest(manifest(example)).toJson(), example);
    }
  }

  @Test
  void theStatesOutcomesAndProblemCodesAreTheProtocols() {
    assertEquals(
        Contract.enumeration(Contract.JOB, "state"),
        Arrays.stream(Job.State.values()).map(Enum::name).toList());
    assertEquals(
        Contract.enumeration(Contract.JOB, "problemCode"),
        Arrays.stream(Problem.Code.values()).map(Enum::name).toList());
    assertEquals(
        Contract.enumeration(Contract.REQUEST, "operation"),
        Arrays.stream(JobRequest.Operation.values()).map(Enum::name).toList());
  }

  private JobService service(final Stage.Planner planner, final Map<String, String> jobEnv)
      throws IOException {
    final ReasoningSettings current = settings;
    final JobService service =
        new JobService(
            current,
            JobSettings.fromEnvironment(jobEnv),
            workers.launcher(current),
            () -> workers.readiness(current),
            planner,
            now::get);
    services.add(service);
    service.open();
    service.start();
    return service;
  }

  private Stage.Planner plans(final ServingDatasets datasets) {
    return Plans.current(new Operations(settings, JobSettings.fromEnvironment(Map.of())), datasets);
  }

  private static Stage.Planner worker(final String behaviour) {
    return worker(behaviour, WorkerStage.DEFAULT_MAX_BUSY_WAIT_MILLIS);
  }

  private static Stage.Planner worker(final String behaviour, final long maxBusyWaitMillis) {
    return (job, request) ->
        List.of(
            new WorkerStage(
                "work",
                context -> {
                  behaviour(context, behaviour);
                  return step();
                },
                maxBusyWaitMillis));
  }

  private static void behaviour(final Stage.Context context, final String behaviour) {
    try {
      Files.writeString(context.directory().resolve("behaviour.sh"), behaviour + "\n");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonObject step() {
    return JSON.parse("{\"protocol\": 1, \"timeoutMillis\": 60000}");
  }

  interface Body {
    Stage.Result run(Stage.Context context) throws InterruptedException;
  }

  private static Stage stage(final String name, final Body body) {
    return new Stage() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Result run(final Context context) throws InterruptedException {
        return body.run(context);
      }
    };
  }

  private static JobRequest request(final String example) throws JobRequest.Invalid {
    return JobRequest.validate(Contract.example(example), "openmetadata");
  }

  private static JobRequest request(final UUID requestId) throws JobRequest.Invalid {
    return JobRequest.validate(requestFor(requestId.toString()), "openmetadata");
  }

  private static JsonObject requestFor(final String requestId) {
    final JsonObject document = Contract.example("refresh-request");
    document.put("requestId", requestId);
    return document;
  }

  /** An OpenMetadata job example wrapped as the store's manifest. */
  private static JsonObject manifest(final String example) {
    final JsonObject manifest = new JsonObject();
    manifest.put("job", Contract.example(example));
    manifest.put("dataset", "openmetadata");
    manifest.put("requestDigest", "sha256:" + "0".repeat(64));
    return manifest;
  }

  private static UUID id(final JobService.Submission submission) {
    return UUID.fromString(submission.job().getString("id"));
  }

  private static String requestIdOf(final JobService service, final UUID id) {
    return service.job(id).orElseThrow().getString("requestId");
  }

  private Path jobDirectory(final UUID id) {
    return settings.reasoningRoot().resolve("jobs").resolve(id.toString());
  }

  private static Predicate<JsonObject> state(final String state) {
    return job -> state.equals(job.getString("state"));
  }

  private static JsonObject await(
      final JobService service, final UUID id, final Predicate<JsonObject> condition)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      final JsonObject job = service.job(id).orElseThrow();
      if (condition.test(job)) {
        return job;
      }
      assertTrue(System.nanoTime() < deadline, "Timed out; last state " + job);
      Thread.sleep(20);
    }
  }

  private static void awaitFile(final Path file) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, file + " never appeared");
      Thread.sleep(20);
    }
  }

  private static void assertConforms(final JsonObject job) {
    assertEquals(List.of(), Contract.violations(Contract.JOB, job), () -> JSON.toString(job));
  }
}
