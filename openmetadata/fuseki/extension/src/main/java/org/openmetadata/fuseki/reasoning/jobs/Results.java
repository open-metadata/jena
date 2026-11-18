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

import java.util.List;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * The RESULTS operation's page of a finished job's result. Each result has one list that can be
 * long, which is paged: a refresh's closures, a check's unsatisfiable classes, an explanation's
 * justifications. Everything else in a result is bounded by the step that wrote it (a governance
 * explanation by its fact bound, which a refresh sets at most to maxResultRows).
 */
final class Results {
  /** The paged list of each kind of result, in the order they are looked for. */
  static final List<String> LISTS = List.of("closures", "unsatisfiableClasses", "justifications");

  private Results() {}

  static JsonObject page(
      final JsonObject job, final JsonObject result, final int offset, final int limit) {
    final JsonObject copy = JSON.copy(result).getAsObject();
    final JsonObject page = new JsonObject();
    page.put("offset", offset);
    page.put("limit", limit);
    for (final String key : LISTS) {
      final JsonValue list = copy.get(key);
      if (list != null && list.isArray()) {
        final JsonArray all = list.getAsArray();
        final JsonArray slice = new JsonArray();
        for (int i = offset; i < all.size() && slice.size() < limit; i++) {
          slice.add(all.get(i));
        }
        copy.put(key, slice);
        page.put("items", key);
        page.put("total", all.size());
        break;
      }
    }
    final JsonObject json = new JsonObject();
    json.put("job", job);
    json.put("result", copy);
    json.put("page", page);
    return json;
  }
}
