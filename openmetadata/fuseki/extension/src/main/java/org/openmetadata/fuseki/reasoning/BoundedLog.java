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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies a worker's output into its step log, keeping the first {@code head} bytes and the last
 * {@code tail}, so a worker that writes without end cannot fill the data volume. The end is kept
 * because that is where a worker's fatal error is; it waits in memory until the output ends.
 */
final class BoundedLog {
  private final Path file;
  private final int head;
  private final byte[] tail;
  private int tailEnd;
  private boolean tailWrapped;
  private long omitted;

  BoundedLog(final Path file, final int head, final int tail) {
    this.file = file;
    this.head = head;
    this.tail = new byte[tail];
  }

  /** Copies until the stream ends, then appends the kept tail. */
  void copy(final InputStream in) throws IOException {
    try (OutputStream out = Files.newOutputStream(file)) {
      final byte[] buffer = new byte[8192];
      long written = 0;
      for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
        int offset = 0;
        if (written < head) {
          final int direct = (int) Math.min(read, head - written);
          out.write(buffer, 0, direct);
          written += direct;
          offset = direct;
        }
        for (int i = offset; i < read; i++) {
          keep(buffer[i]);
        }
      }
      if (omitted > 0) {
        out.write(
            ("\n[" + omitted + " bytes of output omitted]\n").getBytes(StandardCharsets.UTF_8));
      }
      if (tailWrapped) {
        out.write(tail, tailEnd, tail.length - tailEnd);
      }
      out.write(tail, 0, tailEnd);
    }
  }

  private void keep(final byte value) {
    if (tail.length == 0) {
      omitted++;
      return;
    }
    if (tailWrapped) {
      omitted++;
    }
    tail[tailEnd] = value;
    tailEnd++;
    if (tailEnd == tail.length) {
      tailEnd = 0;
      tailWrapped = true;
    }
  }
}
