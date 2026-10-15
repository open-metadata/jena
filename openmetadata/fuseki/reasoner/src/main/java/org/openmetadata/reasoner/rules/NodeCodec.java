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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TextDirection;
import org.apache.jena.graph.Triple;

/**
 * The spool's encoding of RDF terms: exact, including blank node labels, so a term read back is the
 * term the dataset holds. A text syntax would have to re-encode blank node labels.
 */
final class NodeCodec {
  private static final int ABSENT = 0;
  private static final int IRI = 1;
  private static final int BLANK = 2;
  private static final int LITERAL = 3;
  private static final int TRIPLE = 4;

  private NodeCodec() {}

  static void write(final DataOutput out, final Node node) throws IOException {
    if (node == null) {
      out.writeByte(ABSENT);
    } else if (node.isURI()) {
      out.writeByte(IRI);
      string(out, node.getURI());
    } else if (node.isBlank()) {
      out.writeByte(BLANK);
      string(out, node.getBlankNodeLabel());
    } else if (node.isLiteral()) {
      out.writeByte(LITERAL);
      string(out, node.getLiteralLexicalForm());
      string(out, node.getLiteralDatatypeURI());
      string(out, node.getLiteralLanguage());
      final TextDirection direction = node.getLiteralBaseDirection();
      string(out, direction == null ? "" : direction.direction());
    } else if (node.isTripleTerm()) {
      out.writeByte(TRIPLE);
      final Triple triple = node.getTriple();
      write(out, triple.getSubject());
      write(out, triple.getPredicate());
      write(out, triple.getObject());
    } else {
      throw new IOException("Cannot spool the term " + node);
    }
  }

  static Node read(final DataInput in) throws IOException {
    final int tag = in.readByte();
    return switch (tag) {
      case ABSENT -> null;
      case IRI -> NodeFactory.createURI(string(in));
      case BLANK -> NodeFactory.createBlankNode(string(in));
      case LITERAL -> {
        final String lexical = string(in);
        final String datatype = string(in);
        final String language = string(in);
        final String direction = string(in);
        yield NodeFactory.createLiteral(
            lexical,
            language.isEmpty() ? null : language,
            direction.isEmpty() ? null : TextDirection.create(direction),
            language.isEmpty() ? TypeMapper.getInstance().getSafeTypeByName(datatype) : null);
      }
      case TRIPLE -> NodeFactory.createTripleTerm(read(in), read(in), read(in));
      default -> throw new IOException("Corrupt spool: unknown term tag " + tag);
    };
  }

  private static void string(final DataOutput out, final String value) throws IOException {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String string(final DataInput in) throws IOException {
    final int length = in.readInt();
    if (length < 0) {
      throw new IOException("Corrupt spool: negative string length");
    }
    final byte[] bytes = new byte[length];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
