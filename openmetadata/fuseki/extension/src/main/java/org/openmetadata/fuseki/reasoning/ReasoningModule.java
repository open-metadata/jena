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
import java.util.Set;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiAutoModule;
import org.apache.jena.rdf.model.Model;
import org.openmetadata.fuseki.reasoning.jobs.JobService;
import org.openmetadata.fuseki.reasoning.jobs.JobSettings;
import org.openmetadata.fuseki.reasoning.jobs.ReasoningServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the reasoning operations at /reasoning/* and runs their jobs. With
 * OPENMETADATA_REASONING_ENABLED not true the operations still answer, capabilities with DISABLED
 * and submissions with 503, and nothing is written to disk. At startup it reports whether the
 * container's memory can hold a worker. It never affects serving: a failure to set up reasoning is
 * logged, not thrown.
 */
public final class ReasoningModule implements FusekiAutoModule {
  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private JobService jobs;

  @Override
  public String name() {
    return "OpenMetadata reasoning";
  }

  @Override
  public void prepare(
      final FusekiServer.Builder builder, final Set<String> datasetNames, final Model config) {
    final ReasoningSettings settings;
    final JobService service;
    try {
      settings = ReasoningSettings.fromEnvironment(System.getenv());
      service = JobService.create(settings, JobSettings.fromEnvironment(System.getenv()));
      service.open();
    } catch (final RuntimeException | IOException e) {
      LOG.error("Reasoning operations are not served: {}", e.getMessage());
      return;
    }
    if (datasetNames.contains("/reasoning")) {
      LOG.warn("A dataset named 'reasoning' hides the reasoning operations at /reasoning");
    }
    jobs = service;
    builder.addServlet(ReasoningServlet.PATH_SPEC, new ReasoningServlet(service));
  }

  @Override
  public void serverAfterStarting(final FusekiServer server) {
    if (jobs != null) {
      jobs.start();
    }
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

  @Override
  public void serverStopped(final FusekiServer server) {
    if (jobs != null) {
      jobs.close();
      jobs = null;
    }
  }
}
