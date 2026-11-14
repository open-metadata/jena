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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * OpenMetadata's rule admission vectors (resources/rule-vectors), compiled through the bridge the
 * Fuseki module admits bundles with: every file must be accepted or refused with exactly the
 * problem codes OpenMetadata's RuleAdmissionCheck expects, so a rule OpenMetadata saves never fails
 * admission in the store.
 */
class RuleCompilerVectorsTest {
  @TestFactory
  Stream<DynamicTest> everyVectorHasItsRecordedOutcome() throws IOException {
    final List<String> lines;
    try (InputStream in = resource("expected.txt")) {
      lines =
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .toList();
    }
    return lines.stream()
        .map(
            line -> {
              final String[] fields = line.split(" ");
              return DynamicTest.dynamicTest(
                  fields[0],
                  () -> {
                    final String body;
                    try (InputStream in = resource(fields[0])) {
                      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    final JsonObject rule = new JsonObject();
                    rule.put("name", "vector-rule");
                    rule.put("ruleBody", body);
                    final JsonArray rules = new JsonArray();
                    rules.add(rule);
                    final TreeSet<String> codes = new TreeSet<>();
                    JSON.parseAny(ServerBridge.compile(JSON.toString(rules), 64))
                        .getAsArray()
                        .forEach(problem -> codes.add(problem.getAsObject().getString("code")));
                    final List<String> expected =
                        fields[1].equals("accepted")
                            ? List.of()
                            : Arrays.asList(fields).subList(2, fields.length);
                    assertEquals(fields[1].equals("accepted") ? 0 : 1, codes.isEmpty() ? 0 : 1);
                    assertEquals(expected, List.copyOf(codes), fields[0]);
                  });
            });
  }

  private static InputStream resource(final String name) {
    return RuleCompilerVectorsTest.class.getResourceAsStream("/rule-vectors/" + name);
  }
}
