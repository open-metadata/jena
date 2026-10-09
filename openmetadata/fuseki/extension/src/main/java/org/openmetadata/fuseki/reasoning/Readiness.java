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
package org.openmetadata.fuseki.reasoning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;

/**
 * Whether the container's memory can hold a reasoner worker next to Fuseki. The requirement is
 * {@code limit >= (H_f + O_f + H_w + O_w + C_min) / 0.9}: every JVM region capped, plus a page
 * cache floor for serving, under the limit with a 10% margin, so that a worker's out-of-memory is a
 * Java error inside the worker and never a kernel OOM kill of the container. When it does not fit,
 * no step is admitted; serving is unaffected either way.
 */
public record Readiness(
    Status status,
    long requiredBytes,
    long availableBytes,
    String limitSource,
    Map<String, Long> terms,
    long anonymousBytes,
    List<String> reasons) {

  public enum Status {
    READY,
    NOT_READY,
    DISABLED
  }

  /**
   * Evaluates the budget. Before a step it also checks the container's current anonymous memory:
   * with no worker running that is Fuseki's, and if it exceeds H_f + O_f, Fuseki's off-heap has
   * outgrown O_f and the step must not start.
   *
   * @param fusekiHeap H_f measured in the Fuseki JVM, or -1 to use the configured value
   * @param subtractSelf true when this process is not Fuseki (the command line), so its own memory
   *     is not mistaken for Fuseki's
   */
  public static Readiness evaluate(
      final ReasoningSettings settings,
      final ContainerMemory memory,
      final long fusekiHeap,
      final boolean beforeStep,
      final boolean subtractSelf) {
    final ContainerMemory.Limit limit = memory.limit();
    final long heapF =
        fusekiHeap > 0
            ? fusekiHeap
            : settings.fusekiHeap() > 0 ? settings.fusekiHeap() : limit.bytes() / 4;
    final long offHeapF = ReasoningSettings.fusekiOffHeap(heapF);
    final long floor =
        settings.pageCacheFloorBytes() >= 0
            ? settings.pageCacheFloorBytes()
            : sizeOnDisk(settings.servingDatabases()) * settings.pageCacheFloorPercent() / 100;
    final long sum = heapF + offHeapF + settings.workerHeap() + settings.workerOffHeap() + floor;
    final long required = Math.ceilDiv(sum * 10, 9);

    final Map<String, Long> terms = new LinkedHashMap<>();
    terms.put("fusekiHeap", heapF);
    terms.put("fusekiOffHeap", offHeapF);
    terms.put("workerHeap", settings.workerHeap());
    terms.put("workerOffHeap", settings.workerOffHeap());
    terms.put("pageCacheFloor", floor);

    final List<String> reasons = new ArrayList<>();
    Status status = Status.READY;
    if (!settings.enabled()) {
      status = Status.DISABLED;
      reasons.add(ReasoningSettings.ENABLED + " is not true");
    }
    if (limit.bytes() < required) {
      status = status == Status.DISABLED ? status : Status.NOT_READY;
      reasons.add(
          "The container memory limit "
              + Sizes.format(limit.bytes())
              + " ("
              + limit.source()
              + ") is below the "
              + Sizes.format(required)
              + " reasoning needs: (H_f + O_f + H_w + O_w + C_min) / 0.9");
    }
    long anonymous = -1;
    if (beforeStep) {
      final OptionalLong containerAnonymous = memory.anonymousBytes();
      if (containerAnonymous.isPresent()) {
        anonymous = containerAnonymous.getAsLong();
        if (subtractSelf) {
          anonymous = Math.max(0, anonymous - memory.selfAnonymousBytes().orElse(0));
        }
        if (anonymous > heapF + offHeapF) {
          status = status == Status.DISABLED ? status : Status.NOT_READY;
          reasons.add(
              "Fuseki holds "
                  + Sizes.format(anonymous)
                  + " of anonymous memory, more than H_f + O_f = "
                  + Sizes.format(heapF + offHeapF)
                  + ": its off-heap has outgrown O_f, so no worker is launched");
        }
      }
    }
    return new Readiness(
        status, required, limit.bytes(), limit.source(), terms, anonymous, List.copyOf(reasons));
  }

  public boolean admits() {
    return status == Status.READY;
  }

  public JsonObject toJson() {
    final JsonObject json = new JsonObject();
    json.put("status", status.name());
    json.put("requiredBytes", requiredBytes);
    json.put("availableBytes", availableBytes);
    json.put("limitSource", limitSource);
    final JsonObject termsJson = new JsonObject();
    terms.forEach(termsJson::put);
    json.put("terms", termsJson);
    if (anonymousBytes >= 0) {
      json.put("fusekiAnonymousBytes", anonymousBytes);
    }
    final JsonArray reasonsJson = new JsonArray();
    reasons.forEach(reasonsJson::add);
    json.put("reasons", reasonsJson);
    return json;
  }

  public String summary() {
    return status
        + ": requires "
        + Sizes.format(requiredBytes)
        + ", available "
        + Sizes.format(availableBytes)
        + " ("
        + limitSource
        + ")"
        + (reasons.isEmpty() ? "" : "; " + String.join("; ", reasons));
  }

  static long sizeOnDisk(final Path directory) {
    if (!Files.isDirectory(directory)) {
      return 0;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      return files
          .filter(Files::isRegularFile)
          .mapToLong(
              file -> {
                try {
                  return Files.size(file);
                } catch (final IOException e) {
                  return 0;
                }
              })
          .sum();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
