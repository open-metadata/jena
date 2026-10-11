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

/**
 * The worker's exit statuses, the same values as org.openmetadata.reasoner.ExitCodes. The extension
 * is compiled against fuseki-server.jar alone and cannot see the worker's classes, so the values
 * are repeated here; the reasoner's tests compile this class next to ExitCodes and require them to
 * be equal.
 */
final class WorkerExitCodes {
  static final int COMPLETED = 0;
  static final int INTERNAL_ERROR = 1;
  static final int INVALID_STEP = 2;

  /** HotSpot's status under -XX:+ExitOnOutOfMemoryError. */
  static final int OUT_OF_MEMORY = 3;

  static final int INPUT_REJECTED = 4;
  static final int TIME_BUDGET = 5;

  /** 128 plus the signal number, as the JDK reports a process ended by a signal. */
  static final int SIGNALLED = 128;

  static final int SIGKILL = SIGNALLED + 9;

  private WorkerExitCodes() {}
}
