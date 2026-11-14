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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobInputsTest {
  private static final long NOW = 1_791_497_100_000L;
  private static final long ROOMY = 1L << 40;

  @TempDir Path root;
  private JobInputs inputs;

  @BeforeEach
  void open() {
    inputs = new JobInputs(root);
  }

  @Test
  void bodyIsHeldUnderItsDigestAndTheSameBytesAgainChangeNothing() throws Exception {
    final byte[] bytes = bytes("release one");
    final String hex = Releases.hash(bytes);

    final JobInputs.Stored first = put(hex, bytes);
    assertTrue(first.created());
    assertEquals(bytes.length, first.bytes());
    assertArrayEquals(bytes, Files.readAllBytes(held(hex)));

    final JobInputs.Stored again = put(hex, bytes);
    assertFalse(again.created(), "re-uploading held bytes is an idempotent success");
    assertEquals(bytes.length, again.bytes());
    assertEquals(List.of(hex), names(), "no second copy and no temporary file");
  }

  @Test
  void aBodyThatDoesNotHashToThePathIsRefusedAndLeavesNothing() throws Exception {
    final String hex = Releases.hash(bytes("what the path says"));
    final JobService.Refused refused =
        assertThrows(JobService.Refused.class, () -> put(hex, bytes("what was sent")));
    assertEquals(400, refused.status());
    assertEquals(Problem.Code.INVALID_REQUEST, refused.problem().code());
    assertTrue(refused.problem().message().contains(hex), refused.problem().message());
    assertEquals(List.of(), names());
  }

  @Test
  void aBodyOverTheLimitIsRefusedWhileItIsReadAndLeavesNothing() throws Exception {
    final byte[] bytes = bytes("0123456789");
    final String hex = Releases.hash(bytes);
    final JobService.Refused refused =
        assertThrows(
            JobService.Refused.class,
            () -> inputs.put(hex, new ByteArrayInputStream(bytes), 9, ROOMY, () -> 0, NOW));
    assertEquals(413, refused.status());
    assertEquals(Problem.Code.LIMIT_EXCEEDED, refused.problem().code());
    assertEquals("maxUploadBytes", refused.problem().subject());
    assertEquals(List.of(), names());
    assertTrue(inputs.put(hex, new ByteArrayInputStream(bytes), 10, ROOMY, () -> 0, NOW).created());
  }

  @Test
  void anEmptyBodyIsRefused() {
    final String hex = Releases.hash(new byte[0]);
    final JobService.Refused refused =
        assertThrows(JobService.Refused.class, () -> put(hex, new byte[0]));
    assertEquals(400, refused.status());
  }

  @Test
  void anUploadThatWouldPutTheReasoningDataOverItsQuotaIsRefusedUnlessHeld() throws Exception {
    final byte[] bytes = bytes("fits the limit, not the quota");
    final String hex = Releases.hash(bytes);
    final JobService.Refused refused =
        assertThrows(
            JobService.Refused.class,
            () ->
                inputs.put(
                    hex,
                    new ByteArrayInputStream(bytes),
                    ROOMY,
                    1_000,
                    () -> JobStore.sizeOnDisk(root) + 1_001,
                    NOW));
    assertEquals(503, refused.status());
    assertEquals(Problem.Code.LIMIT_EXCEEDED, refused.problem().code());
    assertEquals("maxDiskBytes", refused.problem().subject());
    assertEquals(List.of(), names());

    assertTrue(put(hex, bytes).created());
    final JobInputs.Stored again =
        inputs.put(hex, new ByteArrayInputStream(bytes), ROOMY, 1, () -> ROOMY, NOW);
    assertFalse(again.created(), "bytes already held cost no quota");
  }

  @Test
  void uploadsNoJobNamesAreRemovedAfterTheHoldAndAReuploadRestartsIt() throws Exception {
    final byte[] bytes = bytes("kept for an hour");
    final String hex = Releases.hash(bytes);
    put(hex, bytes);

    inputs.sweep(Set.of(), NOW + JobInputs.HOLD_MILLIS - 1, false);
    assertEquals(List.of(hex), names(), "still within the hold");

    put(hex, bytes, NOW + JobInputs.HOLD_MILLIS - 1);
    inputs.sweep(Set.of(), NOW + JobInputs.HOLD_MILLIS + 1, false);
    assertEquals(List.of(hex), names(), "the re-upload restarted the hold");

    inputs.sweep(Set.of(), NOW + 2 * JobInputs.HOLD_MILLIS, false);
    assertEquals(List.of(), names());
  }

  @Test
  void anUploadAJobStillNamesIsKeptHoweverOldItIs() throws Exception {
    final byte[] bytes = bytes("read by a queued job");
    final String hex = Releases.hash(bytes);
    put(hex, bytes);
    inputs.sweep(Set.of(hex), NOW + 100 * JobInputs.HOLD_MILLIS, false);
    assertEquals(List.of(hex), names());
    inputs.sweep(Set.of(), NOW + 100 * JobInputs.HOLD_MILLIS, false);
    assertEquals(List.of(), names());
  }

  @Test
  void startingUpRemovesHalfWrittenUploadsAndOnlyOldOnesOtherwise() throws Exception {
    final Path directory = Files.createDirectories(root.resolve(JobInputs.DIRECTORY));
    final Path half = Files.writeString(directory.resolve("upload-1.tmp"), "half");
    final Path stranger = Files.writeString(directory.resolve("notes.txt"), "not ours");
    Files.setLastModifiedTime(stranger, FileTime.fromMillis(0));
    final byte[] bytes = bytes("fresh");
    put(Releases.hash(bytes), bytes);

    inputs.sweep(Set.of(), NOW, false);
    assertTrue(Files.exists(half), "a recent temporary file may be an upload in flight");
    inputs.sweep(Set.of(), NOW, true);
    assertFalse(Files.exists(half), "no upload is in flight at startup");
    assertTrue(Files.exists(stranger), "files that are not uploads are never removed");
    assertEquals(2, names().size());
  }

  private JobInputs.Stored put(final String hex, final byte[] bytes) throws Exception {
    return put(hex, bytes, NOW);
  }

  private JobInputs.Stored put(final String hex, final byte[] bytes, final long now)
      throws Exception {
    return inputs.put(hex, new ByteArrayInputStream(bytes), ROOMY, ROOMY, () -> 0, now);
  }

  private Path held(final String hex) {
    return root.resolve(JobInputs.DIRECTORY).resolve(hex);
  }

  private List<String> names() throws IOException {
    final Path directory = root.resolve(JobInputs.DIRECTORY);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(file -> file.getFileName().toString()).sorted().toList();
    }
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
