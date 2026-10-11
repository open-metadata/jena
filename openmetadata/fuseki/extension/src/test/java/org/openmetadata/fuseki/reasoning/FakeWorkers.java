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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A container for tests outside this package: a reasoning root, cgroup files whose budget fits, and
 * a launcher whose "java" is a shell script that runs the behaviour.sh next to the step file, so
 * jobs run real worker processes with chosen behaviour.
 */
public final class FakeWorkers {
  private final Path root;
  private final Path fakeJava;

  public FakeWorkers(final Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root.resolve("sys/fs/cgroup"));
    Files.writeString(root.resolve("sys/fs/cgroup/memory.max"), Long.toString(64 * Sizes.GIB));
    Files.writeString(root.resolve("sys/fs/cgroup/memory.stat"), "anon 0\n");
    fakeJava = root.resolve("fake-java");
    Files.writeString(
        fakeJava,
        """
        #!/bin/sh
        for last; do :; done
        cd "$(dirname "$last")"
        . ./behaviour.sh
        """);
    Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  public ContainerMemory memory() {
    return new ContainerMemory(root);
  }

  public ReasoningSettings settings(final Map<String, String> extra) {
    final Map<String, String> environment = new HashMap<>();
    environment.put("OPENMETADATA_REASONING_ENABLED", "true");
    environment.put("FUSEKI_HEAP", "1g");
    environment.put("OPENMETADATA_REASONER_PAGE_CACHE_FLOOR", "0");
    environment.put("FUSEKI_BASE", root.resolve("fuseki").toString());
    environment.put("FUSEKI_HOME", root.resolve("jena-fuseki").toString());
    environment.putAll(extra);
    return ReasoningSettings.fromEnvironment(environment);
  }

  /** A launcher with a 1.5 s grace between SIGTERM and SIGKILL. */
  public WorkerLauncher launcher(final ReasoningSettings settings) {
    return new WorkerLauncher(
        settings, memory(), -1, false, List.of(), fakeJava.toString(), System.getenv(), 300, 1_500);
  }

  public Readiness readiness(final ReasoningSettings settings) {
    return Readiness.evaluate(settings, memory(), -1, false, false);
  }
}
