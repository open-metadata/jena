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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The image's layout under a test directory, for running the packaged worker with the launcher's
 * real command: FUSEKI_HOME holds fuseki-server.jar and reasoner/, FUSEKI_BASE holds reasoning/,
 * and a cgroup root reports a limit large enough for any test heap. The priority commands are used
 * where the platform has them, as in the image.
 */
public final class ImageLayout {
  private final Path home;
  private final Path base;
  private final Path cgroupRoot;

  private ImageLayout(final Path home, final Path base, final Path cgroupRoot) {
    this.home = home;
    this.base = base;
    this.cgroupRoot = cgroupRoot;
  }

  /** Lays out the image under {@code root}, linking the built fuseki-server.jar and worker jars. */
  public static ImageLayout create(
      final Path root, final Path fusekiServerJar, final Path reasonerDirectory)
      throws IOException {
    final Path home = Files.createDirectories(root.resolve("jena-fuseki"));
    Files.createSymbolicLink(home.resolve("fuseki-server.jar"), fusekiServerJar.toAbsolutePath());
    Files.createSymbolicLink(home.resolve("reasoner"), reasonerDirectory.toAbsolutePath());
    final Path base = Files.createDirectories(root.resolve("fuseki/reasoning")).getParent();
    final Path cgroup = Files.createDirectories(root.resolve("cgroup/sys/fs/cgroup"));
    Files.writeString(cgroup.resolve("memory.max"), Long.toString(1024 * Sizes.GIB));
    Files.writeString(cgroup.resolve("memory.stat"), "anon 0\n");
    return new ImageLayout(home, base, root.resolve("cgroup"));
  }

  /** A job directory under the reasoning root. */
  public Path job(final String name) throws IOException {
    return Files.createDirectories(base.resolve("reasoning/jobs").resolve(name));
  }

  /** The launcher the extension would build, with this worker heap and two CPUs. */
  public WorkerLauncher launcher(final String heap) {
    return launcher(settings(heap));
  }

  /** The reasoning settings of this image with this worker heap and two CPUs. */
  public ReasoningSettings settings(final String heap) {
    return ReasoningSettings.fromEnvironment(
        Map.of(
            ReasoningSettings.ENABLED,
            "true",
            ReasoningSettings.HEAP,
            heap,
            ReasoningSettings.PAGE_CACHE_FLOOR,
            "0",
            ReasoningSettings.CPUS,
            "2",
            "FUSEKI_HEAP",
            "1g",
            "FUSEKI_BASE",
            base.toString(),
            "FUSEKI_HOME",
            home.toString()));
  }

  /** The launcher the extension would build with these settings. */
  public WorkerLauncher launcher(final ReasoningSettings settings) {
    return new WorkerLauncher(
        settings,
        new ContainerMemory(cgroupRoot),
        -1,
        true,
        priorityCommandsAvailable() ? WorkerLauncher.PRIORITY : List.of(),
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        System.getenv(),
        WorkerLauncher.DEFAULT_TERMINATE_AFTER_MILLIS,
        WorkerLauncher.DEFAULT_KILL_AFTER_MILLIS);
  }

  /** choom, nice and ionice are Linux commands; elsewhere the worker runs without them. */
  private static boolean priorityCommandsAvailable() {
    final String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    for (final String command : List.of("choom", "nice", "ionice")) {
      boolean found = false;
      for (final String directory : path.split(":")) {
        found |= Files.isExecutable(Path.of(directory, command));
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }
}
