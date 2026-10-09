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

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The reasoning settings, read from the same environment variables the entrypoint validates, so the
 * Fuseki module and the command line in {@code docker exec} agree. Invalid values throw an
 * IllegalArgumentException naming the variable.
 *
 * @param enabled OPENMETADATA_REASONING_ENABLED
 * @param workerHeap H_w, OPENMETADATA_REASONER_HEAP: the worker's -Xms and -Xmx
 * @param workerOffHeap O_w, OPENMETADATA_REASONER_OFF_HEAP: at least the worker's capped regions
 * @param pageCacheFloorBytes C_min as bytes, or -1 when given as a percentage
 * @param pageCacheFloorPercent C_min as a percentage of the serving datasets' size on disk
 * @param cpus OPENMETADATA_REASONER_CPUS: the worker's -XX:ActiveProcessorCount
 * @param fusekiHeap H_f from FUSEKI_HEAP or the last -Xmx in JVM_ARGS; -1 when JVM_ARGS sets none,
 *     and the JVM then defaults to a quarter of the container limit
 * @param fusekiBase FUSEKI_BASE, the data volume
 * @param fusekiHome FUSEKI_HOME, the read-only distribution in the image
 */
public record ReasoningSettings(
    boolean enabled,
    long workerHeap,
    long workerOffHeap,
    long pageCacheFloorBytes,
    int pageCacheFloorPercent,
    int cpus,
    long fusekiHeap,
    Path fusekiBase,
    Path fusekiHome) {

  public static final String ENABLED = "OPENMETADATA_REASONING_ENABLED";
  public static final String HEAP = "OPENMETADATA_REASONER_HEAP";
  public static final String OFF_HEAP = "OPENMETADATA_REASONER_OFF_HEAP";
  public static final String PAGE_CACHE_FLOOR = "OPENMETADATA_REASONER_PAGE_CACHE_FLOOR";
  public static final String CPUS = "OPENMETADATA_REASONER_CPUS";

  public static final long DEFAULT_WORKER_HEAP = 2 * Sizes.GIB;
  public static final long MIN_WORKER_HEAP = 16 * Sizes.MIB;

  /**
   * The worker's capped regions (metaspace 256 MiB, code cache 128 MiB, direct buffers 64 MiB) plus
   * 64 MiB for thread stacks, GC structures and the VM itself. O_w may be raised, never set below
   * this, or the budget would undercount what the launch flags allow.
   */
  public static final long MIN_WORKER_OFF_HEAP = 512 * Sizes.MIB;

  public static final int DEFAULT_PAGE_CACHE_FLOOR_PERCENT = 50;
  private static final Pattern PERCENT = Pattern.compile("(0|[1-9][0-9]?|100)%");
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]{0,5}");

  public static ReasoningSettings fromEnvironment(final Map<String, String> environment) {
    final String enabledValue = environment.getOrDefault(ENABLED, "false");
    if (!enabledValue.equals("true") && !enabledValue.equals("false")) {
      throw new IllegalArgumentException(
          ENABLED + " must be true or false, got '" + enabledValue + "'");
    }
    final long heap =
        environment.containsKey(HEAP)
            ? Sizes.parse(HEAP, environment.get(HEAP))
            : DEFAULT_WORKER_HEAP;
    if (heap < MIN_WORKER_HEAP) {
      throw new IllegalArgumentException(
          HEAP + " must be at least 16m, got '" + environment.get(HEAP) + "'");
    }
    final long offHeap =
        environment.containsKey(OFF_HEAP)
            ? Sizes.parse(OFF_HEAP, environment.get(OFF_HEAP))
            : MIN_WORKER_OFF_HEAP;
    if (offHeap < MIN_WORKER_OFF_HEAP) {
      throw new IllegalArgumentException(
          OFF_HEAP
              + " must be at least 512m, the worker's capped metaspace, code cache and direct"
              + " memory plus stacks and GC; got '"
              + environment.get(OFF_HEAP)
              + "'");
    }
    long floorBytes = -1;
    int floorPercent = DEFAULT_PAGE_CACHE_FLOOR_PERCENT;
    final String floor = environment.get(PAGE_CACHE_FLOOR);
    if (floor != null) {
      if (PERCENT.matcher(floor).matches()) {
        floorPercent = Integer.parseInt(floor.substring(0, floor.length() - 1));
      } else if (floor.equals("0")) {
        floorBytes = 0;
      } else {
        try {
          floorBytes = Sizes.parse(PAGE_CACHE_FLOOR, floor);
        } catch (final IllegalArgumentException e) {
          throw new IllegalArgumentException(
              PAGE_CACHE_FLOOR
                  + " must be a size such as 2g or a percentage such as 50%, got '"
                  + floor
                  + "'");
        }
      }
    }
    final int cpus;
    if (environment.containsKey(CPUS)) {
      final String value = environment.get(CPUS);
      if (!POSITIVE_INTEGER.matcher(value).matches()) {
        throw new IllegalArgumentException(
            CPUS + " must be a positive integer, got '" + value + "'");
      }
      cpus = Integer.parseInt(value);
    } else {
      cpus = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
    final long fusekiHeap;
    final String jvmArgs = environment.get("JVM_ARGS");
    if (jvmArgs != null && !jvmArgs.isBlank()) {
      fusekiHeap = Sizes.lastMaxHeap(jvmArgs);
    } else {
      fusekiHeap = Sizes.parse("FUSEKI_HEAP", environment.getOrDefault("FUSEKI_HEAP", "4g"));
    }
    return new ReasoningSettings(
        enabledValue.equals("true"),
        heap,
        offHeap,
        floorBytes,
        floorPercent,
        cpus,
        fusekiHeap,
        Path.of(environment.getOrDefault("FUSEKI_BASE", "/fuseki")),
        Path.of(environment.getOrDefault("FUSEKI_HOME", "/jena-fuseki")));
  }

  /** Job directories, exports and working datasets: on the data volume, never on a tmpfs. */
  public Path reasoningRoot() {
    return fusekiBase.resolve("reasoning");
  }

  public Path servingDatabases() {
    return fusekiBase.resolve("databases");
  }

  /** The worker classpath: fuseki-server.jar first, then the worker jars, all in the image. */
  public String workerClasspath() {
    return fusekiHome.resolve("fuseki-server.jar") + ":" + fusekiHome.resolve("reasoner") + "/*";
  }

  /**
   * O_f, Fuseki's off-heap budget: 1 GiB, or 1.5 GiB with an 8 GiB heap, until Native Memory
   * Tracking measures it.
   */
  public static long fusekiOffHeap(final long fusekiHeap) {
    return fusekiHeap >= 8 * Sizes.GIB ? 3 * Sizes.GIB / 2 : Sizes.GIB;
  }
}
