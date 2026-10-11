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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.sparql.core.DatasetGraph;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.StepFile;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * One closure read from the working dataset into OWL API and admitted: exported, parsed without the
 * network, and checked against the limits and the OWL 2 DL profile.
 */
record ClosureInput(OWLOntology ontology, JsonObject closureJson, Admission admission) {

  static ClosureInput read(final DatasetGraph dataset, final StepFile step) throws Exception {
    final Path document = step.jobDirectory().resolve(step.stepId() + ".closure.nt");
    final ClosureDocument closure;
    final OWLOntology ontology;
    try {
      closure = ClosureDocument.export(dataset, step.closure(), document, step.limits());
      ontology = OntologyLoader.loadClosure(OntologyLoader.newManager(), document);
    } finally {
      Files.deleteIfExists(document);
    }
    final Admission admission = Admission.check(ontology, step.limits());
    return new ClosureInput(ontology, closure.toJson(step.closure().graphs().size()), admission);
  }

  /**
   * Parses entailment checks, one OWL 2 functional-syntax axiom each. A check that names an entity
   * outside the closure is answered INVALID, never NOT_ENTAILED.
   */
  List<Classifier.Check> checks(final Path file, final long maxChecks)
      throws InputRejectedException {
    if (!Files.isRegularFile(file)) {
      throw InputRejectedException.of(
          "INVALID_INPUT",
          "CHECKS_MISSING",
          "Checks file " + file.getFileName() + " does not exist");
    }
    final OWLOntology document = OntologyLoader.loadFunctional(OntologyLoader.newManager(), file);
    final List<OWLAxiom> axioms =
        document
            .logicalAxioms()
            .map(axiom -> (OWLAxiom) axiom.getAxiomWithoutAnnotations())
            .sorted(Comparator.comparing(Object::toString))
            .toList();
    if (axioms.size() > maxChecks) {
      throw InputRejectedException.of(
          "LIMIT_EXCEEDED",
          "TOO_MANY_CHECKS",
          axioms.size() + " checks, over the limit of " + maxChecks);
    }
    final List<Classifier.Check> checks = new ArrayList<>();
    for (final OWLAxiom axiom : axioms) {
      checks.add(new Classifier.Check(axiom, axiom.toString(), unknownEntities(axiom)));
    }
    return checks;
  }

  /** Why an axiom cannot be asked of this closure, or null when every entity is known. */
  String unknownEntities(final OWLAxiom axiom) {
    final List<String> unknown =
        axiom
            .signature()
            .filter(entity -> !entity.isBuiltIn() && !ontology.containsEntityInSignature(entity))
            .map(entity -> entity.getEntityType().getName() + " <" + entity.getIRI() + ">")
            .sorted()
            .toList();
    return unknown.isEmpty() ? null : "Not in the closure: " + String.join(", ", unknown);
  }
}
