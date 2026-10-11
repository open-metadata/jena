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
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.openmetadata.fuseki.reasoning.AtomicFiles;
import org.openmetadata.fuseki.reasoning.WorkerLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job directories under {@code <reasoning root>/jobs/<job id>/}: the request as received, the
 * manifest {@code job.json}, replaced atomically on every change, and whatever the job's steps
 * write. Directories without a manifest, such as steps run by hand with bin/reasoner, are never
 * read or removed.
 */
final class JobStore {
  static final String MANIFEST = "job.json";
  static final String REQUEST = "request.json";
  private static final Logger LOG = LoggerFactory.getLogger(JobStore.class);
  private static final long WORKER_EXIT_WAIT_MILLIS = 10_000;

  private final Path jobs;

  JobStore(final Path reasoningRoot) {
    this.jobs = reasoningRoot.resolve("jobs");
  }

  Path directory(final UUID id) {
    return jobs.resolve(id.toString());
  }

  void create(final Job job, final JobRequest request) throws IOException {
    final Path directory = Files.createDirectories(directory(job.id()));
    AtomicFiles.write(directory.resolve(REQUEST), JSON.toString(request.document()));
    save(job);
  }

  void save(final Job job) throws IOException {
    AtomicFiles.write(directory(job.id()).resolve(MANIFEST), JSON.toString(job.toManifest()));
  }

  JsonObject request(final UUID id) throws IOException {
    try (InputStream in = Files.newInputStream(directory(id).resolve(REQUEST))) {
      return JSON.parse(in);
    }
  }

  /** Every job with a readable manifest; one that cannot be read is logged and left alone. */
  List<Job> load() throws IOException {
    final List<Job> loaded = new ArrayList<>();
    if (!Files.isDirectory(jobs)) {
      return loaded;
    }
    try (DirectoryStream<Path> directories = Files.newDirectoryStream(jobs)) {
      for (final Path directory : directories) {
        final Path manifest = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest) || !isJobId(directory.getFileName().toString())) {
          continue;
        }
        try {
          loaded.add(Job.fromManifest(JSON.read(manifest.toString())));
        } catch (final RuntimeException e) {
          LOG.warn("Ignoring reasoning job {}: unreadable manifest: {}", directory, e.toString());
        }
      }
    }
    return loaded;
  }

  void delete(final UUID id) throws IOException {
    deleteTree(directory(id));
  }

  /**
   * Removes the work a job did not publish: working datasets (its subdirectories), captured input
   * and half-written files. The request, manifest, step files, results and logs stay for diagnosis
   * until the job leaves the history.
   */
  void discardWork(final UUID id) throws IOException {
    final Path directory = directory(id);
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (final Path entry : entries) {
        final String name = entry.getFileName().toString();
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          deleteTree(entry);
        } else if (name.endsWith(".tmp") || name.endsWith(".nq.gz")) {
          Files.deleteIfExists(entry);
        }
      }
    }
  }

  /**
   * Kills any worker a job's directory records as running: after a restart of the server none of
   * them can still belong to a live step. A pid is trusted only while its process runs the worker
   * on a step of this directory, so a reused pid is never killed.
   */
  void killRecordedWorkers(final UUID id) throws IOException {
    final Path directory = directory(id);
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (DirectoryStream<Path> pidFiles = Files.newDirectoryStream(directory, "*.pid")) {
      for (final Path pidFile : pidFiles) {
        final Optional<ProcessHandle> worker = recordedWorker(pidFile, directory);
        worker.ifPresent(
            process -> {
              LOG.warn("Killing reasoner worker {} left by job {}", process.pid(), id);
              process.destroy();
              if (!awaitExit(process, WORKER_EXIT_WAIT_MILLIS)) {
                process.destroyForcibly();
                awaitExit(process, WORKER_EXIT_WAIT_MILLIS);
              }
            });
        Files.deleteIfExists(pidFile);
      }
    }
  }

  /** Bytes under the reasoning root, which every job, working dataset and snapshot shares. */
  static long sizeOnDisk(final Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    final long[] total = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
            total[0] += attributes.size();
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(final Path file, final IOException e) {
            // A file removed while walking, such as a finished step's temporary file.
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }

  static Optional<ProcessHandle> recordedWorker(final Path pidFile, final Path directory) {
    final long pid;
    try {
      pid = Long.parseLong(Files.readString(pidFile).trim());
    } catch (final IOException | NumberFormatException e) {
      return Optional.empty();
    }
    return ProcessHandle.of(pid)
        .filter(
            process ->
                process
                    .info()
                    .commandLine()
                    .map(
                        line ->
                            line.contains(WorkerLauncher.WORKER_MAIN)
                                && line.contains(directory.toString()))
                    .orElse(false));
  }

  private static boolean awaitExit(final ProcessHandle process, final long millis) {
    try {
      process.onExit().get(millis, TimeUnit.MILLISECONDS);
      return true;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return !process.isAlive();
    } catch (final Exception e) {
      return !process.isAlive();
    }
  }

  private static boolean isJobId(final String name) {
    try {
      return UUID.fromString(name).toString().equals(name);
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static void deleteTree(final Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(final Path directory, final IOException e)
              throws IOException {
            if (e != null) {
              throw e;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
