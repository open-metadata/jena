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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.openmetadata.fuseki.reasoning.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The steps each operation takes. CHECK and EXPLAIN capture the request's import closure from the
 * serving dataset, bulk-load it into a private working dataset in a worker, and classify or explain
 * there; the answer goes to the job's outcome, with its detail in {@code result.json} in the job
 * directory, which the RESULTS operation serves. An EXPLAIN of a fact the dataset's current
 * snapshot derived with a governance rule is answered from that snapshot's witnesses instead.
 *
 * <p>A REFRESH captures every active closure, the rule bundle and the catalog triples the rules
 * read; loads them; classifies each closure into its entailment graph; runs the rules to a fixed
 * point; compacts; and publishes the working dataset as the dataset's new snapshot ({@link
 * SnapshotStore}). Every step is bounded by the job's deadline and the disk quota, the classify and
 * rules steps together by maxDerivedTriples, and the rules by maxRulePasses; a refresh that reaches
 * any of them, or meets an inconsistent closure, fails and publishes nothing.
 *
 * <p>A job has {@link JobSettings#maxJobSeconds()} from its start: each worker step gets what is
 * left as its deadline. A check that reaches a limit or its time answers INCOMPLETE with the
 * problem, as the protocol defines; one whose input is invalid fails.
 */
final class Plans {
  static final String CAPTURE = "capture";
  static final String SPOOL = "capture.nq.gz";
  static final String RESULT = "result.json";
  static final String WORKING = "working";
  static final String CHECK_FILE = "check.ofn";
  static final String REFRESH_CAPTURE = "capture.json";

  /** Rule problems a refused bundle reports in its message, the first in bundle order. */
  static final int MAX_REPORTED_RULE_PROBLEMS = 10;

  /** The RULES step's limits as capabilities name them, for a refresh that reaches one. */
  static final Map<String, String> RULE_LIMITS =
      Map.of(
          "maxPasses", "maxRulePasses",
          "maxNewFacts", "maxDerivedTriples",
          "maxSpoolBytes", "maxDiskBytes");

  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private Plans() {}

  static Stage.Planner current(final Operations operations, final ServingDatasets datasets) {
    return (job, request) -> {
      final long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(operations.jobSettings().maxJobSeconds());
      return switch (job.operation()) {
        case REFRESH ->
            operations.ruleCode().isEmpty()
                ? List.of(Stage.unavailable(CAPTURE, Operations.RULE_CODE_MISSING))
                : List.of(
                    refreshCapture(operations, datasets, deadline),
                    refreshLoad(operations, deadline),
                    classifyClosures(operations, deadline),
                    rules(operations, deadline),
                    compact(deadline),
                    publish(operations));
        case CHECK ->
            List.of(
                capture(operations, datasets, deadline),
                load(operations, deadline),
                classify(operations, deadline));
        case EXPLAIN ->
            List.of(
                governance(operations),
                capture(operations, datasets, deadline),
                load(operations, deadline),
                explain(operations, deadline));
      };
    };
  }

  private static Stage capture(
      final Operations operations, final ServingDatasets datasets, final long deadline) {
    return stage(
        CAPTURE,
        context -> {
          final DatasetGraph dataset = datasets.get(context.job().dataset());
          if (dataset == null) {
            return Stage.Result.failed(
                Problem.of(
                        Problem.Code.INVALID_REQUEST,
                        "Dataset '" + context.job().dataset() + "' is no longer served")
                    .atStep(CAPTURE));
          }
          final long left = diskLeft(operations);
          final Capture.Bounds bounds =
              new Capture.Bounds(
                  Operations.MAX_IMPORTS,
                  operations.workerLimits().maxClosureTriples(),
                  left,
                  deadline);
          final JsonObject request = context.request();
          try {
            final Capture.Result captured =
                Capture.run(
                    dataset,
                    new Capture.Selection(
                        request.getString("ontologyIri"),
                        request.getString("ontologySelection"),
                        request.hasKey("ontologyDigest")
                            ? request.getString("ontologyDigest")
                            : null),
                    context.directory().resolve(SPOOL),
                    bounds,
                    context.cancelled());
            AtomicFiles.write(
                context.directory().resolve(CAPTURE + ".json"), JSON.toString(captured.toJson()));
            LOG.info(
                "Reasoning job {} captured {} manifests and {} graphs, {} triples, {} bytes in {}"
                    + " ms",
                context.job().id(),
                captured.manifests().size(),
                captured.graphs().size(),
                captured.triples(),
                captured.bytes(),
                captured.millis());
            return Stage.Result.completed();
          } catch (final Capture.Cancelled e) {
            return Stage.Result.stopped();
          } catch (final Capture.Refused e) {
            return answerOrFail(e.problem().atStep(CAPTURE));
          }
        });
  }

  private static Stage load(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "load",
            context -> {
              final JsonObject step = step("LOAD", deadline);
              final JsonArray inputs = new JsonArray();
              inputs.add(SPOOL);
              step.put("inputs", inputs);
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "load",
        context -> {
          final Stage.Result loaded = bounded(worker, context, deadline);
          if (loaded.problem() != null || loaded.cancelled()) {
            return loaded;
          }
          // A working dataset is far larger than its compressed spool (TDB2 allocates its files
          // in segments: 254 MB for a 1.7 MB spool of 225,000 quads), so the quota is checked
          // again once it exists, before anything reasons over it; the job's end removes it.
          final long quota =
              operations.jobSettings().diskQuota(operations.settings().reasoningRoot());
          final long used = JobStore.sizeOnDisk(operations.settings().reasoningRoot());
          if (used > quota) {
            return Stage.Result.answered(
                "INCOMPLETE",
                Problem.of(
                        Problem.Code.LIMIT_EXCEEDED,
                        "The working dataset brought the reasoning data to "
                            + used
                            + " bytes, over its quota of "
                            + quota)
                    .withSubject("maxDiskBytes")
                    .atStep("load"));
          }
          return loaded;
        });
  }

  private static Stage classify(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "classify",
            context -> {
              final JsonObject step = step("CLASSIFY", deadline);
              step.put("closure", closure(context));
              if (Files.exists(context.directory().resolve(CHECK_FILE))) {
                step.put("checks", CHECK_FILE);
              }
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "classify",
        context -> {
          final JsonObject check = context.request().getObj("check");
          final String axiom;
          try {
            axiom = FunctionalSyntax.check(check);
          } catch (final FunctionalSyntax.Inexpressible e) {
            return Stage.Result.failed(
                Problem.of(Problem.Code.INVALID_REQUEST, e.getMessage()).atStep("classify"));
          }
          if (axiom != null) {
            AtomicFiles.write(context.directory().resolve(CHECK_FILE), "Ontology(" + axiom + ")\n");
          }
          final Stage.Result ran = bounded(worker, context, deadline);
          if (ran.problem() != null || ran.cancelled()) {
            return ran;
          }
          return checkAnswer(context, check.getString("kind"));
        });
  }

  private static Stage explain(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "explain",
            context -> {
              final JsonObject step = step("EXPLAIN", deadline);
              step.put("closure", closure(context));
              final JsonObject request = context.request().getObj("explain");
              final JsonObject explain = new JsonObject();
              try {
                explain.put("axiom", FunctionalSyntax.statement(request.getObj("statement")));
              } catch (final FunctionalSyntax.Inexpressible e) {
                // JobRequest refuses such a request before it is queued.
                throw new IllegalStateException(e);
              }
              explain.put(
                  "maxJustifications",
                  request.hasKey("maxJustifications")
                      ? request.getNumber("maxJustifications").intValue()
                      : 1);
              step.put("explain", explain);
              step.put("limits", limits(operations, context));
              return step;
            });
    return stage(
        "explain",
        context -> {
          final Stage.Result ran = bounded(worker, context, deadline);
          if (ran.problem() != null || ran.cancelled()) {
            return ran;
          }
          return explainAnswer(context);
        });
  }

  /**
   * Captures a refresh's input. A refresh whose source revision is already older than the current
   * snapshot's stops here, before any work; the generation it captured at goes into capture.json
   * for the publication's compare-and-set.
   */
  private static Stage refreshCapture(
      final Operations operations, final ServingDatasets datasets, final long deadline) {
    return stage(
        CAPTURE,
        context -> {
          final DatasetGraph dataset = datasets.get(context.job().dataset());
          if (dataset == null) {
            return Stage.Result.failed(
                Problem.of(
                        Problem.Code.INVALID_REQUEST,
                        "Dataset '" + context.job().dataset() + "' is no longer served")
                    .atStep(CAPTURE));
          }
          final JsonObject request = context.request();
          final Revision revision = Revision.of(request);
          final String name = context.job().dataset();
          final long base = operations.snapshots().generation(name);
          final Optional<JsonObject> current = operations.snapshots().currentMetadata(name);
          if (current.isPresent()) {
            final Revision published = Revision.of(current.get().getObj("input"));
            if (published.datasetGeneration().equals(revision.datasetGeneration())
                && published.watermark() > revision.watermark()) {
              return Stage.Result.failed(
                  Problem.of(
                          Problem.Code.SUPERSEDED,
                          "Snapshot "
                              + current.get().getString("id")
                              + " already covers live writes up to "
                              + published.watermark()
                              + ", later than this refresh's "
                              + revision.watermark())
                      .atStep(CAPTURE));
            }
          }
          final Capture.Bounds bounds =
              new Capture.Bounds(
                  Operations.MAX_IMPORTS,
                  operations.workerLimits().maxClosureTriples(),
                  diskLeft(operations),
                  deadline);
          try {
            final RefreshCapture.Result captured =
                RefreshCapture.run(
                    dataset,
                    request.getString("ruleBundleDigest"),
                    carriesRules(request) ? request.get("rules").getAsArray() : null,
                    request.hasKey("ontologyDigest") ? request.getString("ontologyDigest") : null,
                    context.directory(),
                    bounds,
                    context.cancelled());
            final Optional<Problem> refused = admit(operations, captured.bundle());
            if (refused.isPresent()) {
              return Stage.Result.failed(refused.get().atStep(CAPTURE));
            }
            final JsonObject json = captured.toJson();
            json.put("baseGeneration", base);
            AtomicFiles.write(context.directory().resolve(REFRESH_CAPTURE), JSON.toString(json));
            LOG.info(
                "Reasoning job {} captured {} closures, {} rules and {} catalog triples in {}"
                    + " graphs: {} triples, {} bytes in {} ms",
                context.job().id(),
                captured.closures().size(),
                captured.bundle().rules().size(),
                captured.catalogTriples(),
                captured.catalogGraphs().size(),
                captured.triples(),
                captured.bytes(),
                captured.millis());
            return Stage.Result.completed();
          } catch (final Capture.Cancelled e) {
            return Stage.Result.stopped();
          } catch (final Capture.Refused e) {
            return Stage.Result.failed(e.problem().atStep(CAPTURE));
          }
        });
  }

  /**
   * Whether a refresh carries its rules. OpenMetadata names its bundle by digest and sends no rules
   * (its docs/rdf-rule-bundle-contract.md); an empty list, as older requests send, says the same.
   */
  static boolean carriesRules(final JsonObject request) {
    return request.hasKey("rules") && !request.get("rules").getAsArray().isEmpty();
  }

  /**
   * Compiles every rule of a bundle with the RULES step's compiler, so an invalid bundle is refused
   * before anything runs, naming its first refused rule.
   */
  static Optional<Problem> admit(final Operations operations, final RuleBundle bundle) {
    if (bundle.rules().isEmpty()) {
      return Optional.empty();
    }
    final Optional<RuleCode> code = operations.ruleCode();
    if (code.isEmpty()) {
      return Optional.of(Problem.of(Problem.Code.NOT_IMPLEMENTED, Operations.RULE_CODE_MISSING));
    }
    final List<RuleCode.RuleProblem> problems =
        code.get().compile(bundle.rulesJson(), operations.workerLimits().maxRulePatterns());
    if (problems.isEmpty()) {
      return Optional.empty();
    }
    final StringBuilder message = new StringBuilder("The rule bundle is refused: ");
    for (int i = 0; i < Math.min(problems.size(), MAX_REPORTED_RULE_PROBLEMS); i++) {
      message
          .append(i == 0 ? "" : "; ")
          .append(problems.get(i).code())
          .append(' ')
          .append(problems.get(i).message());
    }
    if (problems.size() > MAX_REPORTED_RULE_PROBLEMS) {
      message.append("; and ").append(problems.size() - MAX_REPORTED_RULE_PROBLEMS).append(" more");
    }
    return Optional.of(
        Problem.of(Problem.Code.RULE_FAILED, message.toString())
            .withSubject(problems.get(0).rule()));
  }

  private static Stage refreshLoad(final Operations operations, final long deadline) {
    return failingAtLimits(load(operations, deadline));
  }

  /**
   * Classifies each captured closure into its entailment graph, one worker step per closure, in the
   * shared working dataset. An inconsistent closure fails the refresh: its consequences would be
   * arbitrary, so nothing is published.
   */
  private static Stage classifyClosures(final Operations operations, final long deadline) {
    return stage(
        "classify",
        context -> {
          final JsonObject capture = json(context, REFRESH_CAPTURE);
          final long maxDerived = operations.workerLimits().maxNewFacts();
          long derived = 0;
          final JsonArray outcomes = new JsonArray();
          final JsonArray engines = new JsonArray();
          for (final JsonValue value : capture.get("closures").getAsArray()) {
            final RefreshCapture.Closure closure =
                RefreshCapture.Closure.fromJson(value.getAsObject());
            final String name = "classify-" + closure.index();
            if (context.cancelled().getAsBoolean()) {
              return Stage.Result.stopped();
            }
            if (System.nanoTime() - deadline >= 0) {
              return Stage.Result.failed(outOfTime().atStep(name));
            }
            final long left = maxDerived - derived;
            final WorkerStage worker =
                new WorkerStage(
                    name,
                    ctx -> {
                      final JsonObject step = step("CLASSIFY", deadline);
                      final JsonObject closureJson = new JsonObject();
                      closureJson.put("root", closure.ontologyIri());
                      closureJson.put("graphsFile", closure.graphsFile());
                      step.put("closure", closureJson);
                      step.put("output", closure.entailments());
                      final JsonObject limits = limits(operations, ctx);
                      limits.put(
                          "maxOutputTriples",
                          Math.max(
                              1, Math.min(operations.workerLimits().maxOutputTriples(), left)));
                      step.put("limits", limits);
                      return step;
                    });
            final Stage.Result ran = worker.run(context);
            if (ran.cancelled()) {
              return ran;
            }
            if (ran.problem() != null) {
              Problem problem = ran.problem();
              if (problem.code() == Problem.Code.LIMIT_EXCEEDED && problem.subject() == null) {
                problem = problem.withSubject("maxDerivedTriples");
              } else if (problem.subject() == null) {
                problem = problem.withSubject(closure.ontologyIri());
              }
              return Stage.Result.failed(problem);
            }
            final JsonObject manifest = manifest(context, name);
            final JsonObject entailments = manifest.getObj("entailments");
            if (!entailments.getBoolean("consistent")) {
              return Stage.Result.failed(
                  Problem.of(
                          Problem.Code.INCONSISTENT_ONTOLOGY,
                          "The active closure of <"
                              + closure.ontologyIri()
                              + "> (version <"
                              + closure.versionIri()
                              + ">) is inconsistent, so it has no consequences to publish;"
                              + " explain the inconsistency with an EXPLAIN job")
                      .withSubject(closure.ontologyIri())
                      .atStep(name));
            }
            derived += entailments.getNumber("outputTriples").longValue();
            final JsonObject outcome = closure.toJson();
            outcome.remove("graphsFile");
            outcome.put("consistent", true);
            outcome.put("unsatisfiableCount", entailments.get("unsatisfiable"));
            outcome.put("unsatisfiableClasses", manifest.get("unsatisfiable"));
            outcome.put("entailedTriples", entailments.get("outputTriples"));
            copy(manifest, outcome, "engine");
            outcomes.add(outcome);
            addEngine(engines, manifest);
          }
          final JsonObject classified = new JsonObject();
          classified.put("closures", outcomes);
          classified.put("derived", derived);
          classified.put("engines", engines);
          AtomicFiles.write(
              context.directory().resolve("classify.json"), JSON.toString(classified));
          return Stage.Result.completed().withEngines(engines);
        });
  }

  /**
   * Runs the bundle to a fixed point over the catalog, the closures and their entailments. Its new
   * facts get what the closures' entailments left of maxDerivedTriples; its spool, what the disk
   * quota leaves. A refresh without enabled rules has nothing to run.
   */
  private static Stage rules(final Operations operations, final long deadline) {
    final WorkerStage worker =
        new WorkerStage(
            "rules",
            context -> {
              final JsonObject step = step("RULES", deadline);
              step.put("readFile", RefreshCapture.READ_FILE);
              final JsonArray rules = new JsonArray();
              for (final JsonValue value : bundleRules(context)) {
                final JsonObject rule = value.getAsObject();
                final JsonObject spec = new JsonObject();
                spec.put("name", rule.getString("name"));
                spec.put("ruleType", rule.getString("ruleType"));
                spec.put("ruleBody", rule.getString("ruleBody"));
                spec.put("output", RefreshCapture.ruleGraph(rule.getString("name")));
                rules.add(spec);
              }
              step.put("rules", rules);
              step.put("provenance", RefreshCapture.PROVENANCE);
              final long classified =
                  json(context, "classify.json").getNumber("derived").longValue();
              final JsonObject limits = new JsonObject();
              limits.put(
                  "maxNewFacts", Math.max(1, operations.workerLimits().maxNewFacts() - classified));
              limits.put("maxPasses", operations.workerLimits().maxPasses());
              long disk;
              try {
                disk = diskLeft(operations);
              } catch (final IOException e) {
                disk = 1;
              }
              limits.put(
                  "maxSpoolBytes",
                  Math.max(1, Math.min(operations.workerLimits().maxSpoolBytes(), disk)));
              step.put("limits", limits);
              return step;
            });
    return stage(
        "rules",
        context -> {
          final JsonArray engines = json(context, "classify.json").get("engines").getAsArray();
          if (bundleRules(context).isEmpty()) {
            return Stage.Result.completed();
          }
          if (System.nanoTime() - deadline >= 0) {
            return Stage.Result.failed(outOfTime().atStep("rules"));
          }
          final Stage.Result ran = worker.run(context);
          if (ran.cancelled()) {
            return ran;
          }
          if (ran.problem() != null) {
            final Problem problem = ran.problem();
            final String limit =
                problem.subject() == null ? null : RULE_LIMITS.get(problem.subject());
            return Stage.Result.failed(limit == null ? problem : problem.withSubject(limit));
          }
          final JsonArray all = JSON.copy(engines).getAsArray();
          all.add(Operations.governanceEngine());
          return Stage.Result.completed().withEngines(all);
        });
  }

  private static Stage compact(final long deadline) {
    final WorkerStage worker = new WorkerStage("compact", context -> step("COMPACT", deadline));
    return stage(
        "compact",
        context -> {
          if (System.nanoTime() - deadline >= 0) {
            return Stage.Result.failed(outOfTime().atStep("compact"));
          }
          return worker.run(context);
        });
  }

  /**
   * Publishes the compacted working dataset as the dataset's new snapshot, by compare-and-set
   * against the generation the capture saw; the refresh's result describes it.
   */
  private static Stage publish(final Operations operations) {
    return stage(
        "publish",
        context -> {
          final JsonObject capture = json(context, REFRESH_CAPTURE);
          final JsonObject classified = json(context, "classify.json");
          final Revision revision = Revision.of(context.request());
          final JsonObject detail = new JsonObject();
          detail.put("activationDigest", capture.get("activationDigest"));
          detail.put("ruleBundle", capture.get("rules"));
          detail.put("closures", classified.get("closures"));
          detail.put("rules", ruleSummary(context));
          final JsonObject catalog = new JsonObject();
          catalog.put("triples", capture.getObj("catalog").get("triples"));
          catalog.put("graphs", capture.getObj("catalog").get("graphs").getAsArray().size());
          catalog.put("anyPredicate", capture.getObj("catalog").get("anyPredicate"));
          detail.put("catalog", catalog);
          detail.put("provenanceGraph", RefreshCapture.PROVENANCE);
          final SnapshotStore.Candidate candidate =
              new SnapshotStore.Candidate(
                  context.job().id(),
                  context.job().sequence(),
                  revision.datasetGeneration(),
                  revision.watermark(),
                  context.job().input(),
                  detail);
          final JsonObject snapshot;
          try {
            snapshot =
                operations
                    .snapshots()
                    .publish(
                        context.job().dataset(),
                        capture.getNumber("baseGeneration").longValue(),
                        candidate,
                        context.directory().resolve(WORKING),
                        System.currentTimeMillis());
          } catch (final SnapshotStore.Superseded e) {
            return Stage.Result.failed(
                Problem.of(Problem.Code.SUPERSEDED, e.getMessage()).atStep("publish"));
          }
          final JsonObject result = new JsonObject();
          result.put("operation", "REFRESH");
          result.put("snapshot", snapshot);
          result.put("generation", operations.snapshots().generation(context.job().dataset()));
          result.put("activationDigest", capture.get("activationDigest"));
          result.put("ruleBundle", capture.get("rules"));
          result.put("catalog", catalog);
          result.put("closures", classified.get("closures"));
          result.put("rules", ruleSummary(context));
          AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
          return Stage.Result.completed().withSnapshot(UUID.fromString(snapshot.getString("id")));
        });
  }

  /**
   * Answers an EXPLAIN of a fact the dataset's current snapshot derived with a governance rule,
   * from the witnesses its RULES step recorded: the rule, the pass and the premises, each explained
   * in turn down to asserted facts. Any other statement goes on to the OWL explanation.
   */
  private static Stage governance(final Operations operations) {
    return stage(
        "snapshot",
        context -> {
          final Triple fact = triple(context.request().getObj("explain").getObj("statement"));
          final Optional<SnapshotStore.Lease> leased =
              operations.snapshots().lease(context.job().dataset());
          if (fact == null || leased.isEmpty()) {
            return Stage.Result.completed();
          }
          try (SnapshotStore.Lease lease = leased.get()) {
            final DatasetGraph snapshot = lease.database();
            snapshot.begin(TxnType.READ);
            try {
              if (!derivedByRule(snapshot, fact)) {
                return Stage.Result.completed();
              }
              if (operations.ruleCode().isEmpty()) {
                return Stage.Result.failed(
                    Problem.of(Problem.Code.NOT_IMPLEMENTED, Operations.RULE_CODE_MISSING)
                        .atStep("snapshot"));
              }
              final JsonObject explanation =
                  operations
                      .ruleCode()
                      .get()
                      .explain(
                          snapshot,
                          RefreshCapture.PROVENANCE,
                          fact,
                          GOVERNANCE_DEPTH,
                          Math.min(GOVERNANCE_FACTS, operations.jobSettings().maxResultRows()),
                          GOVERNANCE_PATH_LENGTH,
                          GOVERNANCE_PATH_NODES);
              if (explanation == null) {
                return Stage.Result.completed();
              }
              final JsonObject result = new JsonObject();
              result.put("operation", "EXPLAIN");
              result.put("outcome", "ENTAILED");
              result.put("kind", "GOVERNANCE");
              result.put("snapshot", lease.description());
              result.put("generation", lease.generation());
              result.put("explanation", explanation);
              AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
              final JsonArray engines = new JsonArray();
              engines.add(Operations.governanceEngine());
              return Stage.Result.answered("ENTAILED", null).withEngines(engines);
            } finally {
              snapshot.end();
            }
          }
        });
  }

  /** Bounds of a governance explanation: the worker's defaults, the facts cut to a result page. */
  static final int GOVERNANCE_DEPTH = 16;

  static final int GOVERNANCE_FACTS = 1_000;
  static final int GOVERNANCE_PATH_LENGTH = 256;
  static final int GOVERNANCE_PATH_NODES = 100_000;

  private static boolean derivedByRule(final DatasetGraph snapshot, final Triple fact) {
    final Iterator<Quad> quads =
        snapshot.find(Node.ANY, fact.getSubject(), fact.getPredicate(), fact.getObject());
    while (quads.hasNext()) {
      final Node graph = quads.next().getGraph();
      if (graph.isURI() && graph.getURI().startsWith(RefreshCapture.ruleGraph(""))) {
        return true;
      }
    }
    return false;
  }

  /** The statement as a triple; null for a literal the schema admits but RDF cannot write. */
  static Triple triple(final JsonObject statement) {
    final Node subject = NodeFactory.createURI(statement.getString("subjectIri"));
    final Node predicate = NodeFactory.createURI(statement.getString("predicateIri"));
    final Node object;
    if ("IRI".equals(statement.getString("objectKind"))) {
      if (!statement.hasKey("objectIri")) {
        return null;
      }
      object = NodeFactory.createURI(statement.getString("objectIri"));
    } else {
      if (!statement.hasKey("literalValue")) {
        return null;
      }
      final String value = statement.getString("literalValue");
      if (statement.hasKey("language")) {
        object = NodeFactory.createLiteralLang(value, statement.getString("language"));
      } else if (statement.hasKey("datatypeIri")) {
        object =
            NodeFactory.createLiteralDT(
                value,
                TypeMapper.getInstance().getSafeTypeByName(statement.getString("datatypeIri")));
      } else {
        object = NodeFactory.createLiteralString(value);
      }
    }
    return Triple.create(subject, predicate, object);
  }

  /** A refresh's rules, as its capture wrote them. */
  private static JsonArray bundleRules(final Stage.Context context) {
    return JSON.parseAny(
            IO.readWholeFileAsUTF8(
                context.directory().resolve(RefreshCapture.RULES_FILE).toString()))
        .getAsArray();
  }

  /** Per-rule counts of the RULES step, or none when the bundle had no rules. */
  private static JsonObject ruleSummary(final Stage.Context context) {
    final JsonObject summary = new JsonObject();
    final Path file = context.directory().resolve("rules.result.json");
    if (!Files.isRegularFile(file)) {
      summary.put("ran", false);
      return summary;
    }
    final JsonObject manifest = JSON.read(file.toString());
    summary.put("ran", true);
    summary.put("outcome", manifest.get("outcome"));
    copy(manifest, summary, "passes", "newFacts", "derived", "solutions", "rules");
    return summary;
  }

  /** The source revision a request or a snapshot's input names. */
  record Revision(String datasetGeneration, long watermark) {
    static Revision of(final JsonObject withRevision) {
      final JsonObject revision = withRevision.getObj("sourceRevision");
      return new Revision(
          revision.getString("datasetGeneration"),
          revision.getNumber("liveWriteWatermark").longValue());
    }
  }

  /**
   * A refresh publishes complete results or none: a limit a check would answer INCOMPLETE with
   * fails the refresh instead.
   */
  private static Stage failingAtLimits(final Stage inner) {
    return stage(
        inner.name(),
        context -> {
          final Stage.Result result = inner.run(context);
          return result.answered() && result.problem() != null
              ? Stage.Result.failed(result.problem())
              : result;
        });
  }

  private static JsonObject json(final Stage.Context context, final String file) {
    return JSON.read(context.directory().resolve(file).toString());
  }

  private static void addEngine(final JsonArray engines, final JsonObject manifest) {
    final JsonArray found = engines(manifest);
    for (final JsonValue engine : found) {
      if (!engines.contains(engine)) {
        engines.add(engine);
      }
    }
  }

  /** The job's answer to a check, from the classify step's manifest. */
  private static Stage.Result checkAnswer(final Stage.Context context, final String kind)
      throws IOException {
    final JsonObject manifest = manifest(context, "classify");
    final boolean consistent = manifest.getObj("entailments").getBoolean("consistent");
    final JsonObject result = new JsonObject();
    result.put("operation", "CHECK");
    result.put("kind", kind);
    result.put("consistent", consistent);
    result.put("unsatisfiableCount", manifest.getObj("entailments").get("unsatisfiable"));
    result.put("unsatisfiableClasses", manifest.get("unsatisfiable"));
    final String outcome;
    if (kind.equals("CONSISTENCY")) {
      outcome = consistent ? "NOT_ENTAILED" : "INCONSISTENT";
    } else {
      final JsonObject answer = manifest.get("checks").getAsArray().get(0).getAsObject();
      result.put("check", answer);
      if (answer.getString("outcome").equals("INVALID")) {
        return Stage.Result.failed(
            Problem.of(
                    Problem.Code.INVALID_REQUEST,
                    "The check cannot be asked of this closure: " + answer.getString("reason"))
                .atStep("classify"));
      }
      outcome = answer.getString("outcome");
    }
    result.put("outcome", outcome);
    copy(manifest, result, "engine", "routing", "closure", "admission");
    AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
    return Stage.Result.answered(outcome, null).withEngines(engines(manifest));
  }

  /** The job's answer to an explanation, from the explain step's manifest. */
  private static Stage.Result explainAnswer(final Stage.Context context) throws IOException {
    final JsonObject manifest = manifest(context, "explain");
    final String outcome =
        switch (manifest.getString("outcome")) {
          case "EXPLAINED" -> "ENTAILED";
          case "INCONSISTENT" -> "INCONSISTENT";
          default -> "NOT_ENTAILED";
        };
    final JsonObject result = new JsonObject();
    result.put("operation", "EXPLAIN");
    result.put("outcome", outcome);
    copy(
        manifest,
        result,
        "target",
        "justifications",
        "justificationsIncomplete",
        "consistencyChecks",
        "engine",
        "closure",
        "admission");
    AtomicFiles.write(context.directory().resolve(RESULT), JSON.toString(result));
    return Stage.Result.answered(outcome, null).withEngines(engines(manifest));
  }

  /**
   * Runs a worker step unless the job's time is gone, and reads a limit, a deadline or the worker's
   * heap running out as the INCOMPLETE answer the protocol gives a check, not as a failure.
   */
  private static Stage.Result bounded(
      final WorkerStage worker, final Stage.Context context, final long deadline)
      throws IOException, InterruptedException {
    if (System.nanoTime() - deadline >= 0) {
      return answerOrFail(outOfTime().atStep(worker.name()));
    }
    final Stage.Result result = worker.run(context);
    return result.problem() == null ? result : answerOrFail(result.problem());
  }

  private static Stage.Result answerOrFail(final Problem problem) {
    return switch (problem.code()) {
      case LIMIT_EXCEEDED, MEMORY_BUDGET_EXHAUSTED -> Stage.Result.answered("INCOMPLETE", problem);
      default -> Stage.Result.failed(problem);
    };
  }

  private static Problem outOfTime() {
    return Problem.of(Problem.Code.LIMIT_EXCEEDED, "The job ran out of time")
        .withSubject("maxJobSeconds");
  }

  private static JsonObject step(final String kind, final long deadline) {
    final JsonObject step = new JsonObject();
    step.put("protocol", 1);
    step.put("kind", kind);
    step.put("dataset", WORKING);
    step.put(
        "timeoutMillis", Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    return step;
  }

  /** The working dataset holds exactly the captured closure, so the closure is all of it. */
  private static JsonObject closure(final Stage.Context context) {
    final JsonObject closure = new JsonObject();
    closure.put("root", context.request().getString("ontologyIri"));
    closure.put("allGraphs", true);
    return closure;
  }

  private static JsonObject limits(final Operations operations, final Stage.Context context) {
    long spool;
    try {
      spool = Files.size(context.directory().resolve(SPOOL));
    } catch (final IOException e) {
      spool = 1;
    }
    return operations.workerLimits().toJson(spool);
  }

  private static long diskLeft(final Operations operations) throws IOException {
    final Path root = operations.settings().reasoningRoot();
    return operations.jobSettings().diskQuota(root) - JobStore.sizeOnDisk(root);
  }

  private static JsonObject manifest(final Stage.Context context, final String step) {
    return JSON.read(context.directory().resolve(step + ".result.json").toString());
  }

  private static JsonArray engines(final JsonObject manifest) {
    final JsonArray engines = new JsonArray();
    final JsonValue engine = manifest.get("engine");
    if (engine != null && engine.isObject()) {
      engines.add(
          Operations.engine(
              engine.getAsObject().getString("name"), engine.getAsObject().getString("version")));
    }
    return engines;
  }

  private static void copy(final JsonObject from, final JsonObject to, final String... keys) {
    for (final String key : keys) {
      if (from.hasKey(key)) {
        to.put(key, from.get(key));
      }
    }
  }

  @FunctionalInterface
  private interface Body {
    Stage.Result run(Stage.Context context) throws IOException, InterruptedException;
  }

  private static Stage stage(final String name, final Body body) {
    return new Stage() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Result run(final Context context) throws IOException, InterruptedException {
        return body.run(context);
      }
    };
  }
}
