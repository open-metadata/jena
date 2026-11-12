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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * The governance rules a REFRESH runs: the enabled items of the request's {@code rules}
 * (OpenMetadata's ADR:2026-11-10-reasoning-jobs-name-ontology-releases-and-carry-their-rules). The
 * request's {@code ruleBundleDigest} is OpenMetadata's and opaque here, so it is recorded with the
 * snapshot and never recomputed.
 *
 * <p>Only {@code name}, {@code ruleType} and {@code ruleBody} reach the RULES step; the rules'
 * order does not, since the step's fixed point is the same for every order.
 */
public final class RuleBundle {
  /** A rule body, as OpenMetadata admits it. */
  public static final int MAX_BODY_CHARACTERS = 16_384;

  /** One rule of a bundle. */
  public record Rule(String name, String ruleType, String body) {
    JsonObject toJson() {
      final JsonObject json = new JsonObject();
      json.put("name", name);
      json.put("ruleType", ruleType);
      json.put("ruleBody", body);
      return json;
    }
  }

  private final List<Rule> rules;

  private RuleBundle(final List<Rule> rules) {
    this.rules = rules;
  }

  /** The rules in name order. */
  public List<Rule> rules() {
    return rules;
  }

  public JsonArray rulesJson() {
    final JsonArray array = new JsonArray();
    rules.forEach(rule -> array.add(rule.toJson()));
    return array;
  }

  /**
   * The enabled rules of a request, whose shape the request validation already checked; what it
   * cannot see is a name used twice, which would send two rules' conclusions to one graph, and a
   * body over what OpenMetadata admits.
   *
   * @throws Capture.Refused INVALID_REQUEST for either
   */
  static RuleBundle of(final JsonArray requested) throws Capture.Refused {
    final List<Rule> enabled = new ArrayList<>();
    final Set<String> names = new HashSet<>();
    for (final JsonValue value : requested) {
      final JsonObject rule = value.getAsObject();
      if (rule.hasKey("enabled") && !rule.getBoolean("enabled")) {
        continue;
      }
      final String name = rule.getString("name");
      final String body = rule.getString("ruleBody");
      if (!names.add(name)) {
        throw invalid("The request names rule '" + name + "' twice", name);
      }
      if (body.length() > MAX_BODY_CHARACTERS) {
        throw invalid(
            "Rule '" + name + "' has a body over " + MAX_BODY_CHARACTERS + " characters", name);
      }
      enabled.add(
          new Rule(name, rule.hasKey("ruleType") ? rule.getString("ruleType") : "CONSTRUCT", body));
    }
    enabled.sort(Comparator.comparing(Rule::name));
    return new RuleBundle(List.copyOf(enabled));
  }

  private static Capture.Refused invalid(final String message, final String rule) {
    return new Capture.Refused(Problem.of(Problem.Code.INVALID_REQUEST, message).withSubject(rule));
  }
}
