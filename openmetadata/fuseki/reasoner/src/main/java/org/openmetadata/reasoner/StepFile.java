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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.openmetadata.reasoner.owl.EngineChoice;

/**
 * One step of a job, read from a JSON step file in the job directory. Every path it names is
 * relative to that directory and must stay inside it, so a step can only touch its own job's
 * private files, never a serving dataset. The result manifest and the optional canonical report are
 * always written next to the step file as {@code <name>.result.json} and {@code <name>.report.txt},
 * so the launcher finds them without parsing the step file.
 */
public record StepFile(
    Path path,
    Path jobDirectory,
    String stepId,
    StepKind kind,
    Path dataset,
    long timeoutMillis,
    boolean report,
    List<Path> inputs,
    Closure closure,
    EngineChoice engine,
    String outputGraph,
    Path checks,
    Explain explain,
    Governance governance,
    List<BatchClosure> closures,
    Limits limits) {

  public static final int PROTOCOL = 1;
  private static final long MAX_STEP_FILE_BYTES = 1 << 20;
  private static final Pattern STEP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Set<String> KEYS =
      Set.of(
          "protocol",
          "stepId",
          "kind",
          "dataset",
          "timeoutMillis",
          "report",
          "inputs",
          "closure",
          "engine",
          "output",
          "checks",
          "explain",
          "closuresFile",
          "read",
          "readFile",
          "rules",
          "provenance",
          "limits");
  private static final Pattern RULE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}[a-z0-9]");
  private static final Set<String> RULE_KEYS =
      Set.of(
          "name",
          "displayName",
          "description",
          "ruleType",
          "ruleBody",
          "enabled",
          "priority",
          "tags",
          "output");

  /** Graph names the RULES step keeps for itself; no caller may name one. */
  public static final String RESERVED_GRAPH_PREFIX = "urn:openmetadata:reasoner:";

  /**
   * The import closure to reason over: named graphs of the working dataset and its root. With
   * {@code allGraphs} the graphs are every named graph of the working dataset, as when it holds
   * exactly one captured closure, and {@code graphs} is empty; a closure of many small graphs then
   * does not have to be listed in the step file. With {@code graphsFile} they are read from a file
   * in the job directory, one IRI per line, as when a working dataset holds several closures.
   */
  public record Closure(String root, List<String> graphs, boolean allGraphs) {}

  /**
   * Graphs one closure may list, in the step file or its graphs file: the store's maxImports, which
   * the extension's capture enforces before a closure gets here.
   */
  public static final int MAX_CLOSURE_GRAPHS = 10_000;

  private static final long MAX_GRAPHS_FILE_BYTES = 64L << 20;

  /**
   * One closure of a CLASSIFY_ALL step: what a CLASSIFY step of its own would name, and the step id
   * its result manifest is written under, {@code <step id>-<position>}.
   */
  public record BatchClosure(String stepId, Closure closure, String outputGraph) {}

  /** Closures one CLASSIFY_ALL step classifies: the releases the extension's refresh admits. */
  public static final int MAX_BATCH_CLOSURES = 1_000;

  private static final long MAX_CLOSURES_FILE_BYTES = 16L << 20;

  /**
   * What to justify: one entailed axiom, in OWL 2 functional syntax, or the inconsistency; and how
   * many justifications to compute at most.
   */
  public record Explain(String axiom, boolean inconsistency, int maxJustifications) {}

  /** The most justifications one EXPLAIN step computes, as OpenMetadata's request allows. */
  public static final int MAX_JUSTIFICATIONS = 10;

  /**
   * One rule of a RULES step's bundle, in OpenMetadata's rule shape plus the graph its conclusions
   * go to. The body is validated by the step, not here, so a rule the step refuses is reported as a
   * rejected input with every problem, not as a malformed step file.
   */
  public record Rule(
      String name, String ruleType, String body, boolean enabled, long priority, String output) {}

  /**
   * What a RULES step reads and writes: the graphs its rules read, the rules, and the provenance
   * file in the job directory that receives one witness per derived fact. No graph a rule writes
   * may be read.
   */
  public record Governance(List<String> read, List<Rule> rules, Path provenance) {}

  public static Path resultPath(final Path stepFile) {
    return stepFile.resolveSibling(baseName(stepFile) + ".result.json");
  }

  public static Path reportPath(final Path stepFile) {
    return stepFile.resolveSibling(baseName(stepFile) + ".report.txt");
  }

  private static String baseName(final Path stepFile) {
    final String name = stepFile.getFileName().toString();
    return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
  }

  public static StepFile read(final Path path) throws InvalidStepException {
    final Path file = path.toAbsolutePath().normalize();
    final JsonObject json = parse(file);
    for (final String key : json.keys()) {
      if (!KEYS.contains(key)) {
        throw new InvalidStepException("Unknown step file key '" + key + "'");
      }
    }
    final long protocol = requiredLong(json, "protocol");
    if (protocol != PROTOCOL) {
      throw new InvalidStepException(
          "Unsupported protocol " + protocol + "; this worker speaks " + PROTOCOL);
    }
    final Path jobDirectory = file.getParent();
    final String stepId = requiredString(json, "stepId");
    if (!STEP_ID.matcher(stepId).matches()) {
      throw new InvalidStepException("stepId must match " + STEP_ID.pattern());
    }
    final StepKind kind = enumValue(StepKind.class, requiredString(json, "kind"), "kind");
    final Path dataset = insideJob(jobDirectory, requiredString(json, "dataset"), "dataset");
    if (dataset.equals(jobDirectory)) {
      throw new InvalidStepException("dataset must be a directory inside the job directory");
    }
    final long timeoutMillis = requiredLong(json, "timeoutMillis");
    if (timeoutMillis <= 0) {
      throw new InvalidStepException("timeoutMillis must be positive");
    }
    final boolean report = json.hasKey("report") && booleanValue(json, "report");
    final Limits limits = Limits.fromJson(json.get("limits"));

    final List<Path> inputs = new ArrayList<>();
    Closure closure = null;
    EngineChoice engine = EngineChoice.AUTO;
    String outputGraph = null;
    Path checks = null;
    Explain explain = null;
    Governance governance = null;
    List<BatchClosure> closures = List.of();
    if (kind != StepKind.RULES) {
      rejectKeys(json, kind, "read", "readFile", "rules", "provenance");
    }
    if (kind != StepKind.CLASSIFY_ALL) {
      rejectKeys(json, kind, "closuresFile");
    }
    switch (kind) {
      case LOAD -> {
        rejectKeys(json, kind, "closure", "engine", "output", "checks", "explain");
        final JsonValue array = json.get("inputs");
        if (array == null || !array.isArray() || array.getAsArray().isEmpty()) {
          throw new InvalidStepException("LOAD needs a non-empty inputs array");
        }
        for (final JsonValue input : array.getAsArray()) {
          if (!input.isString()) {
            throw new InvalidStepException("inputs must be strings");
          }
          inputs.add(insideJob(jobDirectory, input.getAsString().value(), "inputs"));
        }
      }
      case CLASSIFY, EXPLAIN -> {
        rejectKeys(json, kind, "inputs");
        closure = closure(json.get("closure"), jobDirectory);
        if (json.hasKey("engine")) {
          engine = enumValue(EngineChoice.class, requiredString(json, "engine"), "engine");
        }
        if (kind == StepKind.CLASSIFY) {
          rejectKeys(json, kind, "explain");
          if (json.hasKey("output")) {
            outputGraph = iri(requiredString(json, "output"), "output");
            if (closure.allGraphs() || closure.graphs().contains(outputGraph)) {
              // With allGraphs the output graph would join the closure of the next step.
              throw new InvalidStepException(
                  "output must not be one of the closure's graphs, and is not allowed with"
                      + " closure.allGraphs");
            }
          }
          if (json.hasKey("checks")) {
            checks = insideJob(jobDirectory, requiredString(json, "checks"), "checks");
          }
        } else {
          rejectKeys(json, kind, "output", "checks");
          explain = explain(json.get("explain"));
        }
      }
      case CLASSIFY_ALL -> {
        // Each closure names its own output; a report would repeat every output graph.
        rejectKeys(json, kind, "inputs", "closure", "output", "checks", "explain", "report");
        if (json.hasKey("engine")) {
          engine = enumValue(EngineChoice.class, requiredString(json, "engine"), "engine");
        }
        closures =
            closures(
                insideJob(jobDirectory, requiredString(json, "closuresFile"), "closuresFile"),
                stepId,
                jobDirectory);
      }
      case COMPACT ->
          rejectKeys(json, kind, "inputs", "closure", "engine", "output", "checks", "explain");
      case RULES -> {
        rejectKeys(json, kind, "inputs", "closure", "engine", "output", "checks", "explain");
        governance = governance(json, jobDirectory);
        if (governance.provenance().startsWith(dataset)) {
          throw new InvalidStepException("provenance must be a file outside the dataset");
        }
      }
    }
    return new StepFile(
        file,
        jobDirectory,
        stepId,
        kind,
        dataset,
        timeoutMillis,
        report,
        List.copyOf(inputs),
        closure,
        engine,
        outputGraph,
        checks,
        explain,
        governance,
        closures,
        limits);
  }

  /**
   * One closure of this CLASSIFY_ALL step as the CLASSIFY step it would otherwise have had: the
   * same dataset, engine choice and limits, the closure's own id and output graph, and what the
   * closures before it left of the output budget.
   */
  public StepFile forClosure(final BatchClosure one, final long maxOutputTriples) {
    return new StepFile(
        jobDirectory.resolve(one.stepId() + ".json"),
        jobDirectory,
        one.stepId(),
        StepKind.CLASSIFY,
        dataset,
        timeoutMillis,
        false,
        List.of(),
        one.closure(),
        engine,
        one.outputGraph(),
        null,
        null,
        null,
        List.of(),
        limits.withMaxOutputTriples(maxOutputTriples));
  }

  /** Graphs a RULES step's read file may list: a refresh's catalog graphs and closure graphs. */
  public static final int MAX_READ_GRAPHS = 100_000;

  private static Governance governance(final JsonObject json, final Path jobDirectory)
      throws InvalidStepException {
    if (json.hasKey("read") == json.hasKey("readFile")) {
      throw new InvalidStepException("RULES needs exactly one of read and readFile");
    }
    final List<String> listed = new ArrayList<>();
    if (json.hasKey("readFile")) {
      listed.addAll(
          graphsFile(insideJob(jobDirectory, requiredString(json, "readFile"), "readFile")));
      if (listed.size() > MAX_READ_GRAPHS) {
        throw new InvalidStepException(
            "readFile lists " + listed.size() + " graphs, more than " + MAX_READ_GRAPHS);
      }
    } else {
      final JsonValue graphs = json.get("read");
      if (!graphs.isArray()) {
        throw new InvalidStepException("RULES needs a non-empty read array of graph IRIs");
      }
      for (final JsonValue graph : graphs.getAsArray()) {
        if (!graph.isString()) {
          throw new InvalidStepException("read must contain strings");
        }
        listed.add(graph.getAsString().value());
      }
    }
    if (listed.isEmpty()) {
      throw new InvalidStepException("RULES needs a non-empty read array of graph IRIs");
    }
    final Set<String> read = new LinkedHashSet<>();
    for (final String graph : listed) {
      if (!read.add(graphIri(graph, "read"))) {
        throw new InvalidStepException("read lists " + graph + " twice");
      }
    }
    final Path provenance =
        insideJob(jobDirectory, requiredString(json, "provenance"), "provenance");
    if (provenance.equals(jobDirectory) || Files.isDirectory(provenance)) {
      throw new InvalidStepException("provenance must be a file inside the job directory");
    }
    final JsonValue array = json.get("rules");
    if (array == null || !array.isArray() || array.getAsArray().isEmpty()) {
      throw new InvalidStepException("RULES needs a non-empty rules array");
    }
    final List<Rule> rules = new ArrayList<>();
    final Set<String> names = new LinkedHashSet<>();
    for (final JsonValue value : array.getAsArray()) {
      final Rule rule = rule(value);
      if (!names.add(rule.name())) {
        throw new InvalidStepException("rules lists '" + rule.name() + "' twice");
      }
      if (rule.output() != null) {
        if (read.contains(rule.output())) {
          throw new InvalidStepException(
              "rule '" + rule.name() + "' writes " + rule.output() + ", which the rules read");
        }
      }
      rules.add(rule);
    }
    return new Governance(List.copyOf(read), List.copyOf(rules), provenance);
  }

  private static Rule rule(final JsonValue value) throws InvalidStepException {
    if (value == null || !value.isObject()) {
      throw new InvalidStepException("rules must contain objects");
    }
    final JsonObject json = value.getAsObject();
    for (final String key : json.keys()) {
      if (!RULE_KEYS.contains(key)) {
        throw new InvalidStepException("Unknown rule key '" + key + "'");
      }
    }
    final String name = requiredString(json, "name");
    if (!RULE_NAME.matcher(name).matches()) {
      throw new InvalidStepException("rule name '" + name + "' must match " + RULE_NAME.pattern());
    }
    final String body = requiredString(json, "ruleBody");
    final boolean enabled = !json.hasKey("enabled") || booleanValue(json, "enabled");
    final String ruleType =
        json.hasKey("ruleType") ? requiredString(json, "ruleType") : "CONSTRUCT";
    long priority = 0;
    if (json.hasKey("priority")) {
      priority = requiredLong(json, "priority");
      if (priority < 0 || priority > 10_000) {
        throw new InvalidStepException("rule '" + name + "': priority must be 0 to 10000");
      }
    }
    for (final String text : List.of("displayName", "description")) {
      if (json.hasKey(text) && !json.get(text).isString()) {
        throw new InvalidStepException("rule '" + name + "': " + text + " must be a string");
      }
    }
    if (json.hasKey("tags")
        && (!json.get("tags").isArray()
            || !json.get("tags").getAsArray().stream().allMatch(JsonValue::isString))) {
      throw new InvalidStepException("rule '" + name + "': tags must be an array of strings");
    }
    String output = null;
    if (json.hasKey("output")) {
      output = graphIri(requiredString(json, "output"), "rule '" + name + "' output");
    } else if (enabled) {
      throw new InvalidStepException("enabled rule '" + name + "' needs an output graph");
    }
    return new Rule(name, ruleType, body, enabled, priority, output);
  }

  private static String graphIri(final String value, final String key) throws InvalidStepException {
    final String iri = iri(value, key);
    if (iri.startsWith(RESERVED_GRAPH_PREFIX)) {
      throw new InvalidStepException(key + " must not name a graph under " + RESERVED_GRAPH_PREFIX);
    }
    return iri;
  }

  private static JsonObject parse(final Path file) throws InvalidStepException {
    try {
      if (!Files.isRegularFile(file)) {
        throw new InvalidStepException("Step file " + file + " does not exist");
      }
      if (Files.size(file) > MAX_STEP_FILE_BYTES) {
        throw new InvalidStepException(
            "Step file is larger than " + MAX_STEP_FILE_BYTES + " bytes");
      }
      try (InputStream in = Files.newInputStream(file)) {
        return JSON.parse(in);
      }
    } catch (final IOException | JsonException e) {
      throw new InvalidStepException("Cannot read step file " + file + ": " + e.getMessage());
    }
  }

  /**
   * Resolves a relative path inside the job directory. Symbolic links are resolved for the parts
   * that exist, so a link cannot lead a step out of its job.
   */
  static Path insideJob(final Path jobDirectory, final String relative, final String key)
      throws InvalidStepException {
    if (relative.isEmpty() || relative.indexOf('\0') >= 0) {
      throw new InvalidStepException(key + " must be a non-empty relative path");
    }
    final Path candidate = Path.of(relative);
    if (candidate.isAbsolute()) {
      throw new InvalidStepException(key + " must be relative to the job directory: " + relative);
    }
    final Path resolved = jobDirectory.resolve(candidate).normalize();
    if (!resolved.startsWith(jobDirectory)) {
      throw new InvalidStepException(key + " leaves the job directory: " + relative);
    }
    try {
      final Path realJob = jobDirectory.toRealPath();
      Path existing = resolved;
      while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();
      }
      if (existing != null && !existing.toRealPath().startsWith(realJob)) {
        throw new InvalidStepException(
            key + " leaves the job directory through a link: " + relative);
      }
    } catch (final IOException e) {
      throw new InvalidStepException(
          "Cannot resolve " + key + " " + relative + ": " + e.getMessage());
    }
    return resolved;
  }

  private static Closure closure(final JsonValue value, final Path jobDirectory)
      throws InvalidStepException {
    if (value == null || !value.isObject()) {
      throw new InvalidStepException("closure must be an object with root and graphs");
    }
    final JsonObject json = value.getAsObject();
    for (final String key : json.keys()) {
      if (!key.equals("root")
          && !key.equals("graphs")
          && !key.equals("allGraphs")
          && !key.equals("graphsFile")) {
        throw new InvalidStepException("Unknown closure key '" + key + "'");
      }
    }
    final String root = iri(requiredString(json, "root"), "closure.root");
    final int forms =
        (json.hasKey("allGraphs") ? 1 : 0)
            + (json.hasKey("graphs") ? 1 : 0)
            + (json.hasKey("graphsFile") ? 1 : 0);
    if (forms != 1) {
      throw new InvalidStepException(
          "closure needs exactly one of graphs, graphsFile and allGraphs");
    }
    if (json.hasKey("allGraphs")) {
      if (!booleanValue(json, "allGraphs")) {
        throw new InvalidStepException("closure.allGraphs must be true");
      }
      return new Closure(root, List.of(), true);
    }
    final List<String> listed = new ArrayList<>();
    if (json.hasKey("graphsFile")) {
      listed.addAll(
          graphsFile(insideJob(jobDirectory, requiredString(json, "graphsFile"), "graphsFile")));
    } else {
      final JsonValue graphs = json.get("graphs");
      if (graphs == null || !graphs.isArray()) {
        throw new InvalidStepException("closure.graphs must be a non-empty array of graph IRIs");
      }
      for (final JsonValue graph : graphs.getAsArray()) {
        if (!graph.isString()) {
          throw new InvalidStepException("closure.graphs must contain strings");
        }
        listed.add(graph.getAsString().value());
      }
    }
    if (listed.isEmpty()) {
      throw new InvalidStepException("closure.graphs must be a non-empty array of graph IRIs");
    }
    if (listed.size() > MAX_CLOSURE_GRAPHS) {
      throw new InvalidStepException(
          "closure lists " + listed.size() + " graphs, more than " + MAX_CLOSURE_GRAPHS);
    }
    final Set<String> names = new LinkedHashSet<>();
    for (final String graph : listed) {
      if (!names.add(iri(graph, "closure.graphs"))) {
        throw new InvalidStepException("closure.graphs lists " + graph + " twice");
      }
    }
    return new Closure(root, List.copyOf(names), false);
  }

  /**
   * The closures of a CLASSIFY_ALL step, from a JSON array in the job directory: a refresh may
   * classify a thousand releases, whose IRIs would not all fit a step file. Every closure is
   * validated before any is classified. An output graph may be no closure's input, or a closure's
   * entailments would depend on the order the closures ran in.
   */
  private static List<BatchClosure> closures(
      final Path file, final String stepId, final Path jobDirectory) throws InvalidStepException {
    final JsonValue parsed;
    try {
      if (!Files.isRegularFile(file)) {
        throw new InvalidStepException("Closures file " + file.getFileName() + " is missing");
      }
      if (Files.size(file) > MAX_CLOSURES_FILE_BYTES) {
        throw new InvalidStepException(
            "Closures file "
                + file.getFileName()
                + " is larger than "
                + MAX_CLOSURES_FILE_BYTES
                + " bytes");
      }
      try (InputStream in = Files.newInputStream(file)) {
        parsed = JSON.parseAny(in);
      }
    } catch (final IOException | JsonException e) {
      throw new InvalidStepException("Cannot read closures file: " + e.getMessage());
    }
    if (!parsed.isArray() || parsed.getAsArray().isEmpty()) {
      throw new InvalidStepException("closuresFile must hold a non-empty array of closures");
    }
    if (parsed.getAsArray().size() > MAX_BATCH_CLOSURES) {
      throw new InvalidStepException(
          "closuresFile lists "
              + parsed.getAsArray().size()
              + " closures, more than "
              + MAX_BATCH_CLOSURES);
    }
    final List<BatchClosure> closures = new ArrayList<>();
    final Set<String> outputs = new LinkedHashSet<>();
    long graphs = 0;
    for (final JsonValue value : parsed.getAsArray()) {
      if (!value.isObject()) {
        throw new InvalidStepException("closuresFile must contain objects");
      }
      final String id = stepId + "-" + closures.size();
      if (!STEP_ID.matcher(id).matches()) {
        throw new InvalidStepException("stepId is too long to name its closures' results");
      }
      final JsonObject closureJson = new JsonObject();
      String output = null;
      for (final String key : value.getAsObject().keys()) {
        if (key.equals("output")) {
          output = iri(requiredString(value.getAsObject(), "output"), id + " output");
        } else {
          closureJson.put(key, value.getAsObject().get(key));
        }
      }
      if (output == null) {
        throw new InvalidStepException("closure " + id + " needs an output graph");
      }
      if (!outputs.add(output)) {
        throw new InvalidStepException("closuresFile writes " + output + " twice");
      }
      final Closure closure = closure(closureJson, jobDirectory);
      if (closure.allGraphs()) {
        // With allGraphs the output graphs of earlier closures would join later ones.
        throw new InvalidStepException("closure.allGraphs is not allowed in CLASSIFY_ALL");
      }
      graphs += closure.graphs().size();
      if (graphs > MAX_READ_GRAPHS) {
        throw new InvalidStepException(
            "closuresFile lists more than " + MAX_READ_GRAPHS + " graphs in all");
      }
      closures.add(new BatchClosure(id, closure, output));
    }
    for (final BatchClosure closure : closures) {
      for (final String graph : closure.closure().graphs()) {
        if (outputs.contains(graph)) {
          throw new InvalidStepException(
              "output " + graph + " is one of the graphs of closure " + closure.stepId());
        }
      }
    }
    return List.copyOf(closures);
  }

  /** The graphs a graphs file lists, one IRI per line; blank lines are ignored. */
  private static List<String> graphsFile(final Path file) throws InvalidStepException {
    try {
      if (!Files.isRegularFile(file)) {
        throw new InvalidStepException("Graphs file " + file.getFileName() + " is missing");
      }
      if (Files.size(file) > MAX_GRAPHS_FILE_BYTES) {
        throw new InvalidStepException(
            "Graphs file "
                + file.getFileName()
                + " is larger than "
                + MAX_GRAPHS_FILE_BYTES
                + " bytes");
      }
      return Files.readAllLines(file).stream()
          .map(String::strip)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (final IOException e) {
      throw new InvalidStepException("Cannot read graphs file: " + e.getMessage());
    }
  }

  private static Explain explain(final JsonValue value) throws InvalidStepException {
    if (value == null || !value.isObject()) {
      throw new InvalidStepException("EXPLAIN needs an explain object");
    }
    final JsonObject json = value.getAsObject();
    final boolean inconsistency =
        json.hasKey("inconsistency") && booleanValue(json, "inconsistency");
    final String axiom = json.hasKey("axiom") ? requiredString(json, "axiom") : null;
    if (inconsistency == (axiom != null)) {
      throw new InvalidStepException("explain needs exactly one of axiom or inconsistency=true");
    }
    for (final String key : json.keys()) {
      if (!key.equals("axiom")
          && !key.equals("inconsistency")
          && !key.equals("maxJustifications")) {
        throw new InvalidStepException("Unknown explain key '" + key + "'");
      }
    }
    long maxJustifications = 1;
    if (json.hasKey("maxJustifications")) {
      maxJustifications = requiredLong(json, "maxJustifications");
      if (maxJustifications < 1 || maxJustifications > MAX_JUSTIFICATIONS) {
        throw new InvalidStepException(
            "explain.maxJustifications must be between 1 and " + MAX_JUSTIFICATIONS);
      }
    }
    return new Explain(axiom, inconsistency, (int) maxJustifications);
  }

  private static void rejectKeys(final JsonObject json, final StepKind kind, final String... keys)
      throws InvalidStepException {
    for (final String key : keys) {
      if (json.hasKey(key)) {
        throw new InvalidStepException(kind + " does not take '" + key + "'");
      }
    }
  }

  static String iri(final String value, final String key) throws InvalidStepException {
    try {
      final URI uri = new URI(value);
      if (!uri.isAbsolute()) {
        throw new InvalidStepException(key + " must be an absolute IRI: " + value);
      }
      final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      if (scheme.equals("file") || scheme.equals("jar")) {
        throw new InvalidStepException(key + " must not name a file: " + value);
      }
      return value;
    } catch (final URISyntaxException e) {
      throw new InvalidStepException(key + " is not a valid IRI: " + value);
    }
  }

  private static String requiredString(final JsonObject json, final String key)
      throws InvalidStepException {
    final JsonValue value = json.get(key);
    if (value == null || !value.isString() || value.getAsString().value().isEmpty()) {
      throw new InvalidStepException(key + " must be a non-empty string");
    }
    return value.getAsString().value();
  }

  /**
   * An integer JSON number. A fraction, an exponent with a fraction left over, or a value outside
   * the range of a long is refused rather than rounded.
   */
  static long requiredLong(final JsonObject json, final String key) throws InvalidStepException {
    final JsonValue value = json.get(key);
    if (value == null || !value.isNumber()) {
      throw new InvalidStepException(key + " must be a number");
    }
    try {
      return new BigDecimal(value.getAsNumber().value().toString()).longValueExact();
    } catch (final NumberFormatException | ArithmeticException e) {
      throw new InvalidStepException(key + " must be an integer within the range of a long");
    }
  }

  private static boolean booleanValue(final JsonObject json, final String key)
      throws InvalidStepException {
    final JsonValue value = json.get(key);
    if (!value.isBoolean()) {
      throw new InvalidStepException(key + " must be true or false");
    }
    return value.getAsBoolean().value();
  }

  private static <E extends Enum<E>> E enumValue(
      final Class<E> type, final String value, final String key) throws InvalidStepException {
    try {
      return Enum.valueOf(type, value);
    } catch (final IllegalArgumentException e) {
      throw new InvalidStepException("Unknown " + key + " '" + value + "'");
    }
  }
}
