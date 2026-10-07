package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.model.PlacedChunk;
import org.example.sectoriadb.repository.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transaction-scoped primitives of the chunk index (see doc 09): the {@code chunks}, {@code chunks_by_blob},
 * {@code chunk_gc} and {@code chunk_orphans} trees and the reference counting that keeps them consistent with the
 * manifests. Every method runs inside the caller's transaction, so "manifest + references" is one atomic commit.
 */
final class Chunks {

    private static final Logger log = LoggerFactory.getLogger(Chunks.class);

    /** (poolId, chunkKey u64) -> {@link ChunkEntry} */
    static final String CHUNKS = "chunks";
    /** (blobId, chunkKey u64) -> empty: the chunks stored in a blob (resize, blob deletion, per-blob counts) */
    static final String CHUNKS_BY_BLOB = "chunks_by_blob";
    /** (poolId, chunkKey u64, txId u64) -> u64 queuedAtMillis: chunks whose refcount reached zero, for the collector */
    static final String CHUNK_GC = "chunk_gc";
    /** (blobId, chunkKey u64) -> u64 recordedAtMillis: physical copies that no index entry points to */
    static final String CHUNK_ORPHANS = "chunk_orphans";

    private static final byte[] REFS_KEY = Keys.of("chunk_refs");

    private Chunks() {
    }

    // ------------------------------------------------------------------ keys

    static byte[] key(String poolId, long chunkKey) {
        return Keys.builder().string(poolId).longUnsigned(chunkKey).build();
    }

    static byte[] gcKey(String poolId, long chunkKey, long txId) {
        return Keys.builder().string(poolId).longUnsigned(chunkKey).longUnsigned(txId).build();
    }

    static long chunkKeyOf(byte[] key) {
        Keys.Reader r = new Keys.Reader(key);
        r.string();
        return r.longUnsigned();
    }

    private static byte[] millis() {
        return ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array();
    }

    // ------------------------------------------------------------------ reads

    static Optional<ChunkEntry> get(ReadTxn tx, String poolId, long chunkKey) {
        return tx.tree(CHUNKS).get(key(poolId, chunkKey)).map(ChunkCodec::decode);
    }

    // ------------------------------------------------------------------ references

    /** Occurrences of each distinct key, in first-seen order. */
    static Map<Long, Integer> occurrences(long[] keys) {
        Map<Long, Integer> occ = new LinkedHashMap<>();
        for (long k : keys) occ.merge(k, 1, Integer::sum);
        return occ;
    }

    /**
     * Adds the references of a manifest that is being committed: for each distinct chunk key, {@code occurrences}
     * references. Runs in the commit transaction of the manifest.
     *
     * <ul>
     *   <li>entry exists: {@code refcount += n}; a zero-reference entry (queued for collection) is revived and its
     *       {@code chunk_gc} rows are removed; if this upload wrote its own copy into another blob, that copy is
     *       recorded in {@code chunk_orphans};</li>
     *   <li>no entry: one is created from the upload's placement (blob, length, CRC) with {@code refcount = n}.
     *       This needs the placement and a blob that still exists; an upload that deduplicated against an entry
     *       which has vanished since, or one whose blob was replaced, fails with
     *       {@link ChunkRepository.ChunkPlacementException} (nothing is written).</li>
     * </ul>
     */
    static void addRefs(WriteTxn tx, String poolId, long[] keys, List<PlacedChunk> placed) {
        if (keys.length == 0) return;
        Map<Long, PlacedChunk> placement = new HashMap<>();
        for (PlacedChunk p : placed) placement.put(p.key(), p);
        BTree chunks = tx.tree(CHUNKS);
        BTree byBlob = tx.tree(CHUNKS_BY_BLOB);
        BTree orphans = tx.tree(CHUNK_ORPHANS);
        long added = 0;
        for (Map.Entry<Long, Integer> o : occurrences(keys).entrySet()) {
            long k = o.getKey();
            int n = o.getValue();
            byte[] ck = key(poolId, k);
            PlacedChunk p = placement.get(k);
            Optional<ChunkEntry> found = chunks.get(ck).map(ChunkCodec::decode);
            if (found.isPresent()) {
                ChunkEntry e = found.get();
                if (p != null && (p.dataLength() != e.dataLength() || p.crc32c() != e.crc32c())) {
                    throw new ChunkRepository.ChunkPlacementException("Chunk 0x" + Long.toHexString(k) + " of pool "
                            + poolId + " is indexed with length " + e.dataLength() + "/crc " + e.crc32c()
                            + " but this upload wrote length " + p.dataLength() + "/crc " + p.crc32c()
                            + " under the same key (hash collision between uncommitted uploads)");
                }
                if (e.refcount() == 0) removeGcRows(tx, poolId, k);   // revived: an upload deduplicated against a zombie
                chunks.put(ck, ChunkCodec.encode(new ChunkEntry(e.blobId(), e.dataLength(), e.crc32c(), e.refcount() + n)));
                if (p != null && !p.indexed() && !p.blobId().equals(e.blobId())
                        && tx.tree(Trees.BLOBS).containsKey(Trees.idKey(p.blobId()))) {
                    // this upload physically wrote the chunk into another blob than the one the index chose
                    orphans.put(Keys.builder().string(p.blobId()).longUnsigned(k).build(), millis());
                }
            } else {
                if (p == null) {
                    throw new ChunkRepository.ChunkPlacementException("Chunk 0x" + Long.toHexString(k) + " of pool "
                            + poolId + " is not in the chunk index and the commit carries no placement for it");
                }
                if (p.indexed()) {
                    throw new ChunkRepository.ChunkPlacementException("Chunk 0x" + Long.toHexString(k) + " of pool "
                            + poolId + " was in the chunk index when the upload started but is gone: it was collected"
                            + " meanwhile, the upload must be retried");
                }
                if (!tx.tree(Trees.BLOBS).containsKey(Trees.idKey(p.blobId()))) {
                    throw new ChunkRepository.ChunkPlacementException("Blob " + p.blobId() + " that received chunk 0x"
                            + Long.toHexString(k) + " no longer exists (replaced or deleted during the upload)");
                }
                chunks.put(ck, ChunkCodec.encode(new ChunkEntry(p.blobId(), p.dataLength(), p.crc32c(), n)));
                byte[] bk = Keys.builder().string(p.blobId()).longUnsigned(k).build();
                byBlob.put(bk, Trees.EMPTY);
                orphans.delete(bk);                    // a recorded orphan copy in this very blob is the indexed one now
            }
            added += n;
        }
        adjustRefs(tx, added);
    }

    /**
     * Releases the references of a manifest that stops being live, in the transaction that retires it. Chunks that
     * reach zero stay in the index with {@code refcount = 0} (their bytes are still in the blob) and are queued in
     * {@code chunk_gc}; nothing is freed here. An entry that does not exist (a manifest written without references)
     * is ignored.
     */
    static void releaseRefs(WriteTxn tx, String poolId, long[] keys) {
        if (keys.length == 0) return;
        BTree chunks = tx.tree(CHUNKS);
        BTree gc = tx.tree(CHUNK_GC);
        long released = 0;
        for (Map.Entry<Long, Integer> o : occurrences(keys).entrySet()) {
            long k = o.getKey();
            int n = o.getValue();
            byte[] ck = key(poolId, k);
            Optional<ChunkEntry> found = chunks.get(ck).map(ChunkCodec::decode);
            if (found.isEmpty()) {
                log.warn("Releasing chunk 0x{} of pool {}: not in the chunk index, ignored", Long.toHexString(k), poolId);
                continue;
            }
            ChunkEntry e = found.get();
            long now = e.refcount() - n;
            if (now < 0) {
                log.error("Chunk 0x{} of pool {} released {} time(s) but has {} reference(s): refcount clamped to 0",
                        Long.toHexString(k), poolId, n, e.refcount());
                n = (int) e.refcount();
                now = 0;
            }
            released += n;
            chunks.put(ck, ChunkCodec.encode(new ChunkEntry(e.blobId(), e.dataLength(), e.crc32c(), now)));
            if (now == 0 && e.refcount() > 0) {
                gc.put(gcKey(poolId, k, tx.txId() + 1), millis());
            }
        }
        adjustRefs(tx, -released);
    }

    private static void removeGcRows(WriteTxn tx, String poolId, long chunkKey) {
        BTree gc = tx.tree(CHUNK_GC);
        byte[] prefix = Keys.builder().string(poolId).longUnsigned(chunkKey).build();
        List<byte[]> rows = new ArrayList<>();
        try (Cursor c = gc.scanPrefix(prefix)) {
            while (c.next()) rows.add(c.key());
        }
        for (byte[] r : rows) gc.delete(r);
    }

    // ------------------------------------------------------------------ totals

    static long totalRefs(ReadTxn tx) {
        return tx.tree(Trees.STATS).get(REFS_KEY).map(v -> ByteBuffer.wrap(v).getLong()).orElse(0L);
    }

    private static void adjustRefs(WriteTxn tx, long delta) {
        if (delta == 0) return;
        long now = Math.max(0, totalRefs(tx) + delta);
        tx.tree(Trees.STATS).put(REFS_KEY, ByteBuffer.allocate(8).putLong(now).array());
    }

    // ------------------------------------------------------------------ blob-level maintenance

    /** Keys of the chunks stored in a blob (from {@code chunks_by_blob}). */
    static List<Long> keysOfBlob(ReadTxn tx, String blobId) {
        List<Long> keys = new ArrayList<>();
        try (Cursor c = tx.tree(CHUNKS_BY_BLOB).scanPrefix(Keys.of(blobId))) {
            while (c.next()) keys.add(chunkKeyOf(c.key()));
        }
        return keys;
    }

    /** Number of index entries with at least one reference among the chunks stored in the blob. */
    static long referencedChunksOfBlob(ReadTxn tx, String poolId, String blobId) {
        long n = 0;
        for (long k : keysOfBlob(tx, blobId)) {
            if (get(tx, poolId, k).map(e -> e.refcount() > 0).orElse(false)) n++;
        }
        return n;
    }

    /**
     * Resize commit: every index entry and orphan record of {@code oldBlobId} now belongs to {@code newBlobId}
     * (the migration copied every active slot of the old table, referenced or not). Returns the number of index
     * entries moved.
     */
    static int moveBlob(WriteTxn tx, String poolId, String oldBlobId, String newBlobId) {
        BTree chunks = tx.tree(CHUNKS);
        BTree byBlob = tx.tree(CHUNKS_BY_BLOB);
        int moved = 0;
        for (long k : keysOfBlob(tx, oldBlobId)) {
            byte[] ck = key(poolId, k);
            Optional<ChunkEntry> e = chunks.get(ck).map(ChunkCodec::decode);
            if (e.isPresent()) {
                ChunkEntry v = e.get();
                chunks.put(ck, ChunkCodec.encode(new ChunkEntry(newBlobId, v.dataLength(), v.crc32c(), v.refcount())));
                moved++;
            }
            byBlob.delete(Keys.builder().string(oldBlobId).longUnsigned(k).build());
            byBlob.put(Keys.builder().string(newBlobId).longUnsigned(k).build(), Trees.EMPTY);
        }
        BTree orphans = tx.tree(CHUNK_ORPHANS);
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> vals = new ArrayList<>();
        try (Cursor c = orphans.scanPrefix(Keys.of(oldBlobId))) {
            while (c.next()) {
                keys.add(c.key());
                vals.add(c.value());
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            long k = chunkKeyOf(keys.get(i));
            orphans.delete(keys.get(i));
            orphans.put(Keys.builder().string(newBlobId).longUnsigned(k).build(), vals.get(i));
        }
        return moved;
    }

    /**
     * Drops everything the chunk index knows about a blob that goes away: its entries (which must all be
     * unreferenced), their gc rows, the by-blob rows and the orphan records.
     */
    static void purgeBlob(WriteTxn tx, String poolId, String blobId) {
        BTree chunks = tx.tree(CHUNKS);
        long dropped = 0;
        for (long k : keysOfBlob(tx, blobId)) {
            Optional<ChunkEntry> e = get(tx, poolId, k);
            if (e.isPresent() && e.get().blobId().equals(blobId)) {
                dropped += e.get().refcount();
                removeGcRows(tx, poolId, k);
                chunks.delete(key(poolId, k));
            }
            tx.tree(CHUNKS_BY_BLOB).delete(Keys.builder().string(blobId).longUnsigned(k).build());
        }
        adjustRefs(tx, -dropped);
        deletePrefix(tx.tree(CHUNK_ORPHANS), Keys.of(blobId));
    }

    /** Bucket deletion: the whole index of the pool. */
    static void purgePool(WriteTxn tx, String poolId, List<String> blobIds) {
        long dropped = 0;
        BTree chunks = tx.tree(CHUNKS);
        List<byte[]> keys = new ArrayList<>();
        try (Cursor c = chunks.scanPrefix(Keys.of(poolId))) {
            while (c.next()) {
                keys.add(c.key());
                dropped += ChunkCodec.decode(c.value()).refcount();
            }
        }
        for (byte[] k : keys) chunks.delete(k);
        adjustRefs(tx, -dropped);
        deletePrefix(tx.tree(CHUNK_GC), Keys.of(poolId));
        for (String blobId : blobIds) {
            deletePrefix(tx.tree(CHUNKS_BY_BLOB), Keys.of(blobId));
            deletePrefix(tx.tree(CHUNK_ORPHANS), Keys.of(blobId));
        }
    }

    private static void deletePrefix(BTree tree, byte[] prefix) {
        List<byte[]> keys = new ArrayList<>();
        try (Cursor c = tree.scanPrefix(prefix)) {
            while (c.next()) keys.add(c.key());
        }
        for (byte[] k : keys) tree.delete(k);
    }
}
