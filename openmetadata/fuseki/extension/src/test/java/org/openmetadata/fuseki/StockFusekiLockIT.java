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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiModules;
import org.apache.jena.query.Dataset;
import org.apache.jena.tdb2.TDB2Factory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README's reason for the extension is that stock Fuseki holds TDB2's single writer lock while
 * a Graph Store upload body transfers. This runs stock Fuseki, with no extension, and fails when
 * that stops being true: the extension is then worth removing, or at least rewriting.
 */
class StockFusekiLockIT {
  @TempDir private Path directory;

  @Test
  void stockGraphStoreUploadHoldsTheWriterLockWhileTheBodyTransfers() throws Exception {
    final Dataset dataset = TDB2Factory.connectDataset(directory.resolve("tdb").toString());
    final FusekiServer server =
        FusekiServer.create()
            .port(0)
            .fusekiModules(FusekiModules.empty())
            .add("/stock", dataset)
            .build()
            .start();
    try (Socket upload = new Socket("localhost", server.getPort());
        HttpClient client = HttpClient.newHttpClient()) {
      final String headers =
          "POST /stock/data?graph=urn:test:graph HTTP/1.1\r\nHost: localhost\r\n"
              + "Content-Type: application/n-triples\r\nContent-Length: 1000000\r\n\r\n";
      final HttpRequest update =
          HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/stock/update"))
              .timeout(Duration.ofSeconds(2))
              .header("Content-Type", "application/sparql-update")
              .POST(HttpRequest.BodyPublishers.ofString("INSERT DATA { <urn:a> <urn:b> <urn:c> }"))
              .build();
      assertEquals(
          2,
          client.send(update, HttpResponse.BodyHandlers.discarding()).statusCode() / 100,
          "without an upload in flight the update must succeed, or the timeout below proves nothing");

      upload.getOutputStream().write(
          (headers + "<urn:test:s> <urn:test:p> <urn:test:o> .\n").getBytes(StandardCharsets.UTF_8));
      upload.getOutputStream().flush();
      Thread.sleep(500);

      assertThrows(
          HttpTimeoutException.class,
          () -> client.send(update, HttpResponse.BodyHandlers.discarding()),
          "stock Fuseki no longer blocks a small update behind a slow Graph Store upload."
              + " Re-measure with README.md's scenario, then decide whether the extension is"
              + " still needed");
    } finally {
      server.stop();
      dataset.close();
    }
  }
}
