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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;

/**
 * The worker's rule code, run in the Fuseki JVM for the two things a job thread cannot hand to a
 * worker: admitting a bundle with the RULES step's own compiler before any work starts, and
 * explaining a governance fact of a published snapshot, which only this JVM may open (TDB2 locks a
 * database to one process). It is the worker jar's {@code ServerBridge}, loaded in a class loader
 * that holds that one jar and delegates to the extension's loader for Jena, so the server never
 * loads the OWL API or any other worker dependency: a path that needed one would fail, not load it.
 */
public final class RuleCode implements AutoCloseable {
  static final String BRIDGE = "org.openmetadata.reasoner.rules.ServerBridge";

  /** A rule problem, as the compiler reports it. */
  public record RuleProblem(String rule, String code, String message) {}

  private final URLClassLoader loader;
  private final Method compile;
  private final Method explain;

  private RuleCode(final URLClassLoader loader, final Class<?> bridge)
      throws NoSuchMethodException {
    this.loader = loader;
    this.compile = bridge.getMethod("compile", String.class, long.class);
    this.explain =
        bridge.getMethod(
            "explain",
            DatasetGraph.class,
            String.class,
            Triple.class,
            int.class,
            int.class,
            int.class,
            int.class);
  }

  /**
   * The rule code of the worker installed in {@code reasoner}, the image's worker directory; empty
   * when there is no worker jar there, as in the extension's own tests.
   */
  public static Optional<RuleCode> load(final Path reasoner) {
    return load(reasoner, RuleCode.class.getClassLoader());
  }

  /** As {@link #load(Path)}, with {@code parent} supplying Jena. */
  static Optional<RuleCode> load(final Path reasoner, final ClassLoader parent) {
    final Path jar = workerJar(reasoner);
    if (jar == null) {
      return Optional.empty();
    }
    try {
      final URLClassLoader loader =
          new URLClassLoader("openmetadata-rule-code", new URL[] {jar.toUri().toURL()}, parent);
      return Optional.of(new RuleCode(loader, Class.forName(BRIDGE, true, loader)));
    } catch (final IOException | ReflectiveOperationException | LinkageError e) {
      return Optional.empty();
    }
  }

  /** Compiles every rule, enabled or not; the problems are empty when the bundle is admitted. */
  public List<RuleProblem> compile(final JsonArray rules, final long maxRulePatterns) {
    final String problems = (String) invoke(compile, JSON.toString(rules), maxRulePatterns);
    final List<RuleProblem> list = new ArrayList<>();
    for (final JsonValue value : JSON.parseAny(problems).getAsArray()) {
      final JsonObject problem = value.getAsObject();
      list.add(
          new RuleProblem(
              problem.getString("rule"), problem.getString("code"), problem.getString("message")));
    }
    return list;
  }

  /**
   * Explains a fact from a snapshot's provenance, inside the caller's read transaction; null when
   * the snapshot holds no converged run.
   */
  public JsonObject explain(
      final DatasetGraph snapshot,
      final String provenance,
      final Triple fact,
      final int maxDepth,
      final int maxFacts,
      final int maxPathLength,
      final int maxPathNodes) {
    final String json =
        (String)
            invoke(
                explain,
                snapshot,
                provenance,
                fact,
                maxDepth,
                maxFacts,
                maxPathLength,
                maxPathNodes);
    return json == null ? null : JSON.parse(json);
  }

  @Override
  public void close() throws IOException {
    loader.close();
  }

  private static Object invoke(final Method method, final Object... arguments) {
    try {
      return method.invoke(null, arguments);
    } catch (final InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The worker's own jar: the one whose manifest names the engine builds. */
  private static Path workerJar(final Path reasoner) {
    if (!Files.isDirectory(reasoner)) {
      return null;
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(reasoner, "*.jar")) {
      for (final Path jar : jars) {
        try (JarFile file = new JarFile(jar.toFile())) {
          if (file.getManifest() != null
              && file.getManifest().getMainAttributes().getValue("OpenMetadata-HermiT-Version")
                  != null) {
            return jar;
          }
        }
      }
    } catch (final IOException e) {
      return null;
    }
    return null;
  }
}
