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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;

/**
 * Captures an import closure in a JVM of its own, so a test can give it a small heap and read its
 * peak: {@code CaptureProbe <dataset> <ontology IRI> <closure digest> <spool>} prints the capture's
 * result with the heap's peak use.
 */
public final class CaptureProbe {
  private CaptureProbe() {}

  public static void main(final String[] args) throws Exception {
    final DatasetGraph dataset = DatabaseMgr.connectDatasetGraph(Location.create(Path.of(args[0])));
    final Capture.Result result =
        Capture.run(
            dataset,
            new Capture.Selection(args[1], "APPROVED", args[2]),
            Path.of(args[3]),
            new Capture.Bounds(
                Operations.MAX_IMPORTS,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                System.nanoTime() + TimeUnit.HOURS.toNanos(1)),
            () -> false);
    final JsonObject json = result.toJson();
    json.remove("graphs");
    // The sum of each heap pool's peak, an upper bound of the heap's peak use.
    long peak = 0;
    for (final MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        peak += pool.getPeakUsage().getUsed();
      }
    }
    json.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
    json.put("heapPeakBytes", peak);
    System.out.println(JSON.toStringFlat(json));
  }
}
