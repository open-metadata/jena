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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Requests are validated against OpenMetadata's request schema: its examples are accepted, and
 * every violation its contract test and pull request 35199 name is refused with the field's path.
 */
class JobRequestTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "refresh-request",
        "refresh-request-with-rule",
        "check-subsumption-request",
        "explain-request"
      })
  void everyOpenMetadataExampleIsAccepted(final String example) throws Exception {
    final JsonObject document = Contract.example(example);
    final JobRequest request = JobRequest.validate(document, "openmetadata");
    assertEquals(UUID.fromString(document.getString("requestId")), request.requestId());
    assertEquals(document.getString("operation"), request.operation().name());
  }

  @Test
  void theInputRecordsTheIdentifyingFieldsWithoutInterpretingThem() throws Exception {
    final JobRequest refresh =
        JobRequest.validate(Contract.example("refresh-request-with-rule"), "openmetadata");
    final JsonObject input = refresh.input();
    assertEquals(
        java.util.Set.of("sourceRevision", "ontologyReleases", "ruleBundleDigest"), input.keys());
    assertEquals(
        Contract.example("refresh-request-with-rule").get("sourceRevision"),
        input.get("sourceRevision"));
    final JobRequest check =
        JobRequest.validate(Contract.example("check-subsumption-request"), "openmetadata");
    assertEquals(
        java.util.Set.of("sourceRevision", "ontologyReleases", "releaseRdf"), check.input().keys());
    assertEquals(
        Contract.example("check-subsumption-request").get("releaseRdf"),
        check.input().get("releaseRdf"));
  }

  @Test
  void aRefreshMayListNoActiveRelease() throws Exception {
    final JsonObject document = Contract.example("refresh-request");
    document.get("ontologyReleases").getAsArray().clear();
    assertEquals(JobRequest.Operation.REFRESH, JobRequest.validate(document, "ds").operation());
  }

  static Stream<Arguments> violations() {
    return Stream.of(
        violation(
            "a digest that is not a SHA-256",
            "refresh-request",
            r -> r.put("ruleBundleDigest", "sha256:example"),
            "ruleBundleDigest"),
        violation(
            "a negative live-write watermark",
            "refresh-request",
            r -> r.getObj("sourceRevision").put("liveWriteWatermark", -1),
            "sourceRevision.liveWriteWatermark: must be at least 0"),
        violation(
            "a fractional live-write watermark",
            "refresh-request",
            r -> r.getObj("sourceRevision").put("liveWriteWatermark", JSON.parseAny("1.5")),
            "sourceRevision.liveWriteWatermark: must be an integer"),
        violation(
            "a request without a source revision",
            "refresh-request",
            r -> r.remove("sourceRevision"),
            "sourceRevision: is required"),
        violation(
            "a request field the protocol does not define",
            "refresh-request",
            r -> r.put("ontologyUrl", "https://example.org/remote.owl"),
            "ontologyUrl: is not a field the protocol defines"),
        violation(
            "the APPROVED/CANDIDATE selection that releases replaced",
            "refresh-request",
            r -> r.put("ontologySelection", "APPROVED"),
            "ontologySelection: is not a field the protocol defines"),
        violation(
            "a request without its ontology releases",
            "refresh-request",
            r -> r.remove("ontologyReleases"),
            "ontologyReleases: is required"),
        violation(
            "a release digest in the store's sha256: form instead of the release's own",
            "refresh-request",
            r ->
                r.get("ontologyReleases")
                    .getAsArray()
                    .get(0)
                    .getAsObject()
                    .put("contentDigest", "sha256:" + "a".repeat(64)),
            "ontologyReleases[0].contentDigest: must be 64 lowercase hexadecimal digits"),
        violation(
            "a release without its release ID",
            "refresh-request",
            r -> r.get("ontologyReleases").getAsArray().get(0).getAsObject().remove("releaseId"),
            "ontologyReleases[0].releaseId: is required"),
        violation(
            "a release listed twice",
            "refresh-request",
            r -> {
              final JsonArray releases = r.get("ontologyReleases").getAsArray();
              releases.add(JSON.copy(releases.get(0)));
            },
            "ontologyReleases[1].ontologyId: "),
        violation(
            "two different releases of one ontology",
            "refresh-request",
            r -> {
              final JsonArray releases = r.get("ontologyReleases").getAsArray();
              final JsonObject other = (JsonObject) JSON.copy(releases.get(0));
              other.put("releaseId", UUID.nameUUIDFromBytes(new byte[] {7}).toString());
              releases.add(other);
            },
            "ontologyReleases[1].ontologyId: "),
        violation(
            "more releases than a request may list",
            "refresh-request",
            r -> {
              final JsonArray releases = r.get("ontologyReleases").getAsArray();
              for (int i = 0; releases.size() <= 1000; i++) {
                final JsonObject release = (JsonObject) JSON.copy(releases.get(0));
                release.put("releaseId", UUID.nameUUIDFromBytes(new byte[] {(byte) i}).toString());
                release.put(
                    "ontologyId", UUID.nameUUIDFromBytes(new byte[] {1, (byte) i}).toString());
                releases.add(release);
              }
            },
            "ontologyReleases: must have at most 1000 items"),
        violation(
            "a REFRESH without its rules inline",
            "refresh-request",
            r -> r.remove("rules"),
            "rules: is required for REFRESH"),
        violation(
            "a REFRESH carrying uploaded RDF",
            "refresh-request",
            r ->
                r.put(
                    "releaseRdf", Contract.example("check-subsumption-request").get("releaseRdf")),
            "releaseRdf: is not accepted for REFRESH"),
        violation(
            "more rules than one bundle may carry",
            "refresh-request-with-rule",
            r -> {
              final JsonArray rules = r.get("rules").getAsArray();
              while (rules.size() <= 256) {
                rules.add(JSON.copy(rules.get(0)));
              }
            },
            "rules: must have at most 256 items"),
        violation(
            "a rule with a field inferenceRule.json does not define",
            "refresh-request-with-rule",
            r -> r.get("rules").getAsArray().get(0).getAsObject().put("script", "x"),
            "rules[0].script"),
        violation(
            "a request ID that is not a UUID",
            "refresh-request",
            r -> r.put("requestId", "retry-1"),
            "requestId: must be a UUID"),
        violation(
            "an unknown operation",
            "refresh-request",
            r -> r.put("operation", "MATERIALIZE"),
            "operation: must be one of"),
        violation(
            "a REFRESH without a rule bundle digest",
            "refresh-request",
            r -> r.remove("ruleBundleDigest"),
            "ruleBundleDigest: is required for REFRESH"),
        violation(
            "a CHECK without its uploaded release",
            "check-subsumption-request",
            r -> r.remove("releaseRdf"),
            "releaseRdf: is required for CHECK"),
        violation(
            "a CHECK over no release",
            "check-subsumption-request",
            r -> r.get("ontologyReleases").getAsArray().clear(),
            "ontologyReleases: must have exactly 1 item for CHECK"),
        violation(
            "a CHECK over two releases",
            "check-subsumption-request",
            r -> {
              final JsonObject second =
                  (JsonObject) JSON.copy(r.get("ontologyReleases").getAsArray().get(0));
              second.put("ontologyId", "6d0e4b2a-9f1c-4a3e-8b7d-5c2a1f0e9d8c");
              r.get("ontologyReleases").getAsArray().add(second);
            },
            "ontologyReleases: must have exactly 1 item for CHECK"),
        violation(
            "uploaded RDF in a format the store does not read",
            "check-subsumption-request",
            r -> r.getObj("releaseRdf").put("mediaType", "text/turtle"),
            "releaseRdf.mediaType: must be one of"),
        violation(
            "uploaded RDF with no bytes",
            "check-subsumption-request",
            r -> r.getObj("releaseRdf").put("byteSize", 0),
            "releaseRdf.byteSize: must be at least 1"),
        violation(
            "an upload digest that is not a SHA-256",
            "check-subsumption-request",
            r -> r.getObj("releaseRdf").put("digest", "sha256:example"),
            "releaseRdf.digest: must be sha256:"),
        violation(
            "a CHECK without a question",
            "check-subsumption-request",
            r -> r.remove("check"),
            "check: is required for CHECK"),
        violation(
            "an EXPLAIN without its uploaded release",
            "explain-request",
            r -> r.remove("releaseRdf"),
            "releaseRdf: is required for EXPLAIN"),
        violation(
            "an EXPLAIN without a statement to justify",
            "explain-request",
            r -> r.remove("explain"),
            "explain: is required for EXPLAIN"),
        violation(
            "a SUBSUMPTION without a superclass",
            "check-subsumption-request",
            r -> r.getObj("check").remove("superClassExpression"),
            "superClassExpression: is required for check.kind SUBSUMPTION"),
        violation(
            "a SATISFIABILITY without a class",
            "check-subsumption-request",
            r -> {
              final JsonObject check = r.getObj("check");
              check.put("kind", "SATISFIABILITY");
              check.remove("classExpression");
              check.remove("superClassExpression");
            },
            "classExpression: is required for check.kind SATISFIABILITY"),
        violation(
            "an ENTAILMENT without a statement",
            "check-subsumption-request",
            r -> {
              final JsonObject check = r.getObj("check");
              check.put("kind", "ENTAILMENT");
              check.remove("classExpression");
              check.remove("superClassExpression");
            },
            "statement: is required for check.kind ENTAILMENT"),
        violation(
            "a nested expression of an unknown kind",
            "check-subsumption-request",
            r ->
                r.getObj("check")
                    .getObj("superClassExpression")
                    .getObj("filler")
                    .put("kind", "COMPLEMENT"),
            "check.superClassExpression.filler.kind: must be one of"),
        violation(
            "an enumeration that repeats an individual",
            "check-subsumption-request",
            r -> {
              final JsonArray iris =
                  r.getObj("check").getObj("classExpression").get("individualIris").getAsArray();
              iris.add(iris.get(0));
            },
            "check.classExpression.individualIris: items must be unique"),
        violation(
            "more justifications than an EXPLAIN may ask for",
            "explain-request",
            r -> r.getObj("explain").put("maxJustifications", 11),
            "explain.maxJustifications: must be at most 10"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("violations")
  void theContractRejects(
      final String violation,
      final String example,
      final Consumer<JsonObject> change,
      final String expected) {
    final JsonObject document = Contract.example(example);
    change.accept(document);
    final JobRequest.Invalid invalid =
        assertThrows(JobRequest.Invalid.class, () -> JobRequest.validate(document, "ds"));
    assertTrue(
        invalid.violations().stream().anyMatch(message -> message.startsWith(expected)),
        () -> "Expected '" + expected + "', got " + invalid.violations());
  }

  @Test
  void aConsistencyCheckTakesNoExpression() throws Exception {
    final JsonObject document = Contract.example("check-subsumption-request");
    final JsonObject check = document.getObj("check");
    check.put("kind", "CONSISTENCY");
    check.remove("classExpression");
    check.remove("superClassExpression");
    assertEquals(JobRequest.Operation.CHECK, JobRequest.validate(document, "ds").operation());
  }

  @Test
  void bodiesAreBoundedInSizeAndDepthBeforeTheyAreParsed() {
    final byte[] deep =
        ("{\"a\":" + "[".repeat(JobRequest.MAX_DEPTH) + "]".repeat(JobRequest.MAX_DEPTH) + "}")
            .getBytes(StandardCharsets.UTF_8);
    assertTrue(
        assertThrows(JobRequest.Invalid.class, () -> JobRequest.read(deep, "ds"))
            .getMessage()
            .contains("deeper than"));
    final byte[] brackets = "{\"a\":\"[[[[[[\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(1, JobRequest.depth(brackets));
    assertThrows(
        JobRequest.Invalid.class, () -> JobRequest.read(new byte[JobRequest.MAX_BYTES + 1], "ds"));
    assertThrows(
        JobRequest.Invalid.class,
        () -> JobRequest.read("[1,2]".getBytes(StandardCharsets.UTF_8), "ds"));
  }

  @Test
  void theDigestIdentifiesARetryButNotADifferentRequest() throws Exception {
    final JsonObject document = Contract.example("refresh-request");
    final String digest = JobRequest.validate(document, "ds").digest();

    final JsonObject reordered = new JsonObject();
    document.keys().stream()
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(key -> reordered.put(key, document.get(key)));
    assertEquals(digest, JobRequest.validate(reordered, "ds").digest());

    assertNotEquals(digest, JobRequest.validate(document, "other").digest());
    document.getObj("sourceRevision").put("liveWriteWatermark", 18443);
    assertNotEquals(digest, JobRequest.validate(document, "ds").digest());
  }

  private static Arguments violation(
      final String violation,
      final String example,
      final Consumer<JsonObject> change,
      final String expected) {
    return Arguments.of(violation, example, change, expected);
  }
}
