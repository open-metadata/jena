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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.function.Consumer;
import org.apache.jena.atlas.json.JsonObject;
import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.elk.owlapi.ElkReasoner;
import org.semanticweb.elk.owlapi.ElkReasonerConfiguration;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The two engines, with the configuration the worker always uses. Code outside this class only sees
 * OWL API {@link OWLReasoner}s. HermiT is never created through its no-argument factory methods,
 * which silently ignore unsupported datatypes for Protégé.
 */
public enum Engine {
  HERMIT("HermiT", "hermit.version"),
  ELK("ELK", "elk.version");

  /**
   * HermiT's disjunction learning is a search heuristic, not a semantic switch: HermiT is sound and
   * complete either way, so results never depend on it, only running time. See the README for the
   * measurements behind this value.
   */
  static final boolean HERMIT_DISJUNCTION_LEARNING = false;

  private static final Properties VERSIONS = loadVersions();
  private final String displayName;
  private final String versionKey;

  Engine(final String displayName, final String versionKey) {
    this.displayName = displayName;
    this.versionKey = versionKey;
  }

  public String displayName() {
    return displayName;
  }

  public String version() {
    return VERSIONS.getProperty(versionKey, "unknown");
  }

  public static String owlapiVersion() {
    return VERSIONS.getProperty("owlapi.version", "unknown");
  }

  public static String workerVersion() {
    return VERSIONS.getProperty("worker.version", "unknown");
  }

  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("name", displayName);
    json.put("version", version());
    json.put("owlapi", owlapiVersion());
    if (this == HERMIT) {
      json.put("disjunctionLearning", HERMIT_DISJUNCTION_LEARNING);
    }
    return json;
  }

  /** HermiT's configuration, explicit in every field that changes what or how it answers. */
  static Configuration hermitConfiguration(final FreshEntityPolicy freshEntities) {
    final Configuration configuration = new Configuration();
    // An inconsistent closure is detected with isConsistent() first, and nothing else is read
    // from it; consequences of an inconsistent ontology are never published.
    configuration.throwInconsistentOntologyException = false;
    // A datatype outside the OWL 2 datatype map is an error, never silently dropped.
    configuration.ignoreUnsupportedDatatypes = false;
    configuration.freshEntityPolicy = freshEntities;
    configuration.individualNodeSetPolicy = IndividualNodeSetPolicy.BY_NAME;
    configuration.useDisjunctionLearning = HERMIT_DISJUNCTION_LEARNING;
    // Deadlines are enforced by interrupting the reasoner, not by HermiT's per-task timeout.
    configuration.individualTaskTimeout = -1;
    configuration.bufferChanges = false;
    configuration.reasonerProgressMonitor = null;
    return configuration;
  }

  /**
   * A factory for the explanation generator. It always applies {@link #hermitConfiguration} and
   * reports every reasoner it creates, so a deadline can interrupt them. It allows fresh entities
   * because the generator asks about the target expression while its candidate ontology does not
   * yet contain every entity of it.
   */
  static OWLReasonerFactory explanationFactory(final Consumer<OWLReasoner> created) {
    return new OWLReasonerFactory() {
      @Override
      public String getReasonerName() {
        return HERMIT.displayName;
      }

      @Override
      public OWLReasoner createNonBufferingReasoner(final OWLOntology ontology) {
        return create(ontology);
      }

      @Override
      public OWLReasoner createReasoner(final OWLOntology ontology) {
        return create(ontology);
      }

      @Override
      public OWLReasoner createNonBufferingReasoner(
          final OWLOntology ontology, final OWLReasonerConfiguration ignored) {
        return create(ontology);
      }

      @Override
      public OWLReasoner createReasoner(
          final OWLOntology ontology, final OWLReasonerConfiguration ignored) {
        return create(ontology);
      }

      private OWLReasoner create(final OWLOntology ontology) {
        final OWLReasoner reasoner =
            new Reasoner(hermitConfiguration(FreshEntityPolicy.ALLOW), ontology);
        created.accept(reasoner);
        return reasoner;
      }
    };
  }

  /**
   * The reasoner for classification and checks. Queries about entities outside the closure are
   * rejected before they reach it; the engine refusing them as well keeps a typo from ever reading
   * as NOT_ENTAILED.
   */
  static OWLReasoner hermit(final OWLOntology ontology) {
    return new Reasoner(hermitConfiguration(FreshEntityPolicy.DISALLOW), ontology);
  }

  /** ELK with its defaults; its worker threads follow -XX:ActiveProcessorCount. */
  static ElkReasoner elk(final OWLOntology ontology) {
    return new ElkReasonerFactory().createReasoner(ontology, new ElkReasonerConfiguration());
  }

  private static Properties loadVersions() {
    final Properties properties = new Properties();
    try (InputStream in = Engine.class.getResourceAsStream("/openmetadata-reasoner.properties")) {
      if (in != null) {
        properties.load(in);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }
}
