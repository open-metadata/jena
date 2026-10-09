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
package org.openmetadata.reasoner;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.jena.atlas.json.JsonObject;

/**
 * The step's own deadline. When it passes, the watchdog interrupts the engine that is running, if
 * the step registered one, and gives it a short grace period to unwind; a step that is not
 * interruptible, or does not unwind in time, is ended by {@code onExpiry}, which records the
 * outcome and halts the JVM. The launcher's kill deadline is the backstop for a JVM too wedged to
 * run this thread. The watchdog also samples heap use, so every manifest reports the step's peak.
 */
public final class Watchdog implements AutoCloseable {
  static final long SAMPLE_MILLIS = 50;
  static final long UNWIND_GRACE_MILLIS = 5_000;

  private final long deadlineNanos;
  private final Consumer<Watchdog> onExpiry;
  private final AtomicReference<Runnable> interrupter = new AtomicReference<>();
  private final List<MemoryPoolMXBean> heapPools =
      ManagementFactory.getMemoryPoolMXBeans().stream()
          .filter(pool -> pool.getType() == MemoryType.HEAP)
          .toList();
  private final Thread thread;
  private volatile boolean finished;
  private volatile long expiredAtNanos;
  private volatile boolean expired;
  private volatile long peakUsed;
  private volatile long peakAfterGc;

  public Watchdog(final long timeoutMillis, final Consumer<Watchdog> onExpiry) {
    this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    this.onExpiry = onExpiry;
    this.thread = new Thread(this::watch, "reasoner-watchdog");
    thread.setDaemon(true);
    thread.start();
  }

  /** Registers how to interrupt the work now running; {@code null} when nothing can be. */
  public void guard(final Runnable interrupt) {
    interrupter.set(interrupt);
    if (expired && interrupt != null) {
      interrupt.run();
    }
  }

  public boolean expired() {
    return expired;
  }

  /** Milliseconds from the deadline to now, the latency of an interrupted step's exit. */
  public long millisSinceExpiry() {
    return expired ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - expiredAtNanos) : 0;
  }

  public void finish() {
    finished = true;
    thread.interrupt();
    sample();
  }

  @Override
  public void close() {
    finish();
  }

  public JsonObject workerJson() {
    final JsonObject json = new JsonObject();
    json.put("javaVersion", Runtime.version().toString());
    json.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    json.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
    json.put("heapPeakUsedBytes", peakUsed);
    json.put("heapPeakAfterGcBytes", peakAfterGc);
    return json;
  }

  private void watch() {
    long unwindDeadline = Long.MAX_VALUE;
    while (!finished) {
      sample();
      final long now = System.nanoTime();
      if (!expired && now - deadlineNanos >= 0) {
        expiredAtNanos = now;
        expired = true;
        final Runnable interrupt = interrupter.get();
        if (interrupt == null) {
          unwindDeadline = now;
        } else {
          unwindDeadline = now + TimeUnit.MILLISECONDS.toNanos(UNWIND_GRACE_MILLIS);
          try {
            interrupt.run();
          } catch (final RuntimeException e) {
            unwindDeadline = now;
          }
        }
      }
      if (expired && now - unwindDeadline >= 0) {
        if (!finished) {
          onExpiry.accept(this);
        }
        return;
      }
      try {
        Thread.sleep(SAMPLE_MILLIS);
      } catch (final InterruptedException e) {
        // Only finish() interrupts this thread; the loop condition ends it.
      }
    }
  }

  private void sample() {
    long used = 0;
    long afterGc = 0;
    for (final MemoryPoolMXBean pool : heapPools) {
      used += pool.getUsage().getUsed();
      final MemoryUsage collection = pool.getCollectionUsage();
      if (collection != null) {
        afterGc += collection.getUsed();
      }
    }
    if (used > peakUsed) {
      peakUsed = used;
    }
    if (afterGc > peakAfterGc) {
      peakAfterGc = afterGc;
    }
  }
}
