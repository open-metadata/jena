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
 * Which engine a step should use. {@code AUTO} is the production setting: closures in the OWL 2 EL
 * profile that use only what ELK supports go to ELK, everything else to HermiT. Forcing an engine
 * exists so tests can run one closure through both and compare.
 */
public enum EngineChoice {
  AUTO,
  HERMIT,
  ELK
}
