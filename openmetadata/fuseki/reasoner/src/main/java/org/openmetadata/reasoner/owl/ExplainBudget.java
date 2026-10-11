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
package org.openmetadata.reasoner.owl;

/**
 * Counts the consistency checks of one EXPLAIN step. Each runs on a fresh reasoner over a copy of
 * part of the closure, so the count bounds the step's work independently of how long one check
 * takes, which the deadline bounds.
 */
final class ExplainBudget {
  private final long limit;
  private long spent;

  ExplainBudget(final long limit) {
    this.limit = limit;
  }

  void spend() {
    if (++spent > limit) {
      throw new Exceeded(limit);
    }
  }

  long spent() {
    return spent;
  }

  /** Unchecked, to leave the explanation generator; the step turns it into a rejection. */
  static final class Exceeded extends RuntimeException {
    Exceeded(final long limit) {
      super("The justification needs more than " + limit + " consistency checks");
    }
  }
}
