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
          "limits");

  /** The import closure to reason over: named graphs of the working dataset and its root. */
  public record Closure(String root, List<String> graphs) {}

  /** What to justify: one entailed axiom, in OWL 2 functional syntax, or the inconsistency. */
  public record Explain(String axiom, boolean inconsistency) {}

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
        closure = closure(json.get("closure"));
        if (json.hasKey("engine")) {
          engine = enumValue(EngineChoice.class, requiredString(json, "engine"), "engine");
        }
        if (kind == StepKind.CLASSIFY) {
          rejectKeys(json, kind, "explain");
          if (json.hasKey("output")) {
            outputGraph = iri(requiredString(json, "output"), "output");
            if (closure.graphs().contains(outputGraph)) {
              throw new InvalidStepException("output must not be one of the closure's graphs");
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
      case COMPACT ->
          rejectKeys(json, kind, "inputs", "closure", "engine", "output", "checks", "explain");
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
        limits);
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

  private static Closure closure(final JsonValue value) throws InvalidStepException {
    if (value == null || !value.isObject()) {
      throw new InvalidStepException("closure must be an object with root and graphs");
    }
    final JsonObject json = value.getAsObject();
    for (final String key : json.keys()) {
      if (!key.equals("root") && !key.equals("graphs")) {
        throw new InvalidStepException("Unknown closure key '" + key + "'");
      }
    }
    final String root = iri(requiredString(json, "root"), "closure.root");
    final JsonValue graphs = json.get("graphs");
    if (graphs == null || !graphs.isArray() || graphs.getAsArray().isEmpty()) {
      throw new InvalidStepException("closure.graphs must be a non-empty array of graph IRIs");
    }
    final Set<String> names = new LinkedHashSet<>();
    for (final JsonValue graph : graphs.getAsArray()) {
      if (!graph.isString()) {
        throw new InvalidStepException("closure.graphs must contain strings");
      }
      if (!names.add(iri(graph.getAsString().value(), "closure.graphs"))) {
        throw new InvalidStepException("closure.graphs lists " + graph + " twice");
      }
    }
    return new Closure(root, List.copyOf(names));
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
      if (!key.equals("axiom") && !key.equals("inconsistency")) {
        throw new InvalidStepException("Unknown explain key '" + key + "'");
      }
    }
    return new Explain(axiom, inconsistency);
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

  private static long requiredLong(final JsonObject json, final String key)
      throws InvalidStepException {
    final JsonValue value = json.get(key);
    if (value == null || !value.isNumber()) {
      throw new InvalidStepException(key + " must be a number");
    }
    final Number number = value.getAsNumber().value();
    if (number.doubleValue() != number.longValue()) {
      throw new InvalidStepException(key + " must be an integer");
    }
    return number.longValue();
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
