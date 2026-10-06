package org.example.service.impl;

import org.example.model.*;
import org.example.service.StorageIOEngine;
import org.example.tools.BytesHasher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bucketed cuckoo hash table backed by a single blob file.
 *
 * Blob file layout:
 *   [0 .. META_SIZE-1]              — metadata: totalSlots * 9 bytes (8b chunkId + 1b state)
 *   [META_SIZE .. META_SIZE+TA-1]   — Table A: numBuckets * 4 slots * chunkSize bytes
 *   [META_SIZE+TA .. end]           — Table B: same size as Table A
 *
 * Each bucket has exactly SLOTS_PER_BUCKET = 4 slots.
 * Hash A: |murmur(key)| % numBuckets
 * Hash B: |(key ^ TWIST) * MULT| % numBuckets
 */
public class CuckooHashTable {

    public static final int SLOTS_PER_BUCKET = 4;

    private static final int META_ENTRY_BYTES = 9; // 8 bytes chunkId (long) + 1 byte state
    private static final long HASH_B_TWIST = 0x9e3779b97f4a7c15L;
    private static final long HASH_B_MULT = 0x6c62272e07bb0142L;

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

    private final long metaSectionSize;
    private final long tableAOffset;
    private final long tableBOffset;

    /** Callback for iterating active chunks. Can throw IOException. */
    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(long chunkKey, ByteBuffer rawData) throws IOException;
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
        this.totalSlots = 2 * numBuckets * SLOTS_PER_BUCKET;
        this.chunkIdMeta = new long[totalSlots];
        this.stateMeta = new byte[totalSlots];
        Arrays.fill(stateMeta, SlotStateMap.FREE.getValue());

        this.metaSectionSize = (long) totalSlots * META_ENTRY_BYTES;
        long tableSize = (long) numBuckets * SLOTS_PER_BUCKET * chunkSize;
        this.tableAOffset = metaSectionSize;
        this.tableBOffset = metaSectionSize + tableSize;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Inserts a chunk into the table. If the key already exists (dedup), returns the existing location.
     * Returns the ChunkLocation where the original chunk was placed.
     */
    public ChunkLocation insert(long chunkKey, ByteBuffer chunkData) throws IOException {
        Optional<ChunkLocation> existing = lookup(chunkKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        long currentKey = chunkKey;
        ByteBuffer currentData = copyBuffer(chunkData);
        ChunkLocation originalLocation = null;
        int currentTableId = 0; // start from table A

        for (int depth = 0; depth <= maxEvictions; depth++) {
            int bucket = (currentTableId == 0) ? bucketA(currentKey) : bucketB(currentKey);

            // Try to place in a free slot
            for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                int idx = flatIndex(currentTableId, bucket, s);
                if (isFreeSlot(idx)) {
                    writeSlot(currentTableId, bucket, s, currentKey, currentData);
                    if (originalLocation == null) {
                        originalLocation = new ChunkLocation(CuckooTable.fromId(currentTableId), bucket, s);
                    }
                    return originalLocation;
                }
            }

            // Bucket is full — evict a random slot, put current item there, re-insert evicted
            int evictSlot = ThreadLocalRandom.current().nextInt(SLOTS_PER_BUCKET);
            int evictIdx = flatIndex(currentTableId, bucket, evictSlot);
            long evictedKey = chunkIdMeta[evictIdx];
            ByteBuffer evictedData = ioEngine.readChunk(blobFile, dataOffset(currentTableId, bucket, evictSlot), chunkSize);

            writeSlot(currentTableId, bucket, evictSlot, currentKey, currentData);
            if (originalLocation == null) {
                originalLocation = new ChunkLocation(CuckooTable.fromId(currentTableId), bucket, evictSlot);
            }

            currentKey = evictedKey;
            currentData = evictedData;
            currentTableId = 1 - currentTableId; // alternate tables
        }

        throw new IOException("CuckooHashTable: max eviction depth reached — table may be too full");
    }

    /**
     * Looks up a chunk by key. Returns empty if not found.
     */
    public Optional<ChunkLocation> lookup(long chunkKey) {
        int bA = bucketA(chunkKey);
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(0, bA, s);
            if (stateMeta[idx] == SlotStateMap.ACTIVE.getValue() && chunkIdMeta[idx] == chunkKey) {
                return Optional.of(new ChunkLocation(CuckooTable.A, bA, s));
            }
        }

        int bB = bucketB(chunkKey);
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(1, bB, s);
            if (stateMeta[idx] == SlotStateMap.ACTIVE.getValue() && chunkIdMeta[idx] == chunkKey) {
                return Optional.of(new ChunkLocation(CuckooTable.B, bB, s));
            }
        }

        return Optional.empty();
    }

    /**
     * Reads chunk data from a known location.
     * actualSize trims the result (useful for the last chunk which may be smaller than chunkSize).
     */
    public ByteBuffer readChunk(ChunkLocation loc, int actualSize) throws IOException {
        int tableId = loc.table().getId();
        long offset = dataOffset(tableId, loc.bucketIndex(), loc.slotIndex());
        ByteBuffer buf = ioEngine.readChunk(blobFile, offset, chunkSize);
        if (actualSize < chunkSize) {
            byte[] bytes = new byte[actualSize];
            buf.get(bytes);
            return ByteBuffer.wrap(bytes);
        }
        return buf;
    }

    /**
     * Returns an in-memory snapshot of a bucket (for inspection / debugging).
     */
    public Bucket getBucket(CuckooTable table, int bucketIndex) {
        Bucket bucket = new Bucket(table, bucketIndex);
        int tableId = table.getId();
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(tableId, bucketIndex, s);
            bucket.setSlot(s, chunkIdMeta[idx], SlotStateMap.fromValue(stateMeta[idx]));
        }
        return bucket;
    }

    /**
     * Loads metadata from disk into memory (used for hot-restart after process restart).
     */
    public void loadMetadataFromDisk() throws IOException {
        int metaBytes = (int) metaSectionSize;
        ByteBuffer buf = ioEngine.readChunk(blobFile, 0, metaBytes);
        for (int i = 0; i < totalSlots; i++) {
            chunkIdMeta[i] = buf.getLong();
            stateMeta[i] = buf.get();
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
     */
    public void forEachActiveChunk(ChunkConsumer consumer) throws IOException {
        for (int tableId = 0; tableId < 2; tableId++) {
            for (int bucket = 0; bucket < numBuckets; bucket++) {
                for (int slot = 0; slot < SLOTS_PER_BUCKET; slot++) {
                    int idx = flatIndex(tableId, bucket, slot);
                    if (stateMeta[idx] == SlotStateMap.ACTIVE.getValue()) {
                        long key = chunkIdMeta[idx];
                        long offset = dataOffset(tableId, bucket, slot);
                        ByteBuffer data = ioEngine.readChunk(blobFile, offset, chunkSize);
                        consumer.accept(key, data);
                    }
                }
            }
        }
    }

    /** Returns fill statistics for capacity monitoring and auto-resize decisions. */
    public FillStats getFillStats() {
        int active = 0;
        for (byte state : stateMeta) {
            if (state == SlotStateMap.ACTIVE.getValue()) active++;
        }
        return new FillStats(active, totalSlots);
    }

    public int getNumBuckets() { return numBuckets; }
    public int getChunkSize() { return chunkSize; }
    public BlobFile getBlobFile() { return blobFile; }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private int flatIndex(int tableId, int bucket, int slot) {
        return tableId * numBuckets * SLOTS_PER_BUCKET + bucket * SLOTS_PER_BUCKET + slot;
    }

    private long dataOffset(int tableId, int bucket, int slot) {
        long tableBase = (tableId == 0) ? tableAOffset : tableBOffset;
        return tableBase + (long) (bucket * SLOTS_PER_BUCKET + slot) * chunkSize;
    }

    private int bucketA(long key) {
        return (int) (Math.abs(key) % numBuckets);
    }

    private int bucketB(long key) {
        long mixed = (key ^ HASH_B_TWIST) * HASH_B_MULT;
        return (int) (Math.abs(mixed) % numBuckets);
    }

    private boolean isFreeSlot(int idx) {
        return stateMeta[idx] == SlotStateMap.FREE.getValue()
                || stateMeta[idx] == SlotStateMap.DELETED.getValue();
    }

    private void writeSlot(int tableId, int bucket, int slot, long chunkKey, ByteBuffer data) throws IOException {
        int idx = flatIndex(tableId, bucket, slot);
        chunkIdMeta[idx] = chunkKey;
        stateMeta[idx] = SlotStateMap.ACTIVE.getValue();

        // Pad data to exactly chunkSize bytes before writing
        ByteBuffer padded = ByteBuffer.allocate(chunkSize);
        ByteBuffer src = data.duplicate();
        src.rewind();
        int toCopy = Math.min(src.remaining(), chunkSize);
        byte[] bytes = new byte[toCopy];
        src.get(bytes);
        padded.put(bytes);
        padded.flip();

        ioEngine.writeChunk(blobFile, dataOffset(tableId, bucket, slot), padded);
        flushMetaSlot(idx);
    }

    private void flushMetaSlot(int idx) throws IOException {
        long offset = (long) idx * META_ENTRY_BYTES;
        ByteBuffer buf = ByteBuffer.allocate(META_ENTRY_BYTES);
        buf.putLong(chunkIdMeta[idx]);
        buf.put(stateMeta[idx]);
        buf.flip();
        ioEngine.writeChunk(blobFile, offset, buf);
    }

    private ByteBuffer copyBuffer(ByteBuffer src) {
        ByteBuffer copy = src.duplicate();
        copy.rewind();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return ByteBuffer.wrap(bytes);
    }
}
