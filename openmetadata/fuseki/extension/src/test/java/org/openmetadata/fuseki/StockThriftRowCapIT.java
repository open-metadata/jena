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
package org.openmetadata.fuseki;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.lang.RiotParsers;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.jupiter.api.Test;

/**
 * Jena 6.3.0 refuses an RDF Thrift row above 100,000 bytes, and {@link ThriftRows} exists to lift
 * that. This runs Jena's own reader, with no extension, and fails when that stops being true.
 */
class StockThriftRowCapIT {
  private static final String RECHECK =
      "Stock Jena now accepts RDF Thrift rows above 100,000 bytes. Check TRDF in the Jena release"
          + " you are porting to: if the cap is gone or configurable, drop ThriftRows and its use"
          + " in BoundedGraphStore.parse, then update README.md's \"What the extension adds\" and"
          + " this test.";

  @Test
  void stockReaderRefusesARowAboveOneHundredThousandBytes() throws Exception {
    final byte[] accepted = thrift(99_000);
    final byte[] refused = thrift(150_000);

    assertDoesNotThrow(
        () -> readWithStockReader(accepted),
        "stock Jena rejected a 99,000 character row, so the cap is now below 100,000 bytes and"
            + " the premise of this test and of ThriftRows needs rechecking");
    assertThrows(RiotException.class, () -> readWithStockReader(refused), RECHECK);
  }

  private static void readWithStockReader(final byte[] body) {
    RiotParsers.factoryRDFThrift
        .create(Lang.RDFTHRIFT, null)
        .read(new ByteArrayInputStream(body), null, null, StreamRDFLib.sinkNull(), null);
  }

  static byte[] thrift(final int literalLength) {
    final Graph graph = GraphFactory.createDefaultGraph();
    graph.add(
        Triple.create(
            NodeFactory.createURI("urn:test:s"),
            NodeFactory.createURI("urn:test:p"),
            NodeFactory.createLiteralString("a".repeat(literalLength))));
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    RDFDataMgr.write(out, graph, Lang.RDFTHRIFT);
    return out.toByteArray();
  }
}
