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
package org.openmetadata.reasoner.rules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Sorts a file of fixed-length records by their bytes, unsigned, holding one run of them in memory
 * at a time: runs are sorted and written to a scratch file, then merged. The heap it needs is one
 * run and a small buffer per run, whatever the file's size.
 */
final class RecordSorter {
  /** Records sorted in memory at once: 1.5 MB of 24-byte records. */
  static final int RUN_RECORDS = 1 << 16;

  /** Buffers of a merge, all runs together; a run's buffer is never under one page. */
  private static final int MERGE_BUFFER_BYTES = 2 << 20;

  private RecordSorter() {}

  /**
   * Writes the records of {@code input} to {@code out} in order.
   *
   * @param length bytes per record
   * @param runRecords records per sorted run
   * @param scratch a file to keep the runs in; deleted before returning
   */
  static void sort(
      final Path input,
      final int length,
      final int runRecords,
      final Path scratch,
      final OutputStream out)
      throws IOException {
    final long total = Files.size(input);
    if (total % length != 0) {
      throw new IOException(input + " does not hold whole records of " + length + " bytes");
    }
    final long records = total / length;
    final byte[] run = new byte[(int) Math.min(records, runRecords) * length];
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(input), 1 << 16))) {
      if (records <= runRecords) {
        in.readFully(run);
        writeSorted(run, run.length, length, out);
        return;
      }
      try {
        try (OutputStream runs =
            new BufferedOutputStream(Files.newOutputStream(scratch), 1 << 16)) {
          for (long done = 0; done < total; ) {
            final int size = (int) Math.min(run.length, total - done);
            in.readFully(run, 0, size);
            writeSorted(run, size, length, runs);
            done += size;
          }
        }
        merge(scratch, total, run.length, length, out);
      } finally {
        Files.deleteIfExists(scratch);
      }
    }
  }

  private static void writeSorted(
      final byte[] run, final int size, final int length, final OutputStream out)
      throws IOException {
    final Integer[] order = new Integer[size / length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i * length;
    }
    Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(run, a, a + length, run, b, b + length));
    for (final Integer offset : order) {
      out.write(run, offset, length);
    }
  }

  private static void merge(
      final Path runs,
      final long total,
      final int runBytes,
      final int length,
      final OutputStream out)
      throws IOException {
    final int count = (int) ((total + runBytes - 1) / runBytes);
    final int buffer = Math.max(4096 / length, MERGE_BUFFER_BYTES / count / length) * length;
    try (FileChannel channel = FileChannel.open(runs, StandardOpenOption.READ)) {
      final PriorityQueue<Cursor> heads =
          new PriorityQueue<>(
              count,
              (a, b) ->
                  Arrays.compareUnsigned(
                      a.bytes, a.at, a.at + length, b.bytes, b.at, b.at + length));
      for (int i = 0; i < count; i++) {
        final long from = (long) i * runBytes;
        final Cursor cursor = new Cursor(channel, from, Math.min(total, from + runBytes), buffer);
        if (cursor.advance(length)) {
          heads.add(cursor);
        }
      }
      while (!heads.isEmpty()) {
        final Cursor head = heads.poll();
        out.write(head.bytes, head.at, length);
        if (head.advance(length)) {
          heads.add(head);
        }
      }
    }
  }

  /** One run being merged: its current record is {@code bytes[at, at + length)}. */
  private static final class Cursor {
    private final FileChannel channel;
    private final long end;
    private final byte[] bytes;
    private long position;
    private int filled;
    private int at;

    Cursor(final FileChannel channel, final long from, final long end, final int buffer) {
      this.channel = channel;
      this.position = from;
      this.end = end;
      this.bytes = new byte[buffer];
      this.at = -1;
    }

    boolean advance(final int length) throws IOException {
      at = at < 0 ? 0 : at + length;
      if (at < filled) {
        return true;
      }
      if (position >= end) {
        return false;
      }
      filled = (int) Math.min(bytes.length, end - position);
      final ByteBuffer target = ByteBuffer.wrap(bytes, 0, filled);
      while (target.hasRemaining()) {
        if (channel.read(target, position + target.position()) < 0) {
          throw new EOFException("The sorted runs end before " + end);
        }
      }
      position += filled;
      at = 0;
      return true;
    }
  }
}
