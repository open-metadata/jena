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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

/**
 * The container's memory limit and current anonymous memory, read from cgroup v2, else cgroup v1,
 * else physical memory when no limit is set. Paths are resolved against a root, so tests can
 * provide the files.
 */
public final class ContainerMemory {
  /** cgroup v1 reports "no limit" as a page-aligned value near Long.MAX_VALUE. */
  private static final long UNLIMITED_THRESHOLD = 1L << 62;

  private final Path root;

  public ContainerMemory(final Path root) {
    this.root = root;
  }

  public static ContainerMemory system() {
    return new ContainerMemory(Path.of("/"));
  }

  /** The limit in bytes and where it came from. */
  public record Limit(long bytes, String source) {}

  public Limit limit() {
    final OptionalLong physical = meminfo("MemTotal:");
    final Path v2 = root.resolve("sys/fs/cgroup/memory.max");
    if (Files.isRegularFile(v2)) {
      final String value = read(v2).trim();
      if (!value.equals("max")) {
        return bounded(Long.parseLong(value), "cgroup v2 memory.max", physical);
      }
    } else {
      final Path v1 = root.resolve("sys/fs/cgroup/memory/memory.limit_in_bytes");
      if (Files.isRegularFile(v1)) {
        final long value = Long.parseLong(read(v1).trim());
        if (value < UNLIMITED_THRESHOLD) {
          return bounded(value, "cgroup v1 memory.limit_in_bytes", physical);
        }
      }
    }
    if (physical.isEmpty()) {
      throw new IllegalStateException(
          "Neither a cgroup memory limit nor /proc/meminfo is readable");
    }
    return new Limit(physical.getAsLong(), "physical memory (no container limit)");
  }

  /** The container's anonymous memory: cgroup v2 anon, or cgroup v1 total_rss; empty if unknown. */
  public OptionalLong anonymousBytes() {
    final Path v2 = root.resolve("sys/fs/cgroup/memory.stat");
    if (Files.isRegularFile(v2) && Files.isRegularFile(root.resolve("sys/fs/cgroup/memory.max"))) {
      return statField(v2, "anon");
    }
    final Path v1 = root.resolve("sys/fs/cgroup/memory/memory.stat");
    if (Files.isRegularFile(v1)) {
      final OptionalLong total = statField(v1, "total_rss");
      return total.isPresent() ? total : statField(v1, "rss");
    }
    return OptionalLong.empty();
  }

  /** This process's resident anonymous memory, from /proc/self/status; empty if unknown. */
  public OptionalLong selfAnonymousBytes() {
    final Path status = root.resolve("proc/self/status");
    if (!Files.isRegularFile(status)) {
      return OptionalLong.empty();
    }
    return kibField(status, "RssAnon:");
  }

  private OptionalLong meminfo(final String field) {
    final Path meminfo = root.resolve("proc/meminfo");
    return Files.isRegularFile(meminfo) ? kibField(meminfo, field) : OptionalLong.empty();
  }

  private static Limit bounded(final long limit, final String source, final OptionalLong physical) {
    if (physical.isPresent() && physical.getAsLong() < limit) {
      return new Limit(physical.getAsLong(), "physical memory (below the " + source + " limit)");
    }
    return new Limit(limit, source);
  }

  private static OptionalLong statField(final Path file, final String field) {
    for (final String line : lines(file)) {
      final String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && parts[0].equals(field)) {
        return OptionalLong.of(Long.parseLong(parts[1]));
      }
    }
    return OptionalLong.empty();
  }

  private static OptionalLong kibField(final Path file, final String field) {
    for (final String line : lines(file)) {
      if (line.startsWith(field)) {
        final String[] parts = line.substring(field.length()).trim().split("\\s+");
        return OptionalLong.of(Long.parseLong(parts[0]) * Sizes.KIB);
      }
    }
    return OptionalLong.empty();
  }

  private static List<String> lines(final Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  private static String read(final Path file) {
    return String.join("\n", lines(file));
  }
}
