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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.openmetadata.reasoner.InputRejectedException;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Problem;
import org.openmetadata.reasoner.StepFile;

/**
 * Exports one import closure from the working dataset, in one read transaction, as a single
 * N-Triples document that OWL API parses. The closure is the union of the listed named graphs.
 *
 * <p>Imports are resolved here, never by OWL API: every {@code owl:imports} target must be the IRI
 * or version IRI of an ontology header inside the closure, or the closure is rejected. The document
 * then carries only the root's header and no {@code owl:imports} triples at all, so OWL API has
 * nothing to fetch. Headers of imported ontologies lose only their typing and version triples;
 * their annotations, like everything else, pass through unchanged.
 */
final class ClosureDocument {
  private static final Node TYPE = RDF.type.asNode();
  private static final Node ONTOLOGY = OWL2.Ontology.asNode();
  private static final Node IMPORTS = OWL2.imports.asNode();
  private static final Node VERSION_IRI = OWL2.versionIRI.asNode();

  /** Types of a blank node that is an anonymous class expression, data range or list. */
  private static final Set<Node> EXPRESSION_TYPES =
      Set.of(OWL2.Restriction.asNode(), OWL2.Class.asNode(), RDFS.Datatype.asNode());

  /** Predicates whose subject is an anonymous expression or list in the OWL 2 RDF mapping. */
  private static final Set<Node> EXPRESSION_PREDICATES =
      Set.of(
          OWL2.onProperty.asNode(),
          OWL2.intersectionOf.asNode(),
          OWL2.unionOf.asNode(),
          OWL2.complementOf.asNode(),
          OWL2.oneOf.asNode(),
          OWL2.onDatatype.asNode(),
          OWL2.withRestrictions.asNode(),
          OWL2.datatypeComplementOf.asNode(),
          OWL2.inverseOf.asNode(),
          RDF.first.asNode());

  /** Predicates that only ever describe an anonymous restriction or list, never a named entity. */
  private static final Set<Node> ANONYMOUS_ONLY_PREDICATES =
      Set.of(
          OWL2.onProperty.asNode(),
          OWL2.onProperties.asNode(),
          OWL2.someValuesFrom.asNode(),
          OWL2.allValuesFrom.asNode(),
          OWL2.hasValue.asNode(),
          OWL2.hasSelf.asNode(),
          OWL2.minCardinality.asNode(),
          OWL2.maxCardinality.asNode(),
          OWL2.cardinality.asNode(),
          OWL2.minQualifiedCardinality.asNode(),
          OWL2.maxQualifiedCardinality.asNode(),
          OWL2.qualifiedCardinality.asNode(),
          OWL2.onClass.asNode(),
          OWL2.onDataRange.asNode(),
          RDF.first.asNode(),
          RDF.rest.asNode());

  private final Path file;
  private final long triples;
  private final String root;
  private final TreeMap<String, TreeSet<String>> imports;

  private ClosureDocument(
      final Path file,
      final long triples,
      final String root,
      final TreeMap<String, TreeSet<String>> imports) {
    this.file = file;
    this.triples = triples;
    this.root = root;
    this.imports = imports;
  }

  Path file() {
    return file;
  }

  long triples() {
    return triples;
  }

  JsonObject toJson(final int graphs) {
    final JsonObject json = new JsonObject();
    json.put("root", root);
    json.put("graphs", graphs);
    json.put("triples", triples);
    final JsonObject importsJson = new JsonObject();
    imports.forEach(
        (ontology, targets) -> {
          final JsonArray array = new JsonArray();
          targets.forEach(array::add);
          importsJson.put(ontology, array);
        });
    json.put("imports", importsJson);
    return json;
  }

  static ClosureDocument export(
      final DatasetGraph dataset,
      final StepFile.Closure closure,
      final Path file,
      final Limits limits)
      throws InputRejectedException, IOException {
    dataset.begin(TxnType.READ);
    try {
      return exportInTransaction(dataset, closure, file, limits);
    } finally {
      dataset.end();
    }
  }

  private static ClosureDocument exportInTransaction(
      final DatasetGraph dataset,
      final StepFile.Closure closure,
      final Path file,
      final Limits limits)
      throws InputRejectedException, IOException {
    final List<Problem> problems = new ArrayList<>();
    final List<Node> graphs = new ArrayList<>();
    for (final String name : closure.graphs()) {
      final Node graph = NodeFactory.createURI(name);
      if (!dataset.containsGraph(graph)) {
        problems.add(new Problem("MISSING_GRAPH", "Closure graph <" + name + "> is not loaded"));
      }
      graphs.add(graph);
    }
    if (!problems.isEmpty()) {
      throw new InputRejectedException("INVALID_INPUT", problems);
    }

    final Set<Node> headers = new HashSet<>();
    final Set<Node> names = new HashSet<>();
    final List<Triple> importTriples = new ArrayList<>();
    final Set<Node> referencedBlankNodes = new HashSet<>();
    final Set<Node> expressionBlankNodes = new HashSet<>();
    final TreeSet<String> misplaced = new TreeSet<>();
    long triples = 0;
    for (final Node graph : graphs) {
      final Iterator<Quad> quads = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY);
      while (quads.hasNext()) {
        final Quad quad = quads.next();
        triples++;
        if (triples > limits.maxClosureTriples()) {
          throw InputRejectedException.of(
              "LIMIT_EXCEEDED",
              "CLOSURE_TOO_LARGE",
              "The closure has more than " + limits.maxClosureTriples() + " triples");
        }
        final Node subject = quad.getSubject();
        final Node predicate = quad.getPredicate();
        final Node object = quad.getObject();
        if (predicate.equals(TYPE) && object.equals(ONTOLOGY)) {
          headers.add(subject);
          names.add(subject);
        } else if (predicate.equals(IMPORTS)) {
          importTriples.add(quad.asTriple());
        }
        if (object.isBlank()) {
          referencedBlankNodes.add(object);
        }
        if (subject.isBlank()) {
          if (EXPRESSION_PREDICATES.contains(predicate)
              || (predicate.equals(TYPE) && EXPRESSION_TYPES.contains(object))) {
            expressionBlankNodes.add(subject);
          }
        } else if (ANONYMOUS_ONLY_PREDICATES.contains(predicate) && misplaced.size() < 10) {
          misplaced.add(label(subject) + " " + label(predicate) + " " + label(object));
        }
      }
    }
    for (final Node graph : graphs) {
      final Iterator<Quad> versions = dataset.find(graph, Node.ANY, VERSION_IRI, Node.ANY);
      while (versions.hasNext()) {
        final Quad quad = versions.next();
        if (headers.contains(quad.getSubject())) {
          names.add(quad.getObject());
        }
      }
    }

    final Node root = NodeFactory.createURI(closure.root());
    if (!headers.contains(root)) {
      problems.add(
          new Problem(
              "ROOT_NOT_IN_CLOSURE",
              "No graph of the closure declares <" + closure.root() + "> an owl:Ontology"));
    }
    final TreeMap<String, TreeSet<String>> imports = new TreeMap<>();
    for (final Triple triple : importTriples) {
      if (!headers.contains(triple.getSubject())) {
        continue;
      }
      final Node target = triple.getObject();
      final String importer = label(triple.getSubject());
      if (!target.isURI()) {
        problems.add(new Problem("INVALID_IMPORT", importer + " imports a non-IRI " + target));
      } else if (!names.contains(target)) {
        problems.add(
            new Problem(
                "MISSING_IMPORT",
                importer
                    + " imports <"
                    + target.getURI()
                    + ">, which is not in the closure; imports are never fetched"));
      }
      imports.computeIfAbsent(importer, key -> new TreeSet<>()).add(label(target));
    }
    if (!problems.isEmpty()) {
      throw new InputRejectedException("INVALID_INPUT", problems);
    }
    // OWL API ignores both of these without a trace, so they are caught here: an anonymous
    // expression no triple refers to (left behind when the triple linking it was not written)
    // and restriction or list vocabulary on a named entity.
    expressionBlankNodes.removeAll(referencedBlankNodes);
    if (!expressionBlankNodes.isEmpty()) {
      problems.add(
          new Problem(
              "UNUSED_EXPRESSION",
              expressionBlankNodes.size()
                  + " anonymous class expressions, data ranges or lists are not used by any"
                  + " axiom; they are never dropped silently"));
    }
    if (!misplaced.isEmpty()) {
      problems.add(
          new Problem(
              "MISPLACED_VOCABULARY",
              "Restriction or list vocabulary on a named entity, which the OWL 2 RDF mapping"
                  + " cannot consume: "
                  + String.join("; ", misplaced)));
    }
    if (!problems.isEmpty()) {
      throw new InputRejectedException("NOT_OWL2_DL", problems);
    }

    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
      final StreamRDF writer = StreamRDFWriter.getWriterStream(out, RDFFormat.NTRIPLES);
      writer.start();
      for (final Node graph : graphs) {
        final Iterator<Quad> quads = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY);
        while (quads.hasNext()) {
          final Triple triple = quads.next().asTriple();
          if (!isImportStructure(triple, headers, root)) {
            writer.triple(triple);
          }
        }
      }
      writer.finish();
    }
    return new ClosureDocument(file, triples, closure.root(), imports);
  }

  private static boolean isImportStructure(
      final Triple triple, final Set<Node> headers, final Node root) {
    final Node subject = triple.getSubject();
    if (!headers.contains(subject)) {
      return false;
    }
    final Node predicate = triple.getPredicate();
    if (predicate.equals(IMPORTS)) {
      return true;
    }
    if (subject.equals(root)) {
      return false;
    }
    return predicate.equals(VERSION_IRI)
        || (predicate.equals(TYPE) && triple.getObject().equals(ONTOLOGY));
  }

  private static String label(final Node node) {
    return node.isURI() ? node.getURI() : node.toString();
  }
}
