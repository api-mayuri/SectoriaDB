package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.Bucket;
import org.example.sectoriadb.model.ChunkLocation;
import org.example.sectoriadb.model.CuckooTable;
import org.example.sectoriadb.model.SlotStateMap;
import org.example.sectoriadb.service.StorageIOEngine;
import org.example.sectoriadb.tools.BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bucketed cuckoo hash table backed by a single blob file.
 *
 * Blob file layout:
 *   [0 .. META_SIZE-1]              — metadata: totalSlots * 9 bytes (8b chunkId + 1b state)
 *   [META_SIZE .. META_SIZE+TA-1]   — Table A: numBuckets * 4 slots * chunkSize bytes
 *   [META_SIZE+TA .. end]           — Table B: same size as Table A
 *
 * Each bucket has exactly SLOTS_PER_BUCKET = 4 slots.
 * Hash A: floorMod(key, numBuckets)
 * Hash B: floorMod((key ^ TWIST) * MULT, numBuckets)
 *
 * <h3>Insert</h3>
 * {@link #insert} is atomic with respect to failures: the eviction path is searched on the in-memory
 * metadata only (BFS, at most {@code maxEvictions} moves). If there is no path, {@link TableFullException}
 * is thrown and nothing has been modified. Otherwise the chunks along the path are moved starting from the
 * free end of the path, so at every instant (including a crash) each stored chunk is present in at least
 * one ACTIVE slot with correct data. See docs/architecture/01-engine-safety.md.
 *
 * <h3>Concurrency</h3>
 * The table owns a {@link ReentrantReadWriteLock}. Mutations take the write lock, lookups, reads, statistics
 * and iteration take the read lock. A {@link ChunkLocation} is only valid while the lock is held: a later
 * insert may move the chunk to its other candidate slot. Use {@link #readChunkByKey} to look up and read
 * atomically, or hold {@link #lockForWrite()} around a multi-step operation.
 */
public class CuckooHashTable implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CuckooHashTable.class);

    public static final int SLOTS_PER_BUCKET = 4;

    private static final int META_ENTRY_BYTES = 9; // 8 bytes chunkId (long) + 1 byte state
    private static final long HASH_B_TWIST = 0x9e3779b97f4a7c15L;
    private static final long HASH_B_MULT = 0x6c62272e07bb0142L;

    private static final byte FREE = SlotStateMap.FREE.getValue();
    private static final byte ACTIVE = SlotStateMap.ACTIVE.getValue();
    private static final byte DELETED = SlotStateMap.DELETED.getValue();
    private static final byte RESERVED = SlotStateMap.RESERVED.getValue();

    private final BlobFile blobFile;
    private final StorageIOEngine ioEngine;
    private final BytesHasher hasher;
    private final int numBuckets;
    private final int chunkSize;
    private final int maxEvictions;

    // Flat in-memory metadata.
    // Slot index = tableId * numBuckets * SLOTS_PER_BUCKET + bucket * SLOTS_PER_BUCKET + slot
    private final long[] chunkIdMeta;
    private final byte[] stateMeta;
    private final int totalSlots;
    private final int slotsPerTable;

    private final long metaSectionSize;
    private final long tableAOffset;
    private final long tableBOffset;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Callback for iterating active chunks. Can throw IOException. */
    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(long chunkKey, ByteBuffer rawData) throws IOException;
    }

    /** Handle returned by {@link #lockForWrite()}; closing it releases the lock. */
    public interface WriteGuard extends AutoCloseable {
        @Override
        void close();
    }

    /** Fill statistics for a blob file. */
    public record FillStats(int activeSlots, int totalSlots) {
        public double fillPercent() {
            return totalSlots == 0 ? 0.0 : 100.0 * activeSlots / totalSlots;
        }
        public long freeBytes(int chunkSize) {
            return (long)(totalSlots - activeSlots) * chunkSize;
        }
        public long usedBytes(int chunkSize) {
            return (long) activeSlots * chunkSize;
        }
    }

    /** Convenience constructor — uses a safe default of 32 evictions (matches original behaviour). */
    public CuckooHashTable(BlobFile blobFile,
                           StorageIOEngine ioEngine,
                           BytesHasher hasher,
                           int numBuckets,
                           int chunkSize) {
        this(blobFile, ioEngine, hasher, numBuckets, chunkSize, 32);
    }

    /** Primary constructor — maxEvictions is configurable. */
    public CuckooHashTable(BlobFile blobFile,
                           StorageIOEngine ioEngine,
                           BytesHasher hasher,
                           int numBuckets,
                           int chunkSize,
                           int maxEvictions) {
        this.blobFile = blobFile;
        this.ioEngine = ioEngine;
        this.hasher = hasher;
        this.numBuckets = numBuckets;
        this.chunkSize = chunkSize;
        this.maxEvictions = maxEvictions;
        this.slotsPerTable = numBuckets * SLOTS_PER_BUCKET;
        this.totalSlots = 2 * slotsPerTable;
        this.chunkIdMeta = new long[totalSlots];
        this.stateMeta = new byte[totalSlots];
        Arrays.fill(stateMeta, FREE);

        this.metaSectionSize = (long) totalSlots * META_ENTRY_BYTES;
        long tableSize = (long) slotsPerTable * chunkSize;
        this.tableAOffset = metaSectionSize;
        this.tableBOffset = metaSectionSize + tableSize;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Acquires the table write lock for a multi-step operation (e.g. several inserts that must not
     * interleave with readers or other writers). The lock is reentrant, so insert/lookup may be called
     * while it is held. Always use try-with-resources. Do not call it while holding only a read lock
     * (e.g. from inside a {@link ChunkConsumer}) — that would deadlock.
     */
    public WriteGuard lockForWrite() {
        lock.writeLock().lock();
        return lock.writeLock()::unlock;
    }

    /**
     * Inserts a chunk into the table. If the key already exists (dedup), returns the existing location.
     * Returns the ChunkLocation where the chunk was placed.
     *
     * @throws TableFullException if no eviction path of at most maxEvictions moves exists; the table is unchanged
     */
    public ChunkLocation insert(long chunkKey, ByteBuffer chunkData) throws IOException {
        lock.writeLock().lock();
        try {
            Optional<ChunkLocation> existing = lookupUnlocked(chunkKey);
            if (existing.isPresent()) {
                return existing.get();
            }

            int[] path = findEvictionPath(chunkKey);
            if (path == null) {
                throw new TableFullException("CuckooHashTable: no eviction path within " + maxEvictions
                        + " moves — table is too full");
            }

            byte[] payload = padToChunkSize(chunkData);

            // Move chunks from the free end of the path backwards. Invariant after every step: each chunk
            // that was stored before the insert is ACTIVE (with its data) in at least one slot.
            for (int i = path.length - 1; i >= 1; i--) {
                int src = path[i - 1];
                int dst = path[i];
                long movedKey = chunkIdMeta[src];

                ByteBuffer data = ioEngine.readChunk(blobFile, dataOffset(src), chunkSize);
                ioEngine.writeChunk(blobFile, dataOffset(dst), data);
                ioEngine.force(blobFile);                       // data durable before it is announced
                writeMeta(dst, movedKey, ACTIVE);               // chunk now lives in dst (and still in src)
                writeMeta(src, movedKey, RESERVED);             // src may now be overwritten
            }

            int target = path[0];
            ioEngine.writeChunk(blobFile, dataOffset(target), ByteBuffer.wrap(payload));
            ioEngine.force(blobFile);
            writeMeta(target, chunkKey, ACTIVE);
            return locationOf(target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Looks up a chunk by key. Returns empty if not found.
     */
    public Optional<ChunkLocation> lookup(long chunkKey) {
        lock.readLock().lock();
        try {
            return lookupUnlocked(chunkKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads chunk data from a known location.
     * actualSize trims the result (useful for the last chunk which may be smaller than chunkSize).
     *
     * Note: the location may become stale if another thread inserts concurrently (chunks move between
     * their two candidate slots). Prefer {@link #readChunkByKey} unless the caller holds {@link #lockForWrite()}.
     */
    public ByteBuffer readChunk(ChunkLocation loc, int actualSize) throws IOException {
        lock.readLock().lock();
        try {
            int idx = flatIndex(loc.table().getId(), loc.bucketIndex(), loc.slotIndex());
            return trim(ioEngine.readChunk(blobFile, dataOffset(idx), chunkSize), actualSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Atomically looks up a chunk and reads its data under one read lock.
     * Returns empty if the key is not stored.
     */
    public Optional<ByteBuffer> readChunkByKey(long chunkKey, int actualSize) throws IOException {
        lock.readLock().lock();
        try {
            int idx = findActiveSlot(chunkKey);
            if (idx < 0) {
                return Optional.empty();
            }
            return Optional.of(trim(ioEngine.readChunk(blobFile, dataOffset(idx), chunkSize), actualSize));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns an in-memory snapshot of a bucket (for inspection / debugging).
     */
    public Bucket getBucket(CuckooTable table, int bucketIndex) {
        lock.readLock().lock();
        try {
            Bucket bucket = new Bucket(table, bucketIndex);
            int tableId = table.getId();
            for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                int idx = flatIndex(tableId, bucketIndex, s);
                bucket.setSlot(s, chunkIdMeta[idx], SlotStateMap.fromValue(stateMeta[idx]));
            }
            return bucket;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Loads metadata from disk into memory (used for hot-restart after process restart).
     *
     * Also repairs the leftovers of an interrupted insert: RESERVED slots (their chunk was already copied
     * to another ACTIVE slot) become FREE, and a key that is ACTIVE in both of its candidate slots keeps
     * only its table-A copy.
     */
    public void loadMetadataFromDisk() throws IOException {
        lock.writeLock().lock();
        try {
            int metaBytes = (int) metaSectionSize;
            ByteBuffer buf = ioEngine.readChunk(blobFile, 0, metaBytes);
            for (int i = 0; i < totalSlots; i++) {
                chunkIdMeta[i] = buf.getLong();
                stateMeta[i] = buf.get();
            }
            repairAfterCrash();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns the total blob file size needed for the given parameters. */
    public static long computeRequiredBlobSize(int numBuckets, int chunkSize) {
        int totalSlots = 2 * numBuckets * SLOTS_PER_BUCKET;
        long metaSize = (long) totalSlots * META_ENTRY_BYTES;
        long tableSize = (long) numBuckets * SLOTS_PER_BUCKET * chunkSize;
        return metaSize + 2 * tableSize;
    }

    /**
     * Iterates every active chunk in this table and invokes the consumer.
     * Used by ResizeService to migrate data to a new blob file.
     *
     * Holds the read lock for the whole iteration, so writers to this table wait. The consumer must not
     * insert into this same table (read-to-write upgrade would deadlock).
     */
    public void forEachActiveChunk(ChunkConsumer consumer) throws IOException {
        lock.readLock().lock();
        try {
            for (int idx = 0; idx < totalSlots; idx++) {
                if (stateMeta[idx] == ACTIVE) {
                    ByteBuffer data = ioEngine.readChunk(blobFile, dataOffset(idx), chunkSize);
                    consumer.accept(chunkIdMeta[idx], data);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns fill statistics for capacity monitoring and auto-resize decisions. */
    public FillStats getFillStats() {
        lock.readLock().lock();
        try {
            int active = 0;
            for (byte state : stateMeta) {
                if (state == ACTIVE) active++;
            }
            return new FillStats(active, totalSlots);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Closes the IO engine (and its file channel). Waits for in-flight operations. */
    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            ioEngine.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int getNumBuckets() { return numBuckets; }
    public int getChunkSize() { return chunkSize; }
    public BlobFile getBlobFile() { return blobFile; }

    // ─── Eviction path search ────────────────────────────────────────────────

    /**
     * BFS over buckets using in-memory metadata only. Roots are the two candidate buckets of the new key
     * (table A and table B). An edge goes from a bucket to the alternate bucket of one of its ACTIVE chunks.
     * Returns the flat slot indices p[0..n]: the new chunk goes to p[0], the chunk in p[i-1] moves to p[i],
     * and p[n] is a free slot (n = number of moves, 0 if a root bucket has room). Null if no path of at
     * most maxEvictions moves exists.
     */
    private int[] findEvictionPath(long newKey) {
        // node: {tableId, bucket, parentNodeIndex, slotInParentOfMovedChunk, depth}
        List<int[]> nodes = new ArrayList<>();
        BitSet visited = new BitSet(2 * numBuckets);
        int bA = bucketA(newKey);
        int bB = bucketB(newKey);
        nodes.add(new int[]{0, bA, -1, -1, 0});
        visited.set(bA);
        nodes.add(new int[]{1, bB, -1, -1, 0});
        visited.set(numBuckets + bB);

        for (int head = 0; head < nodes.size(); head++) {
            int[] node = nodes.get(head);
            int tableId = node[0];
            int bucket = node[1];

            for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                int idx = flatIndex(tableId, bucket, s);
                if (isFreeSlot(idx)) {
                    return buildPath(nodes, head, s);
                }
            }

            if (node[4] >= maxEvictions) {
                continue; // cannot move anything out of this bucket within the budget
            }
            int other = 1 - tableId;
            for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                int idx = flatIndex(tableId, bucket, s);
                if (stateMeta[idx] != ACTIVE) {
                    continue;
                }
                long key = chunkIdMeta[idx];
                int otherBucket = (other == 0) ? bucketA(key) : bucketB(key);
                int visitId = other * numBuckets + otherBucket;
                if (!visited.get(visitId)) {
                    visited.set(visitId);
                    nodes.add(new int[]{other, otherBucket, head, s, node[4] + 1});
                }
            }
        }
        return null;
    }

    private int[] buildPath(List<int[]> nodes, int endNode, int freeSlot) {
        int[] end = nodes.get(endNode);
        int depth = end[4];
        int[] path = new int[depth + 1];
        path[depth] = flatIndex(end[0], end[1], freeSlot);
        int cur = endNode;
        for (int i = depth - 1; i >= 0; i--) {
            int[] child = nodes.get(cur);
            int[] parent = nodes.get(child[2]);
            path[i] = flatIndex(parent[0], parent[1], child[3]);
            cur = child[2];
        }
        return path;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Optional<ChunkLocation> lookupUnlocked(long chunkKey) {
        int idx = findActiveSlot(chunkKey);
        return idx < 0 ? Optional.empty() : Optional.of(locationOf(idx));
    }

    /** Flat index of an ACTIVE slot holding the key (either candidate position), or -1. */
    private int findActiveSlot(long chunkKey) {
        int bA = bucketA(chunkKey);
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(0, bA, s);
            if (stateMeta[idx] == ACTIVE && chunkIdMeta[idx] == chunkKey) {
                return idx;
            }
        }
        int bB = bucketB(chunkKey);
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(1, bB, s);
            if (stateMeta[idx] == ACTIVE && chunkIdMeta[idx] == chunkKey) {
                return idx;
            }
        }
        return -1;
    }

    private ChunkLocation locationOf(int idx) {
        int tableId = idx / slotsPerTable;
        int inTable = idx % slotsPerTable;
        return new ChunkLocation(CuckooTable.fromId(tableId), inTable / SLOTS_PER_BUCKET, inTable % SLOTS_PER_BUCKET);
    }

    private int flatIndex(int tableId, int bucket, int slot) {
        return tableId * slotsPerTable + bucket * SLOTS_PER_BUCKET + slot;
    }

    private long dataOffset(int idx) {
        int tableId = idx / slotsPerTable;
        long tableBase = (tableId == 0) ? tableAOffset : tableBOffset;
        return tableBase + (long) (idx % slotsPerTable) * chunkSize;
    }

    private int bucketA(long key) {
        return (int) Math.floorMod(key, (long) numBuckets);
    }

    private int bucketB(long key) {
        long mixed = (key ^ HASH_B_TWIST) * HASH_B_MULT;
        return (int) Math.floorMod(mixed, (long) numBuckets);
    }

    private boolean isFreeSlot(int idx) {
        return stateMeta[idx] == FREE || stateMeta[idx] == DELETED;
    }

    /** Writes the metadata entry, makes it durable, then updates the in-memory copy. */
    private void writeMeta(int idx, long chunkKey, byte state) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(META_ENTRY_BYTES);
        buf.putLong(chunkKey);
        buf.put(state);
        buf.flip();
        ioEngine.writeChunk(blobFile, (long) idx * META_ENTRY_BYTES, buf);
        ioEngine.force(blobFile);
        chunkIdMeta[idx] = chunkKey;
        stateMeta[idx] = state;
    }

    private void repairAfterCrash() throws IOException {
        for (int idx = 0; idx < totalSlots; idx++) {
            if (stateMeta[idx] == RESERVED) {
                log.warn("Recovery: releasing RESERVED slot {} of blob {}", idx, blobFile.id());
                writeMeta(idx, 0L, FREE);
            }
        }
        for (int idx = 0; idx < slotsPerTable; idx++) {
            if (stateMeta[idx] != ACTIVE) {
                continue;
            }
            long key = chunkIdMeta[idx];
            int bB = bucketB(key);
            for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                int dup = flatIndex(1, bB, s);
                if (stateMeta[dup] == ACTIVE && chunkIdMeta[dup] == key) {
                    log.warn("Recovery: dropping duplicate copy of chunk 0x{} in slot {} of blob {}",
                            Long.toHexString(key), dup, blobFile.id());
                    writeMeta(dup, 0L, FREE);
                }
            }
        }
    }

    private byte[] padToChunkSize(ByteBuffer data) {
        ByteBuffer src = data.duplicate();
        src.rewind();
        byte[] padded = new byte[chunkSize];
        src.get(padded, 0, Math.min(src.remaining(), chunkSize));
        return padded;
    }

    private ByteBuffer trim(ByteBuffer buf, int actualSize) {
        if (actualSize < chunkSize) {
            byte[] bytes = new byte[actualSize];
            buf.get(bytes);
            return ByteBuffer.wrap(bytes);
        }
        return buf;
    }
}
