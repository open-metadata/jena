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
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job inputs that OpenMetadata uploads before it submits the job that reads them, under {@code
 * <reasoning root>/inputs/<64 hex digits>}. The name is the SHA-256 of the bytes, so the same bytes
 * are stored once, a second upload of them changes nothing, and a job that names a digest can check
 * what it reads. Uploads live under the reasoning root, so they count against its disk quota with
 * everything else.
 *
 * <p>An upload is kept while a queued or running job names it. Once none does it is kept for {@link
 * #HOLD_MILLIS} from its last upload, so a client can submit a second job over the same bytes
 * without sending them again, and then it is removed. Uploads that no job ever named leave the same
 * way.
 */
public final class JobInputs {
  static final String DIRECTORY = "inputs";
  static final String TEMPORARY = ".tmp";

  /** Long enough to submit a job after the upload and to retry once; short enough to bound disk. */
  static final long HOLD_MILLIS = TimeUnit.HOURS.toMillis(1);

  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
  private static final int BUFFER_BYTES = 64 * 1024;

  private final Path directory;
  private final Object commit = new Object();

  /** What an upload left behind. */
  public record Stored(boolean created, long bytes) {}

  JobInputs(final Path reasoningRoot) {
    this.directory = reasoningRoot.resolve(DIRECTORY);
  }

  static boolean isHex(final String text) {
    return HEX.matcher(text).matches();
  }

  /** Where the upload with this {@code sha256:<hex>} digest is held, whether or not it is. */
  static Path path(final Path reasoningRoot, final String digest) {
    return reasoningRoot.resolve(DIRECTORY).resolve(digest.substring("sha256:".length()));
  }

  /**
   * Reads {@code body} to its end, refusing more than {@code maxBytes}, and holds it under {@code
   * hex} if it hashes to it. A body already held is received and checked like any other and then
   * dropped, so a retry costs the client its bytes but never the store its quota.
   *
   * @param quotaBytes what the reasoning root may use in all; an upload that would leave it above
   *     that is refused and removed
   * @param usedBytes measures what the reasoning root uses now, temporary files included
   */
  Stored put(
      final String hex,
      final InputStream body,
      final long maxBytes,
      final long quotaBytes,
      final UsedBytes usedBytes,
      final long now)
      throws JobService.Refused, IOException {
    Files.createDirectories(directory);
    final Path target = directory.resolve(hex);
    final Path temporary = Files.createTempFile(directory, "upload-", TEMPORARY);
    boolean published = false;
    try {
      final String actual = receive(body, temporary, maxBytes);
      if (!actual.equals(hex)) {
        throw new JobService.Refused(
            400,
            Problem.of(
                Problem.Code.INVALID_REQUEST,
                "The body hashes to sha256:"
                    + actual
                    + ", not the sha256:"
                    + hex
                    + " in the path"));
      }
      synchronized (commit) {
        if (Files.isRegularFile(target)) {
          Files.setLastModifiedTime(target, FileTime.fromMillis(now));
          return new Stored(false, Files.size(target));
        }
        final long used = usedBytes.get();
        if (used > quotaBytes) {
          throw new JobService.Refused(
              503,
              Problem.of(
                      Problem.Code.LIMIT_EXCEEDED,
                      "Holding this upload would bring the reasoning data to "
                          + used
                          + " bytes, over its quota of "
                          + quotaBytes
                          + " bytes")
                  .withSubject("maxDiskBytes"));
        }
        Files.setLastModifiedTime(temporary, FileTime.fromMillis(now));
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        published = true;
        return new Stored(true, Files.size(target));
      }
    } finally {
      if (!published) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /** Measures the bytes the reasoning root uses. */
  @FunctionalInterface
  interface UsedBytes {
    long get() throws IOException;
  }

  /**
   * Removes uploads no job in {@code inUse} names that were last uploaded before the hold, and
   * half-written files that old. At startup no upload is being received, so {@code startup} also
   * removes every half-written file.
   */
  void sweep(final Set<String> inUse, final long now, final boolean startup) {
    if (!Files.isDirectory(directory)) {
      return;
    }
    synchronized (commit) {
      sweepLocked(inUse, now, startup);
    }
  }

  private void sweepLocked(final Set<String> inUse, final long now, final boolean startup) {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
      for (final Path file : files) {
        final String name = file.getFileName().toString();
        final boolean temporary = name.endsWith(TEMPORARY);
        if (!temporary && (!isHex(name) || inUse.contains(name))) {
          continue;
        }
        final long modified = Files.getLastModifiedTime(file).toMillis();
        if ((temporary && startup) || modified < now - HOLD_MILLIS) {
          Files.deleteIfExists(file);
        }
      }
    } catch (final IOException e) {
      LOG.warn("Cannot sweep the reasoning inputs: {}", e.toString());
    }
  }

  /** The hex SHA-256 of the body, which is copied to {@code into}. */
  private static String receive(final InputStream body, final Path into, final long maxBytes)
      throws JobService.Refused, IOException {
    final MessageDigest sha256 = sha256();
    final byte[] buffer = new byte[BUFFER_BYTES];
    long total = 0;
    try (OutputStream out = Files.newOutputStream(into)) {
      int read;
      while ((read = body.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new JobService.Refused(
              413,
              Problem.of(Problem.Code.LIMIT_EXCEEDED, "An upload is at most " + maxBytes + " bytes")
                  .withSubject("maxUploadBytes"));
        }
        sha256.update(buffer, 0, read);
        out.write(buffer, 0, read);
      }
    }
    if (total == 0) {
      throw new JobService.Refused(
          400, Problem.of(Problem.Code.INVALID_REQUEST, "An upload has no bytes"));
    }
    return HexFormat.of().formatHex(sha256.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
