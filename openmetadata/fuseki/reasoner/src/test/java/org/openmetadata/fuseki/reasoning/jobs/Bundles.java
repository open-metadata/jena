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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;

/** The governance rules a REFRESH test sends inline, and a stand-in for OpenMetadata's digest. */
final class Bundles {
  private Bundles() {}

  /** A starter rule's name and body, from the reasoner's copy of OpenMetadata's rules. */
  static RuleBundle.Rule starter(final String name) {
    try (InputStream in = Bundles.class.getResourceAsStream("/rules/" + name + ".json")) {
      final JsonObject rule = JSON.parse(in);
      return new RuleBundle.Rule(name, "CONSTRUCT", rule.getString("ruleBody"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Any well-formed digest of the rules: the store records OpenMetadata's and never checks it. */
  static String digest(final JsonArray rules) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(JSON.toString(rules).getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
