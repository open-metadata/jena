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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imports are never fetched. An HTTP server on localhost serves a valid ontology at every IRI the
 * inputs name and counts requests; whatever the inputs say, it must receive none.
 */
class NoNetworkTest {
  @TempDir Path job;
  private HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private String base;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          final byte[] body =
              ("<?xml version=\"1.0\"?><rdf:RDF"
                      + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                      + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\"><owl:Ontology/></rdf:RDF>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/rdf+xml");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    Jobs.file(
        job,
        "input.trig",
        """
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        GRAPH <urn:missing> {
          <http://example.org/missing> a owl:Ontology ; owl:imports <%1$s/remote> .
          <http://example.org/n#A> a owl:Class .
        }
        GRAPH <urn:local-root> {
          <http://example.org/local> a owl:Ontology ; owl:imports <%1$s/served> .
          <http://example.org/n#A> a owl:Class ; rdfs:subClassOf <http://example.org/n#B> .
        }
        GRAPH <urn:local-import> {
          <%1$s/served> a owl:Ontology .
          <http://example.org/n#B> a owl:Class .
        }
        """
            .formatted(base));
    assertEquals(
        ExitCodes.COMPLETED, Jobs.run(Jobs.write(job, "load", Jobs.load("working", "input.trig"))));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void anImportOutsideTheClosureIsRejectedWithoutAFetch() {
    final Path step =
        Jobs.write(
            job,
            "missing",
            Jobs.classify("missing", "http://example.org/missing", "urn:missing", ""));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(step));
    assertEquals("MISSING_IMPORT", Jobs.firstProblem(step));
    assertEquals(0, requests.get());
  }

  @Test
  void anImportInsideTheClosureResolvesLocallyEvenWhenItsIriIsReachable() throws Exception {
    final String step =
        """
{"protocol": 1, "stepId": "local", "kind": "CLASSIFY", "dataset": "working",
 "timeoutMillis": 60000, "report": true,
 "closure": {"root": "http://example.org/local", "graphs": ["urn:local-root", "urn:local-import"]}}
""";
    final Path file = Jobs.write(job, "local", step);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(file));
    assertEquals(
        "OUTCOME CLASSIFIED\nSUBCLASS <http://example.org/n#A> <http://example.org/n#B>\n",
        Jobs.report(file));
    assertEquals(0, requests.get());
  }

  @Test
  void importsSmuggledIntoChecksOrExplanationsAreNotFetched() {
    Jobs.file(
        job,
        "checks.ofn",
        "Ontology(Import(<"
            + base
            + "/checks>) SubClassOf(<http://example.org/n#A> <http://example.org/n#B>))");
    final Path checks =
        Jobs.write(
            job,
            "checks",
            """
{"protocol": 1, "stepId": "checks", "kind": "CLASSIFY", "dataset": "working",
 "timeoutMillis": 60000, "checks": "checks.ofn",
 "closure": {"root": "http://example.org/local", "graphs": ["urn:local-root", "urn:local-import"]}}
""");
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(checks));
    final Path explain =
        Jobs.write(
            job,
            "explain",
            """
{"protocol": 1, "stepId": "explain", "kind": "EXPLAIN", "dataset": "working",
 "timeoutMillis": 60000,
 "closure": {"root": "http://example.org/local", "graphs": ["urn:local-root", "urn:local-import"]},
 "explain": {"axiom": "Import(<%s/explain>) SubClassOf(<http://example.org/n#A> <http://example.org/n#B>)"}}
"""
                .formatted(base));
    assertEquals(ExitCodes.INPUT_REJECTED, Jobs.run(explain));
    assertEquals(0, requests.get());
  }
}
