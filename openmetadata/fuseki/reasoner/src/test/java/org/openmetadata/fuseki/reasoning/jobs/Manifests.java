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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;

/**
 * Publishes version manifests into a dataset as OpenMetadata's OntologyClosures writes them: the
 * graph {@code {base}graph/manifest/{hex}} with the owl:Ontology header, the omr:OntologyVersion
 * node and one omr:pinnedDigest per member graph, which must already be in the dataset. The
 * extension's tests have the same class; the reasoner's tests compile only the extension's main
 * sources.
 */
public final class Manifests {
  public static final String OMR = "https://open-metadata.org/reasoning/";

  /** A published closure, as the request and an importing manifest name it. */
  public record Published(String ontologyIri, String versionIri, String digest, String graph) {}

  private Manifests() {}

  public static Published publish(
      final DatasetGraph dataset,
      final String base,
      final String ontologyIri,
      final String glossaryIri,
      final String selection,
      final List<String> members,
      final List<Published> imports) {
    final TreeMap<String, String> pinned = new TreeMap<>();
    Txn.executeRead(
        dataset,
        () -> {
          for (final String member : members) {
            final Node graph = NodeFactory.createURI(member);
            pinned.put(member, GraphDigest.of(dataset.getGraph(graph).find().toList()));
          }
        });
    final List<String> lines = new ArrayList<>();
    lines.add("om-closure-v2");
    lines.add("ontology <" + ontologyIri + ">");
    lines.add("glossary <" + glossaryIri + ">");
    lines.add("selection " + selection);
    pinned.forEach((graph, digest) -> lines.add("member <" + graph + "> " + digest));
    final List<String> importLines = new ArrayList<>();
    imports.forEach(
        imported ->
            importLines.add("import <" + imported.ontologyIri() + "> " + imported.digest()));
    importLines.sort(String::compareTo);
    lines.addAll(importLines);
    final String digest = closureDigest(lines);
    final String hex = digest.substring("sha256:".length());
    final String version = Capture.versionIri(ontologyIri, selection, digest);
    final String graph = base + "graph/manifest/" + hex;
    final StringBuilder trig = new StringBuilder();
    trig.append("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n")
        .append("PREFIX omr: <")
        .append(OMR)
        .append(">\n")
        .append("GRAPH <")
        .append(graph)
        .append("> {\n")
        .append('<')
        .append(ontologyIri)
        .append("> a owl:Ontology ; owl:versionIRI <")
        .append(version)
        .append("> .\n");
    for (final Published imported : imports) {
      trig.append('<')
          .append(ontologyIri)
          .append("> owl:imports <")
          .append(imported.versionIri())
          .append("> .\n");
    }
    trig.append('<')
        .append(version)
        .append("> a omr:OntologyVersion ; omr:ontology <")
        .append(ontologyIri)
        .append("> ; omr:glossary <")
        .append(glossaryIri)
        .append("> ; omr:selection \"")
        .append(selection)
        .append("\" ; omr:closureDigest \"")
        .append(digest)
        .append("\" ; omr:digestAlgorithm \"om-closure-v2\" ; omr:memberCount ")
        .append(pinned.size())
        .append(" ; omr:createdAt \"2026-10-15T08:00:00Z\"^^")
        .append("<http://www.w3.org/2001/XMLSchema#dateTime> .\n");
    for (final Published imported : imports) {
      trig.append('<')
          .append(version)
          .append("> omr:importsManifest <")
          .append(imported.graph())
          .append("> .\n");
    }
    pinned.forEach(
        (member, memberDigest) ->
            trig.append('<')
                .append(member)
                .append("> omr:pinnedDigest \"")
                .append(memberDigest)
                .append("\" .\n"));
    trig.append("}\n");
    Txn.executeWrite(
        dataset, () -> RDFParser.fromString(trig.toString(), Lang.TRIG).parse(dataset));
    return new Published(ontologyIri, version, digest, graph);
  }

  static String closureDigest(final List<String> lines) {
    try {
      final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      for (final String line : lines) {
        sha256.update((line + "\n").getBytes(StandardCharsets.UTF_8));
      }
      return "sha256:" + HexFormat.of().formatHex(sha256.digest());
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
