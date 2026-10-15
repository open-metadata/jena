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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiModules;
import org.apache.jena.fuseki.mod.shiro.FMod_Shiro;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.reasoning.FakeWorkers;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;

/**
 * The reasoning operations over HTTP, on a real Fuseki server with Shiro: first with the image's
 * own shiro.ini, where only the admin account may use them, then with the operator configuration
 * README.md documents for a non-admin service account. Jobs run a stand-in worker process.
 */
class ReasoningOperationsIT {
  private static final Path IMAGE_SHIRO = Path.of("..", "shiro.ini");
  private static final String SLOW_TO_STOP =
      "trap 'sleep 1; touch stopped; exit 143' TERM; touch started;"
          + " while :; do sleep 0.1; done";

  @TempDir Path root;
  private FusekiServer server;
  private JobService service;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
    if (service != null) {
      service.close();
    }
    System.clearProperty("FUSEKI_SHIRO");
    client.close();
  }

  @Test
  void theImagesShiroIniAdmitsOnlyTheAdminAccount() throws Exception {
    final Path shiro = root.resolve("shiro.ini");
    Files.writeString(
        shiro, Files.readString(IMAGE_SHIRO).replace("${ADMIN_PASSWORD}", "admin-pw"));
    start(shiro, worker("exit 0"));

    assertEquals(200, get("/reasoning/capabilities", "admin", "admin-pw").statusCode());
    assertEquals(401, get("/reasoning/capabilities", null, null).statusCode());
    assertEquals(401, get("/reasoning/capabilities", "admin", "wrong").statusCode());
    assertEquals(
        401,
        post("/reasoning/jobs?dataset=ds", null, null, example("refresh-request")).statusCode());
    assertEquals(401, get("/reasoning/jobs/" + UUID.randomUUID(), null, null).statusCode());
  }

  @Test
  void theDocumentedServiceAccountMayUseReasoningAndNothingElse() throws Exception {
    start(resource("shiro/reasoning-service-account.ini"), worker("exit 0"));

    assertEquals(200, get("/reasoning/capabilities", "openmetadata", "service-pw").statusCode());
    assertEquals(
        202,
        post("/reasoning/jobs?dataset=ds", "openmetadata", "service-pw", example("refresh-request"))
            .statusCode());
    assertEquals(200, get("/reasoning/capabilities", "admin", "admin-pw").statusCode());
    assertEquals(403, get("/reasoning/capabilities", "reader", "reader-pw").statusCode());
    assertEquals(401, get("/reasoning/capabilities", null, null).statusCode());
    assertEquals(403, get("/ds/sparql?query=ASK%7B%7D", "openmetadata", "service-pw").statusCode());
  }

  @Test
  void aJobIsSubmittedQueriedAndCancelledOverHttp() throws Exception {
    start(resource("shiro/reasoning-service-account.ini"), worker(SLOW_TO_STOP));

    final HttpResponse<String> capabilities = get("/reasoning/capabilities", "admin", "admin-pw");
    assertTrue(
        capabilities
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("application/json"));
    final JsonObject capabilitiesJson = JSON.parse(capabilities.body());
    assertEquals(List.of(), Contract.violations(Contract.CAPABILITIES, capabilitiesJson));
    assertEquals("READY", capabilitiesJson.getString("readiness"));

    final String request = example("refresh-request");
    final HttpResponse<String> submitted = post("/reasoning/jobs?dataset=ds", request);
    assertEquals(202, submitted.statusCode(), submitted.body());
    final JsonObject job = JSON.parse(submitted.body());
    assertEquals(List.of(), Contract.violations(Contract.JOB, job));
    final String id = job.getString("id");
    assertEquals("/reasoning/jobs/" + id, submitted.headers().firstValue("Location").orElseThrow());

    final HttpResponse<String> retried = post("/reasoning/jobs?dataset=ds", request);
    assertEquals(200, retried.statusCode());
    assertEquals(id, JSON.parse(retried.body()).getString("id"));

    final Path started = settings().reasoningRoot().resolve("jobs/" + id + "/started");
    awaitFile(started);
    final HttpResponse<String> cancelling = delete("/reasoning/jobs/" + id);
    assertEquals(202, cancelling.statusCode(), cancelling.body());
    final JsonObject stopping = JSON.parse(cancelling.body());
    assertEquals("RUNNING", stopping.getString("state"));
    assertTrue(stopping.getBoolean("cancelRequested"));

    final JsonObject cancelled = awaitState(id, "CANCELLED");
    assertEquals(List.of(), Contract.violations(Contract.JOB, cancelled));
    assertTrue(Files.exists(started.resolveSibling("stopped")));
    assertEquals(200, delete("/reasoning/jobs/" + id).statusCode(), "a finished job is unchanged");
  }

  @Test
  void refusalsAnswerWithTheProtocolsProblem() throws Exception {
    start(resource("shiro/reasoning-service-account.ini"), worker("exit 0"));

    assertProblem(
        post("/reasoning/jobs?dataset=ds", "{\"operation\": \"REFRESH\"}"), 400, "INVALID_REQUEST");
    assertProblem(post("/reasoning/jobs", example("refresh-request")), 400, "INVALID_REQUEST");
    assertProblem(
        post("/reasoning/jobs?dataset=nope", example("refresh-request")), 404, "INVALID_REQUEST");
    assertProblem(
        send("POST", "/reasoning/jobs?dataset=ds", "text/plain", example("refresh-request")),
        415,
        "INVALID_REQUEST");
    assertProblem(
        post("/reasoning/jobs?dataset=ds", " ".repeat(JobRequest.MAX_BYTES + 1)),
        413,
        "INVALID_REQUEST");
    assertProblem(
        get("/reasoning/jobs/" + UUID.randomUUID(), "admin", "admin-pw"), 404, "INVALID_REQUEST");
    assertProblem(get("/reasoning/jobs/not-a-uuid", "admin", "admin-pw"), 404, "INVALID_REQUEST");
    final HttpResponse<String> wrongMethod = send("PUT", "/reasoning/capabilities", null, "");
    assertProblem(wrongMethod, 405, "INVALID_REQUEST");
    assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());
    assertProblem(
        get("/reasoning/results/" + UUID.randomUUID(), "admin", "admin-pw"),
        404,
        "INVALID_REQUEST");
    assertProblem(post("/reasoning/query", "{}"), 400, "INVALID_REQUEST");
    assertProblem(post("/reasoning/query?dataset=nope", "{}"), 404, "INVALID_REQUEST");
    assertProblem(post("/reasoning/query?dataset=ds", "{\"query\": \"ASK {}\"}"), 503, "NOT_READY");
    assertProblem(
        post("/reasoning/query?dataset=ds", "{\"query\": \"DESCRIBE <urn:x>\"}"),
        400,
        "INVALID_REQUEST");
  }

  private void start(final Path shiro, final Stage.Planner planner) throws IOException {
    final FakeWorkers workers = workers();
    final ReasoningSettings settings = workers.settings(Map.of());
    service =
        new JobService(
            new Operations(settings, JobSettings.fromEnvironment(Map.of())),
            workers.launcher(settings),
            () -> workers.readiness(settings),
            planner,
            System::currentTimeMillis);
    service.open();
    service.start();
    System.setProperty("FUSEKI_SHIRO", shiro.toAbsolutePath().toString());
    server =
        FusekiServer.create()
            .port(0)
            .fusekiModules(FusekiModules.create(FMod_Shiro.create()))
            .add("/ds", DatasetFactory.createTxnMem())
            .addServlet(ReasoningServlet.PATH_SPEC, new ReasoningServlet(service))
            .build()
            .start();
  }

  private FakeWorkers workers() throws IOException {
    return new FakeWorkers(root);
  }

  private ReasoningSettings settings() throws IOException {
    return workers().settings(Map.of());
  }

  private static Stage.Planner worker(final String behaviour) {
    return (job, request) ->
        List.of(
            new WorkerStage(
                "work",
                context -> {
                  try {
                    Files.writeString(context.directory().resolve("behaviour.sh"), behaviour);
                  } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                  }
                  return JSON.parse("{\"protocol\": 1, \"timeoutMillis\": 60000}");
                }));
  }

  private JsonObject awaitState(final String id, final String state) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (true) {
      final JsonObject job = JSON.parse(get("/reasoning/jobs/" + id, "admin", "admin-pw").body());
      if (state.equals(job.getString("state"))) {
        return job;
      }
      assertTrue(System.nanoTime() < deadline, "Timed out; last " + job);
      Thread.sleep(50);
    }
  }

  private static void awaitFile(final Path file) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!Files.exists(file)) {
      assertTrue(System.nanoTime() < deadline, file + " never appeared");
      Thread.sleep(20);
    }
  }

  private static void assertProblem(
      final HttpResponse<String> response, final int status, final String code) {
    assertEquals(status, response.statusCode(), response.body());
    final JsonObject problem = JSON.parse(response.body());
    assertEquals(code, problem.getString("code"), response.body());
    assertTrue(problem.hasKey("message"));
  }

  private HttpResponse<String> get(final String path, final String user, final String password)
      throws Exception {
    return client.send(
        authorized(HttpRequest.newBuilder(uri(path)), user, password).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(
      final String path, final String user, final String password, final String body)
      throws Exception {
    return client.send(
        authorized(HttpRequest.newBuilder(uri(path)), user, password)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(final String path, final String body) throws Exception {
    return post(path, "admin", "admin-pw", body);
  }

  private HttpResponse<String> delete(final String path) throws Exception {
    return send("DELETE", path, null, "");
  }

  private HttpResponse<String> send(
      final String method, final String path, final String contentType, final String body)
      throws Exception {
    final HttpRequest.Builder builder =
        authorized(HttpRequest.newBuilder(uri(path)), "admin", "admin-pw")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest.Builder authorized(
      final HttpRequest.Builder builder, final String user, final String password) {
    builder.timeout(Duration.ofSeconds(30));
    if (user == null) {
      return builder;
    }
    final String credentials = user + ":" + password;
    return builder.header(
        "Authorization",
        "Basic "
            + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + server.getPort() + path);
  }

  private static String example(final String name) {
    final JsonObject document = Contract.example(name);
    document.put("requestId", UUID.randomUUID().toString());
    return JSON.toString(document);
  }

  private static Path resource(final String name) {
    try {
      return Path.of(ReasoningOperationsIT.class.getResource("/" + name).toURI());
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
