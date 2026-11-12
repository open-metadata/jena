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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release the smoke test uploads to the built image is the Turtle beside it, and a capture
 * accepts it as the release the smoke test names, so a stale or hand-edited fixture fails here
 * rather than in a container.
 */
class ReleaseFixtureTest {
  private static final Path TEST = Path.of("..", "test");
  private static final String ONTOLOGY_ID = "3b9e1f0a-6c2d-4e8f-9a1b-7d5c3e2f1a0b";
  private static final String RELEASE_ID = "8f4a2c6e-1b3d-4f5a-9c7e-2d1b0a9f8e7d";

  @TempDir Path directory;

  @Test
  void theThriftFileIsTheTurtleSource() throws Exception {
    final Model turtle = ModelFactory.createDefaultModel();
    RDFDataMgr.read(turtle, TEST.resolve("check-release.ttl").toUri().toString(), Lang.TURTLE);
    final Model thrift = ModelFactory.createDefaultModel();
    try (InputStream in = Files.newInputStream(TEST.resolve("check-release.rdf-thrift"))) {
      RDFDataMgr.read(thrift, in, Lang.RDFTHRIFT);
    }
    assertTrue(turtle.isIsomorphicWith(thrift), "regenerate check-release.rdf-thrift");
  }

  @Test
  void aCaptureAcceptsItAsTheReleaseTheSmokeTestNames() throws Exception {
    final Path file = TEST.resolve("check-release.rdf-thrift");
    final byte[] bytes = Files.readAllBytes(file);
    final Capture.Result captured =
        Capture.run(
            file,
            new Capture.Release(
                ONTOLOGY_ID, RELEASE_ID, "sha256:" + Releases.hash(bytes), bytes.length),
            directory.resolve("capture.nq.gz"),
            new Capture.Bounds(1 << 20, 1 << 30, System.nanoTime() + TimeUnit.MINUTES.toNanos(1)),
            () -> false);
    assertEquals(Capture.RELEASE_IRI_PREFIX + RELEASE_ID, captured.versionIri());
  }
}
