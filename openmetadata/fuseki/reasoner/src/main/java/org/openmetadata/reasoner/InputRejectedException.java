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

import java.util.List;

/**
 * The input cannot be reasoned over as given: a missing import, a construct outside OWL 2 DL, a
 * triple the OWL mapping cannot consume, or an admission limit. Never silently repaired.
 */
public final class InputRejectedException extends Exception {
  private final String outcome;
  private final transient List<Problem> problems;
  private final long problemCount;

  public InputRejectedException(final String outcome, final List<Problem> problems) {
    this(outcome, problems, problems.size());
  }

  public InputRejectedException(
      final String outcome, final List<Problem> problems, final long problemCount) {
    super(outcome + ": " + (problems.isEmpty() ? "" : problems.get(0).message()));
    this.outcome = outcome;
    this.problems = List.copyOf(problems);
    this.problemCount = problemCount;
  }

  public static InputRejectedException of(
      final String outcome, final String code, final String message) {
    return new InputRejectedException(outcome, List.of(new Problem(code, message)));
  }

  public String outcome() {
    return outcome;
  }

  public List<Problem> problems() {
    return problems;
  }

  public long problemCount() {
    return problemCount;
  }
}
