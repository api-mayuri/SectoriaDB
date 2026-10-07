package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.repository.GcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link GcRepository} over the metastore. Every freeing operation takes the single writer slot with
 * {@link MetaStore#beginWrite()} (not a group-commit body: it must exclude commits for its whole duration, and the
 * slot free it performs through the callback is a side effect that a savepoint could not undo).
 */
@Repository
public class MetaStoreGcRepository implements GcRepository {

    private static final Logger log = LoggerFactory.getLogger(MetaStoreGcRepository.class);

    private final MetaStoreProvider stores;

    public MetaStoreGcRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    // ------------------------------------------------------------------ chunk_gc

    @Override
    public List<ChunkRef> dueChunks(String poolId, long queuedBeforeMillis, int limit) {
        return store().read(tx -> {
            List<ChunkRef> out = new ArrayList<>();
            String curPool = null;
            long curKey = 0;
            long newest = Long.MIN_VALUE;
            boolean open = false;
            try (Cursor c = poolId == null ? tx.tree(Chunks.CHUNK_GC).scan()
                    : tx.tree(Chunks.CHUNK_GC).scanPrefix(Keys.of(poolId))) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String pool = r.string();
                    long key = r.longUnsigned();
                    long queuedAt = ByteBuffer.wrap(c.value()).getLong();
                    if (open && pool.equals(curPool) && key == curKey) {
                        newest = Math.max(newest, queuedAt);
                        continue;
                    }
                    if (open && newest <= queuedBeforeMillis) {
                        out.add(new ChunkRef(curPool, curKey));
                        if (out.size() >= limit) return out;
                    }
                    open = true;
                    curPool = pool;
                    curKey = key;
                    newest = queuedAt;
                }
            }
            if (open && newest <= queuedBeforeMillis && out.size() < limit) out.add(new ChunkRef(curPool, curKey));
            return out;
        });
    }

    private record GcRows(List<byte[]> keys, long newest) {
    }

    private static GcRows gcRows(WriteTxn tx, String poolId, long chunkKey) {
        List<byte[]> keys = new ArrayList<>();
        long newest = Long.MIN_VALUE;
        byte[] prefix = Keys.builder().string(poolId).longUnsigned(chunkKey).build();
        try (Cursor c = tx.tree(Chunks.CHUNK_GC).scanPrefix(prefix)) {
            while (c.next()) {
                keys.add(c.key());
                newest = Math.max(newest, ByteBuffer.wrap(c.value()).getLong());
            }
        }
        return new GcRows(keys, newest);
    }

    private static void dropRows(WriteTxn tx, GcRows rows) {
        BTree gc = tx.tree(Chunks.CHUNK_GC);
        for (byte[] k : rows.keys()) gc.delete(k);
    }

    @Override
    public ChunkBatchResult collectChunks(List<ChunkRef> batch, long queuedBeforeMillis, SlotFreer freer) {
        int freed = 0, absent = 0, revived = 0, gone = 0, tooNew = 0, deferred = 0;
        long bytes = 0;
        List<ChunkRef> removed = new ArrayList<>();
        IOException error = null;
        try (WriteTxn tx = store().beginWrite()) {
            boolean changed = false;
            BTree chunks = tx.tree(Chunks.CHUNKS);
            BTree byBlob = tx.tree(Chunks.CHUNKS_BY_BLOB);
            for (ChunkRef ref : batch) {
                GcRows rows = gcRows(tx, ref.poolId(), ref.chunkKey());
                if (rows.keys().isEmpty()) continue;                       // collected by an earlier step of this very batch
                Optional<ChunkEntry> found = Chunks.get(tx, ref.poolId(), ref.chunkKey());
                if (found.isEmpty()) {
                    dropRows(tx, rows);
                    gone++;
                    changed = true;
                    continue;
                }
                ChunkEntry e = found.get();
                if (e.refcount() != 0) {
                    dropRows(tx, rows);
                    revived++;
                    changed = true;
                    continue;
                }
                if (rows.newest() > queuedBeforeMillis) {
                    tooNew++;
                    continue;
                }
                SlotFree r;
                try {
                    r = freer.free(ref.poolId(), e.blobId(), ref.chunkKey());
                } catch (IOException io) {
                    error = io;                                           // chunk untouched; commit what was done before it
                    break;
                }
                if (r.result() == SlotFree.Result.DEFERRED) {
                    deferred++;
                    continue;
                }
                chunks.delete(Chunks.key(ref.poolId(), ref.chunkKey()));
                byBlob.delete(Keys.builder().string(e.blobId()).longUnsigned(ref.chunkKey()).build());
                dropRows(tx, rows);
                if (r.result() == SlotFree.Result.FREED) {
                    freed++;
                    bytes += r.bytes();
                } else {
                    absent++;
                }
                removed.add(ref);
                changed = true;
            }
            if (changed) tx.commit();
        }
        return new ChunkBatchResult(freed, bytes, absent, revived, gone, tooNew, deferred, removed, error);
    }

    // ------------------------------------------------------------------ strays and orphans

    @Override
    public long[] unindexed(String blobId, long[] keys) {
        return store().read(tx -> {
            Optional<BlobFileEntity> blob = Trees.blob(tx, blobId, false);
            if (blob.isEmpty()) return new long[0];
            String poolId = blob.get().getPoolId();
            long[] out = new long[keys.length];
            int n = 0;
            BTree chunks = tx.tree(Chunks.CHUNKS);
            for (long k : keys) {
                Optional<byte[]> v = chunks.get(Chunks.key(poolId, k));
                if (v.isEmpty() || !ChunkCodec.decode(v.get()).blobId().equals(blobId)) out[n++] = k;
            }
            return java.util.Arrays.copyOf(out, n);
        });
    }

    @Override
    public List<ChunkRepository.Orphan> orphans(int limit) {
        return store().read(tx -> {
            List<ChunkRepository.Orphan> out = new ArrayList<>();
            try (Cursor c = tx.tree(Chunks.CHUNK_ORPHANS).scan()) {
                while (out.size() < limit && c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String blob = r.string();
                    out.add(new ChunkRepository.Orphan(blob, r.longUnsigned(), ByteBuffer.wrap(c.value()).getLong()));
                }
            }
            return out;
        });
    }

    @Override
    public StrayBatchResult freeStrays(String blobId, long[] keys, SlotFreer freer) {
        int freed = 0, notStray = 0, deferred = 0;
        long bytes = 0;
        IOException error = null;
        try (WriteTxn tx = store().beginWrite()) {
            boolean changed = false;
            BTree orphans = tx.tree(Chunks.CHUNK_ORPHANS);
            Optional<BlobFileEntity> blob = Trees.blob(tx, blobId, false);
            if (blob.isEmpty()) {
                // the blob is gone (deleted or replaced): its orphan records went with it or are meaningless
                for (long k : keys) changed |= orphans.delete(Keys.builder().string(blobId).longUnsigned(k).build());
                if (changed) tx.commit();
                return new StrayBatchResult(0, 0, 0, 0, null);
            }
            String poolId = blob.get().getPoolId();
            for (long k : keys) {
                byte[] orphanKey = Keys.builder().string(blobId).longUnsigned(k).build();
                Optional<ChunkEntry> e = Chunks.get(tx, poolId, k);
                if (e.isPresent() && e.get().blobId().equals(blobId)) {
                    notStray++;                                            // the index points here: this is the real copy
                    changed |= orphans.delete(orphanKey);
                    continue;
                }
                SlotFree r;
                try {
                    r = freer.free(poolId, blobId, k);
                } catch (IOException io) {
                    error = io;
                    break;
                }
                if (r.result() == SlotFree.Result.DEFERRED) {
                    deferred++;
                    continue;
                }
                if (r.result() == SlotFree.Result.FREED) {
                    freed++;
                    bytes += r.bytes();
                }
                orphans.delete(orphanKey);
                changed = true;
            }
            if (changed) tx.commit();
        }
        return new StrayBatchResult(freed, bytes, notStray, deferred, error);
    }

    // ------------------------------------------------------------------ tombstones

    @Override
    public List<Tombstone> dueTombstones(long queuedBeforeMillis, int limit) {
        return store().read(tx -> {
            List<Tombstone> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.DELETED_MANIFESTS).scan()) {
                while (out.size() < limit && c.next()) {
                    long at = c.value().length >= 8 ? ByteBuffer.wrap(c.value()).getLong() : 0L;   // legacy rows: due
                    if (at > queuedBeforeMillis) continue;
                    Keys.Reader r = new Keys.Reader(c.key());
                    out.add(new Tombstone(r.longUnsigned(), r.string(), at));
                }
            }
            return out;
        });
    }

    @Override
    public int deleteTombstones(List<Tombstone> tombstones) {
        if (tombstones.isEmpty()) return 0;
        int removed = 0;
        try (WriteTxn tx = store().beginWrite()) {
            BTree queue = tx.tree(Trees.DELETED_MANIFESTS);
            for (Tombstone t : tombstones) {
                Optional<ManifestEntity> m = Trees.manifest(tx, t.manifestId());
                if (m.isPresent() && m.get().isDeleted()) {
                    Trees.deleteManifestRecord(tx, m.get());
                    removed++;
                }
                queue.delete(Trees.gcKey(t.txId(), t.manifestId()));
            }
            tx.commit();
        }
        return removed;
    }

    // ------------------------------------------------------------------ small-object compaction

    @Override
    public CompactionSwap swapSmallBlob(String oldBlobId, String newBlobId, Map<String, SmallMove> moves) {
        try (WriteTxn tx = store().beginWrite()) {
            Optional<BlobFileEntity> oldBlob = Trees.blob(tx, oldBlobId, false);
            Optional<BlobFileEntity> newBlob = Trees.blob(tx, newBlobId, false);
            if (oldBlob.isEmpty()) return new CompactionSwap(false, "the old blob no longer exists", 0, List.of());
            if (newBlob.isEmpty()) return new CompactionSwap(false, "the new blob does not exist", 0, List.of());
            Map<String, ManifestEntity> live = new LinkedHashMap<>();
            Set<String> retired = new HashSet<>();
            for (String id : Trees.manifestIdsOfBlob(tx, oldBlobId)) {
                Optional<ManifestEntity> m = Trees.manifest(tx, id);
                if (m.isEmpty()) continue;
                if (m.get().isDeleted()) {
                    if (moves.containsKey(id)) retired.add(id);
                    continue;
                }
                SmallMove mv = moves.get(id);
                if (mv == null) {
                    return new CompactionSwap(false, "live manifest " + id + " was not part of the copy", 0, List.of());
                }
                if (m.get().getSmallOffset() != mv.oldOffset() || m.get().getSmallLength() != mv.length()
                        || m.get().getSmallCrc32c() != mv.crc32c()) {
                    return new CompactionSwap(false, "manifest " + id + " changed during the copy", 0, List.of());
                }
                live.put(id, m.get());
            }
            for (Map.Entry<String, ManifestEntity> e : live.entrySet()) {
                ManifestEntity m = e.getValue();
                m.setSmallBlobId(newBlobId);
                m.setSmallOffset(moves.get(e.getKey()).newOffset());
                Trees.putManifest(tx, m);                                  // manifests_by_blob follows
            }
            Trees.purgeManifests(tx, List.of(oldBlobId), null);            // tombstones that still name the old blob
            Trees.deleteBlobRecord(tx, oldBlob.get());
            tx.commit();
            return new CompactionSwap(true, null, live.size(), new ArrayList<>(retired));
        }
    }
}
