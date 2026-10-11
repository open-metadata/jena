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

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiAutoModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports at startup whether the container's memory can hold a reasoner worker. It does nothing
 * when OPENMETADATA_REASONING_ENABLED is not true, and never affects serving: a failure to evaluate
 * the budget is logged, not thrown. Job operations arrive in a later phase.
 */
public final class ReasoningModule implements FusekiAutoModule {
  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  @Override
  public String name() {
    return "OpenMetadata reasoning";
  }

  @Override
  public void serverAfterStarting(final FusekiServer server) {
    try {
      final ReasoningSettings settings = ReasoningSettings.fromEnvironment(System.getenv());
      if (!settings.enabled()) {
        return;
      }
      final Readiness readiness =
          Readiness.evaluate(
              settings, ContainerMemory.system(), Runtime.getRuntime().maxMemory(), false, false);
      if (readiness.admits()) {
        LOG.info("Reasoning {}", readiness.summary());
      } else {
        LOG.warn("Reasoning {}", readiness.summary());
      }
    } catch (final RuntimeException e) {
      LOG.warn("Reasoning readiness could not be evaluated: {}", e.getMessage());
    }
  }
}
