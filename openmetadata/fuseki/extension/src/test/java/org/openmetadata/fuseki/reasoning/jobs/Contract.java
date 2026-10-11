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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * OpenMetadata's reasoning schemas and examples, copied into test resources (see SOURCE.txt), and a
 * checker for the documents the store writes. It applies the keywords those schemas use for the
 * store's output (type, properties, required, additionalProperties, enum, pattern, minimum, items,
 * and $ref between the four schemas); a $ref to any other OpenMetadata schema is not followed, and
 * format is not asserted. Requests are validated by JobRequest itself, not by this checker.
 */
final class Contract {
  static final String REQUEST = "rdfReasoningJobRequest";
  static final String JOB = "rdfReasoningJob";
  static final String CAPABILITIES = "rdfReasoningCapabilities";

  private Contract() {}

  static JsonObject example(final String name) {
    return read("/openmetadata-contract/examples/" + name + ".json");
  }

  static JsonObject schema(final String name) {
    return read("/openmetadata-contract/schema/" + name + ".json");
  }

  /** The values a schema definition enumerates, such as the job states. */
  static List<String> enumeration(final String schemaName, final String definition) {
    final List<String> values = new ArrayList<>();
    schema(schemaName)
        .getObj("definitions")
        .getObj(definition)
        .get("enum")
        .getAsArray()
        .forEach(value -> values.add(value.getAsString().value()));
    return values;
  }

  /** Every violation of the named schema in {@code document}; empty when it conforms. */
  static List<String> violations(final String schemaName, final JsonValue document) {
    final List<String> violations = new ArrayList<>();
    check(schemaName, schema(schemaName), document, "$", violations);
    return violations;
  }

  private static void check(
      final String schemaName,
      final JsonObject schema,
      final JsonValue value,
      final String path,
      final List<String> violations) {
    if (schema.hasKey("$ref")) {
      final String ref = schema.getString("$ref");
      final int hash = ref.indexOf('#');
      final String file = hash < 0 ? ref : ref.substring(0, hash);
      if (!file.isEmpty() && !file.matches("rdfReasoning[A-Za-z]+\\.json")) {
        return;
      }
      final String target = file.isEmpty() ? schemaName : file.replace(".json", "");
      JsonObject resolved = schema(target);
      if (hash >= 0) {
        for (final String part : ref.substring(hash + 2).split("/")) {
          resolved = resolved.getObj(part);
        }
      }
      check(target, resolved, value, path, violations);
      return;
    }
    final String type = schema.hasKey("type") ? schema.getString("type") : null;
    if ("object".equals(type)) {
      if (!value.isObject()) {
        violations.add(path + " is not an object");
        return;
      }
      final JsonObject properties =
          schema.hasKey("properties") ? schema.getObj("properties") : new JsonObject();
      if (schema.hasKey("required")) {
        schema
            .get("required")
            .getAsArray()
            .forEach(
                key -> {
                  if (!value.getAsObject().hasKey(key.getAsString().value())) {
                    violations.add(path + "." + key.getAsString().value() + " is required");
                  }
                });
      }
      value
          .getAsObject()
          .forEach(
              (key, member) -> {
                if (properties.hasKey(key)) {
                  check(schemaName, properties.getObj(key), member, path + "." + key, violations);
                } else if (schema.hasKey("additionalProperties")
                    && !schema.getBoolean("additionalProperties")) {
                  violations.add(path + "." + key + " is not allowed");
                }
              });
    } else if ("array".equals(type)) {
      if (!value.isArray()) {
        violations.add(path + " is not an array");
        return;
      }
      for (int i = 0; i < value.getAsArray().size(); i++) {
        check(
            schemaName,
            schema.getObj("items"),
            value.getAsArray().get(i),
            path + "[" + i + "]",
            violations);
      }
    } else if ("string".equals(type)) {
      if (!value.isString()) {
        violations.add(path + " is not a string");
        return;
      }
      final String text = value.getAsString().value();
      if (schema.hasKey("enum") && !schema.get("enum").getAsArray().contains(value)) {
        violations.add(path + " '" + text + "' is not in " + schema.get("enum"));
      }
      if (schema.hasKey("pattern")
          && !Pattern.compile(schema.getString("pattern")).matcher(text).find()) {
        violations.add(path + " '" + text + "' does not match " + schema.getString("pattern"));
      }
    } else if ("integer".equals(type) || "number".equals(type)) {
      if (!value.isNumber()) {
        violations.add(path + " is not a number");
        return;
      }
      final BigDecimal number = new BigDecimal(value.getAsNumber().value().toString());
      if ("integer".equals(type) && number.stripTrailingZeros().scale() > 0) {
        violations.add(path + " is not an integer");
      }
      if (schema.hasKey("minimum")
          && number.compareTo(new BigDecimal(schema.getNumber("minimum").toString())) < 0) {
        violations.add(path + " is below its minimum");
      }
    } else if ("boolean".equals(type) && !value.isBoolean()) {
      violations.add(path + " is not a boolean");
    }
  }

  private static JsonObject read(final String resource) {
    try (InputStream in = Contract.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return JSON.parse(in);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
