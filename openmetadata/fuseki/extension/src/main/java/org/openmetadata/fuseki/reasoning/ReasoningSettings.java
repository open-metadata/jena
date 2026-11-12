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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The reasoning settings, read from the same environment variables the entrypoint validates and
 * with the same rules, so the Fuseki module and the command line in {@code docker exec} agree with
 * the entrypoint: an empty value is unset, and test/reasoning-settings.cases holds the values both
 * are tested against. Invalid values throw an IllegalArgumentException naming the variable.
 *
 * @param enabled OPENMETADATA_REASONING_ENABLED
 * @param workerHeap H_w, OPENMETADATA_REASONER_HEAP: the worker's -Xms and -Xmx
 * @param workerOffHeap O_w, OPENMETADATA_REASONER_OFF_HEAP: at least the worker's capped regions
 * @param pageCacheFloorBytes C_min as bytes, or -1 when given as a percentage
 * @param pageCacheFloorPercent C_min as a percentage of the serving datasets' size on disk
 * @param cpus OPENMETADATA_REASONER_CPUS: the worker's -XX:ActiveProcessorCount
 * @param fusekiHeap H_f from FUSEKI_HEAP or the last -Xmx in JVM_ARGS; -1 when JVM_ARGS sets none,
 *     and the JVM then defaults to a quarter of the container limit
 * @param fusekiDirectMemory Fuseki's direct-buffer limit: the last -XX:MaxDirectMemorySize in
 *     JVM_ARGS, else the {@link #DEFAULT_FUSEKI_DIRECT_MEMORY} the entrypoint appends; 0 when it is
 *     the JVM's default, the maximum heap
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
    long fusekiDirectMemory,
    Path fusekiBase,
    Path fusekiHome) {

  public static final String ENABLED = "OPENMETADATA_REASONING_ENABLED";
  public static final String HEAP = "OPENMETADATA_REASONER_HEAP";
  public static final String OFF_HEAP = "OPENMETADATA_REASONER_OFF_HEAP";
  public static final String PAGE_CACHE_FLOOR = "OPENMETADATA_REASONER_PAGE_CACHE_FLOOR";
  public static final String CPUS = "OPENMETADATA_REASONER_CPUS";

  public static final long DEFAULT_WORKER_HEAP = 2 * Sizes.GIB;

  /**
   * The direct-buffer limit the entrypoint gives Fuseki unless JVM_ARGS sets one. Fuseki allocates
   * about 5 MiB of direct buffers per HTTP request and frees them only when a collection finds
   * their owners, so under the JVM's default limit (the maximum heap) an idle server with a large
   * heap held over 1 GiB of them; at the limit the JVM collects instead.
   */
  public static final long DEFAULT_FUSEKI_DIRECT_MEMORY = 256 * Sizes.MIB;

  public static final long MIN_WORKER_HEAP = 16 * Sizes.MIB;

  /**
   * The worker's capped regions (metaspace 256 MiB, code cache 128 MiB, direct buffers 64 MiB) plus
   * 64 MiB for thread stacks, GC structures and the VM itself. O_w may be raised, never set below
   * this, or the budget would undercount what the launch flags allow.
   */
  public static final long MIN_WORKER_OFF_HEAP = 512 * Sizes.MIB;

  public static final int DEFAULT_PAGE_CACHE_FLOOR_PERCENT = 50;
  private static final Pattern PERCENT = Pattern.compile("(0|[1-9][0-9]?|100)%");
  private static final Pattern CPU_COUNT = Pattern.compile("[1-9][0-9]{0,5}");

  public static ReasoningSettings fromEnvironment(final Map<String, String> variables) {
    // The entrypoint treats an empty variable as unset, and docker exec passes it through as is.
    final Map<String, String> environment = new HashMap<>();
    variables.forEach(
        (name, value) -> {
          if (value != null && !value.isEmpty()) {
            environment.put(name, value);
          }
        });
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
      if (!CPU_COUNT.matcher(value).matches()) {
        throw new IllegalArgumentException(
            CPUS + " must be a positive integer of at most 6 digits, got '" + value + "'");
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
    final long direct = Sizes.lastMaxDirectMemory(jvmArgs);
    return new ReasoningSettings(
        enabledValue.equals("true"),
        heap,
        offHeap,
        floorBytes,
        floorPercent,
        cpus,
        fusekiHeap,
        direct >= 0 ? direct : DEFAULT_FUSEKI_DIRECT_MEMORY,
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
   * O_f, Fuseki's off-heap budget: its direct-buffer limit plus 768 MiB for metaspace, code cache,
   * thread stacks, GC structures and the VM, or 1.25 GiB from an 8 GiB heap, whose GC structures
   * grow with it. Native Memory Tracking measured at most 291 MiB of the latter on a 4 GiB heap
   * while OpenMetadata rebuilt and queried a 20,000-table catalog. With the default 256 MiB direct
   * limit that is 1 GiB, or 1.5 GiB from an 8 GiB heap.
   *
   * @param fusekiDirectMemory the direct-buffer limit, or 0 when it is the JVM's default, the heap
   */
  public static long fusekiOffHeap(final long fusekiHeap, final long fusekiDirectMemory) {
    final long rest = fusekiHeap >= 8 * Sizes.GIB ? 5 * Sizes.GIB / 4 : 3 * Sizes.GIB / 4;
    return rest + (fusekiDirectMemory > 0 ? fusekiDirectMemory : fusekiHeap);
  }
}
