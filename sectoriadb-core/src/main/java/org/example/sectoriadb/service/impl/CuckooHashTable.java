package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.metrics.StorageMetrics;
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
import java.util.zip.CRC32C;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bucketed cuckoo hash table backed by a single blob file (layout: see {@link BlobLayout}).
 *
 * Each bucket has exactly SLOTS_PER_BUCKET = 4 slots. Table A and table B use two independent mixers of the
 * 64-bit chunk key ({@link BlobLayout#bucketA}, {@link BlobLayout#bucketB}).
 *
 * <h3>Integrity</h3>
 * Every slot has a 32-byte meta entry with its own CRC32C and the CRC32C of the stored bytes. Reads verify
 * length and CRC and throw {@link ChunkCorruptedException}. A meta entry that fails its CRC on load is put
 * in memory-only state QUARANTINED: it is never returned by lookup, never chosen as a free slot (so its data
 * is not overwritten), and is counted in {@link FillStats}. Dedup compares length, CRC and then the full
 * bytes; a differing chunk under the same key is a real hash collision and is re-keyed with a salted hash.
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

    public static final int SLOTS_PER_BUCKET = BlobLayout.SLOTS_PER_BUCKET;
    /** Maximum number of keys tried for one chunk (primary key + salted re-keys) before giving up. */
    public static final int MAX_KEY_ATTEMPTS = 8;

    private static final int META_ENTRY_BYTES = BlobLayout.META_ENTRY_BYTES;
    private static final int META_LOAD_BATCH = 8192; // entries per read while loading

    private static final byte FREE = SlotStateMap.FREE.getValue();
    private static final byte ACTIVE = SlotStateMap.ACTIVE.getValue();
    private static final byte DELETED = SlotStateMap.DELETED.getValue();
    private static final byte RESERVED = SlotStateMap.RESERVED.getValue();
    private static final byte QUARANTINED = SlotStateMap.QUARANTINED.getValue();

    private final BlobFile blobFile;
    private final StorageIOEngine ioEngine;
    private final BytesHasher hasher;
    private final int numBuckets;
    private final int chunkSize;
    private final int maxEvictions;
    private final StorageMetrics metrics;
    // O(1) fill statistics, kept in step with stateMeta (guarded by the write lock; read under the read lock)
    private int activeCount;
    private int quarantinedCount;

    // Flat in-memory metadata (17 bytes per slot).
    // Slot index = tableId * numBuckets * SLOTS_PER_BUCKET + bucket * SLOTS_PER_BUCKET + slot
    private final long[] chunkIdMeta;
    private final byte[] stateMeta;
    private final int[] lengthMeta;
    private final int[] crcMeta;
    private final int totalSlots;
    private final int slotsPerTable;
    private long nextSequence = 1;

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
    public record FillStats(int activeSlots, int totalSlots, int quarantinedSlots) {
        public FillStats(int activeSlots, int totalSlots) {
            this(activeSlots, totalSlots, 0);
        }
        public double fillPercent() {
            return totalSlots == 0 ? 0.0 : 100.0 * activeSlots / totalSlots;
        }
        public long freeBytes(int chunkSize) {
            return (long)(totalSlots - activeSlots - quarantinedSlots) * chunkSize;
        }
        public long usedBytes(int chunkSize) {
            return (long) activeSlots * chunkSize;
        }
    }

    /** Result of {@link #scrub()}. {@code problems} is capped at 100 lines. */
    public record ScrubReport(int activeSlots, int ok, int corrupt, int quarantined, List<String> problems) {
    }

    /** Convenience constructor — uses a safe default of 32 evictions. */
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
        this(blobFile, ioEngine, hasher, numBuckets, chunkSize, maxEvictions, StorageMetrics.NOOP);
    }

    /** Full constructor: engine events (evictions, dedup, CRC failures, lock waits) are reported to {@code metrics}. */
    public CuckooHashTable(BlobFile blobFile,
                           StorageIOEngine ioEngine,
                           BytesHasher hasher,
                           int numBuckets,
                           int chunkSize,
                           int maxEvictions,
                           StorageMetrics metrics) {
        this.metrics = metrics;
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
        this.lengthMeta = new int[totalSlots];
        this.crcMeta = new int[totalSlots];
        Arrays.fill(stateMeta, FREE);

        this.tableAOffset = BlobLayout.tableAOffset(numBuckets);
        this.tableBOffset = tableAOffset + (long) slotsPerTable * chunkSize;
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
     * Inserts a chunk (1..chunkSize bytes, taken from the buffer's position to its limit).
     *
     * <ul>
     *   <li>Key not present: the chunk is stored under {@code chunkKey}.</li>
     *   <li>Key present with identical bytes (length, CRC and full bytes compared): dedup, nothing written.</li>
     *   <li>Key present with different bytes: a real hash collision. The chunk is re-keyed with
     *       {@code hasher.hash64(data, attempt)} (attempt = 1, 2, ...) and the first free / identical key wins.
     *       The final key is in {@link InsertResult#key()} and must be recorded by the caller.</li>
     * </ul>
     *
     * @throws TableFullException if no eviction path of at most maxEvictions moves exists; the table is unchanged
     */
    public InsertResult insert(long chunkKey, ByteBuffer chunkData) throws IOException {
        return insertInternal(chunkKey, chunkData, true);
    }

    /**
     * Like {@link #insert} but the chunk must be stored under exactly {@code chunkKey} (used when migrating
     * chunks whose keys are already referenced by manifests). A different chunk under the same key is an error.
     */
    public InsertResult insertPreservingKey(long chunkKey, ByteBuffer chunkData) throws IOException {
        return insertInternal(chunkKey, chunkData, false);
    }

    private InsertResult insertInternal(long chunkKey, ByteBuffer chunkData, boolean mayRekey) throws IOException {
        ByteBuffer src = chunkData.duplicate();
        int len = src.remaining();
        if (len < 1 || len > chunkSize) {
            throw new IllegalArgumentException("Chunk length " + len + " must be in [1, " + chunkSize + "]");
        }
        byte[] payload = new byte[len];
        src.get(payload);
        int crc = crc32c(payload, len);

        long lockT0 = System.nanoTime();
        lock.writeLock().lock();
        metrics.cuckooLockWait(System.nanoTime() - lockT0);
        try {
            long key = chunkKey;
            for (int attempt = 0; ; attempt++) {
                int idx = findActiveSlot(key);
                if (idx < 0) {
                    break;
                }
                if (lengthMeta[idx] == len && crcMeta[idx] == crc) {
                    byte[] stored = readRaw(idx, len);
                    if (Arrays.equals(stored, payload)) {
                        metrics.dedupHit();
                        return new InsertResult(key, locationOf(idx), true);
                    }
                    if (crc32c(stored, len) != crcMeta[idx]) {
                        // Stored bytes are damaged but the new bytes match the recorded CRC: heal in place.
                        metrics.crcFailure(StorageMetrics.CrcKind.CHUNK);
                        log.warn("Healing corrupted chunk 0x{} in slot {} of blob {} from identical incoming data",
                                Long.toHexString(key), idx, blobFile.id());
                        ioEngine.writeChunk(blobFile, dataOffset(idx), ByteBuffer.wrap(payload));
                        ioEngine.force(blobFile);
                        metrics.dedupHit();
                        return new InsertResult(key, locationOf(idx), true);
                    }
                }
                // Same key, different content: a genuine 64-bit hash collision.
                if (!mayRekey) {
                    throw new IOException("Key 0x" + Long.toHexString(key) + " already holds a different chunk in blob "
                            + blobFile.id() + " and re-keying is not allowed");
                }
                if (attempt + 1 >= MAX_KEY_ATTEMPTS) {
                    throw new IOException("Could not find a collision-free key for a chunk after "
                            + MAX_KEY_ATTEMPTS + " attempts in blob " + blobFile.id());
                }
                log.warn("Hash collision on key 0x{} in blob {}: re-keying chunk (attempt {})",
                        Long.toHexString(key), blobFile.id(), attempt + 1);
                metrics.hashCollisionRekey();
                key = hasher.hash64(payload, attempt + 1L);
            }

            int[] path = findEvictionPath(key);
            if (path == null) {
                metrics.tableFull();
                throw new TableFullException("CuckooHashTable: no eviction path within " + maxEvictions
                        + " moves — table is too full");
            }

            metrics.evictionPath(path.length - 1);

            // Move chunks from the free end of the path backwards. Invariant after every step: each chunk
            // that was stored before the insert is ACTIVE (with its data) in at least one slot.
            for (int i = path.length - 1; i >= 1; i--) {
                int src0 = path[i - 1];
                int dst = path[i];
                long movedKey = chunkIdMeta[src0];
                int movedLen = lengthMeta[src0];
                int movedCrc = crcMeta[src0];

                ByteBuffer data = ioEngine.readChunk(blobFile, dataOffset(src0), movedLen);
                ioEngine.writeChunk(blobFile, dataOffset(dst), data);
                ioEngine.force(blobFile);                                   // data durable before it is announced
                writeMeta(dst, movedKey, ACTIVE, movedLen, movedCrc);       // chunk now lives in dst (and still in src)
                writeMeta(src0, movedKey, RESERVED, 0, 0);                  // src may now be overwritten
            }

            int target = path[0];
            ioEngine.writeChunk(blobFile, dataOffset(target), ByteBuffer.wrap(payload));
            ioEngine.force(blobFile);
            writeMeta(target, key, ACTIVE, len, crc);
            return new InsertResult(key, locationOf(target), false);
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
     * Reads (and verifies) chunk data from a known location.
     * actualSize trims the result (useful for the last chunk which may be smaller than chunkSize).
     *
     * Note: the location may become stale if another thread inserts concurrently (chunks move between
     * their two candidate slots). Prefer {@link #readChunkByKey} unless the caller holds {@link #lockForWrite()}.
     */
    public ByteBuffer readChunk(ChunkLocation loc, int actualSize) throws IOException {
        lock.readLock().lock();
        try {
            int idx = flatIndex(loc.table().getId(), loc.bucketIndex(), loc.slotIndex());
            if (stateMeta[idx] != ACTIVE) {
                throw new IOException("Slot " + idx + " of blob " + blobFile.id() + " is not active");
            }
            return readVerified(idx, actualSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Atomically looks up a chunk and reads its data under one read lock; verifies stored length and CRC32C.
     * Returns empty if the key is not stored.
     *
     * @throws ChunkCorruptedException if the stored bytes do not match their recorded length / CRC
     */
    public Optional<ByteBuffer> readChunkByKey(long chunkKey, int actualSize) throws IOException {
        lock.readLock().lock();
        try {
            int idx = findActiveSlot(chunkKey);
            if (idx < 0) {
                return Optional.empty();
            }
            return Optional.of(readVerified(idx, actualSize));
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
     * Verifies the blob header and loads the metadata from disk (hot restart).
     *
     * Header magic / version / CRC / geometry mismatch fails with {@link InvalidBlobHeaderException}.
     * Meta entries whose CRC (or state) is invalid are QUARANTINED in memory (logged at ERROR, never rewritten
     * and never reused). Also repairs the leftovers of an interrupted insert: RESERVED slots (their chunk was
     * already copied to another ACTIVE slot) become FREE, and a key that is ACTIVE in both of its candidate
     * slots keeps only its table-A copy.
     */
    public void loadMetadataFromDisk() throws IOException {
        lock.writeLock().lock();
        try {
            BlobLayout.verifyHeader(ioEngine.readChunk(blobFile, 0, BlobLayout.HEADER_SIZE),
                    numBuckets, chunkSize, blobFile.id());

            byte[] entry = new byte[META_ENTRY_BYTES];
            long maxSeq = 0;
            int quarantined = 0;
            for (int first = 0; first < totalSlots; first += META_LOAD_BATCH) {
                int count = Math.min(META_LOAD_BATCH, totalSlots - first);
                ByteBuffer buf = ioEngine.readChunk(blobFile,
                        BlobLayout.HEADER_SIZE + (long) first * META_ENTRY_BYTES, count * META_ENTRY_BYTES);
                for (int i = 0; i < count; i++) {
                    int idx = first + i;
                    buf.get(entry);
                    ByteBuffer e = ByteBuffer.wrap(entry);
                    chunkIdMeta[idx] = 0;
                    lengthMeta[idx] = 0;
                    crcMeta[idx] = 0;
                    if (isAllZero(entry)) {
                        stateMeta[idx] = FREE;
                        continue;
                    }
                    String problem = null;
                    byte state = e.get(8);
                    if (crc32c(entry, META_ENTRY_BYTES - 4) != e.getInt(META_ENTRY_BYTES - 4)) {
                        problem = "meta entry CRC mismatch";
                    } else if (state != FREE && state != ACTIVE && state != DELETED && state != RESERVED) {
                        problem = "unknown slot state " + state;
                    } else if (state == ACTIVE && (e.getInt(12) < 1 || e.getInt(12) > chunkSize)) {
                        problem = "invalid dataLength " + e.getInt(12);
                    }
                    if (problem != null) {
                        log.error("Blob {}: slot {} quarantined: {}", blobFile.id(), idx, problem);
                        stateMeta[idx] = QUARANTINED;
                        quarantined++;
                        metrics.crcFailure(StorageMetrics.CrcKind.SLOT_META);
                        continue;
                    }
                    stateMeta[idx] = state;
                    chunkIdMeta[idx] = e.getLong(0);
                    lengthMeta[idx] = e.getInt(12);
                    crcMeta[idx] = e.getInt(16);
                    maxSeq = Math.max(maxSeq, e.getLong(20));
                }
            }
            nextSequence = maxSeq + 1;
            activeCount = 0;
            quarantinedCount = quarantined;
            for (byte st : stateMeta) {
                if (st == ACTIVE) activeCount++;
            }
            if (quarantined > 0) {
                log.error("Blob {}: {} slot(s) quarantined (damaged meta entries); they are excluded from lookups "
                        + "and will not be overwritten. Run 'scrub' for details.", blobFile.id(), quarantined);
            }
            repairAfterCrash();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns the total blob file size needed for the given parameters. */
    public static long computeRequiredBlobSize(int numBuckets, int chunkSize) {
        return BlobLayout.requiredSize(numBuckets, chunkSize);
    }

    /**
     * Iterates every active chunk in this table and invokes the consumer with the chunk's stored bytes
     * (length 1..chunkSize), after verifying their length and CRC32C. Used by ResizeService to migrate data.
     *
     * Holds the read lock for the whole iteration, so writers to this table wait. The consumer must not
     * insert into this same table (read-to-write upgrade would deadlock).
     *
     * @throws ChunkCorruptedException if an active chunk fails verification
     */
    public void forEachActiveChunk(ChunkConsumer consumer) throws IOException {
        lock.readLock().lock();
        try {
            for (int idx = 0; idx < totalSlots; idx++) {
                if (stateMeta[idx] == ACTIVE) {
                    byte[] data = readRaw(idx, lengthMeta[idx]);
                    verify(idx, data);
                    consumer.accept(chunkIdMeta[idx], ByteBuffer.wrap(data));
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Verifies every ACTIVE slot's data CRC and reports ok / corrupt / quarantined counts. */
    public ScrubReport scrub() throws IOException {
        lock.readLock().lock();
        try {
            int active = 0, ok = 0, corrupt = 0, quarantined = 0;
            List<String> problems = new ArrayList<>();
            for (int idx = 0; idx < totalSlots; idx++) {
                if (stateMeta[idx] == QUARANTINED) {
                    quarantined++;
                    addProblem(problems, "slot " + idx + ": quarantined (damaged meta entry)");
                } else if (stateMeta[idx] == ACTIVE) {
                    active++;
                    try {
                        verify(idx, readRaw(idx, lengthMeta[idx]));
                        ok++;
                    } catch (ChunkCorruptedException e) {
                        corrupt++;
                        addProblem(problems, e.getMessage());
                    }
                }
            }
            return new ScrubReport(active, ok, corrupt, quarantined, problems);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void addProblem(List<String> problems, String msg) {
        if (problems.size() < 100) {
            problems.add(msg);
        }
    }

    /** Returns fill statistics for capacity monitoring and auto-resize decisions. */
    public FillStats getFillStats() {
        lock.readLock().lock();
        try {
            return new FillStats(activeCount, totalSlots, quarantinedCount);
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
        return BlobLayout.bucketA(key, numBuckets);
    }

    private int bucketB(long key) {
        return BlobLayout.bucketB(key, numBuckets);
    }

    /** FREE and DELETED slots can take a new chunk. QUARANTINED, RESERVED and ACTIVE slots cannot. */
    private boolean isFreeSlot(int idx) {
        return stateMeta[idx] == FREE || stateMeta[idx] == DELETED;
    }

    /** Writes the meta entry, makes it durable, then updates the in-memory copy. */
    private void writeMeta(int idx, long chunkKey, byte state, int dataLength, int dataCrc) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(META_ENTRY_BYTES);
        buf.putLong(chunkKey);
        buf.put(state);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(dataLength);
        buf.putInt(dataCrc);
        buf.putLong(nextSequence++);
        buf.putInt(crc32c(buf.array(), META_ENTRY_BYTES - 4));
        buf.flip();
        ioEngine.writeChunk(blobFile, BlobLayout.HEADER_SIZE + (long) idx * META_ENTRY_BYTES, buf);
        ioEngine.force(blobFile);
        if (stateMeta[idx] == ACTIVE) activeCount--;
        if (state == ACTIVE) activeCount++;
        chunkIdMeta[idx] = chunkKey;
        stateMeta[idx] = state;
        lengthMeta[idx] = dataLength;
        crcMeta[idx] = dataCrc;
    }

    private void repairAfterCrash() throws IOException {
        for (int idx = 0; idx < totalSlots; idx++) {
            if (stateMeta[idx] == RESERVED) {
                log.warn("Recovery: releasing RESERVED slot {} of blob {}", idx, blobFile.id());
                writeMeta(idx, 0L, FREE, 0, 0);
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
                    writeMeta(dup, 0L, FREE, 0, 0);
                }
            }
        }
    }

    private byte[] readRaw(int idx, int length) throws IOException {
        ByteBuffer b = ioEngine.readChunk(blobFile, dataOffset(idx), length);
        byte[] out = new byte[length];
        b.get(out);
        return out;
    }

    private void verify(int idx, byte[] data) throws ChunkCorruptedException {
        if (data.length != lengthMeta[idx]) {
            metrics.crcFailure(StorageMetrics.CrcKind.CHUNK);
            throw new ChunkCorruptedException(blobFile.id(), idx, chunkIdMeta[idx],
                    "length " + data.length + " != recorded " + lengthMeta[idx]);
        }
        int actual = crc32c(data, data.length);
        if (actual != crcMeta[idx]) {
            metrics.crcFailure(StorageMetrics.CrcKind.CHUNK);
            throw new ChunkCorruptedException(blobFile.id(), idx, chunkIdMeta[idx],
                    String.format("CRC32C mismatch (stored bytes 0x%08x, recorded 0x%08x)", actual, crcMeta[idx]));
        }
    }

    private ByteBuffer readVerified(int idx, int actualSize) throws IOException {
        byte[] data = readRaw(idx, lengthMeta[idx]);
        verify(idx, data);
        if (actualSize > data.length) {
            throw new ChunkCorruptedException(blobFile.id(), idx, chunkIdMeta[idx],
                    "requested " + actualSize + " bytes but only " + data.length + " are stored");
        }
        return ByteBuffer.wrap(data, 0, actualSize).slice();
    }

    private static int crc32c(byte[] data, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, length);
        return (int) crc.getValue();
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }
}
