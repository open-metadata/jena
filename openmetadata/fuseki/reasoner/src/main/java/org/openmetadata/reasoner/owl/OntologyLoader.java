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

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Problem;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyLoaderMetaData;
import org.semanticweb.owlapi.io.RDFParserMetaData;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.UnloadableImportException;

/**
 * Parses local documents into OWL API without ever touching the network: the closure document has
 * no imports, the loader refuses any that appear, and every IRI mapping points at a file that
 * cannot exist. Nothing is repaired: illegal punnings stay and fail the OWL 2 DL check, and any
 * triple the OWL mapping cannot consume rejects the closure instead of being dropped.
 */
final class OntologyLoader {
  private static final IRI NO_NETWORK =
      IRI.create(new File("/nonexistent/openmetadata-reasoner/imports-are-never-fetched").toURI());

  private static final String OWLAPI_ERROR_NAMESPACE = "http://org.semanticweb.owlapi/error#";

  private OntologyLoader() {}

  static OWLOntologyManager newManager() {
    final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    manager.getIRIMappers().clear();
    manager.getIRIMappers().add(iri -> NO_NETWORK);
    return manager;
  }

  static OWLOntologyLoaderConfiguration configuration() {
    return new OWLOntologyLoaderConfiguration()
        .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.THROW_EXCEPTION)
        .setFollowRedirects(false)
        .setAcceptingHTTPCompression(false)
        .setRetriesToAttempt(0)
        .setRepairIllegalPunnings(false)
        .setReportStackTraces(false)
        .setStrict(false);
  }

  /** Parses the exported closure; see {@link ClosureDocument}. */
  static OWLOntology loadClosure(final OWLOntologyManager manager, final Path document)
      throws InputRejectedException {
    final OWLOntology ontology =
        load(manager, new FileDocumentSource(document.toFile(), new TurtleDocumentFormat()));
    // OWL API stands in a placeholder entity for an anonymous class expression or data range
    // whose mandatory triples are missing, e.g. a restriction without owl:onProperty.
    final List<String> malformed =
        ontology
            .signature()
            .map(entity -> entity.getIRI().toString())
            .filter(iri -> iri.startsWith(OWLAPI_ERROR_NAMESPACE))
            .sorted()
            .toList();
    if (!malformed.isEmpty()) {
      throw new InputRejectedException(
          "NOT_OWL2_DL",
          List.of(
              new Problem(
                  "MALFORMED_CONSTRUCT",
                  malformed.size()
                      + " anonymous class expressions or data ranges lack mandatory triples"
                      + " (for example a restriction without owl:onProperty); they are never"
                      + " dropped or guessed")),
          malformed.size());
    }
    final List<String> unparsed =
        metadata(ontology)
            .filter(RDFParserMetaData.class::isInstance)
            .map(RDFParserMetaData.class::cast)
            .map(meta -> meta.getUnparsedTriples().map(Object::toString).sorted().toList())
            .orElse(List.of());
    if (!unparsed.isEmpty()) {
      throw new InputRejectedException(
          "NOT_OWL2_DL",
          unparsed.stream()
              .limit(Problem.MAX_REPORTED)
              .map(
                  triple ->
                      new Problem(
                          "UNPARSED_TRIPLE",
                          "The OWL 2 RDF mapping cannot consume "
                              + triple
                              + "; it is never dropped"))
              .toList(),
          unparsed.size());
    }
    return ontology;
  }

  /** Parses a small OWL 2 functional-syntax document, such as the checks of a step. */
  static OWLOntology loadFunctional(final OWLOntologyManager manager, final Path document)
      throws InputRejectedException {
    return load(
        manager, new FileDocumentSource(document.toFile(), new FunctionalSyntaxDocumentFormat()));
  }

  static OWLOntology loadFunctional(final OWLOntologyManager manager, final String text)
      throws InputRejectedException {
    return load(
        manager,
        new StringDocumentSource(
            text, "urn:openmetadata:reasoner:query", new FunctionalSyntaxDocumentFormat(), null));
  }

  private static OWLOntology load(
      final OWLOntologyManager manager, final OWLOntologyDocumentSource source)
      throws InputRejectedException {
    final OWLOntology ontology;
    try {
      ontology = manager.loadOntologyFromOntologyDocument(source, configuration());
    } catch (final UnloadableImportException e) {
      throw InputRejectedException.of(
          "INVALID_INPUT", "IMPORT_NOT_ALLOWED", "The document declares imports; none are allowed");
    } catch (final OWLOntologyCreationException | OWLRuntimeException e) {
      throw InputRejectedException.of("INVALID_INPUT", "PARSE_ERROR", firstLine(e.getMessage()));
    }
    if (ontology.importsDeclarations().findAny().isPresent()) {
      throw InputRejectedException.of(
          "INVALID_INPUT", "IMPORT_NOT_ALLOWED", "The document declares imports; none are allowed");
    }
    return ontology;
  }

  private static Optional<OWLOntologyLoaderMetaData> metadata(final OWLOntology ontology) {
    final OWLDocumentFormat format = ontology.getFormat();
    return format == null ? Optional.empty() : format.getOntologyLoaderMetaData();
  }

  private static String firstLine(final String message) {
    if (message == null) {
      return "unparseable document";
    }
    final int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
