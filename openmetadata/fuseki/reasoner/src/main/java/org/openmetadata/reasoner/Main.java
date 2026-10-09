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

import java.nio.file.Path;

/**
 * Entry point of the reasoner worker: {@code Main <step-file>}. It runs exactly one step and exits
 * with a status from {@link ExitCodes}. It has no network listener and reads no credentials; it
 * opens only the private working dataset named in its step file.
 */
public final class Main {
  static {
    // Before any logger exists: the worker logs to stderr, which the launcher captures per step.
    if (System.getProperty("log4j2.configurationFile") == null) {
      System.setProperty(
          "log4j2.configurationFile", "classpath:openmetadata-reasoner-log4j2.properties");
    }
  }

  private Main() {}

  public static void main(final String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: org.openmetadata.reasoner.Main <step-file>");
      System.exit(ExitCodes.INVALID_STEP);
    }
    System.exit(new StepRunner(true).run(Path.of(args[0])));
  }
}
