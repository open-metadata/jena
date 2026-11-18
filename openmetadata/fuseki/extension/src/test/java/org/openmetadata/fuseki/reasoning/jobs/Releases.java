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
package org.openmetadata.fuseki.reasoning.jobs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;

/**
 * Ontology releases as OpenMetadata uploads them: RDF-Thrift of one ontology's members under an
 * owl:Ontology header stating the release's owl:versionIRI. The reasoner's tests have the same
 * class, because they compile only the extension's main sources.
 */
public final class Releases {
  public static final String ONTOLOGY_IRI = "https://open-metadata.org/ontology/finance";
  public static final String F = "https://open-metadata.org/finance#";
  public static final String PREFIXES =
      """
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
      @prefix dct: <http://purl.org/dc/terms/> .
      @prefix : <https://open-metadata.org/finance#> .
      """;

  /** An uploaded release and the request fields that name it. */
  public record Release(UUID ontologyId, UUID releaseId, byte[] rdf) {
    public long byteSize() {
      return rdf.length;
    }

    public String digest() {
      return "sha256:" + hash(rdf);
    }

    public JsonObject ref() {
      final JsonObject ref = new JsonObject();
      ref.put("ontologyId", ontologyId.toString());
      ref.put("releaseId", releaseId.toString());
      ref.put("contentDigest", hash(releaseId.toString().getBytes(StandardCharsets.UTF_8)));
      return ref;
    }

    public JsonObject upload() {
      final JsonObject upload = new JsonObject();
      upload.put("digest", digest());
      upload.put("byteSize", byteSize());
      upload.put("mediaType", "application/rdf+thrift");
      return upload;
    }

    /** Names this release in a CHECK or EXPLAIN request. */
    public JsonObject into(final JsonObject request) {
      final JsonArray releases = new JsonArray();
      releases.add(ref());
      request.put("ontologyReleases", releases);
      request.put("releaseRdf", upload());
      return request;
    }
  }

  private Releases() {}

  /** A release of a new ontology whose header states its own version IRI, with these members. */
  public static Release of(final String members) {
    final UUID release = UUID.randomUUID();
    return raw(UUID.randomUUID(), release, header(release) + members);
  }

  /** The header a release's export starts with. */
  public static String header(final UUID releaseId) {
    return "<%s> a owl:Ontology ; owl:versionIRI <%s%s> .\n"
        .formatted(ONTOLOGY_IRI, Capture.RELEASE_IRI_PREFIX, releaseId);
  }

  /** A release made of exactly this Turtle, whatever its header says. */
  public static Release raw(final UUID ontologyId, final UUID releaseId, final String turtle) {
    return new Release(ontologyId, releaseId, thrift(turtle));
  }

  /**
   * Makes the release the active one of its ontology in a serving dataset: its triples are the
   * graph {@code graph/ontology/{ontologyId}}, replacing whatever that graph held.
   */
  public static void activate(final DatasetGraph serving, final Release release) {
    final Node graph = NodeFactory.createURI(Capture.ONTOLOGY_GRAPH_PREFIX + release.ontologyId());
    Txn.executeWrite(
        serving,
        () -> {
          serving.deleteAny(graph, Node.ANY, Node.ANY, Node.ANY);
          RDFParser.source(new ByteArrayInputStream(release.rdf()))
              .lang(Lang.RDFTHRIFT)
              .parse(
                  new StreamRDFBase() {
                    @Override
                    public void triple(final Triple triple) {
                      serving.add(
                          graph, triple.getSubject(), triple.getPredicate(), triple.getObject());
                    }
                  });
        });
  }

  public static byte[] thrift(final String turtle) {
    final Model model = ModelFactory.createDefaultModel();
    RDFDataMgr.read(model, new StringReader(PREFIXES + turtle), null, Lang.TURTLE);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    RDFDataMgr.write(out, model, RDFFormat.RDF_THRIFT);
    return out.toByteArray();
  }

  static String hash(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
