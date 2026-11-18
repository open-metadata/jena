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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Records come out in unsigned byte order, each exactly once, whether or not they fit one run. */
class RecordSorterTest {
  @TempDir Path directory;

  @Test
  void recordsAreSortedAsUnsignedBytesAcrossRunsOfAnySize() throws Exception {
    final int length = 5;
    final Random random = new Random(42);
    for (final int[] shape :
        new int[][] {{0, 4}, {1, 4}, {4, 4}, {5, 4}, {1_003, 10}, {1_000, 1}}) {
      final List<byte[]> records = new ArrayList<>();
      final ByteArrayOutputStream input = new ByteArrayOutputStream();
      for (int i = 0; i < shape[0]; i++) {
        final byte[] record = new byte[length];
        random.nextBytes(record);
        // A narrow first byte makes equal prefixes, and so comparisons past them, common.
        record[0] = (byte) (random.nextInt(3) * 127);
        records.add(record);
        input.writeBytes(record);
      }
      final Path file = directory.resolve("records");
      Files.write(file, input.toByteArray());
      final ByteArrayOutputStream sorted = new ByteArrayOutputStream();
      final Path scratch = directory.resolve("scratch");
      RecordSorter.sort(file, length, shape[1], scratch, sorted);
      records.sort(Arrays::compareUnsigned);
      final ByteArrayOutputStream expected = new ByteArrayOutputStream();
      records.forEach(expected::writeBytes);
      assertArrayEquals(expected.toByteArray(), sorted.toByteArray(), Arrays.toString(shape));
      assertFalse(Files.exists(scratch), "the runs are removed");
    }
  }

  @Test
  void aFileOfPartialRecordsIsRefused() throws Exception {
    final Path file = directory.resolve("records");
    Files.write(file, new byte[7]);
    final IOException e =
        assertThrows(
            IOException.class,
            () ->
                RecordSorter.sort(
                    file, 5, 4, directory.resolve("scratch"), new ByteArrayOutputStream()));
    assertEquals(true, e.getMessage().contains("whole records"));
  }
}
