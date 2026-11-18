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
package org.openmetadata.reasoner.store;

import java.util.Iterator;
import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.dboe.base.file.BinaryDataFile;
import org.apache.jena.dboe.base.record.Record;
import org.apache.jena.dboe.trans.bplustree.BPlusTree;
import org.apache.jena.dboe.trans.bplustree.rewriter.BPlusTreeRewriter;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.store.DatasetGraphTDB;
import org.apache.jena.tdb2.store.nodetable.NodeTableTRDF;
import org.apache.jena.tdb2.store.tupletable.TupleIndex;
import org.apache.jena.tdb2.store.tupletable.TupleIndexRecord;
import org.apache.jena.tdb2.sys.CopyDSG;
import org.apache.jena.tdb2.sys.TDBInternal;

/**
 * Copies a TDB2 dataset into an empty one without re-inserting its quads. An index's records come
 * out of its B+tree already in that index's order, so each tree of the copy is built bottom-up from
 * full blocks, the way TDB2's bulk loader builds one from sorted input; inserting quad by quad
 * instead descends six trees per quad and leaves their blocks half full. The node table's data file
 * is copied byte for byte: a node's id is its offset in that file, so the copy keeps every id and
 * the index records are valid in it unchanged.
 *
 * <p>Each index of the copy comes from the same index of the source, where a quad-by-quad copy
 * would rebuild them all from one. So the copy refuses a source whose indexes do not hold the same
 * number of records: such a dataset answers a query differently by the index that serves it, and
 * the copy would too.
 */
final class PackedCopy {
  private static final int COPY_BUFFER_BYTES = 1 << 20;

  private PackedCopy() {}

  /**
   * @param source a TDB2 dataset; read in one transaction
   * @param target a TDB2 dataset with the same store parameters that nothing was ever written to
   */
  static void copy(final DatasetGraph source, final DatasetGraph target) {
    Txn.executeRead(
        source,
        () ->
            Txn.executeWrite(
                target,
                () -> {
                  final DatasetGraphTDB from = TDBInternal.getDatasetGraphTDB(source);
                  final DatasetGraphTDB to = TDBInternal.getDatasetGraphTDB(target);
                  nodes(nodeTable(from), nodeTable(to));
                  indexes(
                      from.getTripleTable().getNodeTupleTable().getTupleTable().getIndexes(),
                      target);
                  indexes(
                      from.getQuadTable().getNodeTupleTable().getTupleTable().getIndexes(), target);
                  CopyDSG.copyPrefixes(source, target);
                }));
  }

  /** Triples and quads share one node table. */
  private static NodeTableTRDF nodeTable(final DatasetGraphTDB dataset) {
    return (NodeTableTRDF)
        dataset.getQuadTable().getNodeTupleTable().getNodeTable().baseNodeTable();
  }

  private static void nodes(final NodeTableTRDF from, final NodeTableTRDF to) {
    final BinaryDataFile data = from.getData();
    final BinaryDataFile copy = to.getData();
    if (copy.length() != 0) {
      throw new IllegalStateException("The dataset to copy into already holds nodes");
    }
    final long length = data.length();
    final byte[] buffer = new byte[COPY_BUFFER_BYTES];
    for (long position = 0; position < length; ) {
      final int count = (int) Math.min(buffer.length, length - position);
      if (data.read(position, buffer, 0, count) != count) {
        throw new IllegalStateException("The node file ends before its length of " + length);
      }
      copy.write(buffer, 0, count);
      position += count;
    }
    pack((BPlusTree) from.getIndex(), (BPlusTree) to.getIndex());
  }

  private static void indexes(final TupleIndex[] from, final DatasetGraph target) {
    long expected = -1;
    for (final TupleIndex index : from) {
      final long records = pack(tree(index), tree(TDBInternal.findIndex(target, index.getName())));
      if (expected >= 0 && records != expected) {
        throw new IllegalStateException(
            "The dataset's indexes disagree: "
                + from[0].getName()
                + " holds "
                + expected
                + " records and "
                + index.getName()
                + " "
                + records);
      }
      expected = records;
    }
  }

  private static BPlusTree tree(final TupleIndex index) {
    return (BPlusTree) ((TupleIndexRecord) index).getRangeIndex();
  }

  /**
   * Builds {@code to}, which must be empty, from the records of {@code from}. The rewriter writes
   * through the empty tree's own block managers and puts the root where an empty tree has it, so
   * the state the surrounding transaction commits for {@code to} describes the packed tree.
   *
   * @return the records copied
   */
  private static long pack(final BPlusTree from, final BPlusTree to) {
    if (!to.isEmpty()) {
      throw new IllegalStateException("The dataset to copy into already holds records");
    }
    final Iterator<Record> records = from.iterator();
    if (!records.hasNext()) {
      return 0;
    }
    final long[] copied = {0};
    BPlusTreeRewriter.packIntoBPlusTree(
        Iter.map(
            records,
            record -> {
              copied[0]++;
              return record;
            }),
        to.getParams(),
        to.getRecordFactory(),
        to.getStateManager().getBufferChannel(),
        to.getNodeManager().getBlockMgr(),
        to.getRecordsMgr().getBlockMgr());
    return copied[0];
  }
}
