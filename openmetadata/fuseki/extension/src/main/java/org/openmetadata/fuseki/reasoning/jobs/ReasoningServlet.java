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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;

/**
 * The reasoning operations, served by one servlet the module registers at {@value #PATH_SPEC}.
 * Fuseki dispatches a dataset's operations by endpoint name, so {@code /reasoning/jobs/{id}} could
 * not be routed there; this servlet routes the path itself. It sits behind the same Shiro filter as
 * every other path, so the image's shiro.ini makes it admin-only.
 *
 * <pre>
 *   GET    /reasoning/capabilities           protocol version, readiness, operations, memory
 *                                            budget, limits
 *   POST   /reasoning/jobs?dataset=NAME      submit a job against a serving dataset: 202, or 200
 *                                            with the existing job for a known request ID
 *   GET    /reasoning/jobs/{id}              the job's state
 *   DELETE /reasoning/jobs/{id}              request cancellation: 202 while it stops, else 200
 *   GET    /reasoning/results/{id}           a page of a finished job's result
 *                                            (?offset=, ?limit= up to maxResultRows)
 *   POST   /reasoning/query?dataset=NAME     a bounded SELECT or ASK over the dataset's current
 *                                            snapshot
 * </pre>
 *
 * Every refusal answers with the protocol's problem object.
 */
public final class ReasoningServlet extends HttpServlet {
  public static final String PATH_SPEC = "/reasoning/*";
  private static final Pattern DATASET = Pattern.compile("/?[A-Za-z0-9][A-Za-z0-9_.-]*");

  private final transient JobService jobs;

  public ReasoningServlet(final JobService jobs) {
    this.jobs = jobs;
  }

  enum Resource {
    CAPABILITIES("GET"),
    JOBS("POST"),
    JOB("GET", "DELETE"),
    RESULTS("GET"),
    QUERY("POST");

    private final Set<String> methods;

    Resource(final String... methods) {
      this.methods = Set.of(methods);
    }

    Set<String> methods() {
      return methods;
    }
  }

  record Route(Resource resource, UUID id) {}

  /** The resource a path below /reasoning names, or empty when it names none. */
  static Optional<Route> route(final String pathInfo) {
    if (pathInfo == null) {
      return Optional.empty();
    }
    final String[] parts = pathInfo.split("/", -1);
    if (parts.length == 2) {
      return switch (parts[1]) {
        case "capabilities" -> Optional.of(new Route(Resource.CAPABILITIES, null));
        case "jobs" -> Optional.of(new Route(Resource.JOBS, null));
        case "query" -> Optional.of(new Route(Resource.QUERY, null));
        default -> Optional.empty();
      };
    }
    if (parts.length == 3) {
      final Optional<UUID> id = uuid(parts[2]);
      if (id.isPresent() && parts[1].equals("jobs")) {
        return Optional.of(new Route(Resource.JOB, id.get()));
      }
      if (id.isPresent() && parts[1].equals("results")) {
        return Optional.of(new Route(Resource.RESULTS, id.get()));
      }
    }
    return Optional.empty();
  }

  @Override
  protected void service(final HttpServletRequest request, final HttpServletResponse response)
      throws IOException {
    final Optional<Route> found = route(request.getPathInfo());
    if (found.isEmpty()) {
      problem(response, 404, "No reasoning operation at " + request.getRequestURI());
      return;
    }
    final Route route = found.get();
    final String method = request.getMethod();
    if (!route.resource().methods().contains(method)) {
      response.setHeader("Allow", String.join(", ", route.resource().methods()));
      problem(response, 405, method + " is not allowed here");
      return;
    }
    switch (route.resource()) {
      case CAPABILITIES -> send(response, 200, jobs.capabilities());
      case JOBS -> submit(request, response);
      case JOB -> {
        final Optional<JsonObject> job =
            method.equals("GET") ? jobs.job(route.id()) : jobs.cancel(route.id());
        if (job.isEmpty()) {
          problem(response, 404, "No job " + route.id());
        } else {
          final boolean stopping = "RUNNING".equals(job.get().getString("state"));
          send(response, method.equals("DELETE") && stopping ? 202 : 200, job.get());
        }
      }
      case RESULTS -> results(request, response, route.id());
      case QUERY -> query(request, response);
    }
  }

  private void results(
      final HttpServletRequest request, final HttpServletResponse response, final UUID id)
      throws IOException {
    final int max = jobs.operations().jobSettings().maxResultRows();
    final Integer offset = integer(request.getParameter("offset"), 0, Integer.MAX_VALUE);
    final Integer limit = integer(request.getParameter("limit"), 1, max);
    if (offset == null || limit == null) {
      problem(response, 400, "offset is a non-negative integer and limit is 1 to " + max);
      return;
    }
    try {
      send(
          response,
          200,
          jobs.results(id, offset, request.getParameter("limit") == null ? max : limit));
    } catch (final JobService.Refused e) {
      send(response, e.status(), e.problem().toJson());
    }
  }

  private void query(final HttpServletRequest request, final HttpServletResponse response)
      throws IOException {
    final String name = servedDataset(request, response);
    if (name == null) {
      return;
    }
    final byte[] body = jsonBody(request, response, SnapshotQueries.MAX_REQUEST_BYTES);
    if (body == null) {
      return;
    }
    try {
      final byte[] answer = jobs.operations().queries().run(name, body).body();
      response.setStatus(200);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-store");
      response.setContentLength(answer.length);
      try (OutputStream out = response.getOutputStream()) {
        out.write(answer);
      }
    } catch (final JobService.Refused e) {
      send(response, e.status(), e.problem().toJson());
    }
  }

  /** A query parameter as an integer in range, the fallback when absent, or null when invalid. */
  private static Integer integer(final String value, final int min, final int max) {
    if (value == null) {
      return min == 0 ? 0 : max;
    }
    try {
      final int parsed = Integer.parseInt(value);
      return parsed < min || parsed > max ? null : parsed;
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  /** The served dataset ?dataset= names, or null once a refusal has been sent. */
  private String servedDataset(final HttpServletRequest request, final HttpServletResponse response)
      throws IOException {
    final String dataset = request.getParameter("dataset");
    if (dataset == null || !DATASET.matcher(dataset).matches()) {
      problem(response, 400, "Name the serving dataset with ?dataset=<name>");
      return null;
    }
    final String name = dataset.startsWith("/") ? dataset.substring(1) : dataset;
    final DataAccessPointRegistry registry = DataAccessPointRegistry.get(getServletContext());
    if (registry == null || !registry.isRegistered("/" + name)) {
      problem(response, 404, "No dataset '" + name + "' is served here");
      return null;
    }
    return name;
  }

  /** A JSON body of at most {@code max} bytes, or null once a refusal has been sent. */
  private static byte[] jsonBody(
      final HttpServletRequest request, final HttpServletResponse response, final int max)
      throws IOException {
    final String contentType = request.getContentType();
    if (contentType == null
        || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
      problem(response, 415, "A request body is application/json");
      return null;
    }
    if (request.getContentLengthLong() > max) {
      problem(response, 413, "A request body is at most " + max + " bytes");
      return null;
    }
    final byte[] body = readBounded(request.getInputStream(), max);
    if (body == null) {
      problem(response, 413, "A request body is at most " + max + " bytes");
    }
    return body;
  }

  private void submit(final HttpServletRequest request, final HttpServletResponse response)
      throws IOException {
    final String name = servedDataset(request, response);
    if (name == null) {
      return;
    }
    final byte[] body = jsonBody(request, response, JobRequest.MAX_BYTES);
    if (body == null) {
      return;
    }
    final JobRequest jobRequest;
    try {
      jobRequest = JobRequest.read(body, name);
    } catch (final JobRequest.Invalid e) {
      problem(response, 400, e.getMessage());
      return;
    }
    try {
      final JobService.Submission submission = jobs.submit(jobRequest);
      final String id = submission.job().getString("id");
      response.setHeader("Location", request.getContextPath() + "/reasoning/jobs/" + id);
      send(response, submission.created() ? 202 : 200, submission.job());
    } catch (final JobService.Refused e) {
      send(response, e.status(), e.problem().toJson());
    }
  }

  /** The body, or null when it is longer than {@code max}; never reads more than max + 1 bytes. */
  static byte[] readBounded(final InputStream in, final int max) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(buffer, 0, Math.min(buffer.length, max + 1 - total))) > 0) {
      out.write(buffer, 0, read);
      total += read;
      if (total > max) {
        return null;
      }
    }
    return out.toByteArray();
  }

  private static void problem(
      final HttpServletResponse response, final int status, final String message)
      throws IOException {
    send(response, status, Problem.of(Problem.Code.INVALID_REQUEST, message).toJson());
  }

  private static void send(
      final HttpServletResponse response, final int status, final JsonObject body)
      throws IOException {
    final byte[] bytes = JSON.toString(body).getBytes(StandardCharsets.UTF_8);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setContentLength(bytes.length);
    try (OutputStream out = response.getOutputStream()) {
      out.write(bytes);
    }
  }

  private static Optional<UUID> uuid(final String text) {
    try {
      final UUID id = UUID.fromString(text);
      return id.toString().equals(text.toLowerCase(Locale.ROOT))
          ? Optional.of(id)
          : Optional.empty();
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
