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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.atlas.json.JsonValue;

/**
 * A job request, validated against OpenMetadata's rdfReasoningJobRequest.json and the schemas it
 * references (ontologyExpression, rdfStatement, inferenceRule), including the per-operation and
 * per-check requirements of OpenMetadata pull request 35199. The store implements the protocol from
 * those schemas without OpenMetadata's classes, so every rule is checked here and pinned by tests
 * that load OpenMetadata's own examples. One rule the schema states only in prose is enforced too:
 * a REFRESH accepts only the APPROVED ontology selection.
 *
 * @param digest SHA-256 of the request in a canonical form, to tell a retry from a different
 *     request that reuses its ID
 */
public record JobRequest(
    UUID requestId, Operation operation, JsonObject document, String dataset, String digest) {

  public enum Operation {
    REFRESH,
    CHECK,
    EXPLAIN
  }

  /** Large enough for 256 rules of several KiB each; the body is refused unread beyond it. */
  public static final int MAX_BYTES = 4 << 20;

  /**
   * Deeper than any expression OpenMetadata authors; checked before parsing, because the JSON
   * parser recurses once per level.
   */
  public static final int MAX_DEPTH = 64;

  private static final int MAX_ERRORS = 20;
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern RULE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,62}[a-z0-9]");
  private static final Set<String> SELECTIONS = Set.of("APPROVED", "CANDIDATE");

  /** Thrown with every violation found, up to a bound; the message lists them. */
  public static final class Invalid extends Exception {
    private final List<String> violations;

    Invalid(final List<String> violations) {
      super(String.join("; ", violations));
      this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
      return violations;
    }
  }

  /** Parses and validates a request body sent to {@code dataset}. */
  public static JobRequest read(final byte[] body, final String dataset) throws Invalid {
    if (body.length > MAX_BYTES) {
      throw new Invalid(List.of("the request is larger than " + MAX_BYTES + " bytes"));
    }
    if (depth(body) > MAX_DEPTH) {
      throw new Invalid(List.of("the request nests deeper than " + MAX_DEPTH + " levels"));
    }
    final JsonObject document;
    try {
      document = JSON.parse(new ByteArrayInputStream(body));
    } catch (final JsonException e) {
      throw new Invalid(List.of("the request is not a JSON object: " + e.getMessage()));
    }
    return validate(document, dataset);
  }

  public static JobRequest validate(final JsonObject document, final String dataset)
      throws Invalid {
    final Validator check = new Validator();
    check.request(document);
    if (!check.errors.isEmpty()) {
      throw new Invalid(check.errors);
    }
    return new JobRequest(
        UUID.fromString(document.getString("requestId")),
        Operation.valueOf(document.getString("operation")),
        document,
        dataset,
        digest(document, dataset));
  }

  /**
   * What the job reasons over, as rdfReasoningSnapshot.json's input: the fields of the request that
   * identify its input, recorded with the job without interpretation.
   */
  public JsonObject input() {
    final JsonObject input = new JsonObject();
    for (final String key :
        List.of(
            "sourceRevision",
            "ontologySelection",
            "ontologyIri",
            "ontologyDigest",
            "ruleBundleDigest")) {
      if (document.hasKey(key)) {
        input.put(key, JSON.copy(document.get(key)));
      }
    }
    return input;
  }

  /** The deepest nesting of objects and arrays, ignoring brackets inside strings. */
  static int depth(final byte[] body) {
    int depth = 0;
    int deepest = 0;
    boolean inString = false;
    for (int i = 0; i < body.length; i++) {
      final byte b = body[i];
      if (inString) {
        if (b == '\\') {
          i++;
        } else if (b == '"') {
          inString = false;
        }
      } else if (b == '"') {
        inString = true;
      } else if (b == '{' || b == '[') {
        deepest = Math.max(deepest, ++depth);
      } else if (b == '}' || b == ']') {
        depth--;
      }
    }
    return deepest;
  }

  private static String digest(final JsonObject document, final String dataset) {
    final StringBuilder canonical = new StringBuilder(dataset).append('\n');
    canonical(document, canonical);
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Keys sorted and numbers by value, so a client that reorders fields still retries the same. */
  private static void canonical(final JsonValue value, final StringBuilder out) {
    if (value.isObject()) {
      final TreeMap<String, JsonValue> sorted = new TreeMap<>();
      value.getAsObject().forEach(sorted::put);
      out.append('{');
      sorted.forEach(
          (key, member) -> {
            out.append(JSON.toStringFlat(new JsonString(key))).append(':');
            canonical(member, out);
            out.append(',');
          });
      out.append('}');
    } else if (value.isArray()) {
      out.append('[');
      for (final JsonValue member : value.getAsArray()) {
        canonical(member, out);
        out.append(',');
      }
      out.append(']');
    } else if (value.isNumber()) {
      out.append(
          new BigDecimal(value.getAsNumber().value().toString()).stripTrailingZeros().toString());
    } else {
      out.append(JSON.toStringFlat(value));
    }
  }

  /** Collects violations with their JSON paths, in the schemas' terms. */
  private static final class Validator {
    private final List<String> errors = new ArrayList<>();

    void request(final JsonObject json) {
      keys(
          json,
          "",
          Set.of(
              "requestId",
              "operation",
              "sourceRevision",
              "ontologySelection",
              "ontologyIri",
              "ontologyDigest",
              "ruleBundleDigest",
              "rules",
              "check",
              "explain"),
          List.of("requestId", "operation", "sourceRevision", "ontologySelection"));
      uuid(json.get("requestId"), "requestId");
      final String operation =
          choice(json.get("operation"), "operation", Set.of("REFRESH", "CHECK", "EXPLAIN"));
      sourceRevision(json.get("sourceRevision"));
      final String selection =
          choice(json.get("ontologySelection"), "ontologySelection", SELECTIONS);
      optional(json, "ontologyIri", value -> uri(value, "ontologyIri"));
      optional(json, "ontologyDigest", value -> digest(value, "ontologyDigest"));
      optional(json, "ruleBundleDigest", value -> digest(value, "ruleBundleDigest"));
      optional(json, "rules", this::rules);
      optional(json, "check", this::check);
      optional(json, "explain", this::explain);
      if (operation == null) {
        return;
      }
      switch (operation) {
        case "REFRESH" -> {
          requireFor(json, operation, "ruleBundleDigest");
          if ("CANDIDATE".equals(selection)) {
            error("ontologySelection: a REFRESH accepts only APPROVED");
          }
        }
        case "CHECK" -> requireFor(json, operation, "ontologyIri", "check");
        default -> requireFor(json, operation, "ontologyIri", "explain");
      }
    }

    private void sourceRevision(final JsonValue value) {
      final JsonObject json =
          object(
              value,
              "sourceRevision",
              Set.of("datasetGeneration", "liveWriteWatermark"),
              List.of("datasetGeneration", "liveWriteWatermark"));
      if (json != null) {
        uuid(json.get("datasetGeneration"), "sourceRevision.datasetGeneration");
        integer(json.get("liveWriteWatermark"), "sourceRevision.liveWriteWatermark", 0L, null);
      }
    }

    private void rules(final JsonValue value) {
      final JsonArray rules = array(value, "rules", 256);
      if (rules == null) {
        return;
      }
      for (int i = 0; i < rules.size(); i++) {
        final String path = "rules[" + i + "]";
        final JsonObject rule =
            object(
                rules.get(i),
                path,
                Set.of(
                    "name",
                    "displayName",
                    "description",
                    "ruleType",
                    "ruleBody",
                    "enabled",
                    "priority",
                    "tags"),
                List.of("name", "ruleBody"));
        if (rule == null) {
          continue;
        }
        final String name = string(rule.get("name"), path + ".name");
        if (name != null && !RULE_NAME.matcher(name).matches()) {
          error(path + ".name: must match " + RULE_NAME.pattern());
        }
        optional(rule, "displayName", v -> string(v, path + ".displayName"));
        optional(rule, "description", v -> string(v, path + ".description"));
        optional(rule, "ruleType", v -> choice(v, path + ".ruleType", Set.of("CONSTRUCT", "RDFS")));
        final String body = string(rule.get("ruleBody"), path + ".ruleBody");
        if (body != null && body.codePointCount(0, body.length()) < 16) {
          error(path + ".ruleBody: must be at least 16 characters");
        }
        optional(rule, "enabled", v -> bool(v, path + ".enabled"));
        optional(rule, "priority", v -> integer(v, path + ".priority", 0L, 10_000L));
        optional(
            rule,
            "tags",
            v -> {
              final JsonArray tags = array(v, path + ".tags", Integer.MAX_VALUE);
              if (tags != null) {
                for (int t = 0; t < tags.size(); t++) {
                  string(tags.get(t), path + ".tags[" + t + "]");
                }
              }
            });
      }
    }

    private void check(final JsonValue value) {
      final JsonObject json =
          object(
              value,
              "check",
              Set.of("kind", "classExpression", "superClassExpression", "statement"),
              List.of("kind"));
      if (json == null) {
        return;
      }
      final String kind =
          choice(
              json.get("kind"),
              "check.kind",
              Set.of("CONSISTENCY", "SATISFIABILITY", "SUBSUMPTION", "ENTAILMENT"));
      optional(json, "classExpression", v -> expression(v, "check.classExpression"));
      optional(json, "superClassExpression", v -> expression(v, "check.superClassExpression"));
      optional(json, "statement", v -> statement(v, "check.statement"));
      if (kind == null) {
        return;
      }
      switch (kind) {
        case "SATISFIABILITY" -> requireFor(json, "check.kind " + kind, "classExpression");
        case "SUBSUMPTION" ->
            requireFor(json, "check.kind " + kind, "classExpression", "superClassExpression");
        case "ENTAILMENT" -> requireFor(json, "check.kind " + kind, "statement");
        default -> {}
      }
    }

    private void explain(final JsonValue value) {
      final JsonObject json =
          object(value, "explain", Set.of("statement", "maxJustifications"), List.of("statement"));
      if (json != null) {
        optional(json, "statement", v -> statement(v, "explain.statement"));
        optional(json, "maxJustifications", v -> integer(v, "explain.maxJustifications", 1L, 10L));
      }
    }

    private void expression(final JsonValue value, final String path) {
      final JsonObject json =
          object(
              value,
              path,
              Set.of(
                  "kind",
                  "classIri",
                  "operands",
                  "individualIris",
                  "propertyIri",
                  "restrictionKind",
                  "filler",
                  "cardinality",
                  "individualIri",
                  "literal"),
              List.of("kind"));
      if (json == null) {
        return;
      }
      choice(
          json.get("kind"),
          path + ".kind",
          Set.of("NAMED_CLASS", "INTERSECTION", "UNION", "ONE_OF", "RESTRICTION"));
      optional(json, "classIri", v -> uri(v, path + ".classIri"));
      optional(
          json,
          "operands",
          v -> {
            final JsonArray operands = array(v, path + ".operands", Integer.MAX_VALUE);
            if (operands != null) {
              for (int i = 0; i < operands.size(); i++) {
                expression(operands.get(i), path + ".operands[" + i + "]");
              }
            }
          });
      optional(
          json,
          "individualIris",
          v -> {
            final JsonArray iris = array(v, path + ".individualIris", Integer.MAX_VALUE);
            if (iris != null) {
              final Set<String> seen = new HashSet<>();
              for (int i = 0; i < iris.size(); i++) {
                final String iri = uri(iris.get(i), path + ".individualIris[" + i + "]");
                if (iri != null && !seen.add(iri)) {
                  error(path + ".individualIris: items must be unique");
                }
              }
            }
          });
      optional(json, "propertyIri", v -> uri(v, path + ".propertyIri"));
      optional(
          json,
          "restrictionKind",
          v ->
              choice(
                  v,
                  path + ".restrictionKind",
                  Set.of("SOME", "ONLY", "VALUE", "MIN", "MAX", "EXACT")));
      optional(json, "filler", v -> expression(v, path + ".filler"));
      optional(json, "cardinality", v -> integer(v, path + ".cardinality", 0L, null));
      optional(json, "individualIri", v -> uri(v, path + ".individualIri"));
      optional(
          json,
          "literal",
          v -> {
            final JsonObject literal =
                object(v, path + ".literal", Set.of("value", "datatypeIri"), List.of("value"));
            if (literal != null) {
              optional(literal, "value", s -> string(s, path + ".literal.value"));
              optional(literal, "datatypeIri", s -> uri(s, path + ".literal.datatypeIri"));
            }
          });
    }

    private void statement(final JsonValue value, final String path) {
      final JsonObject json =
          object(
              value,
              path,
              Set.of(
                  "subjectIri",
                  "predicateIri",
                  "objectKind",
                  "objectIri",
                  "literalValue",
                  "datatypeIri",
                  "language"),
              List.of("subjectIri", "predicateIri", "objectKind"));
      if (json == null) {
        return;
      }
      optional(json, "subjectIri", v -> uri(v, path + ".subjectIri"));
      optional(json, "predicateIri", v -> uri(v, path + ".predicateIri"));
      optional(json, "objectKind", v -> choice(v, path + ".objectKind", Set.of("IRI", "LITERAL")));
      optional(json, "objectIri", v -> uri(v, path + ".objectIri"));
      optional(json, "literalValue", v -> string(v, path + ".literalValue"));
      optional(json, "datatypeIri", v -> uri(v, path + ".datatypeIri"));
      optional(json, "language", v -> string(v, path + ".language"));
    }

    private JsonObject object(
        final JsonValue value,
        final String path,
        final Set<String> allowed,
        final List<String> required) {
      if (value == null) {
        return null;
      }
      if (!value.isObject()) {
        error(path + ": must be an object");
        return null;
      }
      keys(value.getAsObject(), path, allowed, required);
      return value.getAsObject();
    }

    private void keys(
        final JsonObject json,
        final String path,
        final Set<String> allowed,
        final List<String> required) {
      final String prefix = path.isEmpty() ? "" : path + ".";
      for (final String key : json.keys()) {
        if (!allowed.contains(key)) {
          error(prefix + key + ": is not a field the protocol defines");
        }
      }
      for (final String key : required) {
        if (!json.hasKey(key)) {
          error(prefix + key + ": is required");
        }
      }
    }

    private void requireFor(final JsonObject json, final String condition, final String... keys) {
      for (final String key : keys) {
        if (!json.hasKey(key)) {
          error(key + ": is required for " + condition);
        }
      }
    }

    private void optional(final JsonObject json, final String key, final Consumer<JsonValue> rule) {
      final JsonValue value = json.get(key);
      if (value != null) {
        rule.accept(value);
      }
    }

    private String string(final JsonValue value, final String path) {
      if (value == null) {
        return null;
      }
      if (!value.isString()) {
        error(path + ": must be a string");
        return null;
      }
      return value.getAsString().value();
    }

    private String choice(final JsonValue value, final String path, final Set<String> choices) {
      final String text = string(value, path);
      if (text != null && !choices.contains(text)) {
        error(path + ": must be one of " + new TreeSet<>(choices) + ", got '" + text + "'");
        return null;
      }
      return text;
    }

    private void uuid(final JsonValue value, final String path) {
      final String text = string(value, path);
      if (text != null && !UUID_PATTERN.matcher(text).matches()) {
        error(path + ": must be a UUID");
      }
    }

    private void digest(final JsonValue value, final String path) {
      final String text = string(value, path);
      if (text != null && !DIGEST.matcher(text).matches()) {
        error(path + ": must be sha256: followed by 64 lowercase hexadecimal digits");
      }
    }

    private String uri(final JsonValue value, final String path) {
      final String text = string(value, path);
      if (text == null) {
        return null;
      }
      try {
        if (new URI(text).isAbsolute()) {
          return text;
        }
      } catch (final URISyntaxException e) {
        // Reported below with the other invalid values.
      }
      error(path + ": must be an absolute URI");
      return null;
    }

    private void bool(final JsonValue value, final String path) {
      if (!value.isBoolean()) {
        error(path + ": must be true or false");
      }
    }

    private void integer(
        final JsonValue value, final String path, final Long minimum, final Long maximum) {
      if (value == null) {
        return;
      }
      final long number;
      try {
        if (!value.isNumber()) {
          throw new ArithmeticException();
        }
        number = new BigDecimal(value.getAsNumber().value().toString()).longValueExact();
      } catch (final NumberFormatException | ArithmeticException e) {
        error(path + ": must be an integer");
        return;
      }
      if (minimum != null && number < minimum) {
        error(path + ": must be at least " + minimum);
      }
      if (maximum != null && number > maximum) {
        error(path + ": must be at most " + maximum);
      }
    }

    private JsonArray array(final JsonValue value, final String path, final int maxItems) {
      if (!value.isArray()) {
        error(path + ": must be an array");
        return null;
      }
      if (value.getAsArray().size() > maxItems) {
        error(path + ": must have at most " + maxItems + " items");
      }
      return value.getAsArray();
    }

    private void error(final String message) {
      if (errors.size() < MAX_ERRORS) {
        errors.add(message);
      } else if (errors.size() == MAX_ERRORS) {
        errors.add("further violations omitted");
      }
    }
  }
}
