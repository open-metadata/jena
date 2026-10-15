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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.fuseki.reasoning.ReasoningSettings;
import org.openmetadata.fuseki.reasoning.WorkerLimits;

/**
 * What this build can do, for capabilities: which operations are AVAILABLE, the limits the store
 * enforces for them, and the engines and constructs they reason with. CHECK and EXPLAIN run; an
 * operation is AVAILABLE only with every limit the protocol requires of it (OpenMetadata's
 * ADR:2026-10-14-rdf-reasoning-capabilities-list-each-operation-and-the-limits-it-enforces).
 */
public final class Operations {
  /**
   * Graphs in one import closure. OpenMetadata keeps each axiom, relationship type and glossary
   * term in its own graph, so a closure is many small graphs; the bound keeps the set of graph
   * names the capture holds in memory, and the worker's step file, small.
   */
  public static final int MAX_IMPORTS = 10_000;

  static final String REFRESH_MISSING =
      "Refreshing a snapshot is not available in this build; it arrives with reasoning snapshots"
          + " and the governance rules step.";
  static final String RESULTS_MISSING =
      "Snapshot results are not available in this build; they arrive with reasoning snapshots.";
  static final String QUERY_MISSING =
      "Snapshot queries are not available in this build; they arrive with reasoning snapshots.";

  /**
   * The OWL 2 constructs, by structural-specification name, that HermiT answers with full
   * semantics. A closure outside OWL 2 DL is rejected before it reaches an engine, so this is all
   * of OWL 2 DL's logical vocabulary; ELK answers only when it reports its result complete.
   */
  static final List<String> COVERAGE =
      List.of(
          "ObjectIntersectionOf",
          "ObjectUnionOf",
          "ObjectComplementOf",
          "ObjectOneOf",
          "ObjectSomeValuesFrom",
          "ObjectAllValuesFrom",
          "ObjectHasValue",
          "ObjectHasSelf",
          "ObjectMinCardinality",
          "ObjectMaxCardinality",
          "ObjectExactCardinality",
          "ObjectInverseOf",
          "DataSomeValuesFrom",
          "DataAllValuesFrom",
          "DataHasValue",
          "DataMinCardinality",
          "DataMaxCardinality",
          "DataExactCardinality",
          "DataIntersectionOf",
          "DataUnionOf",
          "DataComplementOf",
          "DataOneOf",
          "DatatypeRestriction",
          "SubClassOf",
          "EquivalentClasses",
          "DisjointClasses",
          "DisjointUnion",
          "SubObjectPropertyOf",
          "ObjectPropertyChain",
          "EquivalentObjectProperties",
          "DisjointObjectProperties",
          "InverseObjectProperties",
          "ObjectPropertyDomain",
          "ObjectPropertyRange",
          "FunctionalObjectProperty",
          "InverseFunctionalObjectProperty",
          "ReflexiveObjectProperty",
          "IrreflexiveObjectProperty",
          "SymmetricObjectProperty",
          "AsymmetricObjectProperty",
          "TransitiveObjectProperty",
          "SubDataPropertyOf",
          "EquivalentDataProperties",
          "DisjointDataProperties",
          "DataPropertyDomain",
          "DataPropertyRange",
          "FunctionalDataProperty",
          "DatatypeDefinition",
          "HasKey",
          "SameIndividual",
          "DifferentIndividuals",
          "ClassAssertion",
          "ObjectPropertyAssertion",
          "NegativeObjectPropertyAssertion",
          "DataPropertyAssertion",
          "NegativeDataPropertyAssertion");

  private final ReasoningSettings settings;
  private final JobSettings jobSettings;
  private final WorkerLimits workerLimits;
  private final JsonArray engines;

  public Operations(final ReasoningSettings settings, final JobSettings jobSettings) {
    this.settings = settings;
    this.jobSettings = jobSettings;
    this.workerLimits = WorkerLimits.forHeap(settings.workerHeap());
    this.engines = engines(settings.fusekiHome().resolve("reasoner"));
  }

  public WorkerLimits workerLimits() {
    return workerLimits;
  }

  public JobSettings jobSettings() {
    return jobSettings;
  }

  public ReasoningSettings settings() {
    return settings;
  }

  /**
   * The capabilities' operations list. CHECK and EXPLAIN are reported NOT_IMPLEMENTED when the disk
   * quota cannot be resolved: they would then run without one of the limits they need.
   */
  JsonArray operations(final boolean diskQuotaKnown) {
    final String noQuota =
        diskQuotaKnown
            ? null
            : "The reasoning disk quota cannot be resolved, so it is not enforced.";
    final JsonArray operations = new JsonArray();
    operations.add(support("REFRESH", REFRESH_MISSING));
    operations.add(support("CHECK", noQuota));
    operations.add(support("EXPLAIN", noQuota));
    operations.add(support("RESULTS", RESULTS_MISSING));
    operations.add(support("QUERY", QUERY_MISSING));
    return operations;
  }

  /**
   * The limits the store enforces. maxClosureAxioms, maxIndividuals and maxExpressionDepth are the
   * worker's admission limits for its heap; the capture also refuses a closure with more triples
   * than the worker would parse, which protects the axiom limit before any parsing.
   */
  JsonObject limits(final long diskQuota) {
    final JsonObject limits = new JsonObject();
    limits.put("maxQueuedJobs", jobSettings.maxQueuedJobs());
    limits.put("maxClosureAxioms", workerLimits.maxLogicalAxioms());
    limits.put("maxIndividuals", workerLimits.maxIndividuals());
    limits.put("maxExpressionDepth", workerLimits.maxExpressionDepth());
    limits.put("maxImports", MAX_IMPORTS);
    limits.put("maxJobSeconds", jobSettings.maxJobSeconds());
    if (diskQuota > 0) {
      limits.put("maxDiskBytes", diskQuota);
    }
    return limits;
  }

  JsonArray engines() {
    return engines;
  }

  static JsonArray coverage() {
    final JsonArray coverage = new JsonArray();
    COVERAGE.forEach(coverage::add);
    return coverage;
  }

  /** An engine as capabilities and a job's input list it, from a worker manifest's engine. */
  static JsonObject engine(final String name, final String version) {
    final JsonObject engine = new JsonObject();
    engine.put("name", name);
    engine.put("version", version);
    engine.put("profile", name.equals("ELK") ? "OWL 2 EL" : "OWL 2 DL");
    return engine;
  }

  private static JsonObject support(final String operation, final String missing) {
    final JsonObject support = new JsonObject();
    support.put("operation", operation);
    support.put("availability", missing == null ? "AVAILABLE" : "NOT_IMPLEMENTED");
    if (missing != null) {
      support.put("reason", missing);
    }
    return support;
  }

  /**
   * The engine builds of the installed worker, from its jar's manifest; empty when the image has no
   * worker, as in the extension's own tests.
   */
  private static JsonArray engines(final Path reasoner) {
    final JsonArray engines = new JsonArray();
    if (!Files.isDirectory(reasoner)) {
      return engines;
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(reasoner, "*.jar")) {
      for (final Path jar : jars) {
        try (JarFile file = new JarFile(jar.toFile())) {
          final Attributes attributes =
              file.getManifest() == null ? null : file.getManifest().getMainAttributes();
          final String hermit =
              attributes == null ? null : attributes.getValue("OpenMetadata-HermiT-Version");
          if (hermit != null) {
            engines.add(engine("HermiT", hermit));
            engines.add(engine("ELK", attributes.getValue("OpenMetadata-ELK-Version")));
            return engines;
          }
        }
      }
    } catch (final IOException e) {
      // Capabilities then list no engine; jobs still record the engines their worker reports.
    }
    return engines;
  }
}
