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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.thrift.TRDF;
import org.apache.jena.riot.thrift.ThriftRDF;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Reads RDF Thrift with the per-row size cap set to the upload bound. Jena 6.3.0 hard-codes 100,000
 * bytes per triple or quad row in {@code TRDF}, which refuses an entity whose description or SQL is
 * larger. The upload bound already limits what one request can allocate, so a lower row cap adds no
 * protection.
 */
final class ThriftRows {
  private static final int RECURSION_LIMIT = 16;

  private ThriftRows() {}

  static void read(final Path file, final long maxRowBytes, final StreamRDF sink)
      throws IOException {
    final int rowLimit = (int) Math.min(maxRowBytes, Integer.MAX_VALUE);
    final TConfiguration configuration = new TConfiguration(rowLimit, rowLimit, RECURSION_LIMIT);
    try (InputStream input = TRDF.ensureBuffered(Files.newInputStream(file))) {
      final TIOStreamTransport transport = new TIOStreamTransport(configuration, input);
      transport.open();
      ThriftRDF.protocolToStream(TRDF.protocol(transport), sink);
    } catch (TTransportException exception) {
      TRDF.exception(exception);
    }
  }
}
