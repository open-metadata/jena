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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The bytes a RULES step holds in its files outside the dataset at once: a pass's solutions and the
 * witnesses of every pass so far. Those files hold terms as text, so unlike the dataset they grow
 * with every repetition of a long literal; writing past the budget fails with {@link Full}.
 */
final class SpoolBudget {
  private final long maxBytes;
  private long bytes;

  SpoolBudget(final long maxBytes) {
    this.maxBytes = maxBytes;
  }

  /** The step's files reached their size limit. */
  static final class Full extends IOException {
    Full(final long maxBytes) {
      super(
          "The step's spooled solutions and witnesses exceed the limit of " + maxBytes + " bytes");
    }
  }

  /** Gives back the bytes of a file that was deleted or will be overwritten. */
  void release(final long count) {
    bytes -= count;
  }

  /** A stream whose bytes count against this budget; {@code out} should buffer. */
  Counting counting(final OutputStream out) {
    return new Counting(out);
  }

  final class Counting extends FilterOutputStream {
    private long written;

    private Counting(final OutputStream out) {
      super(out);
    }

    long written() {
      return written;
    }

    @Override
    public void write(final int b) throws IOException {
      charge(1);
      out.write(b);
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int length) throws IOException {
      charge(length);
      out.write(buffer, offset, length);
    }

    private void charge(final int count) throws Full {
      if (bytes + count > maxBytes) {
        throw new Full(maxBytes);
      }
      bytes += count;
      written += count;
    }
  }
}
