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
package org.openmetadata.reasoner.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.sys.TDBInternal;

/** OpenMetadata's starter rules as a RULES bundle, and readers of what a step left behind. */
public final class RuleFixtures {
  static final String OM = "https://open-metadata.org/ontology/";
  static final String ASSERTED = "urn:catalog:asserted";
  static final String PROVENANCE = "urn:catalog:provenance";
  static final List<String> STARTER_RULES =
      List.of(
          "domain-membership-inheritance",
          "pii-propagation-via-lineage",
          "schema-tag-inheritance",
          "transitive-lineage-closure");

  private RuleFixtures() {}

  /** A starter rule as OpenMetadata ships it, with its rule graph as output. */
  static JsonObject starter(final String name) {
    try (InputStream in = RuleFixtures.class.getResourceAsStream("/rules/" + name + ".json")) {
      final JsonObject rule = JSON.parse(in);
      rule.put("output", output(name));
      return rule;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String starterBody(final String name) {
    return starter(name).getString("ruleBody");
  }

  /** The starter bundle as OpenMetadata ships it: the all-pairs lineage closure disabled. */
  public static List<JsonObject> starterBundle() {
    return STARTER_RULES.stream().map(RuleFixtures::starter).toList();
  }

  static JsonObject rule(final String name, final String body) {
    final JsonObject rule = new JsonObject();
    rule.put("name", name);
    rule.put("ruleBody", body);
    rule.put("output", output(name));
    return rule;
  }

  static String output(final String rule) {
    return "urn:catalog:inferred:" + rule;
  }

  /** A RULES step over {@link #ASSERTED}. */
  public static String step(
      final String stepId,
      final List<JsonObject> rules,
      final String limits,
      final boolean report) {
    final JsonObject step = new JsonObject();
    step.put("protocol", 1);
    step.put("stepId", stepId);
    step.put("kind", "RULES");
    step.put("dataset", "working");
    step.put("timeoutMillis", 120_000);
    step.put("report", report);
    final JsonArray read = new JsonArray();
    read.add(new JsonString(ASSERTED));
    step.put("read", read);
    step.put("provenance", PROVENANCE);
    final JsonArray array = new JsonArray();
    rules.forEach(array::add);
    step.put("rules", array);
    if (limits != null) {
      step.put("limits", JSON.parse(limits));
    }
    return JSON.toString(step);
  }

  /** The triples of a graph of the working dataset, as sorted N-Triples terms. */
  static Set<String> triples(final Path job, final String graph) {
    return read(
        job,
        dataset -> {
          final Set<String> triples = new TreeSet<>();
          dataset
              .find(NodeFactory.createURI(graph), Node.ANY, Node.ANY, Node.ANY)
              .forEachRemaining(quad -> triples.add(NodeFmtLib.strNT(quad.asTriple())));
          return triples;
        });
  }

  /** Every triple the rules derived, whichever output graph holds it. */
  static Set<String> derived(final Path job, final List<JsonObject> rules) {
    final Set<String> triples = new TreeSet<>();
    for (final JsonObject rule : rules) {
      if (rule.hasKey("output")) {
        triples.addAll(triples(job, rule.getString("output")));
      }
    }
    return triples;
  }

  static <T> T read(final Path job, final Function<DatasetGraph, T> reader) {
    final DatasetGraph dataset =
        DatabaseMgr.connectDatasetGraph(Location.create(job.resolve("working")));
    try {
      return Txn.calculateRead(dataset, () -> reader.apply(dataset));
    } finally {
      TDBInternal.expel(dataset);
    }
  }

  /**
   * A catalog shaped for the starter rules at scale, in graph {@link #ASSERTED}: {@code layers}
   * layers of {@code tablesPerLayer} tables with {@code columnsPerTable} columns each, every table
   * in one of 20 schemas and 10 domains, and column lineage only from each layer to the next, each
   * column feeding {@code fanOut} random columns there. One schema in ten carries a PII tag, and so
   * does one column in a hundred. Deterministic (Park-Miller).
   */
  public static String lineageCatalog(
      final int layers, final int tablesPerLayer, final int columnsPerTable, final int fanOut) {
    final int tables = layers * tablesPerLayer;
    final StringBuilder trig = new StringBuilder(tables * columnsPerTable * (120 + 160 * fanOut));
    trig.append("PREFIX om: <")
        .append(OM)
        .append(">\n")
        .append("PREFIX : <http://example.org/scale/>\n")
        .append("GRAPH <")
        .append(ASSERTED)
        .append("> {\n")
        .append(":pii om:tagFQN \"PII.Sensitive\" .\n:tier om:tagFQN \"Tier.Tier2\" .\n");
    for (int s = 0; s < 20; s++) {
      trig.append(":s").append(s).append(" a om:DatabaseSchema");
      trig.append(s % 10 == 0 ? " ; om:hasTag :pii" : s % 10 == 1 ? " ; om:hasTag :tier" : "");
      trig.append(" .\n");
    }
    final long[] seed = {13};
    final java.util.function.IntUnaryOperator next =
        bound -> {
          seed[0] = (seed[0] * 16807) % 2147483647;
          return (int) (seed[0] % bound);
        };
    final int layerColumns = tablesPerLayer * columnsPerTable;
    long lineage = 0;
    for (int t = 0; t < tables; t++) {
      trig.append(":t").append(t).append(" a om:Table ; om:belongsToSchema :s").append(t % 20);
      trig.append(" ; om:belongsToDomain :d").append(t % 10).append(" .\n");
      for (int c = 0; c < columnsPerTable; c++) {
        final int column = t * columnsPerTable + c;
        trig.append(":t").append(t).append(" om:hasColumn :c").append(column).append(" .\n");
        if (next.applyAsInt(100) == 0) {
          trig.append(":c").append(column).append(" om:hasTag :pii .\n");
        }
        final int layer = t / tablesPerLayer;
        if (layer + 1 < layers) {
          for (int f = 0; f < fanOut; f++) {
            final int target = (layer + 1) * layerColumns + next.applyAsInt(layerColumns);
            trig.append(":l").append(lineage++).append(" om:fromColumn :c").append(column);
            trig.append(" ; om:toColumn :c").append(target).append(" .\n");
          }
        }
      }
    }
    return trig.append("}\n").toString();
  }

  static String om(final String local) {
    return "<" + OM + local + ">";
  }
}
