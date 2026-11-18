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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.Jobs;
import org.openmetadata.reasoner.rules.RuleFixtures;

/**
 * The Fuseki module runs the worker's rule code from the packaged worker jar alone. Here the jar's
 * parent loader can see Jena and the JDK but neither the worker's classes nor any of its
 * dependencies, as in the image, where only fuseki-server.jar and the extension are on the server's
 * class path: admitting a bundle and explaining from a snapshot must work, so neither path reaches
 * the OWL API.
 */
class RuleCodeIsolationIT {
  private static final Path REASONER = Path.of(System.getProperty("reasoner.directory"));
  private static final List<String> HIDDEN =
      List.of(
          "org.openmetadata.reasoner.",
          "org.semanticweb.",
          "uk.ac.manchester.",
          "org.liveontologies.",
          "org.open.hermit",
          "com.google.");

  @TempDir Path job;

  @Test
  void theRuleCodeNeedsOnlyJenaFromTheServer() throws Exception {
    final ClassLoader server =
        new ClassLoader("server", RuleCodeIsolationIT.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(final String name, final boolean resolve)
              throws ClassNotFoundException {
            for (final String prefix : HIDDEN) {
              if (name.startsWith(prefix)) {
                throw new ClassNotFoundException(name);
              }
            }
            return super.loadClass(name, resolve);
          }
        };
    try (RuleCode code = RuleCode.load(REASONER, server).orElseThrow()) {
      final List<RuleCode.RuleProblem> problems =
          code.compile(
              JSON.parseAny(
                      """
[{"name": "ok-rule", "ruleBody": "CONSTRUCT { ?s <urn:q> ?o } WHERE { ?s <urn:p> ?o }"},
 {"name": "bad-rule", "ruleBody": "CONSTRUCT { ?s <urn:q> ?o } WHERE { OPTIONAL { ?s <urn:p> ?o } }"}]
""")
                  .getAsArray(),
              64);
      assertEquals(1, problems.size(), problems.toString());
      assertEquals("bad-rule", problems.get(0).rule());
      assertEquals("RULE_NOT_MONOTONIC", problems.get(0).code());
      assertTrue(problems.get(0).message().contains("bad-rule"));

      final DatasetGraph empty = DatasetGraphFactory.createTxnMem();
      final Triple fact =
          Triple.create(
              NodeFactory.createURI("http://example.org/c3"),
              NodeFactory.createURI("https://open-metadata.org/ontology/hasTag"),
              NodeFactory.createURI("http://example.org/pii"));
      assertNull(
          Txn.calculateRead(
              empty,
              () -> code.explain(empty, RefreshCapture.PROVENANCE, fact, 16, 1000, 256, 100_000)),
          "no converged run to explain from");

      Jobs.file(
          job,
          "catalog.trig",
          """
          PREFIX om: <https://open-metadata.org/ontology/>
          PREFIX : <http://example.org/>
          GRAPH <urn:catalog:asserted> {
            :pii om:tagFQN "PII.Sensitive" . :c1 om:hasTag :pii .
            :l1 om:fromColumn :c1 ; om:toColumn :c2 . :l2 om:fromColumn :c2 ; om:toColumn :c3 .
          }
          """);
      assertEquals(0, Jobs.run(Jobs.write(job, "load", Jobs.load("working", "catalog.trig"))));
      final Path rules =
          Jobs.write(
              job, "rules", RuleFixtures.step("rules", RuleFixtures.starterBundle(), null, false));
      assertEquals(0, Jobs.run(rules), () -> Jobs.manifest(rules).toString());
      final DatasetGraph working =
          DatabaseMgr.connectDatasetGraph(Location.create(job.resolve("working")));
      try {
        final JsonObject explained =
            Txn.calculateRead(
                working,
                () ->
                    code.explain(working, "urn:catalog:provenance", fact, 16, 1000, 256, 100_000));
        assertEquals("DERIVED", explained.getString("origin"), explained.toString());
        assertEquals("pii-propagation-via-lineage", explained.getString("rule"));
        assertTrue(explained.toString().contains("\"ASSERTED\""), explained.toString());
      } finally {
        TDBInternal.expel(working);
      }
      final Class<?> bridge = Class.forName(RuleCode.BRIDGE, false, loaderOf(code));
      assertSame(loaderOf(code), bridge.getClassLoader(), "the worker jar, not the server");
    }
  }

  private static ClassLoader loaderOf(final RuleCode code) throws Exception {
    final var field = RuleCode.class.getDeclaredField("loader");
    field.setAccessible(true);
    return (ClassLoader) field.get(code);
  }
}
