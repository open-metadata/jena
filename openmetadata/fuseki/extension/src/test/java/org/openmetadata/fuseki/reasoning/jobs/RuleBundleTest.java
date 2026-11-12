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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.junit.jupiter.api.Test;

class RuleBundleTest {
  private static final String BODY = "CONSTRUCT { ?s <urn:p> ?o } WHERE { ?s <urn:q> ?o }";

  @Test
  void theEnabledRulesRunInNameOrderWhateverOrderTheRequestSendsThem() throws Exception {
    final RuleBundle bundle =
        RuleBundle.of(
            rules(
                rule("b-rule", "\"ruleType\": \"RDFS\""),
                rule("c-rule", "\"enabled\": false"),
                rule("a-rule", "\"enabled\": true")));
    assertEquals(
        List.of("a-rule", "b-rule"), bundle.rules().stream().map(RuleBundle.Rule::name).toList());
    assertEquals(
        List.of("CONSTRUCT", "RDFS"),
        bundle.rules().stream().map(RuleBundle.Rule::ruleType).toList());
    assertEquals(BODY, bundle.rulesJson().get(0).getAsObject().getString("ruleBody"));
  }

  @Test
  void aDisabledRuleTakesNoPartEvenIfItsNameIsUsedTwice() throws Exception {
    assertEquals(
        1,
        RuleBundle.of(rules(rule("a-rule", "\"enabled\": false"), rule("a-rule", "")))
            .rules()
            .size());
  }

  @Test
  void aNameUsedTwiceIsRefusedBecauseTheirConclusionsWouldShareAGraph() {
    final Capture.Refused refused =
        assertThrows(
            Capture.Refused.class,
            () -> RuleBundle.of(rules(rule("a-rule", ""), rule("a-rule", ""))));
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
    assertEquals("a-rule", refused.problem().subject());
  }

  @Test
  void aBodyOverWhatOpenMetadataAdmitsIsRefused() {
    final JsonArray array =
        JSON.parseAny(
                "[{\"name\": \"a-rule\", \"ruleBody\": \""
                    + "x".repeat(RuleBundle.MAX_BODY_CHARACTERS + 1)
                    + "\"}]")
            .getAsArray();
    final Capture.Refused refused = assertThrows(Capture.Refused.class, () -> RuleBundle.of(array));
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
  }

  private static String rule(final String name, final String extra) {
    return "{\"name\": \""
        + name
        + "\", \"ruleBody\": \""
        + BODY
        + "\""
        + (extra.isEmpty() ? "" : ", " + extra)
        + "}";
  }

  private static JsonArray rules(final String... rules) {
    return JSON.parseAny("[" + String.join(",", rules) + "]").getAsArray();
  }
}
