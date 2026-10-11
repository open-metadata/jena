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
package org.openmetadata.fuseki.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openmetadata.fuseki.reasoning.jobs.JobSettings;

/**
 * The entrypoint, ReasoningSettings and JobSettings validate the reasoning settings with the same
 * rules: every value in test/reasoning-settings.cases must be accepted or refused by the entrypoint
 * and by the extension as the file says. The entrypoint's half needs bash 4 or later, as in the
 * image.
 */
class ReasoningSettingsParityTest {
  private static final Path IMAGE = Path.of("..").toAbsolutePath().normalize();
  private static final Path CASES = IMAGE.resolve("test/reasoning-settings.cases");
  private static final Path ENTRYPOINT = IMAGE.resolve("docker-entrypoint.sh");

  record Case(String variable, String value, boolean valid) {
    String name() {
      return variable + "='" + value + "' " + (valid ? "valid" : "invalid");
    }
  }

  @TestFactory
  Stream<DynamicTest> theExtensionJudgesEveryValueAsTheTableDoes() throws IOException {
    return cases()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.name(),
                    () -> {
                      boolean accepted;
                      try {
                        final Map<String, String> environment =
                            Map.of(entry.variable(), entry.value());
                        ReasoningSettings.fromEnvironment(environment);
                        JobSettings.fromEnvironment(environment);
                        accepted = true;
                      } catch (final IllegalArgumentException e) {
                        assertTrue(e.getMessage().startsWith(entry.variable()), e.getMessage());
                        accepted = false;
                      }
                      assertEquals(entry.valid(), accepted);
                    }));
  }

  @TestFactory
  Stream<DynamicTest> theEntrypointJudgesEveryValueAsTheTableDoes() throws Exception {
    assumeTrue(bashMajorVersion() >= 4, "bash 4 or later is needed to source the entrypoint");
    return cases()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.name(),
                    () -> {
                      final ProcessBuilder builder =
                          new ProcessBuilder(
                                  "bash",
                                  "-c",
                                  "source \"$1\" && validate_reasoning_settings",
                                  "bash",
                                  ENTRYPOINT.toString())
                              .redirectErrorStream(true);
                      builder.environment().clear();
                      builder.environment().put("PATH", System.getenv("PATH"));
                      builder.environment().put(entry.variable(), entry.value());
                      final Process process = builder.start();
                      final String output =
                          new String(
                              process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                      assertEquals(entry.valid(), process.exitValue() == 0, output);
                      if (!entry.valid()) {
                        assertTrue(output.contains("ERROR"), output);
                        assertTrue(output.contains(entry.variable()), output);
                      }
                    }));
  }

  private static Stream<Case> cases() throws IOException {
    return Files.readAllLines(CASES, StandardCharsets.UTF_8).stream()
        .filter(line -> !line.isBlank() && !line.startsWith("#"))
        .map(
            line -> {
              final String[] fields = line.split("\t", -1);
              assertEquals(3, fields.length, line);
              assertTrue(fields[2].equals("valid") || fields[2].equals("invalid"), line);
              return new Case(fields[0], fields[1], fields[2].equals("valid"));
            });
  }

  private static int bashMajorVersion() {
    try {
      final Process process =
          new ProcessBuilder("bash", "-c", "echo ${BASH_VERSINFO[0]}")
              .redirectErrorStream(true)
              .start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
          ? Integer.parseInt(output)
          : 0;
    } catch (final IOException | InterruptedException | NumberFormatException e) {
      return 0;
    }
  }
}
