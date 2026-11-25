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
package org.openmetadata.fuseki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Fails when a library in server-jar/replacements.sha256 stops being a strict upgrade over the
 * version Fuseki bundles. A replacement that is no longer newer swaps a release for an older or
 * identical one and still passes every other check, so the list would rot silently.
 *
 * <p>fuseki-server.jar carries Micrometer without any version metadata, so the bundled version is
 * read from the artifacts jena-fuseki-main resolves, the same ones the shaded jar is built from.
 */
class ReplacementsTripwireTest {
  private static final Path LIST = Path.of("..", "server-jar", "replacements.sha256");
  // Coordinates that replace-libraries.sh removes wholesale from the jar.
  private static final List<String> REPLACED_GROUPS =
      List.of("org/apache/shiro/", "io/micrometer/");
  private static final Pattern LISTED =
      Pattern.compile("^[0-9a-f]{64}\\s+(\\S+)/([^/]+)/([^/]+)/\\2-\\3\\.jar$");

  record Library(String group, String artifact, String version) {
    String coordinate() {
      return group.replace('/', '.') + ":" + artifact;
    }
  }

  @Test
  void everyReplacementIsNewerThanWhatFusekiBundles() throws IOException {
    final List<String> problems =
        problems(listed(Files.readAllLines(LIST)), bundled(System.getProperty("java.class.path")));
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  @Test
  void aReplacementThatIsNotNewerNamesTheArtifactAndSaysToDropIt() {
    final List<Library> list =
        listed(
            List.of(
                line("io/micrometer", "micrometer-core", "1.17.2"),
                line("org/apache/shiro", "shiro-core", "3.0.1"),
                line("org/apache/shiro", "shiro-web", "2.2.0"),
                line("org/apache/shiro", "shiro-lang", "3.0.1"),
                line("org/apache/shiro", "shiro-cache", "3.0.1")));
    final List<Library> bundled =
        List.of(
            new Library("io/micrometer", "micrometer-core", "1.17.2"),
            new Library("org/apache/shiro", "shiro-core", "2.2.1"),
            new Library("org/apache/shiro", "shiro-web", "2.2.1"),
            new Library("org/apache/shiro", "shiro-lang", "2.2.1"));

    final List<String> problems = problems(list, bundled);

    assertEquals(
        List.of(
            "io.micrometer:micrometer-core: replacement 1.17.2 is not newer than the 1.17.2 Fuseki"
                + " bundles. Drop its lines from server-jar/replacements.sha256.",
            "org.apache.shiro:shiro-web: replacement 2.2.0 is older than the 2.2.1 Fuseki bundles."
                + " Drop its lines from server-jar/replacements.sha256.",
            "org.apache.shiro:shiro-cache: Fuseki does not bundle it. Drop its lines from"
                + " server-jar/replacements.sha256."),
        problems);
  }

  @Test
  void aBundledArtifactOfAReplacedLibraryMustBeListed() {
    final List<String> problems =
        problems(
            listed(List.of(line("io/micrometer", "micrometer-core", "1.17.3"))),
            List.of(
                new Library("io/micrometer", "micrometer-core", "1.17.2"),
                new Library("io/micrometer", "micrometer-commons", "1.17.2")));

    assertEquals(
        List.of(
            "io.micrometer:micrometer-commons: Fuseki bundles 1.17.2, but replace-libraries.sh"
                + " deletes every io/micrometer class and this artifact is not in"
                + " server-jar/replacements.sha256. List its replacement, or drop the lines of"
                + " the other io/micrometer artifacts."),
        problems);
  }

  static List<String> problems(final List<Library> listed, final List<Library> bundled) {
    final Map<String, Library> bundledBy = new LinkedHashMap<>();
    bundled.forEach(library -> bundledBy.put(library.coordinate(), library));
    final List<String> problems = new ArrayList<>();
    for (final Library replacement : listed) {
      final Library current = bundledBy.get(replacement.coordinate());
      final String drop = "Drop its lines from server-jar/replacements.sha256.";
      if (current == null) {
        problems.add(replacement.coordinate() + ": Fuseki does not bundle it. " + drop);
        continue;
      }
      final int order = compare(replacement.version(), current.version());
      if (order < 0) {
        problems.add(
            replacement.coordinate()
                + ": replacement "
                + replacement.version()
                + " is older than the "
                + current.version()
                + " Fuseki bundles. "
                + drop);
      } else if (order == 0) {
        problems.add(
            replacement.coordinate()
                + ": replacement "
                + replacement.version()
                + " is not newer than the "
                + current.version()
                + " Fuseki bundles. "
                + drop);
      }
    }
    for (final Library library : bundled) {
      final boolean replacedGroup =
          REPLACED_GROUPS.stream()
              .anyMatch(
                  prefix ->
                      (library.group() + "/").startsWith(prefix)
                          && listed.stream().anyMatch(l -> (l.group() + "/").startsWith(prefix)));
      final boolean isListed =
          listed.stream().anyMatch(l -> l.coordinate().equals(library.coordinate()));
      if (replacedGroup && !isListed) {
        final String prefix =
            REPLACED_GROUPS.stream()
                .filter(p -> (library.group() + "/").startsWith(p))
                .findFirst()
                .orElseThrow();
        problems.add(
            library.coordinate()
                + ": Fuseki bundles "
                + library.version()
                + ", but replace-libraries.sh deletes every "
                + prefix.substring(0, prefix.length() - 1)
                + " class and this artifact is not in server-jar/replacements.sha256. List its"
                + " replacement, or drop the lines of the other "
                + prefix.substring(0, prefix.length() - 1)
                + " artifacts.");
      }
    }
    return problems;
  }

  static List<Library> listed(final List<String> lines) {
    final List<Library> libraries = new ArrayList<>();
    for (final String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      final Matcher matcher = LISTED.matcher(line.strip());
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Unreadable line in replacements.sha256: " + line);
      }
      libraries.add(new Library(matcher.group(1), matcher.group(2), matcher.group(3)));
    }
    return libraries;
  }

  // The local repository lays jars out as <group path>/<artifact>/<version>/<artifact>-<version>.jar,
  // so a list entry's group is whatever the prefix of that path is.
  static List<Library> bundled(final String classPath) {
    final List<Library> libraries = new ArrayList<>();
    for (final String entry : classPath.split(File.pathSeparator)) {
      final String normalized = entry.replace('\\', '/');
      for (final String prefix : REPLACED_GROUPS) {
        final int start = normalized.lastIndexOf("/" + prefix);
        if (start < 0) {
          continue;
        }
        final String[] rest = normalized.substring(start + 1 + prefix.length()).split("/");
        // <artifact>/<version>/<file>, or a sub-group such as crypto/<artifact>/<version>/<file>
        // Fuseki's Shiro Web is the -jakarta classifier jar; the replacement is Jakarta-only.
        final String file = rest[rest.length - 1];
        if (rest.length >= 3 && isJarOf(file, rest[rest.length - 3], rest[rest.length - 2])) {
          final String subGroup =
              rest.length > 3 ? "/" + String.join("/", Arrays.copyOf(rest, rest.length - 3)) : "";
          libraries.add(
              new Library(
                  prefix.substring(0, prefix.length() - 1) + subGroup,
                  rest[rest.length - 3],
                  rest[rest.length - 2]));
        }
      }
    }
    return libraries;
  }

  private static boolean isJarOf(final String file, final String artifact, final String version) {
    final String stem = artifact + "-" + version;
    return file.endsWith(".jar") && (file.equals(stem + ".jar") || file.startsWith(stem + "-"));
  }

  private static String line(final String group, final String artifact, final String version) {
    return "0".repeat(64)
        + "  "
        + group
        + "/"
        + artifact
        + "/"
        + version
        + "/"
        + artifact
        + "-"
        + version
        + ".jar";
  }

  static int compare(final String left, final String right) {
    final String[] a = left.split("[.\\-]");
    final String[] b = right.split("[.\\-]");
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      final long x = i < a.length ? number(a[i]) : 0;
      final long y = i < b.length ? number(b[i]) : 0;
      if (x != y) {
        return Long.compare(x, y);
      }
    }
    return 0;
  }

  private static long number(final String part) {
    return part.matches("\\d+") ? Long.parseLong(part) : -1;
  }
}
