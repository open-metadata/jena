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

/**
 * Process exit statuses of the worker. The launcher maps them to step outcomes without parsing the
 * result manifest; the manifest adds detail. A worker terminated by a signal exits with 128 plus
 * the signal number (143 for SIGTERM, 137 for SIGKILL), which the launcher interprets itself.
 */
public final class ExitCodes {
  /** The step ran to completion; the manifest holds its logical outcome, e.g. INCONSISTENT. */
  public static final int COMPLETED = 0;

  public static final int INTERNAL_ERROR = 1;

  /** The step file is unreadable or violates the step contract. */
  public static final int INVALID_STEP = 2;

  /** Reserved: HotSpot exits with 3 under -XX:+ExitOnOutOfMemoryError. The worker never uses it. */
  public static final int OUT_OF_MEMORY = 3;

  /** The input was rejected: a missing import, an OWL 2 DL violation or an admission limit. */
  public static final int INPUT_REJECTED = 4;

  /** The step's own deadline expired before it finished; nothing it produced is complete. */
  public static final int TIME_BUDGET = 5;

  private ExitCodes() {}
}
