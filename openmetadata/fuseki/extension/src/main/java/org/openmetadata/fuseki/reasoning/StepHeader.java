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
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * What the launcher reads from a step file before it starts a worker: the step's id, which names
 * the files a failed step leaves behind, and its deadline. Both are read with the worker's own
 * rules (org.openmetadata.reasoner.StepFile: the size cap, the step id pattern and exact integers),
 * which the reasoner's tests check against StepFile, so the launcher never waits on a deadline the
 * worker would read differently.
 */
record StepHeader(String stepId, long timeoutMillis) {
  static final long MAX_STEP_FILE_BYTES = 1 << 20;
  static final Pattern STEP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /** Thrown with the reason a step file is refused; the message completes "INVALID_STEP: ". */
  static final class Invalid extends Exception {
    Invalid(final String message) {
      super(message);
    }
  }

  static StepHeader read(final Path stepFile) throws Invalid {
    final JsonObject json;
    try {
      if (Files.size(stepFile) > MAX_STEP_FILE_BYTES) {
        throw new Invalid("the step file is larger than " + MAX_STEP_FILE_BYTES + " bytes");
      }
      try (InputStream in = Files.newInputStream(stepFile)) {
        json = JSON.parse(in);
      }
    } catch (final IOException | JsonException e) {
      throw new Invalid("cannot read the step file: " + e.getMessage());
    }
    final JsonValue stepId = json.get("stepId");
    if (stepId == null
        || !stepId.isString()
        || !STEP_ID.matcher(stepId.getAsString().value()).matches()) {
      throw new Invalid("stepId must match " + STEP_ID.pattern());
    }
    final JsonValue timeout = json.get("timeoutMillis");
    final long timeoutMillis;
    try {
      if (timeout == null || !timeout.isNumber()) {
        throw new ArithmeticException();
      }
      timeoutMillis = new BigDecimal(timeout.getAsNumber().value().toString()).longValueExact();
    } catch (final NumberFormatException | ArithmeticException e) {
      throw new Invalid("timeoutMillis must be a positive integer");
    }
    if (timeoutMillis <= 0) {
      throw new Invalid("timeoutMillis must be a positive integer");
    }
    return new StepHeader(stepId.getAsString().value(), timeoutMillis);
  }
}
