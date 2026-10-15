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
package org.openmetadata.reasoner.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.Test;

/** The spool gives back exactly the terms it was given, blank node labels included. */
class NodeCodecTest {
  @Test
  void everyKindOfTermRoundTrips() throws Exception {
    final List<Node> nodes =
        List.of(
            NodeFactory.createURI("http://example.org/é?x=1#f"),
            NodeFactory.createBlankNode("b0-é:x"),
            NodeFactory.createLiteralString("line\nbreak \"quoted\" " + "x".repeat(70_000)),
            NodeFactory.createLiteralLang("chat", "fr"),
            NodeFactory.createLiteralDirLang("שלום", "he", "rtl"),
            NodeFactory.createLiteralDT("42", XSDDatatype.XSDinteger),
            NodeFactory.createLiteralDT("x", NodeFactory.getType("urn:unknown:type")),
            NodeFactory.createTripleTerm(
                NodeFactory.createURI("urn:s"),
                NodeFactory.createURI("urn:p"),
                NodeFactory.createLiteralString("o")));
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      for (final Node node : nodes) {
        NodeCodec.write(out, node);
      }
      NodeCodec.write(out, null);
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      for (final Node node : nodes) {
        assertEquals(node, NodeCodec.read(in));
      }
      assertNull(NodeCodec.read(in));
    }
  }
}
