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

import java.util.UUID;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * One job's durable state. Its public form is OpenMetadata's rdfReasoningJob.json; its manifest
 * adds what only the store needs. JobService changes it only under its lock; the cancellation flag
 * is also read, without the lock, by the step that may have to stop.
 */
public final class Job {
  public enum State {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean finished() {
      return this != QUEUED && this != RUNNING;
    }
  }

  private final UUID id;
  private final UUID requestId;
  private final JobRequest.Operation operation;
  private final String dataset;
  private final String requestDigest;
  private final JsonObject input;
  private final long submittedAt;
  private State state;
  private volatile boolean cancelRequested;
  private String outcome;
  private UUID snapshotId;
  private Problem problem;
  private String step;
  private int completedSteps;
  private int totalSteps;
  private long startedAt;
  private long completedAt;

  private Job(
      final UUID id,
      final UUID requestId,
      final JobRequest.Operation operation,
      final String dataset,
      final String requestDigest,
      final JsonObject input,
      final long submittedAt) {
    this.id = id;
    this.requestId = requestId;
    this.operation = operation;
    this.dataset = dataset;
    this.requestDigest = requestDigest;
    this.input = input;
    this.submittedAt = submittedAt;
    this.state = State.QUEUED;
  }

  static Job submitted(final JobRequest request, final long now) {
    return new Job(
        UUID.randomUUID(),
        request.requestId(),
        request.operation(),
        request.dataset(),
        request.digest(),
        request.input(),
        now);
  }

  public UUID id() {
    return id;
  }

  public UUID requestId() {
    return requestId;
  }

  public JobRequest.Operation operation() {
    return operation;
  }

  public String dataset() {
    return dataset;
  }

  String requestDigest() {
    return requestDigest;
  }

  public State state() {
    return state;
  }

  public boolean cancelRequested() {
    return cancelRequested;
  }

  public Problem problem() {
    return problem;
  }

  long completedAt() {
    return completedAt;
  }

  void start(final long now, final int steps) {
    state = State.RUNNING;
    startedAt = now;
    totalSteps = steps;
  }

  void progress(final String stepName, final int completed) {
    step = stepName;
    completedSteps = completed;
  }

  void requestCancel() {
    cancelRequested = true;
  }

  void finish(final State finalState, final String answer, final Problem why, final long now) {
    state = finalState;
    outcome = answer;
    problem = why;
    completedAt = now;
  }

  /** The job as OpenMetadata reads it. */
  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("id", id.toString());
    json.put("requestId", requestId.toString());
    json.put("operation", operation.name());
    json.put("state", state.name());
    json.put("cancelRequested", cancelRequested);
    json.put("input", JSON.copy(input));
    if (outcome != null) {
      json.put("outcome", outcome);
    }
    if (snapshotId != null) {
      json.put("snapshotId", snapshotId.toString());
    }
    if (step != null) {
      final JsonObject progress = new JsonObject();
      progress.put("step", step);
      progress.put("completedSteps", completedSteps);
      progress.put("totalSteps", totalSteps);
      json.put("progress", progress);
    }
    if (problem != null) {
      json.put("problem", problem.toJson());
    }
    json.put("submittedAt", submittedAt);
    if (startedAt != 0) {
      json.put("startedAt", startedAt);
    }
    if (completedAt != 0) {
      json.put("completedAt", completedAt);
    }
    return json;
  }

  JsonObject toManifest() {
    final JsonObject manifest = new JsonObject();
    manifest.put("job", toJson());
    manifest.put("dataset", dataset);
    manifest.put("requestDigest", requestDigest);
    return manifest;
  }

  static Job fromManifest(final JsonObject manifest) {
    final JsonObject json = manifest.getObj("job");
    final Job job =
        new Job(
            UUID.fromString(json.getString("id")),
            UUID.fromString(json.getString("requestId")),
            JobRequest.Operation.valueOf(json.getString("operation")),
            manifest.getString("dataset"),
            manifest.getString("requestDigest"),
            json.getObj("input"),
            json.getNumber("submittedAt").longValue());
    job.state = State.valueOf(json.getString("state"));
    job.cancelRequested = json.getBoolean("cancelRequested");
    job.outcome = json.hasKey("outcome") ? json.getString("outcome") : null;
    job.snapshotId =
        json.hasKey("snapshotId") ? UUID.fromString(json.getString("snapshotId")) : null;
    job.problem = json.hasKey("problem") ? Problem.fromJson(json.getObj("problem")) : null;
    if (json.hasKey("progress")) {
      final JsonObject progress = json.getObj("progress");
      job.step = progress.getString("step");
      job.completedSteps = progress.getNumber("completedSteps").intValue();
      job.totalSteps = progress.getNumber("totalSteps").intValue();
    }
    job.startedAt = number(json, "startedAt");
    job.completedAt = number(json, "completedAt");
    return job;
  }

  private static long number(final JsonObject json, final String key) {
    final JsonValue value = json.get(key);
    return value == null ? 0 : value.getAsNumber().value().longValue();
  }
}
