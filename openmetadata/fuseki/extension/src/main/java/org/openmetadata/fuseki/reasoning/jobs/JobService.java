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
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonNumber;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.fuseki.reasoning.ContainerMemory;
import org.openmetadata.fuseki.reasoning.Readiness;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;
import org.openmetadata.fuseki.reasoning.WorkerLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admits, queues, runs, cancels and remembers reasoning jobs. Jobs run one at a time, in order, on
 * one thread of their own, never on a request thread; at most {@link JobSettings#maxQueuedJobs()}
 * wait behind the running one. Every change of state is written to the job's manifest before it is
 * reported. After a restart, jobs that had not finished are INTERRUPTED and their unpublished work
 * and any worker they left are removed.
 *
 * <p>A request ID names one job for as long as the job is kept: submitting it again returns that
 * job. Finished jobs are kept up to a count and an age, and their directories go with them.
 */
public final class JobService implements AutoCloseable {
  public static final String PROTOCOL_VERSION = "1";

  /** Thread interruption ends a worker step with SIGKILL and then waits for the worker to exit. */
  private static final long STOP_WAIT_MILLIS = 60_000;

  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private final ReasoningSettings settings;
  private final JobSettings jobSettings;
  private final WorkerLauncher launcher;
  private final Supplier<Readiness> readiness;
  private final Stage.Planner planner;
  private final LongSupplier clock;
  private final JobStore store;

  private final Object lock = new Object();
  private final Map<UUID, Job> jobs = new HashMap<>();
  private final Map<UUID, Job> byRequest = new HashMap<>();
  private final ArrayDeque<Job> queue = new ArrayDeque<>();
  private Thread runner;
  private boolean closed;

  /** A job, and whether this submission created it or found it by its request ID. */
  public record Submission(JsonObject job, boolean created) {}

  /** A request the service will not act on, with the HTTP status and problem to answer it with. */
  public static final class Refused extends Exception {
    private final int status;
    private final Problem problem;

    Refused(final int status, final Problem problem) {
      super(problem.message());
      this.status = status;
      this.problem = problem;
    }

    public int status() {
      return status;
    }

    public Problem problem() {
      return problem;
    }
  }

  JobService(
      final ReasoningSettings settings,
      final JobSettings jobSettings,
      final WorkerLauncher launcher,
      final Supplier<Readiness> readiness,
      final Stage.Planner planner,
      final LongSupplier clock) {
    this.settings = settings;
    this.jobSettings = jobSettings;
    this.launcher = launcher;
    this.readiness = readiness;
    this.planner = planner;
    this.clock = clock;
    this.store = new JobStore(settings.reasoningRoot());
  }

  /** The service the Fuseki module runs, with the container's memory and this JVM's heap. */
  public static JobService create(final ReasoningSettings settings, final JobSettings jobs) {
    final long fusekiHeap = Runtime.getRuntime().maxMemory();
    final ContainerMemory memory = ContainerMemory.system();
    return new JobService(
        settings,
        jobs,
        new WorkerLauncher(settings, memory, fusekiHeap, false),
        () -> Readiness.evaluate(settings, memory, fusekiHeap, false, false),
        Plans.current(),
        System::currentTimeMillis);
  }

  /**
   * Loads the kept jobs and recovers those a previous run left unfinished. Does nothing while
   * reasoning is disabled, so a disabled server never writes to the reasoning root.
   */
  public void open() throws IOException {
    if (!settings.enabled()) {
      return;
    }
    Files.createDirectories(settings.reasoningRoot().resolve("jobs"));
    int interrupted = 0;
    synchronized (lock) {
      for (final Job job : store.load()) {
        if (!job.state().finished()) {
          store.killRecordedWorkers(job.id());
          store.discardWork(job.id());
          job.finish(Job.State.INTERRUPTED, null, null, clock.getAsLong());
          store.save(job);
          interrupted++;
        }
        jobs.put(job.id(), job);
        byRequest.put(job.requestId(), job);
      }
      retain();
    }
    LOG.info(
        "Reasoning jobs: {} kept, {} interrupted by the last shutdown", jobs.size(), interrupted);
  }

  /** Starts the thread that runs jobs. */
  public void start() {
    if (!settings.enabled()) {
      return;
    }
    synchronized (lock) {
      if (runner != null || closed) {
        return;
      }
      runner = new Thread(this::runJobs, "openmetadata-reasoning-jobs");
      runner.setDaemon(true);
      runner.start();
    }
  }

  /**
   * Stops the job thread. A running worker is killed and awaited, and its job ends INTERRUPTED;
   * queued jobs are interrupted when the server next opens the service.
   */
  @Override
  public void close() {
    final Thread thread;
    synchronized (lock) {
      closed = true;
      lock.notifyAll();
      thread = runner;
    }
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(STOP_WAIT_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** OpenMetadata's rdfReasoningCapabilities document. */
  public JsonObject capabilities() {
    final JsonObject json = new JsonObject();
    json.put("protocolVersion", PROTOCOL_VERSION);
    final JsonArray reasons = new JsonArray();
    Readiness budget = null;
    try {
      budget = readiness.get();
      json.put("readiness", budget.status().name());
      budget.reasons().forEach(reasons::add);
    } catch (final RuntimeException e) {
      json.put("readiness", settings.enabled() ? "NOT_READY" : "DISABLED");
      reasons.add("The memory budget cannot be evaluated: " + e.getMessage());
    }
    json.put("reasons", reasons);
    json.put("operations", Plans.operations());
    // No operation reaches an engine until input capture exists, so none is claimed.
    json.put("engines", new JsonArray());
    json.put("coverage", new JsonArray());
    if (budget != null) {
      final JsonObject memory = new JsonObject();
      memory.put("containerLimitBytes", budget.availableBytes());
      memory.put("requiredBytes", budget.requiredBytes());
      memory.put("fusekiHeapBytes", budget.terms().get("fusekiHeap"));
      memory.put("fusekiOffHeapBytes", budget.terms().get("fusekiOffHeap"));
      memory.put("workerHeapBytes", budget.terms().get("workerHeap"));
      memory.put("workerOffHeapBytes", budget.terms().get("workerOffHeap"));
      memory.put("pageCacheFloorBytes", budget.terms().get("pageCacheFloor"));
      memory.put("safetyMargin", JsonNumber.valueDecimal("0.1"));
      json.put("memoryBudget", memory);
    }
    // Only the limits this build enforces. The protocol requires the rest before the operations
    // that need them may be AVAILABLE, not before readiness may be READY.
    final JsonObject limits = new JsonObject();
    limits.put("maxQueuedJobs", jobSettings.maxQueuedJobs());
    try {
      limits.put("maxDiskBytes", jobSettings.diskQuota(settings.reasoningRoot()));
    } catch (final RuntimeException e) {
      LOG.warn("Cannot resolve the reasoning disk quota: {}", e.toString());
    }
    json.put("limits", limits);
    return json;
  }

  public Submission submit(final JobRequest request) throws Refused {
    if (!settings.enabled()) {
      throw new Refused(
          503,
          Problem.of(
              Problem.Code.NOT_READY,
              "Reasoning is disabled: " + ReasoningSettings.ENABLED + " is not true"));
    }
    synchronized (lock) {
      final Optional<Submission> existing = existing(request);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    final Readiness budget;
    try {
      budget = readiness.get();
    } catch (final RuntimeException e) {
      throw new Refused(
          503,
          Problem.of(
              Problem.Code.NOT_READY, "The memory budget cannot be evaluated: " + e.getMessage()));
    }
    if (!budget.admits()) {
      throw new Refused(
          503, Problem.of(Problem.Code.NOT_READY, String.join(" ", budget.reasons())));
    }
    synchronized (lock) {
      final Optional<Submission> existing = existing(request);
      if (existing.isPresent()) {
        return existing.get();
      }
      if (closed) {
        throw new Refused(503, Problem.of(Problem.Code.NOT_READY, "The server is stopping"));
      }
      retain();
      if (queue.size() >= jobSettings.maxQueuedJobs()) {
        throw new Refused(
            429,
            Problem.of(
                    Problem.Code.LIMIT_EXCEEDED,
                    queue.size()
                        + " jobs are already queued; the limit is "
                        + jobSettings.maxQueuedJobs())
                .withSubject("maxQueuedJobs"));
      }
      requireDisk();
      final Job job = Job.submitted(request, clock.getAsLong());
      try {
        store.create(job, request);
      } catch (final IOException e) {
        deleteQuietly(job.id());
        LOG.error("Cannot record reasoning job {}: {}", job.id(), e.toString());
        throw new Refused(
            500, Problem.of(Problem.Code.INTERNAL, "The job could not be recorded on disk"));
      }
      jobs.put(job.id(), job);
      byRequest.put(job.requestId(), job);
      queue.addLast(job);
      lock.notifyAll();
      LOG.info(
          "Reasoning job {} queued: {} request {}", job.id(), job.operation(), job.requestId());
      return new Submission(job.toJson(), true);
    }
  }

  public Optional<JsonObject> job(final UUID id) {
    synchronized (lock) {
      return Optional.ofNullable(jobs.get(id)).map(Job::toJson);
    }
  }

  /**
   * Requests cancellation. A queued job is CANCELLED at once, since it never started. A running job
   * stays RUNNING with cancelRequested until its current step has stopped: its worker gets SIGTERM
   * now and SIGKILL after the launcher's grace period. A finished job is returned unchanged.
   */
  public Optional<JsonObject> cancel(final UUID id) {
    synchronized (lock) {
      final Job job = jobs.get(id);
      if (job == null) {
        return Optional.empty();
      }
      if (job.state() == Job.State.QUEUED) {
        queue.remove(job);
        job.requestCancel();
        finish(job, Job.State.CANCELLED, null, null);
        LOG.info("Reasoning job {} cancelled before it started", id);
      } else if (job.state() == Job.State.RUNNING) {
        if (!job.cancelRequested()) {
          job.requestCancel();
          save(job);
          LOG.info("Reasoning job {}: cancellation requested", id);
        }
        // Under the lock the running job cannot change, so this stops only its step; a step that
        // starts after the flag was set cancels itself.
        launcher.cancel();
      }
      return Optional.of(job.toJson());
    }
  }

  private Optional<Submission> existing(final JobRequest request) throws Refused {
    final Job job = byRequest.get(request.requestId());
    if (job == null) {
      return Optional.empty();
    }
    if (!job.requestDigest().equals(request.digest())) {
      throw new Refused(
          409,
          Problem.of(
              Problem.Code.INVALID_REQUEST,
              "Request ID "
                  + request.requestId()
                  + " already names job "
                  + job.id()
                  + ", submitted with a different request or dataset"));
    }
    return Optional.of(new Submission(job.toJson(), false));
  }

  private void requireDisk() throws Refused {
    final Path root = settings.reasoningRoot();
    final long used;
    final long quota;
    try {
      used = JobStore.sizeOnDisk(root);
      quota = jobSettings.diskQuota(root);
    } catch (final IOException | RuntimeException e) {
      throw new Refused(
          503,
          Problem.of(Problem.Code.NOT_READY, "Disk use cannot be measured: " + e.getMessage()));
    }
    if (used >= quota) {
      throw new Refused(
          503,
          Problem.of(
                  Problem.Code.LIMIT_EXCEEDED,
                  "The reasoning data uses "
                      + used
                      + " bytes, at or over its quota of "
                      + quota
                      + " bytes; no job is admitted until finished jobs are removed")
              .withSubject("maxDiskBytes"));
    }
  }

  private void runJobs() {
    while (true) {
      final Job job;
      synchronized (lock) {
        while (queue.isEmpty() && !closed) {
          try {
            lock.wait();
          } catch (final InterruptedException e) {
            return;
          }
        }
        if (closed) {
          return;
        }
        job = queue.removeFirst();
      }
      try {
        execute(job);
      } catch (final RuntimeException e) {
        LOG.error("Reasoning job {} failed unexpectedly", job.id(), e);
        end(job, Job.State.FAILED, null, Problem.of(Problem.Code.INTERNAL, e.toString()));
      }
    }
  }

  private void execute(final Job job) {
    final JsonObject request;
    final List<Stage> stages;
    try {
      request = store.request(job.id());
      stages = planner.plan(job, request);
    } catch (final IOException | RuntimeException e) {
      end(
          job,
          Job.State.FAILED,
          null,
          Problem.of(Problem.Code.INTERNAL, "The job cannot be planned: " + e.getMessage()));
      return;
    }
    synchronized (lock) {
      if (job.state() != Job.State.QUEUED) {
        return;
      }
      job.start(clock.getAsLong(), stages.size());
      save(job);
    }
    LOG.info("Reasoning job {} started: {} steps", job.id(), stages.size());
    final Stage.Context context =
        new Stage.Context(job, store.directory(job.id()), request, launcher, job::cancelRequested);
    String outcome = null;
    for (int i = 0; i < stages.size(); i++) {
      if (job.cancelRequested()) {
        end(job, Job.State.CANCELLED, null, null);
        return;
      }
      final Stage stage = stages.get(i);
      synchronized (lock) {
        job.progress(stage.name(), i);
        save(job);
      }
      final Stage.Result result;
      try {
        result = stage.run(context);
      } catch (final InterruptedException e) {
        interrupted(job);
        return;
      } catch (final IOException e) {
        if (Thread.currentThread().isInterrupted()) {
          interrupted(job);
        } else {
          end(
              job,
              Job.State.FAILED,
              null,
              Problem.of(Problem.Code.INTERNAL, e.getMessage()).atStep(stage.name()));
        }
        return;
      }
      if (result.cancelled()) {
        end(job, Job.State.CANCELLED, null, null);
        return;
      }
      if (result.problem() != null) {
        end(job, Job.State.FAILED, null, result.problem());
        return;
      }
      if (result.outcome() != null) {
        outcome = result.outcome();
      }
      synchronized (lock) {
        job.progress(stage.name(), i + 1);
        save(job);
      }
    }
    end(job, Job.State.SUCCEEDED, outcome, null);
  }

  /**
   * Ends a job the server is stopping. The interrupt is cleared first, since it would close the
   * file channels that record the state; the job thread exits after this anyway.
   */
  private void interrupted(final Job job) {
    Thread.interrupted();
    end(job, Job.State.INTERRUPTED, null, null);
  }

  /** Discards the job's unpublished work, then reports its final state. */
  private void end(
      final Job job, final Job.State state, final String outcome, final Problem problem) {
    try {
      store.discardWork(job.id());
    } catch (final IOException e) {
      LOG.warn("Cannot discard the work of reasoning job {}: {}", job.id(), e.toString());
    }
    synchronized (lock) {
      finish(job, state, outcome, problem);
      retain();
    }
    LOG.info(
        "Reasoning job {} {}{}",
        job.id(),
        state,
        problem == null ? "" : ": " + problem.code() + " " + problem.message());
  }

  /** Ends a job once; a job cancelled while it was being dequeued stays CANCELLED. */
  private void finish(
      final Job job, final Job.State state, final String outcome, final Problem problem) {
    if (job.state().finished()) {
      return;
    }
    job.finish(state, outcome, problem, clock.getAsLong());
    save(job);
  }

  /** Removes finished jobs beyond the history's count or age, oldest first. Under the lock. */
  private void retain() {
    final long cutoff = clock.getAsLong() - jobSettings.retentionMillis();
    final List<Job> finished =
        jobs.values().stream()
            .filter(job -> job.state().finished())
            .sorted(Comparator.comparingLong(Job::completedAt))
            .toList();
    final int excess = finished.size() - jobSettings.jobHistory();
    for (int i = 0; i < finished.size(); i++) {
      final Job job = finished.get(i);
      if (i < excess || job.completedAt() < cutoff) {
        deleteQuietly(job.id());
        jobs.remove(job.id());
        byRequest.remove(job.requestId(), job);
      }
    }
  }

  private void save(final Job job) {
    try {
      store.save(job);
    } catch (final IOException e) {
      LOG.error("Cannot write the manifest of reasoning job {}: {}", job.id(), e.toString());
    }
  }

  private void deleteQuietly(final UUID id) {
    try {
      store.delete(id);
    } catch (final IOException e) {
      LOG.warn("Cannot remove reasoning job directory {}: {}", id, e.toString());
    }
  }
}
