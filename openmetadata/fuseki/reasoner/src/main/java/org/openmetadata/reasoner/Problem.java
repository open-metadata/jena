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
package org.openmetadata.reasoner;

import java.util.List;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;

/** A structured diagnostic: a stable code for programs and a message for people. */
public record Problem(String code, String message) {
  /** Manifests stay bounded however many violations an input has; the count is kept separately. */
  public static final int MAX_REPORTED = 100;

  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("code", code);
    json.put("message", message);
    return json;
  }

  static JsonArray toJson(final List<Problem> problems) {
    final JsonArray array = new JsonArray();
    problems.stream().limit(MAX_REPORTED).map(Problem::toJson).forEach(array::add);
    return array;
  }
}
