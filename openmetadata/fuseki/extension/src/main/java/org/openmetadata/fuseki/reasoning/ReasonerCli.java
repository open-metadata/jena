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
import java.nio.file.Path;
import org.apache.jena.atlas.json.JSON;

/**
 * Operator and test entry point to the launcher, run inside the container by
 * /jena-fuseki/reasoner/bin/reasoner. It uses the same settings, readiness check, lock and launch
 * command as the Fuseki module.
 *
 * <pre>
 *   reasoner readiness          memory budget as JSON; exit 0 when READY
 *   reasoner run STEP_FILE...   run steps in order, one JSON outcome each; exit 0 if all succeed
 *   reasoner command STEP_FILE  print the worker's launch command
 * </pre>
 */
public final class ReasonerCli {
  private ReasonerCli() {}

  public static void main(final String[] args) throws IOException {
    if (args.length == 0) {
      usage();
    }
    final ReasoningSettings settings;
    try {
      settings = ReasoningSettings.fromEnvironment(System.getenv());
    } catch (final IllegalArgumentException e) {
      System.err.println("ERROR " + e.getMessage());
      System.exit(2);
      return;
    }
    final WorkerLauncher launcher =
        new WorkerLauncher(settings, ContainerMemory.system(), -1, true);
    switch (args[0]) {
      case "readiness" -> {
        final Readiness readiness = launcher.readiness();
        System.out.println(JSON.toString(readiness.toJson()));
        System.exit(readiness.admits() ? 0 : 1);
      }
      case "run" -> {
        if (args.length < 2) {
          usage();
        }
        boolean allSucceeded = true;
        for (int i = 1; i < args.length; i++) {
          final StepOutcome outcome = launcher.run(Path.of(args[i]));
          System.out.println(JSON.toString(outcome.toJson()));
          allSucceeded &= outcome.status() == StepOutcome.Status.SUCCEEDED;
        }
        System.exit(allSucceeded ? 0 : 1);
      }
      case "command" -> {
        if (args.length != 2) {
          usage();
        }
        System.out.println(String.join(" ", launcher.command(Path.of(args[1]).toAbsolutePath())));
      }
      default -> usage();
    }
  }

  private static void usage() {
    System.err.println("Usage: reasoner readiness | run STEP_FILE... | command STEP_FILE");
    System.exit(2);
  }
}
