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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.fuseki.reasoning.jobs.ReasoningServlet.Resource;
import org.openmetadata.fuseki.reasoning.jobs.ReasoningServlet.Route;

class ReasoningServletTest {
  private static final UUID ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");

  @Test
  void pathsBelowReasoningNameTheirResource() {
    assertEquals(route(Resource.CAPABILITIES, null), ReasoningServlet.route("/capabilities"));
    assertEquals(route(Resource.JOBS, null), ReasoningServlet.route("/jobs"));
    assertEquals(route(Resource.JOB, ID), ReasoningServlet.route("/jobs/" + ID));
    assertEquals(
        route(Resource.JOB, ID), ReasoningServlet.route("/jobs/" + ID.toString().toUpperCase()));
    assertEquals(route(Resource.RESULTS, ID), ReasoningServlet.route("/results/" + ID));
    assertEquals(route(Resource.QUERY, null), ReasoningServlet.route("/query"));
  }

  @Test
  void anythingElseNamesNothing() {
    for (final String path :
        new String[] {
          null,
          "",
          "/",
          "/jobs/",
          "/jobs/42",
          "/jobs/" + ID + "/",
          "/jobs/" + ID + "/x",
          "/capabilities/x",
          "/results",
          "/results/" + ID.toString().replace("-", ""),
          "/../jobs"
        }) {
      assertEquals(Optional.empty(), ReasoningServlet.route(path), String.valueOf(path));
    }
  }

  @Test
  void bodiesAreReadOnlyUpToTheirBound() throws Exception {
    assertArrayEquals(
        new byte[4], ReasoningServlet.readBounded(new ByteArrayInputStream(new byte[4]), 4));
    assertNull(ReasoningServlet.readBounded(new ByteArrayInputStream(new byte[5]), 4));
    final ByteArrayInputStream endless = new ByteArrayInputStream(new byte[100_000]);
    assertNull(ReasoningServlet.readBounded(endless, 10_000));
    assertEquals(100_000 - 10_001, endless.available(), "reads at most one byte past the bound");
  }

  private static Optional<Route> route(final Resource resource, final UUID id) {
    return Optional.of(new Route(resource, id));
  }
}
