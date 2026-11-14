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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            () -> inputs.put(hex, new ByteArrayInputStream(bytes), ROOMY, 1_000, () -> 1_001, NOW));
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

  @Test
  void anUploadThatCannotFitIsRefusedByItsDeclaredLengthBeforeItsBodyIsRead() throws Exception {
    final InputStream unreadable =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("the body was read");
          }
        };
    final JobService.Refused refused =
        assertThrows(
            JobService.Refused.class,
            () -> inputs.put("a".repeat(64), unreadable, 600, ROOMY, 1_000, () -> 500, NOW));
    assertEquals(503, refused.status());
    assertEquals("maxDiskBytes", refused.problem().subject());
    assertEquals(List.of(), names(), "no temporary file was created");

    final JobService.Refused full =
        assertThrows(
            JobService.Refused.class,
            () -> inputs.put("a".repeat(64), unreadable, -1, ROOMY, 1_000, () -> 1_000, NOW));
    assertEquals(503, full.status());
  }

  @Test
  void uploadsBeyondTheConcurrencyCapAreAnsweredBusyUntilOneFinishes() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch reading = new CountDownLatch(JobInputs.MAX_CONCURRENT_UPLOADS);
    final ExecutorService pool = Executors.newCachedThreadPool();
    try {
      final List<Future<JobInputs.Stored>> running = new ArrayList<>();
      for (int i = 0; i < JobInputs.MAX_CONCURRENT_UPLOADS; i++) {
        final byte[] bytes = bytes("upload " + i);
        final InputStream blocked =
            new InputStream() {
              private final ByteArrayInputStream rest = new ByteArrayInputStream(bytes);

              @Override
              public int read() throws IOException {
                return rest.read();
              }

              @Override
              public int read(final byte[] buffer, final int offset, final int length)
                  throws IOException {
                reading.countDown();
                try {
                  release.await();
                } catch (final InterruptedException e) {
                  throw new IOException(e);
                }
                return rest.read(buffer, offset, length);
              }
            };
        running.add(
            pool.submit(
                () -> inputs.put(Releases.hash(bytes), blocked, ROOMY, ROOMY, () -> 0, NOW)));
      }
      assertTrue(reading.await(10, TimeUnit.SECONDS));
      final JobService.Refused busy =
          assertThrows(
              JobService.Refused.class,
              () -> put(Releases.hash(bytes("one too many")), bytes("one too many")));
      assertEquals(429, busy.status());
      assertEquals(Problem.Code.LIMIT_EXCEEDED, busy.problem().code());
      assertEquals("maxConcurrentUploads", busy.problem().subject());

      release.countDown();
      for (final Future<JobInputs.Stored> upload : running) {
        assertTrue(upload.get(10, TimeUnit.SECONDS).created());
      }
      assertTrue(
          put(Releases.hash(bytes("one too many")), bytes("one too many")).created(),
          "a slot is free again");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sweepingIsNotBlockedWhileAnUploadMeasuresTheDisk() throws Exception {
    final CountDownLatch measuring = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      final byte[] bytes = bytes("slow to measure");
      final AtomicInteger calls = new AtomicInteger();
      final Future<JobInputs.Stored> upload =
          pool.submit(
              () ->
                  inputs.put(
                      Releases.hash(bytes),
                      new ByteArrayInputStream(bytes),
                      ROOMY,
                      ROOMY,
                      () -> {
                        if (calls.incrementAndGet() == 2) {
                          measuring.countDown();
                          await(finish);
                        }
                        return 0;
                      },
                      NOW));
      assertTrue(measuring.await(10, TimeUnit.SECONDS));
      inputs.sweep(Set.of(), NOW, false);
      finish.countDown();
      assertTrue(upload.get(10, TimeUnit.SECONDS).created());
    } finally {
      finish.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentUploadsNeverLeaveCommittedStateOverTheQuota() throws Exception {
    final byte[] first = new byte[1_000];
    final byte[] second = new byte[1_000];
    second[0] = 1;
    final long quota = 1_500;
    final CountDownLatch secondMeasuring = new CountDownLatch(1);
    final CountDownLatch firstCommitted = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      final AtomicInteger firstCalls = new AtomicInteger();
      final Future<JobInputs.Stored> a =
          pool.submit(
              () -> {
                final JobInputs.Stored stored =
                    inputs.put(
                        Releases.hash(first),
                        new ByteArrayInputStream(first),
                        ROOMY,
                        quota,
                        () -> {
                          if (firstCalls.incrementAndGet() == 2) {
                            await(secondMeasuring);
                          }
                          return 0;
                        },
                        NOW);
                firstCommitted.countDown();
                return stored;
              });
      final AtomicInteger secondCalls = new AtomicInteger();
      final Future<JobInputs.Stored> b =
          pool.submit(
              () ->
                  inputs.put(
                      Releases.hash(second),
                      new ByteArrayInputStream(second),
                      ROOMY,
                      quota,
                      () -> {
                        if (secondCalls.incrementAndGet() == 2) {
                          secondMeasuring.countDown();
                          await(firstCommitted);
                        }
                        return 0;
                      },
                      NOW));
      assertTrue(a.get(10, TimeUnit.SECONDS).created());
      final ExecutionException refused =
          assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS));
      assertEquals(503, ((JobService.Refused) refused.getCause()).status());
      assertEquals(List.of(Releases.hash(first)), names());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void publishedBytesAreCountedExactlyAcrossSweepsAndReopening() throws Exception {
    final byte[] first = new byte[600];
    final byte[] second = new byte[600];
    second[0] = 1;
    final long quota = 1_000;
    assertTrue(
        inputs
            .put(Releases.hash(first), new ByteArrayInputStream(first), ROOMY, quota, () -> 0, NOW)
            .created());
    final JobService.Refused refused =
        assertThrows(
            JobService.Refused.class,
            () ->
                inputs.put(
                    Releases.hash(second),
                    new ByteArrayInputStream(second),
                    ROOMY,
                    quota,
                    () -> 0,
                    NOW));
    assertEquals(503, refused.status());

    final JobInputs reopened = new JobInputs(root);
    assertThrows(
        JobService.Refused.class,
        () ->
            reopened.put(
                Releases.hash(second),
                new ByteArrayInputStream(second),
                ROOMY,
                quota,
                () -> 0,
                NOW),
        "the bytes held before the restart still count");

    reopened.sweep(Set.of(), NOW + 2 * JobInputs.HOLD_MILLIS, false);
    assertTrue(
        reopened
            .put(
                Releases.hash(second), new ByteArrayInputStream(second), ROOMY, quota, () -> 0, NOW)
            .created(),
        "a swept upload frees its bytes");
  }

  @Test
  void aHeldInputIsReportedByItsSizeAndKeptAliveByAskingAfterIt() throws Exception {
    final byte[] bytes = bytes("held for a while");
    final String hex = Releases.hash(bytes);
    assertTrue(inputs.hold(hex, NOW).isEmpty());
    put(hex, bytes);

    assertEquals(bytes.length, inputs.hold(hex, NOW + JobInputs.HOLD_MILLIS - 1).getAsLong());
    inputs.sweep(Set.of(), NOW + JobInputs.HOLD_MILLIS + 1, false);
    assertEquals(List.of(hex), names(), "asking restarted the hold");

    inputs.sweep(Set.of(), NOW + 3 * JobInputs.HOLD_MILLIS, false);
    assertTrue(inputs.hold(hex, NOW + 3 * JobInputs.HOLD_MILLIS).isEmpty());
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

  private static void await(final CountDownLatch latch) throws IOException {
    try {
      latch.await();
    } catch (final InterruptedException e) {
      throw new IOException(e);
    }
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
