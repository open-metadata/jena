# Third-party components of the reasoner worker

The image ships these jars in `/jena-fuseki/reasoner/`, the reasoner worker's classpath after
`fuseki-server.jar`. They are loaded only by the worker JVM, never by Fuseki. Licence texts are in
`/jena-fuseki/reasoner/licenses/`. Every jar except HermiT is the unmodified Maven Central artifact,
verified at build time against `dependencies.sha256`. HermiT is built from source at a pinned commit.

| Jar | Component | Licence | Source |
|---|---|---|---|
| `hermit-reasoner-1.4.6.519-om.1.jar` | HermiT, OpenMetadata build | LGPL-3.0-or-later (`LGPL-3.0.txt`, `GPL-3.0.txt`) | https://github.com/open-metadata/hermit-reasoner (fork of https://github.com/owlcs/hermit-reasoner) |
| `owlapi-api-5.5.1.jar`, `owlapi-impl-5.5.1.jar`, `owlapi-parsers-5.5.1.jar`, `owlapi-tools-5.5.1.jar`, `owlapi-apibinding-5.5.1.jar` | OWL API 5.5.1 | Apache-2.0 or LGPL-3.0, used under Apache-2.0 | https://github.com/owlcs/owlapi |
| `elk-owlapi-0.6.0.jar`, `elk-reasoner-0.6.0.jar`, `elk-owl-model-0.6.0.jar`, `elk-owl-implementation-0.6.0.jar`, `elk-proofs-0.6.0.jar`, `elk-util-collections-0.6.0.jar`, `elk-util-common-0.6.0.jar`, `elk-util-concurrent-0.6.0.jar`, `elk-util-hashing-0.6.0.jar`, `elk-util-io-0.6.0.jar`, `elk-util-logging-0.6.0.jar` | ELK 0.6.0 | Apache-2.0 | https://github.com/liveontologies/elk-reasoner |
| `puli-0.1.0.jar` | Proof Utility Library (ELK) | Apache-2.0 | https://github.com/liveontologies/puli |
| `owlapi-proof-0.1.0.jar` | OWL API Proof Extension (ELK) | Apache-2.0 | https://github.com/liveontologies/owlapi-proof |
| `axiom-api-1.2.14.jar`, `axiom-impl-1.2.14.jar`, `axiom-dom-1.2.14.jar`, `axiom-c14n-1.2.14.jar` | Apache Axiom 1.2.14 (HermiT: rdf:XMLLiteral canonicalization) | Apache-2.0 | https://ws.apache.org/axiom/ |
| `geronimo-activation_1.1_spec-1.1.jar` | Apache Geronimo JavaBeans Activation API (Axiom) | Apache-2.0 | https://geronimo.apache.org/ |
| `automaton-1.11-8.jar` | dk.brics.automaton (HermiT: datatype facets) | BSD-3-Clause (`dk.brics.automaton-BSD.txt`) | https://www.brics.dk/automaton/ |
| `guava-33.2.0-jre.jar`, `failureaccess-1.0.2.jar` | Guava | Apache-2.0 | https://github.com/google/guava |
| `hppcrt-0.7.5.jar` | HPPC-RT collections (OWL API) | Apache-2.0 | https://github.com/vsonnier/hppcrt |
| `commons-rdf-api-0.5.0.jar` | Apache Commons RDF API (OWL API) | Apache-2.0 | https://commons.apache.org/proper/commons-rdf/ |
| `javax.inject-1.jar` | JSR-330 annotations (OWL API) | Apache-2.0 | https://github.com/javax-inject/javax-inject |
| `jsr305-3.0.2.jar` | JSR-305 annotations (OWL API) | Apache-2.0 | https://github.com/findbugsproject/findbugs |
| `xz-1.9.jar` | XZ for Java (OWL API) | Public domain (`xz-java-COPYING.txt`) | https://tukaani.org/xz/java.html |
| `openmetadata-fuseki-reasoner-6.2.0.jar` | The worker itself | Apache-2.0 | this repository, `openmetadata/fuseki/reasoner` |

## Inside the HermiT jar

- HermiT compiles in JAutomata (package `rationals`), LGPL-2.1 (`LGPL-2.1.txt`),
  https://jautomata.sourceforge.net/.
- The HermiT jar is also an OSGi bundle for Protégé and embeds jar files of its dependencies:
  dk.brics.automaton 1.11-8 (BSD-3-Clause), Apache Axiom 1.2.14 (Apache-2.0), Apache Commons Logging
  1.1.3 (Apache-2.0) and Apache Commons CLI 1.4 (Apache-2.0). A plain Java classpath ignores jars
  nested in a jar, so these copies are never loaded.

## HermiT source

HermiT is LGPL-3.0-or-later. Its complete corresponding source, including the OpenMetadata changes,
is in `/jena-fuseki/reasoner/source/hermit-reasoner-1.4.6.519-om.1-sources.jar` and at the pinned
commit of https://github.com/open-metadata/hermit-reasoner named by the image's build argument
`HERMIT_COMMIT`. The worker uses HermiT only through the OWL API interfaces, from a separate jar
that can be replaced.

## Left out on purpose

These are dependencies of HermiT or the OWL API that the worker does not ship:

- Libraries that `fuseki-server.jar` already bundles at a newer version: Caffeine, commons-io,
  commons-codec, SLF4J and commons-cli.
- Apache Commons Logging 1.1.3. It splits a package with jcl-over-slf4j in `fuseki-server.jar`,
  which serves the same API.
- Woodstox and the StAX2 API. They register themselves JVM-wide as the `javax.xml.stream`
  implementation; Axiom uses the JDK's StAX instead, and the `dl-xmlliteral` conformance fixtures
  cover it.
- The Geronimo StAX API, which duplicates the JDK's `java.xml`.
- Axiom's mail, MIME and XPath dependencies.
- The OWL API's RDF4J, JSON-LD, Apache HttpClient and OBO modules.
