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

/** The worker's step types. Each step opens only the private working dataset of its job. */
public enum StepKind {
  /** Bulk-load exported N-Quads or TriG into a fresh private TDB2 dataset. */
  LOAD,
  /** Classify one ontology import closure and write its finite entailments. */
  CLASSIFY,
  /** Compute one bounded justification for an entailment or an inconsistency. */
  EXPLAIN,
  /** Compact the working dataset before it is published. */
  COMPACT
}
