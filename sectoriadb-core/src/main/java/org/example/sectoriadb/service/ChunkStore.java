package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.model.PlacedChunk;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.UploadHold;
import org.example.sectoriadb.placement.RendezvousPlacement;
import org.example.sectoriadb.placement.RendezvousPlacement.Candidate;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.service.impl.ChunkCorruptedException;
import org.example.sectoriadb.service.impl.ChunkNotFoundException;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.KeyCollisionException;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.tools.BytesHasher;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Pool-wide chunk storage: writes the chunks of an upload into the pool's cuckoo blobs and reads them back through the
 * chunk index (doc 09).
 *
 * <h3>Write ({@link #stage})</h3>
 * For every chunk of the file: hash it (XXH64), look the key up in the committed chunk index (one batched read
 * transaction per window of {@value #WINDOW} chunks). If the index has it, the stored bytes are read and compared
 * with the new ones (length and CRC first, then the bytes): equal means pool-wide deduplication and nothing is
 * written; different means a genuine 64-bit collision and the chunk is re-keyed with a salted hash. Otherwise the
 * chunk is placed by weighted rendezvous hashing ({@link RendezvousPlacement}) among the writable blobs and inserted
 * into the best-ranked one that accepts it; a full blob ({@link TableFullException}) hands the chunk to the next in
 * the same deterministic order. When no blob accepts, the pool grows by one blob. Nothing is recorded in the index
 * here: the placements travel with the manifest and are committed with it, in one transaction.
 *
 * <h3>Read ({@link #reader})</h3>
 * The blob of a chunk comes from the index, never from probing blobs: one batched index lookup per window of
 * {@value #WINDOW} chunks of the request.
 */
@Service
public class ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkStore.class);

    /** Chunks hashed, looked up and placed together: 256 x 16 KiB = 4 MiB of buffered data per upload. */
    static final int WINDOW = 256;
    /** Attempts to find a pool-wide collision-free key for one chunk. */
    static final int MAX_KEY_ATTEMPTS = CuckooHashTable.MAX_KEY_ATTEMPTS;

    private final ChunkRepository index;
    private final BlobService blobs;
    private final HashTableCache cache;
    private final StorageMetrics metrics;
    private final BytesHasher hasher;
    private final LocationCache locations;
    private final UploadGate gate = new UploadGate();

    @Autowired
    public ChunkStore(ChunkRepository index, BlobService blobs, HashTableCache cache, StorageProperties props) {
        this(index, blobs, cache, new XxHash64BytesHasher(), props.getPool().getLocationCacheEntries());
    }

    public ChunkStore(ChunkRepository index, BlobService blobs, HashTableCache cache) {
        this(index, blobs, cache, new XxHash64BytesHasher(), new StorageProperties().getPool().getLocationCacheEntries());
    }

    /** With a custom hasher (tests force key collisions with it). */
    public ChunkStore(ChunkRepository index, BlobService blobs, HashTableCache cache, BytesHasher hasher) {
        this(index, blobs, cache, hasher, new StorageProperties().getPool().getLocationCacheEntries());
    }

    private ChunkStore(ChunkRepository index, BlobService blobs, HashTableCache cache, BytesHasher hasher,
                       int locationCacheEntries) {
        this.index = index;
        this.blobs = blobs;
        this.cache = cache;
        this.metrics = cache.metrics();
        this.hasher = hasher;
        this.locations = new LocationCache(locationCacheEntries);
    }

    // ── Location cache ───────────────────────────────────────────────────────

    /**
     * Small LRU of {@code (pool, chunkKey) -> blobId}: a GET of a 1 MiB object resolves 64 chunks and a B+tree lookup
     * costs about 13 us, which was a 20 % loss on GET medium. An entry is only a <i>hint</i>, never trusted blindly:
     * the chunk is content addressed and the table verifies its length and CRC32C, so a hint that points at a blob
     * without the chunk (replaced by a resize, freed by the collector, deleted) just misses and the authoritative
     * index lookup runs; a hint to a blob that still holds a copy returns the right bytes. Resizes are followed through
     * {@link HashTableCache#resolveRedirect}. Stage 10 calls {@link #forget} after freeing a chunk (not required for
     * correctness, it only frees the slot of the cache).
     */
    static final class LocationCache {
        private record Loc(String pool, long key) {
        }

        private final int capacity;
        private final java.util.LinkedHashMap<Loc, String> map;

        LocationCache(int capacity) {
            this.capacity = Math.max(0, capacity);
            this.map = new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Loc, String> eldest) {
                    return size() > LocationCache.this.capacity;
                }
            };
        }

        synchronized String get(String pool, long key) {
            return capacity == 0 ? null : map.get(new Loc(pool, key));
        }

        synchronized void put(String pool, long key, String blobId) {
            if (capacity > 0) map.put(new Loc(pool, key), blobId.intern());
        }

        synchronized void remove(String pool, long key) {
            map.remove(new Loc(pool, key));
        }

        synchronized int size() {
            return map.size();
        }
    }

    /**
     * The upload gate: {@link #stage} registers every chunked upload on it until the upload commits or is abandoned; the
     * garbage collector uses it to free copies without an index entry only while no upload of the pool is in flight.
     */
    public UploadGate gate() {
        return gate;
    }

    /** Drops the cached location of a chunk (after the collector freed it). */
    public void forget(String poolId, long chunkKey) {
        locations.remove(poolId, chunkKey);
    }

    /** Number of cached chunk locations (tests, diagnostics). */
    public int cachedLocations() {
        return locations.size();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * The chunks an upload wrote, in file order, ready to be committed with its manifest. {@code hold} keeps the
     * garbage collector away from the copies until the commit has finished: the caller releases it (commit, failure,
     * abandonment), see {@link UploadGate}.
     */
    public record Staged(long[] keys, List<PlacedChunk> placed, int chunkSize, int lastChunkSize, long totalBytes,
                         UploadHold hold) {
    }

    /**
     * Splits the file into chunks of the pool's chunk size and stores them (see the class comment). May create
     * the pool's first blobs and, when the pool is nearly full, add one.
     */
    public Staged stage(Path file, PoolEntity pool) throws IOException {
        UploadGate.Hold hold = gate.enter(pool.getId());   // before the first byte is written
        try {
            return stage(file, pool, hold);
        } catch (IOException | RuntimeException | Error e) {
            hold.release();
            throw e;
        }
    }

    private Staged stage(Path file, PoolEntity pool, UploadGate.Hold hold) throws IOException {
        List<BlobFileEntity> pb = blobs.ensureInitialBlobs(pool);
        blobs.growIfOverThreshold(pool);
        int chunkSize = pb.get(0).getChunkSize();

        Map<Long, PlacedChunk> placed = new LinkedHashMap<>();
        long[] keys;
        long size;
        int last = 0;
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            size = fc.size();
            int total = (int) ((size + chunkSize - 1) / chunkSize);
            keys = new long[total];
            for (int base = 0; base < total; base += WINDOW) {
                int n = Math.min(WINDOW, total - base);
                byte[][] data = new byte[n][];
                long[] firstKeys = new long[n];
                for (int i = 0; i < n; i++) {
                    long offset = (long) (base + i) * chunkSize;
                    int len = (int) Math.min(chunkSize, size - offset);
                    byte[] b = new byte[len];
                    try {
                        FileChannelStorageIOEngine.readFully(fc::read, ByteBuffer.wrap(b), offset);
                    } catch (IOException e) {
                        throw new IOException("Source file shrank while being read: " + file
                                + " (expected " + size + " bytes, failed at offset " + offset + ")", e);
                    }
                    data[i] = b;
                    firstKeys[i] = hasher.hash64(b, 0L);
                    last = len;
                }
                Map<Long, ChunkEntry> indexed = index.findAll(pool.getId(), firstKeys);   // one read txn per window
                for (int i = 0; i < n; i++) {
                    keys[base + i] = storeChunk(pool, chunkSize, firstKeys[i], data[i], indexed, placed);
                }
            }
        }
        return new Staged(keys, new ArrayList<>(placed.values()), chunkSize, last, size, hold);
    }

    private long storeChunk(PoolEntity pool, int chunkSize, long firstKey, byte[] data,
                            Map<Long, ChunkEntry> indexed, Map<Long, PlacedChunk> placed) throws IOException {
        int crc = crc32c(data);
        long key = firstKey;
        for (int attempt = 0; attempt < MAX_KEY_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                metrics.hashCollisionRekey();
                key = hasher.hash64(data, attempt);
                log.warn("Chunk key collision in pool {}: re-keying chunk (attempt {})", pool.getName(), attempt);
            }
            PlacedChunk mine = placed.get(key);
            if (mine != null) {
                // the key is already used by a chunk of this very upload: a repeat of it, or a collision
                if (verifyExisting(mine.blobId(), key, mine.dataLength(), mine.crc32c(), data, crc) == Verdict.SAME) return key;
                continue;
            }
            ChunkEntry e = attempt == 0 ? indexed.get(key) : index.find(pool.getId(), key).orElse(null);
            if (e != null) {
                Verdict v = verifyExisting(e.blobId(), key, e.dataLength(), e.crc32c(), data, crc);
                if (v == Verdict.GONE) {
                    // the blob named by the entry was replaced (or deleted) since the lookup: look at the entry again
                    e = index.find(pool.getId(), key).orElse(null);
                    v = e == null ? Verdict.GONE : verifyExisting(e.blobId(), key, e.dataLength(), e.crc32c(), data, crc);
                }
                if (v == Verdict.SAME) {
                    metrics.poolDedupHit();
                    placed.put(key, new PlacedChunk(key, e.blobId(), data.length, crc, true));
                    locations.put(pool.getId(), key, e.blobId());
                    return key;
                }
                if (v == Verdict.DIFFERENT) continue;   // a different chunk is indexed under this key: collision
                // GONE: no usable entry (collected meanwhile): the chunk is new
            }
            try {
                String blobId = place(pool, chunkSize, key, data);
                placed.put(key, new PlacedChunk(key, blobId, data.length, crc, false));
                locations.put(pool.getId(), key, blobId);   // a read right after the PUT needs no index lookup
                return key;
            } catch (KeyCollisionException collision) {
                log.warn("{}", collision.getMessage());   // an uncommitted copy of another chunk sits under this key
            }
        }
        throw new IOException("Could not find a collision-free key for a chunk of pool " + pool.getName()
                + " after " + MAX_KEY_ATTEMPTS + " attempts");
    }

    /**
     * True if the chunk stored in {@code blobId} under {@code key} has exactly the bytes {@code data}: length and CRC
     * are compared with the recorded ones first (no IO), then the stored bytes are read (and CRC-checked by the table)
     * and compared. A copy that is missing or damaged although we hold bytes that match its recorded length and CRC
     * is rewritten in place (heals it). False means the key belongs to a different chunk.
     */
    private Verdict verifyExisting(String blobId, long key, int recordedLen, int recordedCrc,
                                   byte[] data, int dataCrc) throws IOException {
        if (recordedLen != data.length || recordedCrc != dataCrc) return Verdict.DIFFERENT;
        String target = cache.resolveRedirect(blobId);   // a resize may have replaced the blob since the index was read
        try (ResidentHandle<CuckooHashTable> h = blobs.acquireTable(target)) {
            CuckooHashTable table = h.get();
            try {
                var stored = table.readChunkByKey(key, data.length);
                if (stored.isPresent()) {
                    ByteBuffer b = stored.get();
                    byte[] bytes = new byte[b.remaining()];
                    b.get(bytes);
                    return Arrays.equals(bytes, data) ? Verdict.SAME : Verdict.DIFFERENT;
                }
                log.warn("Chunk 0x{} is indexed in blob {} but missing from its table: restoring the copy",
                        Long.toHexString(key), target);
            } catch (ChunkCorruptedException e) {
                log.warn("Chunk 0x{} in blob {} is damaged ({}): healing it from the incoming identical bytes",
                        Long.toHexString(key), target, e.getMessage());
            }
        } catch (BlobService.BlobGoneException | ClosedChannelException gone) {
            return Verdict.GONE;
        }
        return heal(target, key, data);
    }

    /**
     * Writes the bytes back into the blob that the index names (a slot that is missing or damaged although the chunk is
     * indexed). Like every insert it runs under the blob's writer gate; a blob that is frozen by a resize cannot take
     * writes, so this waits for the resize (which then moves the whole blob, or gives up) and heals the replacement.
     */
    private Verdict heal(String blobId, long key, byte[] data) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        String target = blobId;
        while (true) {
            target = cache.resolveRedirect(target);
            try (ResidentHandle<CuckooHashTable> h = blobs.acquireTable(target)) {
                var gate = cache.writerGate(target);
                gate.lock();
                try {
                    if (!cache.isFrozen(target)) {
                        h.get().insertPreservingKey(key, ByteBuffer.wrap(data));
                        return Verdict.SAME;
                    }
                } finally {
                    gate.unlock();
                }
            } catch (BlobService.BlobGoneException | ClosedChannelException gone) {
                return Verdict.GONE;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("Blob " + target + " stayed frozen by a resize: cannot restore chunk 0x" + Long.toHexString(key));
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for a resize", ie);
            }
        }
    }

    /** What the bytes of an indexed chunk say about the incoming ones. */
    private enum Verdict {
        /** identical (also after healing a missing or damaged copy) */
        SAME,
        /** the key belongs to a different chunk: a genuine 64-bit collision */
        DIFFERENT,
        /** the blob is gone: the index entry is stale */
        GONE
    }

    /**
     * Places a new chunk: rank the pool's blobs by weighted rendezvous hashing and insert into the first one that
     * accepts it. Grows the pool when none does.
     */
    private String place(PoolEntity pool, int chunkSize, long key, byte[] data) throws IOException {
        Set<String> refused = new HashSet<>();
        for (int round = 0; round < 8; round++) {
            List<BlobFileEntity> pb = new ArrayList<>(blobs.cuckooBlobsOf(pool));
            pb.removeIf(b -> b.getChunkSize() != chunkSize);
            if (pb.isEmpty()) pb = new ArrayList<>(blobs.ensureInitialBlobs(pool));
            Map<String, BlobFileEntity> byId = new HashMap<>();
            List<Candidate> candidates = new ArrayList<>();
            for (BlobFileEntity b : pb) {
                if (cache.isFrozen(b.getId())) continue;
                try {
                    candidates.add(Candidate.of(b.getId(), blobs.placementWeight(b)));
                    byId.put(b.getId(), b);
                } catch (BlobService.BlobGoneException replacedMeanwhile) {
                    // a resize replaced it after the listing: it is not a candidate any more
                }
            }
            List<Candidate> ranked = RendezvousPlacement.rank(key, candidates);
            boolean first = true;
            for (Candidate c : ranked) {
                if (refused.contains(c.id())) {
                    first = false;
                    continue;
                }
                try (ResidentHandle<CuckooHashTable> table = cache.acquire(byId.get(c.id()))) {
                    var gate = cache.writerGate(c.id());
                    gate.lock();
                    try {
                        if (cache.isFrozen(c.id())) {
                            refused.add(c.id());
                            first = false;
                            continue;
                        }
                        table.get().insertPreservingKey(key, ByteBuffer.wrap(data));
                        if (!first) metrics.placementFallback();
                        return c.id();
                    } finally {
                        gate.unlock();
                    }
                } catch (TableFullException full) {
                    refused.add(c.id());
                    first = false;
                } catch (BlobService.BlobGoneException | ClosedChannelException closed) {
                    // the blob was replaced under us (resize committed): look at the pool again
                    refused.add(c.id());
                    first = false;
                }
            }
            BlobService.Growth g = blobs.growIfNeeded(pool, refused);
            if (g.kind() == BlobService.Growth.Kind.AT_MAX) {
                throw new TableFullException("Pool '" + pool.getName() + "' is full: all " + ranked.size()
                        + " blob(s) refused the chunk and sectoriadb.pool.max-blobs is reached");
            }
        }
        throw new IOException("Could not place a chunk in pool " + pool.getName() + ": the pool kept changing");
    }

    /**
     * Durability barrier of an upload: makes every chunk it wrote (or found already stored in a table, which an upload
     * that has not committed yet may have written without forcing) durable. The metastore transaction that first
     * references the chunks must not be started before this returns. One fsync per blob covers all writers (group force,
     * {@link CuckooHashTable#barrier}). A blob that a resize replaced was forced by the migration; one that is not
     * resident has no pending writes (a table with unforced writes is never evicted).
     */
    public void barrier(List<PlacedChunk> placed) throws IOException {
        Set<String> blobIds = new java.util.LinkedHashSet<>();
        for (PlacedChunk p : placed) blobIds.add(cache.resolveRedirect(p.blobId()));
        for (String id : blobIds) {
            try (ResidentHandle<CuckooHashTable> h = cache.acquireResident(id)) {
                if (h != null) h.get().barrier();
            } catch (ClosedChannelException gone) {
                // closed because the blob was replaced or deleted: nothing of ours is pending there
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Reads the chunks of one manifest; positions are resolved through the chunk index {@value #WINDOW} at a time.
     *
     * @param keys          the chunk list of the manifest
     * @param lastChunkSize bytes of the last chunk (all others have {@code chunkSize})
     */
    public Reader reader(String poolId, long[] keys, int chunkSize, int lastChunkSize) {
        return new Reader(poolId, keys, chunkSize, lastChunkSize);
    }

    public final class Reader {
        private final String poolId;
        private final long[] keys;
        private final int chunkSize;
        private final int lastChunkSize;
        private int winBase = -1;
        private Map<Long, ChunkEntry> window = Map.of();

        private Reader(String poolId, long[] keys, int chunkSize, int lastChunkSize) {
            this.poolId = poolId;
            this.keys = keys;
            this.chunkSize = chunkSize;
            this.lastChunkSize = lastChunkSize;
        }

        public int count() {
            return keys.length;
        }

        /** Bytes of chunk {@code index} within the object. */
        public int sizeOf(int index) {
            return index == keys.length - 1 ? lastChunkSize : chunkSize;
        }

        /** The verified bytes of chunk {@code index} (trimmed to {@link #sizeOf}). */
        public ByteBuffer read(int index) throws IOException {
            long key = keys[index];
            int size = sizeOf(index);
            String hint = locations.get(poolId, key);
            if (hint != null) {
                ByteBuffer b = tryRead(hint, key, size);
                if (b != null) return b;
                locations.remove(poolId, key);   // stale hint: the authoritative lookup below decides
            }
            ChunkEntry e = entry(index, false);
            for (int attempt = 0; ; attempt++) {
                if (e == null) throw new ChunkNotFoundException(key);
                if (e.dataLength() < size) {
                    throw new ChunkCorruptedException(e.blobId(), -1, key, "the chunk index records " + e.dataLength()
                            + " bytes but the manifest needs " + size);
                }
                ByteBuffer b = tryRead(e.blobId(), key, size);
                if (b != null) {
                    locations.put(poolId, key, e.blobId());
                    return b;
                }
                if (attempt > 0) throw new ChunkNotFoundException(key);
                // the lookup may predate a resize that moved the chunk to another blob: look again, once
                e = entry(index, true);
            }
        }

        /** The chunk from this blob (following resizes), or null if the blob is gone or does not hold it. */
        private ByteBuffer tryRead(String blobId, long key, int size) throws IOException {
            try (ResidentHandle<CuckooHashTable> h = blobs.acquireTable(cache.resolveRedirect(blobId))) {
                return h.get().readChunkByKey(key, size).orElse(null);
            } catch (BlobService.BlobGoneException | ClosedChannelException stale) {
                return null;
            }
        }

        private ChunkEntry entry(int index, boolean refresh) {
            if (refresh) {
                return ChunkStore.this.index.find(poolId, keys[index]).orElse(null);
            }
            if (winBase < 0 || index < winBase || index >= winBase + WINDOW) {
                winBase = index - index % WINDOW;
                int end = Math.min(keys.length, winBase + WINDOW);
                // only the positions the location cache does not know: a hot object needs no index lookup at all
                long[] missing = new long[end - winBase];
                int n = 0;
                for (int i = winBase; i < end; i++) {
                    if (locations.get(poolId, keys[i]) == null) missing[n++] = keys[i];
                }
                window = n == 0 ? Map.of() : ChunkStore.this.index.findAll(poolId, Arrays.copyOf(missing, n));
            }
            ChunkEntry e = window.get(keys[index]);
            return e != null ? e : ChunkStore.this.index.find(poolId, keys[index]).orElse(null);
        }
    }

    private static int crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }
}
