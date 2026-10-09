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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Memory sizes in the JVM's -Xmx notation: bytes, or a number with a k, m, g or t suffix. */
public final class Sizes {
  public static final long KIB = 1024;
  public static final long MIB = 1024 * KIB;
  public static final long GIB = 1024 * MIB;
  private static final Pattern SIZE = Pattern.compile("([1-9][0-9]{0,15})([kKmMgGtT]?)");

  private Sizes() {}

  /** Parses a size; throws IllegalArgumentException naming {@code name} when it is invalid. */
  public static long parse(final String name, final String value) {
    final Matcher matcher = SIZE.matcher(value == null ? "" : value.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          name + " must be a size such as 512m or 2g, got '" + value + "'");
    }
    final long number = Long.parseLong(matcher.group(1));
    final long unit =
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
          case "k" -> KIB;
          case "m" -> MIB;
          case "g" -> GIB;
          case "t" -> 1024 * GIB;
          default -> 1;
        };
    if (number > Long.MAX_VALUE / unit) {
      throw new IllegalArgumentException(name + " is too large: '" + value + "'");
    }
    return number * unit;
  }

  /** Formats a byte count for people, with its exact value: {@code 2.0 GiB (2147483648)}. */
  public static String format(final long bytes) {
    if (bytes >= GIB) {
      return String.format(Locale.ROOT, "%.1f GiB (%d)", bytes / (double) GIB, bytes);
    }
    return String.format(Locale.ROOT, "%.0f MiB (%d)", bytes / (double) MIB, bytes);
  }

  /** The value of the last -Xmx flag in a JVM argument string, or -1 when there is none. */
  public static long lastMaxHeap(final String jvmArgs) {
    long result = -1;
    if (jvmArgs == null) {
      return result;
    }
    for (final String argument : jvmArgs.trim().split("\\s+")) {
      if (argument.startsWith("-Xmx")) {
        result = parse("-Xmx in JVM_ARGS", argument.substring("-Xmx".length()));
      }
    }
    return result;
  }
}
