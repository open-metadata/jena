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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openmetadata.fuseki.reasoning.Sizes;

/**
 * How many reasoning jobs may wait, how long finished ones are kept, and how much disk they may
 * use, read with the rules the entrypoint applies (test/reasoning-settings.cases): an empty value
 * is unset, and an invalid one throws an IllegalArgumentException naming the variable.
 *
 * @param maxQueuedJobs OPENMETADATA_REASONING_MAX_QUEUED_JOBS: jobs waiting behind the running one
 * @param jobHistory OPENMETADATA_REASONING_JOB_HISTORY: finished jobs kept, and with them their
 *     request IDs for idempotency
 * @param retentionHours OPENMETADATA_REASONING_JOB_RETENTION_HOURS: how long a finished job is kept
 * @param diskQuotaBytes OPENMETADATA_REASONING_DISK_QUOTA as bytes, or -1 when a percentage
 * @param diskQuotaPercent the quota as a percentage of the data volume's capacity
 * @param maxJobSeconds OPENMETADATA_REASONING_MAX_JOB_SECONDS: how long one job may run, from its
 *     start to its last step's deadline
 * @param maxQuerySeconds OPENMETADATA_REASONING_MAX_QUERY_SECONDS: the deadline of one snapshot
 *     query, and so how long a query can keep a replaced snapshot on disk
 * @param maxResultRows OPENMETADATA_REASONING_MAX_RESULT_ROWS: rows one snapshot query or one page
 *     of a result returns
 * @param maxConcurrentQueries OPENMETADATA_REASONING_MAX_CONCURRENT_QUERIES: snapshot queries that
 *     may run at once; more get HTTP 429
 */
public record JobSettings(
    int maxQueuedJobs,
    int jobHistory,
    int retentionHours,
    long diskQuotaBytes,
    int diskQuotaPercent,
    int maxJobSeconds,
    int maxQuerySeconds,
    int maxResultRows,
    int maxConcurrentQueries) {

  public static final String MAX_QUEUED_JOBS = "OPENMETADATA_REASONING_MAX_QUEUED_JOBS";
  public static final String JOB_HISTORY = "OPENMETADATA_REASONING_JOB_HISTORY";
  public static final String RETENTION_HOURS = "OPENMETADATA_REASONING_JOB_RETENTION_HOURS";
  public static final String DISK_QUOTA = "OPENMETADATA_REASONING_DISK_QUOTA";
  public static final String MAX_JOB_SECONDS = "OPENMETADATA_REASONING_MAX_JOB_SECONDS";
  public static final String MAX_QUERY_SECONDS = "OPENMETADATA_REASONING_MAX_QUERY_SECONDS";
  public static final String MAX_RESULT_ROWS = "OPENMETADATA_REASONING_MAX_RESULT_ROWS";
  public static final String MAX_CONCURRENT_QUERIES =
      "OPENMETADATA_REASONING_MAX_CONCURRENT_QUERIES";

  public static final int DEFAULT_MAX_QUEUED_JOBS = 16;
  public static final int DEFAULT_JOB_HISTORY = 1000;
  public static final int DEFAULT_RETENTION_HOURS = 168;

  /**
   * An hour, until refresh times are measured: long enough for a catalog-sized closure, which
   * classifies in under a minute in the default heap, plus its capture and load.
   */
  public static final int DEFAULT_MAX_JOB_SECONDS = 3600;

  /**
   * A quarter of the volume, until refresh and snapshot sizes are measured: the serving datasets
   * and their blue/green alternates must keep the rest.
   */
  public static final int DEFAULT_DISK_QUOTA_PERCENT = 25;

  /**
   * Below OpenMetadata's 60-second client deadline, as the serving datasets' query timeout is, so
   * the store gives up first and says why.
   */
  public static final int DEFAULT_MAX_QUERY_SECONDS = 30;

  /** OpenMetadata's own page size for SPARQL answers. */
  public static final int DEFAULT_MAX_RESULT_ROWS = 10_000;

  /**
   * Snapshot queries run on request threads in the Fuseki JVM, each holding at most one bounded
   * answer, so a few at a time keep them from crowding out serving.
   */
  public static final int DEFAULT_MAX_CONCURRENT_QUERIES = 4;

  /** Queued and kept jobs are held in memory, a few KiB each, so their counts stay small. */
  private static final Pattern FOUR_DIGITS = Pattern.compile("[1-9][0-9]{0,3}");

  private static final Pattern COUNT = Pattern.compile("[1-9][0-9]{0,5}");
  private static final Pattern THREE_DIGITS = Pattern.compile("[1-9][0-9]{0,2}");
  private static final Pattern PERCENT = Pattern.compile("([1-9][0-9]?|100)%");

  public static JobSettings fromEnvironment(final Map<String, String> environment) {
    final int queue =
        integer(environment, MAX_QUEUED_JOBS, FOUR_DIGITS, 4, DEFAULT_MAX_QUEUED_JOBS);
    final int history = integer(environment, JOB_HISTORY, FOUR_DIGITS, 4, DEFAULT_JOB_HISTORY);
    final int hours = integer(environment, RETENTION_HOURS, COUNT, 6, DEFAULT_RETENTION_HOURS);
    final int jobSeconds = integer(environment, MAX_JOB_SECONDS, COUNT, 6, DEFAULT_MAX_JOB_SECONDS);
    final int querySeconds =
        integer(environment, MAX_QUERY_SECONDS, FOUR_DIGITS, 4, DEFAULT_MAX_QUERY_SECONDS);
    final int resultRows = integer(environment, MAX_RESULT_ROWS, COUNT, 6, DEFAULT_MAX_RESULT_ROWS);
    final int queries =
        integer(
            environment, MAX_CONCURRENT_QUERIES, THREE_DIGITS, 3, DEFAULT_MAX_CONCURRENT_QUERIES);
    long quotaBytes = -1;
    int quotaPercent = DEFAULT_DISK_QUOTA_PERCENT;
    final String quota = value(environment, DISK_QUOTA);
    if (quota != null) {
      if (PERCENT.matcher(quota).matches()) {
        quotaPercent = Integer.parseInt(quota.substring(0, quota.length() - 1));
      } else {
        try {
          quotaBytes = Sizes.parse(DISK_QUOTA, quota);
        } catch (final IllegalArgumentException e) {
          throw new IllegalArgumentException(
              DISK_QUOTA
                  + " must be a size such as 50g or a percentage of the data volume such as 25%,"
                  + " got '"
                  + quota
                  + "'");
        }
      }
    }
    return new JobSettings(
        queue,
        history,
        hours,
        quotaBytes,
        quotaPercent,
        jobSeconds,
        querySeconds,
        resultRows,
        queries);
  }

  public long retentionMillis() {
    return TimeUnit.HOURS.toMillis(retentionHours);
  }

  /** The quota in bytes, a percentage resolved against the capacity of the volume holding root. */
  public long diskQuota(final Path root) {
    if (diskQuotaBytes >= 0) {
      return diskQuotaBytes;
    }
    try {
      Path existing = root.toAbsolutePath();
      while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();
      }
      final long capacity = Files.getFileStore(existing).getTotalSpace();
      return capacity / 100 * diskQuotaPercent;
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int integer(
      final Map<String, String> environment,
      final String name,
      final Pattern pattern,
      final int digits,
      final int fallback) {
    final String value = value(environment, name);
    if (value == null) {
      return fallback;
    }
    if (!pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(
          name
              + " must be a positive integer of at most "
              + digits
              + " digits, got '"
              + value
              + "'");
    }
    return Integer.parseInt(value);
  }

  /** The entrypoint treats an empty variable as unset, and docker exec passes it through as is. */
  private static String value(final Map<String, String> environment, final String name) {
    final String value = environment.get(name);
    return value == null || value.isEmpty() ? null : value;
  }
}
