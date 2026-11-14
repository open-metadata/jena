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
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.tdb2.params.StoreParams;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.openmetadata.fuseki.reasoning.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Published reasoning snapshots, one lineage per serving dataset, under {@code <reasoning
 * root>/snapshots/<dataset>/}: a directory per snapshot holding its compacted TDB2 database
 * ({@value #DATABASE}) and its metadata ({@value #METADATA}), and the pointer {@value #POINTER}
 * naming the current one with its generation.
 *
 * <p><b>Publication is atomic.</b> A refresh's working dataset is moved into a new snapshot
 * directory, opened, and only then made current by replacing the pointer with one atomic rename and
 * swapping the in-memory current snapshot under the store's lock: a reader leases either the old
 * snapshot or the new one, never part of either, and a failure before the rename leaves the old one
 * current and the new directory removed.
 *
 * <p><b>Compare-and-set.</b> A refresh records the generation that was current when it captured its
 * input. It publishes only if that generation is still current, and never over a snapshot of the
 * same dataset generation with a higher live-write watermark, or one published by a later job: an
 * older refresh can never replace a newer snapshot ({@link Superseded}).
 *
 * <p><b>Leases and retention.</b> A query leases the current snapshot for as long as it runs, which
 * its deadline bounds. A replaced snapshot is retired: it is closed and deleted when its last lease
 * ends, so a snapshot in use is never deleted, and at most {@value #MAX_RETIRED} retired snapshots
 * per dataset wait for their readers; a publication finding more waits for them up to the query
 * deadline. When the server starts, everything but each current snapshot is removed. Snapshots open
 * lazily, with TDB2's small store parameters, so their caches stay small in the Fuseki heap.
 */
public final class SnapshotStore implements AutoCloseable {
  static final String DIRECTORY = "snapshots";
  static final String POINTER = "current.json";
  static final String METADATA = "snapshot.json";
  static final String DATABASE = "tdb";
  static final int MAX_RETIRED = 4;

  private static final Logger LOG = LoggerFactory.getLogger("org.openmetadata.fuseki.Reasoning");

  private final Path root;
  private final long leaseMillis;
  private final Object lock = new Object();

  /** One lineage per serving dataset name, which the HTTP layer checks is served. */
  private final Map<String, Lineage> lineages = new HashMap<>();

  private boolean closed;

  /** What a refresh asks to publish. */
  public record Candidate(
      UUID jobId,
      long jobSequence,
      String datasetGeneration,
      long liveWriteWatermark,
      JsonObject input,
      JsonObject detail) {}

  /** A publication the compare-and-set refused: a newer snapshot is, or became, current. */
  public static final class Superseded extends Exception {
    Superseded(final String message) {
      super(message);
    }
  }

  /** A published snapshot. */
  static final class Snapshot {
    final String dataset;
    final UUID id;
    final long generation;
    final Path directory;
    final JsonObject metadata;
    private DatasetGraph database;
    private int leases;
    private boolean retired;

    Snapshot(
        final String dataset,
        final UUID id,
        final long generation,
        final Path directory,
        final JsonObject metadata) {
      this.dataset = dataset;
      this.id = id;
      this.generation = generation;
      this.directory = directory;
      this.metadata = metadata;
    }

    String datasetGeneration() {
      return metadata.getObj("input").getObj("sourceRevision").getString("datasetGeneration");
    }

    long watermark() {
      return metadata
          .getObj("input")
          .getObj("sourceRevision")
          .getNumber("liveWriteWatermark")
          .longValue();
    }

    long jobSequence() {
      return metadata.getObj("store").getNumber("jobSequence").longValue();
    }

    /** The snapshot's public description, OpenMetadata's rdfReasoningSnapshot.json. */
    JsonObject description() {
      final JsonObject json = new JsonObject();
      json.put("id", metadata.getString("id"));
      json.put("jobId", metadata.getString("jobId"));
      json.put("input", JSON.copy(metadata.getObj("input")));
      json.put("publishedAt", metadata.get("publishedAt"));
      return json;
    }
  }

  /** The current and retired snapshots of one serving dataset. */
  private static final class Lineage {
    private Snapshot current;
    private final List<Snapshot> retired = new ArrayList<>();
  }

  /**
   * A reader's hold on a snapshot: the snapshot is not deleted until it is closed. Every read goes
   * through {@link #view()} inside a read transaction the lease's holder begins and ends.
   */
  public final class Lease implements AutoCloseable {
    private final Snapshot snapshot;
    private final DatasetGraph view;
    private boolean released;

    private Lease(final Snapshot snapshot) {
      this.snapshot = snapshot;
      this.view =
          new SnapshotView(snapshot.database, NodeFactory.createURI(RefreshCapture.PROVENANCE));
    }

    /** The snapshot as a query may read it: without its provenance graph. */
    public DatasetGraph view() {
      return view;
    }

    /** The whole snapshot, provenance included, for explanations only. */
    DatasetGraph database() {
      return snapshot.database;
    }

    public UUID id() {
      return snapshot.id;
    }

    public long generation() {
      return snapshot.generation;
    }

    public JsonObject description() {
      return snapshot.description();
    }

    JsonObject metadata() {
      return snapshot.metadata;
    }

    @Override
    public void close() {
      synchronized (lock) {
        if (released) {
          return;
        }
        released = true;
        snapshot.leases--;
        if (snapshot.retired && snapshot.leases == 0) {
          dispose(snapshot);
          lineages.get(snapshot.dataset).retired.remove(snapshot);
          lock.notifyAll();
        }
      }
    }
  }

  /**
   * @param reasoningRoot the reasoning root; snapshots live in its {@value #DIRECTORY}
   * @param leaseMillis how long a lease can last: the query deadline and a margin
   */
  public SnapshotStore(final Path reasoningRoot, final long leaseMillis) {
    this.root = reasoningRoot.resolve(DIRECTORY);
    this.leaseMillis = leaseMillis;
  }

  /**
   * Reads every dataset's pointer and removes what is not a current snapshot: retired snapshots a
   * stopped server could not delete, and directories a publication did not finish.
   */
  public void open() throws IOException {
    Files.createDirectories(root);
    synchronized (lock) {
      try (DirectoryStream<Path> datasets = Files.newDirectoryStream(root)) {
        for (final Path directory : datasets) {
          if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            continue;
          }
          final String dataset = directory.getFileName().toString();
          final Snapshot current = readPointer(dataset, directory);
          try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (final Path entry : entries) {
              final boolean keep =
                  entry.getFileName().toString().equals(POINTER)
                      || (current != null && entry.equals(current.directory));
              if (!keep) {
                LOG.info("Removing reasoning snapshot data {} that is not current", entry);
                deleteTree(entry);
              }
            }
          }
          if (current != null) {
            lineages.computeIfAbsent(dataset, name -> new Lineage()).current = current;
          }
        }
      }
    }
  }

  /** The current snapshot's generation for a dataset, 0 when it has none. */
  public long generation(final String dataset) {
    synchronized (lock) {
      final Lineage lineage = lineages.get(dataset);
      return lineage == null || lineage.current == null ? 0 : lineage.current.generation;
    }
  }

  /** The current snapshot's metadata, for a refresh deciding early whether it is already older. */
  Optional<JsonObject> currentMetadata(final String dataset) {
    synchronized (lock) {
      final Lineage lineage = lineages.get(dataset);
      return lineage == null || lineage.current == null
          ? Optional.empty()
          : Optional.of(JSON.copy(lineage.current.metadata).getAsObject());
    }
  }

  /** Leases the current snapshot of a dataset; empty when none has been published. */
  public Optional<Lease> lease(final String dataset) {
    synchronized (lock) {
      if (closed) {
        return Optional.empty();
      }
      final Lineage lineage = lineages.get(dataset);
      if (lineage == null || lineage.current == null) {
        return Optional.empty();
      }
      final Snapshot snapshot = lineage.current;
      if (snapshot.database == null) {
        snapshot.database = connect(snapshot.directory.resolve(DATABASE));
      }
      snapshot.leases++;
      return Optional.of(new Lease(snapshot));
    }
  }

  /**
   * Publishes a refresh's compacted working dataset as the dataset's new current snapshot, if
   * {@code expectedGeneration} is still current and nothing newer has published.
   *
   * @param working the working dataset's directory; it is moved, never copied
   * @return the published snapshot's description
   */
  public JsonObject publish(
      final String dataset,
      final long expectedGeneration,
      final Candidate candidate,
      final Path working,
      final long now)
      throws Superseded, IOException, InterruptedException {
    synchronized (lock) {
      final Lineage lineage = lineages.computeIfAbsent(dataset, name -> new Lineage());
      check(lineage.current, expectedGeneration, candidate);
      final long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseMillis);
      while (lineage.retired.size() >= MAX_RETIRED && System.nanoTime() - waitUntil < 0) {
        lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(waitUntil - System.nanoTime())));
      }
      if (lineage.retired.size() >= MAX_RETIRED) {
        throw new IOException(
            MAX_RETIRED + " replaced snapshots of '" + dataset + "' are still being read");
      }
      // Waiting released the lock, so another publication may have run meanwhile.
      check(lineage.current, expectedGeneration, candidate);

      final UUID id = UUID.randomUUID();
      final long generation = expectedGeneration + 1;
      final Path directory = Files.createDirectories(root.resolve(dataset)).resolve(id.toString());
      final JsonObject metadata = metadata(id, generation, candidate, now);
      DatasetGraph database = null;
      boolean published = false;
      try {
        Files.createDirectories(directory);
        Files.move(working, directory.resolve(DATABASE), StandardCopyOption.ATOMIC_MOVE);
        database = connect(directory.resolve(DATABASE));
        database.begin(TxnType.READ);
        database.end();
        AtomicFiles.write(directory.resolve(METADATA), JSON.toString(metadata));
        final JsonObject pointer = new JsonObject();
        pointer.put("generation", generation);
        pointer.put("snapshotId", id.toString());
        AtomicFiles.write(root.resolve(dataset).resolve(POINTER), JSON.toString(pointer));
        published = true;
      } finally {
        if (!published) {
          if (database != null) {
            TDBInternal.expel(database);
          }
          deleteTree(directory);
        }
      }
      final Snapshot snapshot = new Snapshot(dataset, id, generation, directory, metadata);
      snapshot.database = database;
      final Snapshot previous = lineage.current;
      lineage.current = snapshot;
      if (previous != null) {
        previous.retired = true;
        if (previous.leases == 0) {
          dispose(previous);
        } else {
          lineage.retired.add(previous);
        }
      }
      LOG.info(
          "Reasoning snapshot {} of '{}' published as generation {}{}",
          id,
          dataset,
          generation,
          previous == null ? "" : ", replacing " + previous.id);
      return snapshot.description();
    }
  }

  /** Retired snapshots of a dataset still held by a reader. */
  int retired(final String dataset) {
    synchronized (lock) {
      final Lineage lineage = lineages.get(dataset);
      return lineage == null ? 0 : lineage.retired.size();
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      for (final Lineage lineage : lineages.values()) {
        if (lineage.current != null && lineage.current.database != null) {
          TDBInternal.expel(lineage.current.database);
          lineage.current.database = null;
        }
        for (final Snapshot snapshot : lineage.retired) {
          if (snapshot.database != null) {
            TDBInternal.expel(snapshot.database);
            snapshot.database = null;
          }
        }
      }
    }
  }

  /** The compare-and-set: refuses a candidate that is not newer than what is current. */
  static void check(final Snapshot current, final long expectedGeneration, final Candidate c)
      throws Superseded {
    final long generation = current == null ? 0 : current.generation;
    if (generation != expectedGeneration) {
      throw new Superseded(
          "Generation "
              + generation
              + " was published after this refresh captured its input at generation "
              + expectedGeneration);
    }
    if (current == null) {
      return;
    }
    if (current.datasetGeneration().equals(c.datasetGeneration())
        && current.watermark() > c.liveWriteWatermark()) {
      throw new Superseded(
          "The current snapshot "
              + current.id
              + " covers live writes up to "
              + current.watermark()
              + ", later than this refresh's "
              + c.liveWriteWatermark());
    }
    if (current.jobSequence() > c.jobSequence()) {
      throw new Superseded(
          "The current snapshot " + current.id + " was published by a later refresh");
    }
  }

  private static JsonObject metadata(
      final UUID id, final long generation, final Candidate candidate, final long now) {
    final JsonObject metadata = new JsonObject();
    metadata.put("id", id.toString());
    metadata.put("jobId", candidate.jobId().toString());
    metadata.put("input", JSON.copy(candidate.input()));
    metadata.put("publishedAt", now);
    final JsonObject store = JSON.copy(candidate.detail()).getAsObject();
    store.put("generation", generation);
    store.put("jobSequence", candidate.jobSequence());
    metadata.put("store", store);
    return metadata;
  }

  private Snapshot readPointer(final String dataset, final Path directory) {
    final Path pointerFile = directory.resolve(POINTER);
    if (!Files.isRegularFile(pointerFile)) {
      return null;
    }
    try {
      final JsonObject pointer = JSON.read(pointerFile.toString());
      final UUID id = UUID.fromString(pointer.getString("snapshotId"));
      final Path snapshot = directory.resolve(id.toString());
      final JsonObject metadata = JSON.read(snapshot.resolve(METADATA).toString());
      if (!Files.isDirectory(snapshot.resolve(DATABASE))) {
        throw new IOException("its database is missing");
      }
      return new Snapshot(
          dataset, id, pointer.getNumber("generation").longValue(), snapshot, metadata);
    } catch (final IOException | RuntimeException e) {
      LOG.warn("Ignoring the reasoning snapshot pointer of '{}': {}", dataset, e.toString());
      return null;
    }
  }

  private static DatasetGraph connect(final Path location) {
    return DatabaseMgr.connectDatasetGraph(
        Location.create(location), StoreParams.getSmallStoreParams());
  }

  /** Closes and deletes a snapshot nobody reads any more. Under the lock. */
  private void dispose(final Snapshot snapshot) {
    if (snapshot.database != null) {
      TDBInternal.expel(snapshot.database);
      snapshot.database = null;
    }
    try {
      deleteTree(snapshot.directory);
      LOG.info("Reasoning snapshot {} of '{}' removed", snapshot.id, snapshot.dataset);
    } catch (final IOException e) {
      LOG.warn("Cannot remove reasoning snapshot {}: {}", snapshot.directory, e.toString());
    }
  }

  static void deleteTree(final Path tree) throws IOException {
    if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        tree,
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
