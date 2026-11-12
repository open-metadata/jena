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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmetadata.fuseki.BoundedGraphStore;

class JobSettingsTest {
  @TempDir Path directory;

  @Test
  void defaultsMatchTheDocumentedStartingPolicy() {
    final JobSettings settings = JobSettings.fromEnvironment(Map.of());
    assertEquals(16, settings.maxQueuedJobs());
    assertEquals(1000, settings.jobHistory());
    assertEquals(168L * 3600 * 1000, settings.retentionMillis());
    assertEquals(-1, settings.diskQuotaBytes());
    assertEquals(25, settings.diskQuotaPercent());
    assertEquals(
        BoundedGraphStore.DEFAULT_MAX_BYTES,
        settings.maxUploadBytes(),
        "the same limit as a Graph Store upload");
    assertEquals(
        2L << 20,
        JobSettings.fromEnvironment(Map.of(JobSettings.MAX_UPLOAD_BYTES, "2m")).maxUploadBytes());
  }

  @Test
  void aPercentageQuotaIsAShareOfTheVolumeHoldingTheReasoningRoot() throws Exception {
    final long capacity = Files.getFileStore(directory).getTotalSpace();
    final Path missing = directory.resolve("reasoning/not/created/yet");
    assertEquals(
        capacity / 100 * 10,
        JobSettings.fromEnvironment(Map.of(JobSettings.DISK_QUOTA, "10%")).diskQuota(missing));
    assertEquals(
        3L << 30,
        JobSettings.fromEnvironment(Map.of(JobSettings.DISK_QUOTA, "3g")).diskQuota(missing));
  }
}
