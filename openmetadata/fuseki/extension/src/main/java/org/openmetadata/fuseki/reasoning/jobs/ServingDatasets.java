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

import org.apache.jena.fuseki.server.DataAccessPoint;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.apache.jena.sparql.core.DatasetGraph;

/** The datasets this server serves, by the name a job request gives with {@code ?dataset=}. */
@FunctionalInterface
public interface ServingDatasets {
  /** The dataset served as {@code /name}, or null when there is none. */
  DatasetGraph get(String name);

  /** The datasets of a running server's registry, looked up when a job starts. */
  static ServingDatasets of(final DataAccessPointRegistry registry) {
    return name -> {
      final DataAccessPoint point = registry.get("/" + name);
      return point == null ? null : point.getDataService().getDataset();
    };
  }
}
