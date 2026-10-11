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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What CLASSIFY persists and COMPACT keeps: the finite entailments in the output graph answer the
 * design's discovery query, "find tables containing Party data", through the inferred hierarchy; an
 * inconsistent closure leaves its output graph empty.
 */
class EntailmentGraphTest {
  @TempDir static Path job;

  @BeforeAll
  static void classifyAndCompact() throws Exception {
    Conformance.prepare(job);
    for (final String fixture :
        List.of("el-intersection-existential", "el-disjoint-inconsistent")) {
      assertEquals(ExitCodes.COMPLETED, Conformance.run(job.resolve(fixture + ".json")));
    }
    final Path compact =
        Jobs.write(
            job,
            "compact",
            """
            {"protocol": 1, "stepId": "compact", "kind": "COMPACT", "dataset": "working",
             "timeoutMillis": 60000}
            """);
    assertEquals(ExitCodes.COMPLETED, Jobs.run(compact));
    assertEquals("COMPACTED", Jobs.outcome(compact));
  }

  @Test
  void mappedAssetsAreDiscoverableThroughTheInferredHierarchy() {
    final List<String> tables =
        select(
            """
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX om: <https://open-metadata.org/ontology/>
PREFIX : <http://example.org/el-intersection-existential#>
SELECT ?asset WHERE {
  GRAPH <urn:fixture:el-intersection-existential:entailments> { ?c rdfs:subClassOf* :Party }
  GRAPH <urn:fixture:el-intersection-existential> { ?c om:mappedTo ?asset }
}
""");
    assertEquals(List.of("https://example.org/tables/registered_customers"), tables);
  }

  @Test
  void anInconsistentClosurePersistsNoConsequences() {
    assertTrue(
        select(
                "SELECT ?s WHERE { GRAPH <urn:fixture:el-disjoint-inconsistent:entailments>"
                    + " { ?s ?p ?o } }")
            .isEmpty());
  }

  private static List<String> select(final String query) {
    final DatasetGraph dataset =
        DatabaseMgr.connectDatasetGraph(Location.create(job.resolve("working")));
    try {
      return Txn.calculateRead(
          dataset,
          () -> {
            try (var execution =
                QueryExecutionFactory.create(
                    query, org.apache.jena.query.DatasetFactory.wrap(dataset))) {
              return ResultSetFormatter.toList(execution.execSelect()).stream()
                  .map(row -> row.get(row.varNames().next()).toString())
                  .toList();
            }
          });
    } finally {
      TDBInternal.expel(dataset);
    }
  }
}
