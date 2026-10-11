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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.reasoner.Limits;
import org.openmetadata.reasoner.Watchdog;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;

/**
 * The classifier asks HermiT only for the property values {@link PropertyValues} says it can entail
 * and reads the rest from the closure. Its values must equal what HermiT gives when asked about
 * every individual and every property, on every answered conformance fixture and on closures built
 * to reach named individuals through anonymous ones.
 */
class PropertyValuesTest {
  private static final String NS = "http://example.org/pv#";

  @TempDir Path directory;

  @TestFactory
  Stream<DynamicTest> everyConsistentFixtureHasExactlyHermitsValues() throws Exception {
    final Path conformance = conformance();
    final List<String> fixtures;
    try (Stream<Path> files = Files.list(conformance)) {
      fixtures =
          files
              .map(path -> path.getFileName().toString())
              .filter(name -> name.endsWith(".expected"))
              .map(name -> name.substring(0, name.length() - ".expected".length()))
              .filter(name -> name.startsWith("dl-") || name.startsWith("el-"))
              .filter(
                  name ->
                      firstLine(conformance.resolve(name + ".expected"))
                          .equals("OUTCOME CLASSIFIED"))
              .sorted()
              .toList();
    }
    assertTrue(fixtures.size() > 20, fixtures.toString());
    return fixtures.stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture, () -> assertSameValues(fromFixture(conformance, fixture))));
  }

  @Test
  void anAnonymousValueMergedIntoANamedIndividualIsFound() throws Exception {
    // d has an S-value whose T-value is y; T is inverse-functional and c has the T-value y, so
    // the S-value is c: S(d, c), though S has no sub-property, inverse or chain.
    final OWLOntology ontology =
        functional(
            """
            Declaration(ObjectProperty(:S)) Declaration(ObjectProperty(:T))
            Declaration(NamedIndividual(:c)) Declaration(NamedIndividual(:d))
            Declaration(NamedIndividual(:y))
            InverseFunctionalObjectProperty(:T)
            ClassAssertion(ObjectSomeValuesFrom(:S ObjectHasValue(:T :y)) :d)
            ObjectPropertyAssertion(:T :c :y)
            """);
    assertTrue(values(ontology).contains(value("d", "S", "c")), values(ontology).toString());
    assertSameValues(ontology);
  }

  @Test
  void assertedValuesFollowEqualIndividuals() throws Exception {
    final OWLOntology ontology =
        functional(
            """
            Declaration(ObjectProperty(:owner)) Declaration(ObjectProperty(:tag))
            Declaration(NamedIndividual(:t)) Declaration(NamedIndividual(:a))
            Declaration(NamedIndividual(:b)) Declaration(NamedIndividual(:pii))
            Declaration(NamedIndividual(:x))
            FunctionalObjectProperty(:owner)
            ObjectPropertyAssertion(:owner :t :a) ObjectPropertyAssertion(:owner :t :b)
            ObjectPropertyAssertion(:tag :a :pii) ObjectPropertyAssertion(:tag :x :a)
            """);
    final PropertyValues analysis = PropertyValues.analyse(ontology);
    assertFalse(analysis.needsEngine(), analysis.reason());
    final TreeSet<String> values = values(ontology);
    assertTrue(values.contains(value("b", "tag", "pii")), values.toString());
    assertTrue(values.contains(value("x", "tag", "b")), values.toString());
    assertSameValues(ontology);
  }

  @Test
  void aChainThroughAnAnonymousIndividualIsEntailed() throws Exception {
    final OWLOntology ontology =
        functional(
            """
            Declaration(ObjectProperty(:hasParent)) Declaration(ObjectProperty(:hasBrother))
            Declaration(ObjectProperty(:hasUncle))
            Declaration(NamedIndividual(:ann)) Declaration(NamedIndividual(:carl))
            SubObjectPropertyOf(ObjectPropertyChain(:hasParent :hasBrother) :hasUncle)
            ClassAssertion(ObjectSomeValuesFrom(:hasParent ObjectHasValue(:hasBrother :carl)) :ann)
            """);
    assertTrue(PropertyValues.analyse(ontology).entailsValues(property("hasUncle")));
    assertTrue(values(ontology).contains(value("ann", "hasUncle", "carl")));
    assertSameValues(ontology);
  }

  @Test
  void reflexiveAndInversePropertiesAreEntailed() throws Exception {
    final OWLOntology ontology =
        functional(
            """
            Declaration(ObjectProperty(:knows)) Declaration(ObjectProperty(:contains))
            Declaration(ObjectProperty(:within)) Declaration(ObjectProperty(:plain))
            Declaration(NamedIndividual(:a)) Declaration(NamedIndividual(:b))
            ReflexiveObjectProperty(:knows)
            InverseObjectProperties(:contains :within)
            ObjectPropertyAssertion(:contains :a :b) ObjectPropertyAssertion(:plain :a :b)
            """);
    final PropertyValues analysis = PropertyValues.analyse(ontology);
    assertTrue(analysis.entailsValues(property("knows")));
    assertTrue(analysis.entailsValues(property("within")));
    assertFalse(analysis.entailsValues(property("plain")));
    assertSameValues(ontology);
  }

  @Test
  void anUnusedTransitivePropertyNeedsNoEngine() throws Exception {
    final OWLOntology ontology =
        functional(
            """
            Declaration(ObjectProperty(:partOf)) Declaration(ObjectProperty(:owner))
            Declaration(NamedIndividual(:a)) Declaration(NamedIndividual(:b))
            TransitiveObjectProperty(:partOf)
            ObjectPropertyAssertion(:owner :a :b)
            """);
    assertFalse(PropertyValues.analyse(ontology).needsEngine());
    assertSameValues(ontology);
  }

  private void assertSameValues(final OWLOntology ontology) throws Exception {
    assertEquals(hermitValues(ontology), values(ontology));
  }

  /** The VALUE facts the classifier records with production routing. */
  private static TreeSet<String> values(final OWLOntology ontology) throws Exception {
    final PropertyValues analysis = PropertyValues.analyse(ontology);
    final EngineRouter.Route route = EngineRouter.route(ontology, EngineChoice.AUTO, analysis);
    try (Watchdog watchdog = new Watchdog(120_000, expired -> {})) {
      final TreeSet<String> values = new TreeSet<>();
      Classifier.run(
          ontology,
          route,
          analysis,
          List.of(),
          watchdog,
          Limits.DEFAULTS,
          fact -> {
            if (fact.kind() == Classification.Kind.VALUE) {
              assertTrue(values.add(fact.line()), () -> "recorded twice: " + fact.line());
            }
          });
      return values;
    }
  }

  /** HermiT's values for every named individual and every object property of the closure. */
  private static TreeSet<String> hermitValues(final OWLOntology ontology) {
    final Reasoner hermit = Engine.hermit(ontology);
    try {
      hermit.precomputeInferences(
          InferenceType.OBJECT_PROPERTY_ASSERTIONS, InferenceType.SAME_INDIVIDUAL);
      final TreeSet<String> values = new TreeSet<>();
      final List<OWLObjectProperty> properties =
          ontology
              .objectPropertiesInSignature()
              .filter(p -> !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
              .toList();
      for (final OWLNamedIndividual individual : ontology.individualsInSignature().toList()) {
        for (final OWLObjectProperty property : properties) {
          hermit
              .getObjectPropertyValues(individual, property)
              .entities()
              .forEach(
                  value ->
                      values.add(
                          "VALUE <"
                              + Classifier.iri(individual)
                              + "> <"
                              + Classifier.iri(property)
                              + "> <"
                              + Classifier.iri(value)
                              + ">"));
        }
      }
      return values;
    } finally {
      hermit.dispose();
    }
  }

  private OWLOntology fromFixture(final Path conformance, final String fixture)
      throws IOException, org.openmetadata.reasoner.InputRejectedException {
    final DatasetGraph dataset =
        RDFDataMgr.loadDatasetGraph(conformance.resolve(fixture + ".trig").toString());
    final Path document = directory.resolve(fixture + ".nt");
    try (OutputStream out = Files.newOutputStream(document)) {
      RDFDataMgr.write(
          out, dataset.getGraph(NodeFactory.createURI("urn:fixture:" + fixture)), Lang.NTRIPLES);
    }
    return OntologyLoader.loadClosure(OntologyLoader.newManager(), document);
  }

  private static OWLOntology functional(final String axioms) throws Exception {
    return OntologyLoader.loadFunctional(
        OntologyLoader.newManager(),
        "Prefix(:=<" + NS + ">) Ontology(<http://example.org/pv>\n" + axioms + ")");
  }

  private static OWLObjectProperty property(final String name) {
    return org.semanticweb.owlapi.apibinding.OWLManager.getOWLDataFactory()
        .getOWLObjectProperty(NS + name);
  }

  private static String value(final String subject, final String property, final String object) {
    return "VALUE <" + NS + subject + "> <" + NS + property + "> <" + NS + object + ">";
  }

  private static Path conformance() throws URISyntaxException {
    return Path.of(PropertyValuesTest.class.getResource("/conformance").toURI());
  }

  private static String firstLine(final Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
