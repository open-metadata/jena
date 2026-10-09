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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;

/** Writes step files and inputs into a job directory and runs them in this JVM. */
final class Jobs {
  private Jobs() {}

  static Path write(final Path job, final String name, final String json) {
    try {
      final Path path = job.resolve(name + ".json");
      Files.writeString(path, json, StandardCharsets.UTF_8);
      return path;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path file(final Path job, final String name, final String content) {
    try {
      final Path path = job.resolve(name);
      Files.writeString(path, content, StandardCharsets.UTF_8);
      return path;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static int run(final Path stepFile) {
    return new StepRunner(false).run(stepFile);
  }

  static JsonObject manifest(final Path stepFile) {
    return JSON.read(StepFile.resultPath(stepFile).toString());
  }

  static String report(final Path stepFile) throws IOException {
    return Files.readString(StepFile.reportPath(stepFile), StandardCharsets.UTF_8);
  }

  static String outcome(final Path stepFile) {
    return manifest(stepFile).getString("outcome");
  }

  static String firstProblem(final Path stepFile) {
    return manifest(stepFile)
        .getArray("problems")
        .findFirst()
        .map(problem -> problem.getAsObject().getString("code"))
        .orElse(null);
  }

  static String load(final String dataset, final String... inputs) {
    final StringBuilder array = new StringBuilder();
    for (final String input : inputs) {
      array.append(array.isEmpty() ? "" : ", ").append('"').append(input).append('"');
    }
    return """
           {"protocol": 1, "stepId": "load", "kind": "LOAD", "dataset": "%s",
            "timeoutMillis": 60000, "inputs": [%s]}
           """
        .formatted(dataset, array);
  }

  static String classify(
      final String id, final String root, final String graph, final String extra) {
    return """
           {"protocol": 1, "stepId": "%s", "kind": "CLASSIFY", "dataset": "working",
            "timeoutMillis": 60000, "report": true,
            "closure": {"root": "%s", "graphs": ["%s"]}%s}
           """
        .formatted(id, root, graph, extra);
  }

  /**
   * A small closure HermiT cannot classify in minutes: n classes, each defined as an intersection
   * with an existential over a union, plus a universal restriction. Deterministic (Park-Miller),
   * the same generator as test/hard-ontology.awk in the image smoke test.
   */
  static String hardOntology(final int n) {
    final StringBuilder trig = new StringBuilder();
    trig.append("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n")
        .append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n")
        .append("PREFIX : <http://example.org/hard#>\n")
        .append("GRAPH <urn:hard> {\n")
        .append("<http://example.org/hard> a owl:Ontology .\n")
        .append(":r a owl:ObjectProperty . :s a owl:ObjectProperty .\n");
    for (int i = 0; i < n; i++) {
      trig.append(":C").append(i).append(" a owl:Class .\n");
    }
    final long[] seed = {7};
    final java.util.function.IntSupplier next =
        () -> {
          seed[0] = (seed[0] * 16807) % 2147483647;
          return (int) (seed[0] % n);
        };
    for (int i = 0; i < n; i++) {
      final int a = next.getAsInt();
      final int b = next.getAsInt();
      int c = next.getAsInt();
      final int d = next.getAsInt();
      if (b == c) {
        c = (c + 1) % n;
      }
      trig.append(":C")
          .append(i)
          .append(" owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :C")
          .append(a)
          .append(" [ a owl:Restriction ; owl:onProperty :r ; owl:someValuesFrom [ a owl:Class ;")
          .append(" owl:unionOf ( :C")
          .append(b)
          .append(" :C")
          .append(c)
          .append(" ) ] ] ) ] .\n");
      trig.append(":C")
          .append(i)
          .append(" rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :s ; owl:allValuesFrom :C")
          .append(d)
          .append(" ] .\n");
    }
    return trig.append("}\n").toString();
  }
}
