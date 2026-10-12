package org.example.sectoriadb.engine.blob;

import org.example.sectoriadb.engine.hash.Crc32c;
import org.example.sectoriadb.engine.format.BlobLayout;
import org.example.sectoriadb.engine.error.InvalidBlobHeaderException;
import org.example.sectoriadb.engine.metrics.EngineListener;
import org.example.sectoriadb.engine.io.BlobFile;
import org.example.sectoriadb.engine.io.StorageIOEngine;
import org.example.sectoriadb.engine.hash.BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.example.sectoriadb.engine.error.ChunkCorruptedException;
import org.example.sectoriadb.engine.error.KeyCollisionException;
import org.example.sectoriadb.engine.error.TableFullException;

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
 * <h3>Durability of a new chunk (doc 10, part B)</h3>
 * A chunk that goes into a free slot is written (data, then meta) WITHOUT an fsync and without waiting for one: the table
 * lock is not held across fsyncs, so concurrent writers do not queue behind two fsyncs per chunk. The writer calls
 * {@link #barrier()} before the metastore commit that first references its chunks; one fsync of the file (a leader, with
 * the others waiting for it) covers every write issued up to that moment. Until then such a slot is not referenced by
 * anything durable: after a crash it may hold stale or partial bytes under an ACTIVE meta entry, which the CRC of the
 * data detects, a repeated upload heals, and the collector's sweep frees. The moves of an eviction path protect chunks
 * that ARE referenced already and keep their ordered fsyncs. See docs/architecture/10-gc-and-resize.md.
 *
 * <h3>Concurrency</h3>
 * The table owns a {@link ReentrantReadWriteLock}. Mutations take the write lock, lookups, reads, statistics
 * and iteration take the read lock. Chunks are always addressed by key: a later insert may move a chunk to
 * its other candidate slot, so {@link #readChunkByKey} looks up and reads atomically.
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
    private final EngineListener listener;
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

    private final EvictionPlanner planner;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // durability barrier: writes issued but not yet forced (see barrier())
    private final ForceBarrier forceBarrier = new ForceBarrier();

    /** Callback for iterating active chunks. Can throw IOException. */
    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(long chunkKey, ByteBuffer rawData) throws IOException;
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
        this(blobFile, ioEngine, hasher, numBuckets, chunkSize, maxEvictions, EngineListener.NOOP);
    }

    /** Full constructor: engine events (evictions, dedup, CRC failures, lock waits) are reported to {@code listener}. */
    public CuckooHashTable(BlobFile blobFile,
                           StorageIOEngine ioEngine,
                           BytesHasher hasher,
                           int numBuckets,
                           int chunkSize,
                           int maxEvictions,
                           EngineListener listener) {
        this.listener = listener;
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
        this.planner = new EvictionPlanner(stateMeta, chunkIdMeta, numBuckets, maxEvictions);

        this.tableAOffset = BlobLayout.tableAOffset(numBuckets);
        this.tableBOffset = tableAOffset + (long) slotsPerTable * chunkSize;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

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
        int crc = Crc32c.of(payload, 0, len);

        long lockT0 = System.nanoTime();
        lock.writeLock().lock();
        listener.onCuckooLockWait(System.nanoTime() - lockT0);
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
                        listener.onDedupHit();
                        return new InsertResult(key, true);
                    }
                    if (Crc32c.of(stored, 0, len) != crcMeta[idx]) {
                        // Stored bytes are damaged but the new bytes match the recorded CRC: heal in place.
                        listener.onChecksumFailure(EngineListener.ChecksumKind.CHUNK);
                        log.warn("Healing corrupted chunk 0x{} in slot {} of blob {} from identical incoming data",
                                Long.toHexString(key), idx, blobFile.id());
                        ioEngine.writeChunk(blobFile, dataOffset(idx), ByteBuffer.wrap(payload));
                        ioEngine.force(blobFile);
                        listener.onDedupHit();
                        return new InsertResult(key, true);
                    }
                }
                // Same key, different content: a genuine 64-bit hash collision.
                if (!mayRekey) {
                    throw new KeyCollisionException("Key 0x" + Long.toHexString(key) + " already holds a different chunk in blob "
                            + blobFile.id() + " and re-keying is not allowed");
                }
                if (attempt + 1 >= MAX_KEY_ATTEMPTS) {
                    throw new IOException("Could not find a collision-free key for a chunk after "
                            + MAX_KEY_ATTEMPTS + " attempts in blob " + blobFile.id());
                }
                log.warn("Hash collision on key 0x{} in blob {}: re-keying chunk (attempt {})",
                        Long.toHexString(key), blobFile.id(), attempt + 1);
                listener.onHashCollisionRekey();
                key = hasher.hash64(payload, attempt + 1L);
            }

            int[] path = planner.findPath(key);
            if (path == null) {
                listener.onTableFull();
                throw new TableFullException("CuckooHashTable: no eviction path within " + maxEvictions
                        + " moves — table is too full");
            }

            listener.onEvictionPath(path.length - 1);

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

            // The new chunk itself is not referenced by anything durable yet: no fsync under the lock, the writer calls
            // barrier() before the commit that references it (see the class comment).
            int target = path[0];
            ioEngine.writeChunk(blobFile, dataOffset(target), ByteBuffer.wrap(payload));
            writeMeta(target, key, ACTIVE, len, crc, false);
            forceBarrier.written();
            return new InsertResult(key, false);
        } finally {
            lock.writeLock().unlock();
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
            long fileSize = ioEngine.size(blobFile);
            long required = BlobLayout.requiredSize(numBuckets, chunkSize);
            if (fileSize >= 0 && fileSize < required) {
                // a truncated file (interrupted copy or restore) would otherwise fail on an arbitrary later read
                throw new InvalidBlobHeaderException("Blob " + blobFile.id() + ": file is " + fileSize
                        + " bytes but its geometry needs " + required + " (truncated?)");
            }

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
                    if (Crc32c.of(entry, 0, META_ENTRY_BYTES - 4) != e.getInt(META_ENTRY_BYTES - 4)) {
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
                        listener.onChecksumFailure(EngineListener.ChecksumKind.SLOT_META);
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
     * Iterates every active chunk in this table and invokes the consumer with the chunk's stored bytes (length
     * 1..chunkSize), after verifying their length and CRC32C. Used by ResizeService to migrate data. The lock is held for one batch of {@code batchSlots} slots at a time and the
     * consumer runs with NO lock held (it may do slow IO, and readers, scrubs and a writer that queues up are not
     * stalled for the whole table). The iteration is only consistent if nothing is inserted or freed meanwhile: a
     * concurrent insert may move a chunk from a slot not visited yet to one already passed. {@code ResizeService} calls it
     * on a blob it has frozen (no inserts, no frees); anybody else must hold the same guarantee.
     */
    public void forEachActiveChunkInBatches(int batchSlots, ChunkConsumer consumer) throws IOException {
        int batch = Math.max(1, batchSlots);
        for (int from = 0; from < totalSlots; from += batch) {
            List<long[]> keys = new ArrayList<>();
            List<byte[]> datas = new ArrayList<>();
            lock.readLock().lock();
            try {
                for (int idx = from; idx < Math.min(totalSlots, from + batch); idx++) {
                    if (stateMeta[idx] == ACTIVE) {
                        byte[] data = readRaw(idx, lengthMeta[idx]);
                        verify(idx, data);
                        keys.add(new long[]{chunkIdMeta[idx]});
                        datas.add(data);
                    }
                }
            } finally {
                lock.readLock().unlock();
            }
            for (int i = 0; i < keys.size(); i++) consumer.accept(keys.get(i)[0], ByteBuffer.wrap(datas.get(i)));
        }
    }

    /**
     * Frees the slot that holds {@code chunkKey} (garbage collection, doc 10): ACTIVE becomes DELETED, the change is
     * forced to disk, the active counter drops (fill statistics and placement weights see the space at once). The data
     * bytes stay where they are and are overwritten by whatever chunk takes the slot next. DELETED slots are free for
     * the insert path (eviction-path search treats them like FREE) and invisible to lookups and reads.
     *
     * <p>Idempotent: a key that is not stored (never was, or already freed) returns {@code false} and changes nothing.
     * Takes the table write lock. If the meta write fails the in-memory state is unchanged and the exception propagates.
     *
     * @return the length of the freed chunk in bytes, or -1 if the key was not stored
     */
    public int freeSlot(long chunkKey) throws IOException {
        lock.writeLock().lock();
        try {
            int idx = findActiveSlot(chunkKey);
            if (idx < 0) {
                return -1;
            }
            int len = lengthMeta[idx];
            writeMeta(idx, chunkKey, DELETED, 0, 0);
            return len;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** True if an ACTIVE slot holds the key (no IO). */
    public boolean contains(long chunkKey) {
        lock.readLock().lock();
        try {
            return findActiveSlot(chunkKey) >= 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** A page of {@link #activeKeys}: {@code next} is the slot to continue from, or -1 when the table is exhausted. */
    public record KeyPage(long[] keys, int next) {
    }

    /**
     * The keys of ACTIVE slots, in slot order, starting at slot {@code fromSlot}, at most {@code max} of them. Metadata
     * only: no data is read and nothing is verified (the sweep of stray copies needs the keys, not the bytes). The
     * keys are a snapshot: slots may change right after the call returns.
     */
    public KeyPage activeKeys(int fromSlot, int max) {
        lock.readLock().lock();
        try {
            long[] out = new long[Math.min(max, 1024)];
            int n = 0;
            int idx = Math.max(0, fromSlot);
            for (; idx < totalSlots && n < max; idx++) {
                if (stateMeta[idx] == ACTIVE) {
                    if (n == out.length) out = Arrays.copyOf(out, Math.min(max, out.length * 2));
                    out[n++] = chunkIdMeta[idx];
                }
            }
            return new KeyPage(Arrays.copyOf(out, n), idx >= totalSlots ? -1 : idx);
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
            if (hasUnforcedWrites()) {
                try {
                    ioEngine.force(blobFile);   // best effort: nobody will ask for the barrier of a closed table
                } catch (IOException e) {
                    log.warn("Blob {}: could not force the writes pending at close: {}", blobFile.id(), e.getMessage());
                }
            }
            ioEngine.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Durability barrier: returns when every chunk inserted into this table before the call is on disk (data and meta
     * entry). Group force: the first caller that finds no fsync running becomes the leader and forces the file once for
     * everything written so far, the others wait and return when a force that started after their insert has
     * completed. A failed force fails the leader's call only; a waiter then forces by itself.
     *
     * <p>Call it before the metastore commit that first references chunks written by {@link #insert} /
     * {@link #insertPreservingKey}, including chunks that were found already stored (they may come from an
     * upload that has not committed and not forced yet). Cheap when nothing is pending.
     */
    public void barrier() throws IOException {
        forceBarrier.await(() -> ioEngine.force(blobFile), listener);
    }

    /** True if inserted chunks were written but not forced to disk yet (such a table is not evicted from the cache). */
    public boolean hasUnforcedWrites() {
        return forceBarrier.hasUnforced();
    }

    /** Memory held by the in-memory slot metadata, in bytes (the weight of a resident table in the cache budget). */
    public long memoryBytes() {
        return (long) totalSlots * (8 + 1 + 4 + 4);
    }

    /** Number of slots of the table (both halves). */
    public int totalSlots() { return totalSlots; }
    public int getNumBuckets() { return numBuckets; }
    public int getChunkSize() { return chunkSize; }
    public BlobFile getBlobFile() { return blobFile; }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /** Test support: flat index of the ACTIVE slot that holds the key, or -1. Not part of the public API. */
    int slotOf(long chunkKey) {
        lock.readLock().lock();
        try {
            return findActiveSlot(chunkKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Test support: file offset of the data bytes of the key's slot (to damage them on purpose). */
    long dataOffsetOf(long chunkKey) {
        lock.readLock().lock();
        try {
            int idx = findActiveSlot(chunkKey);
            if (idx < 0) throw new IllegalArgumentException("key not stored: 0x" + Long.toHexString(chunkKey));
            return dataOffset(idx);
        } finally {
            lock.readLock().unlock();
        }
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


    /** Writes the meta entry, makes it durable, then updates the in-memory copy. */
    private void writeMeta(int idx, long chunkKey, byte state, int dataLength, int dataCrc) throws IOException {
        writeMeta(idx, chunkKey, state, dataLength, dataCrc, true);
    }

    /** As above; with {@code sync == false} the fsync is left to {@link #barrier()}. */
    private void writeMeta(int idx, long chunkKey, byte state, int dataLength, int dataCrc, boolean sync) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(META_ENTRY_BYTES);
        buf.putLong(chunkKey);
        buf.put(state);
        buf.put((byte) 0);
        buf.putShort((short) 0);
        buf.putInt(dataLength);
        buf.putInt(dataCrc);
        buf.putLong(nextSequence++);
        buf.putInt(Crc32c.of(buf.array(), 0, META_ENTRY_BYTES - 4));
        buf.flip();
        ioEngine.writeChunk(blobFile, BlobLayout.HEADER_SIZE + (long) idx * META_ENTRY_BYTES, buf);
        if (sync) ioEngine.force(blobFile);
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
            listener.onChecksumFailure(EngineListener.ChecksumKind.CHUNK);
            throw new ChunkCorruptedException(blobFile.id(), idx, chunkIdMeta[idx],
                    "length " + data.length + " != recorded " + lengthMeta[idx]);
        }
        int actual = Crc32c.of(data, 0, data.length);
        if (actual != crcMeta[idx]) {
            listener.onChecksumFailure(EngineListener.ChecksumKind.CHUNK);
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

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }
}
