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
package org.openmetadata.fuseki;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiModules;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.tdb2.TDB2Factory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThriftRowCapIT {
  private static final Node GRAPH = NodeFactory.createURI("urn:test:graph");
  private static final int UPLOAD_LIMIT = 1024 * 1024;
  private static final int LITERAL_LENGTH = 150_000;
  @TempDir private Path directory;
  private Dataset dataset;
  private FusekiServer server;
  private HttpClient client;
  private URI endpoint;

  @BeforeEach
  void start() {
    dataset = TDB2Factory.connectDataset(directory.resolve("tdb").toString());
    server =
        FusekiServer.create()
            .port(0)
            .fusekiModules(FusekiModules.empty())
            .registerOperation(Operation.GSP_RW, new BoundedGraphStore(5000, UPLOAD_LIMIT))
            .add("/review", dataset)
            .build()
            .start();
    client = HttpClient.newHttpClient();
    endpoint =
        URI.create("http://localhost:" + server.getPort() + "/review/data?graph=urn:test:graph");
  }

  @AfterEach
  void stop() {
    client.close();
    server.stop();
    dataset.close();
  }

  @Test
  void aRowLargerThanJenasStockCapIsStoredWithItsExactLength() throws Exception {
    final HttpResponse<String> response = post(StockThriftRowCapIT.thrift(LITERAL_LENGTH));

    assertEquals(201, response.statusCode(), response.body());
    assertEquals(List.of(LITERAL_LENGTH), storedLiteralLengths());
  }

  @Test
  void replacingAGraphAcceptsTheSameRow() throws Exception {
    assertEquals(201, post(StockThriftRowCapIT.thrift(10)).statusCode());

    final HttpResponse<String> response =
        send(request().PUT(HttpRequest.BodyPublishers.ofByteArray(StockThriftRowCapIT.thrift(LITERAL_LENGTH))));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(List.of(LITERAL_LENGTH), storedLiteralLengths());
  }

  @Test
  void aBodyAboveTheUploadBoundIsStillRefusedWithNothingStored() throws Exception {
    final HttpResponse<String> response = post(StockThriftRowCapIT.thrift(UPLOAD_LIMIT + 1));

    assertEquals(413, response.statusCode(), response.body());
    assertEquals(List.of(), storedLiteralLengths());
  }

  private HttpRequest.Builder request() {
    return HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/rdf+thrift");
  }

  private HttpResponse<String> post(final byte[] body) throws Exception {
    return send(request().POST(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private HttpResponse<String> send(final HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<Integer> storedLiteralLengths() {
    dataset.begin(ReadWrite.READ);
    try {
      return dataset
          .asDatasetGraph()
          .getGraph(GRAPH)
          .find()
          .mapWith(triple -> triple.getObject().getLiteralLexicalForm().length())
          .toList();
    } finally {
      dataset.end();
    }
  }
}
