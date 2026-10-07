package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cache of live CuckooHashTable instances keyed by blob file id, with reference-counted handles and LRU eviction.
 *
 * <p>Loading a table reads the whole slot metadata of the blob from disk and keeps ~9 MiB per blob in memory (17
 * bytes x 524 288 slots), so tables are loaded lazily and the resident ones are bounded:
 * {@code sectoriadb.cache.max-resident-tables} (and optionally {@code max-resident-table-bytes}). Every caller pins a
 * table with {@link #acquire} (try-with-resources) for as long as it uses it; a table is closed only when it has been
 * evicted AND its last handle is released, so a thread that is reading or writing never sees its channel closed.
 * A table with writes that were not forced yet (see {@link CuckooHashTable#barrier()}) is not evicted.
 *
 * <p>The fill statistics of an evicted table are remembered until it is loaded again (nothing else changes a blob), so
 * placement weights and gauges need no reload. Call {@link #evict} before replacing or deleting a blob file.
 */
@Service
public class HashTableCache {

    private static final Logger log = LoggerFactory.getLogger(HashTableCache.class);

    private final ResidentCache<CuckooHashTable> cache;
    /** Fill statistics at the moment a table was evicted: valid until the table is loaded (and can change) again. */
    private final ConcurrentHashMap<String, CuckooHashTable.FillStats> evictedStats = new ConcurrentHashMap<>();
    private final StorageProperties props;
    private final StorageMetrics metrics;

    public HashTableCache(StorageProperties props) {
        this(props, StorageMetrics.NOOP);
    }

    @Autowired
    public HashTableCache(StorageProperties props, StorageMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        StorageProperties.Cache c = props.getCache();
        this.cache = new ResidentCache<>("table", c.getMaxResidentTables(), c.getMaxResidentTableBytes(),
                CuckooHashTable::memoryBytes, t -> !t.hasUnforcedWrites(), CuckooHashTable::close,
                (id, t) -> {
                    evictedStats.put(id, t.getFillStats());
                    metrics.tableEvicted();
                });
    }

    /**
     * The tables currently resident (a snapshot, not pinned: use it only to read plain state such as the fill statistics,
     * which needs no file access). Used for aggregate gauges.
     */
    public java.util.Collection<CuckooHashTable> residentTables() {
        return cache.snapshot();
    }

    /** Number of tables in memory right now. */
    public int residentCount() {
        return cache.size();
    }

    /** Memory the resident tables hold for their slot metadata, in bytes. */
    public long residentBytes() {
        return cache.weight();
    }

    /** Tables evicted by the LRU policy since the start of the process. */
    public long evictions() {
        return cache.evictions();
    }

    /** Metrics receiver given to every table this cache creates (also used by ResizeService for its new tables). */
    public StorageMetrics metrics() {
        return metrics;
    }

    /** Pins the table if it is resident (no IO, no metastore access), else returns null. */
    public ResidentHandle<CuckooHashTable> acquireResident(String blobId) {
        return cache.acquireIfResident(blobId);
    }

    /**
     * Fill statistics of the blob. A resident table answers from memory, an evicted one from the statistics remembered
     * at its eviction, and only a table that was never loaded in this process is loaded for it.
     */
    public CuckooHashTable.FillStats fillStats(BlobFileEntity entity) throws IOException {
        CuckooHashTable t = cache.peek(entity.getId());
        if (t != null) return t.getFillStats();
        CuckooHashTable.FillStats memo = evictedStats.get(entity.getId());
        if (memo != null && !isReplaced(entity.getId())) return memo;
        try (ResidentHandle<CuckooHashTable> h = acquire(entity)) {
            return h.get().getFillStats();
        }
    }

    // ── write gate and redirects (resize of one blob of a multi-blob pool, doc 09) ─────────────────────────

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> gates = new ConcurrentHashMap<>();
    private final java.util.Set<String> frozen = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> redirects = new ConcurrentHashMap<>();

    private ReentrantReadWriteLock gate(String blobId) {
        return gates.computeIfAbsent(blobId, k -> new ReentrantReadWriteLock());
    }

    /**
     * Writers hold this read lock around "check {@link #isFrozen}, then insert into the table", so a resize that
     * froze the blob knows that no insert is in flight and none can start.
     */
    public ReentrantReadWriteLock.ReadLock writerGate(String blobId) {
        return gate(blobId).readLock();
    }

    public boolean isFrozen(String blobId) {
        return frozen.contains(blobId);
    }

    /** Stops new chunks from being placed into the blob and waits for the inserts that are running. */
    public void freeze(String blobId) {
        ReentrantReadWriteLock.WriteLock w = gate(blobId).writeLock();
        w.lock();
        try {
            frozen.add(blobId);
        } finally {
            w.unlock();
        }
    }

    public void unfreeze(String blobId) {
        frozen.remove(blobId);
    }

    /** Records that {@code oldBlobId} was replaced by {@code newBlobId}: uploads that placed chunks into the old blob are re-pointed at commit. */
    public void redirect(String oldBlobId, String newBlobId) {
        redirects.put(oldBlobId, newBlobId);
    }

    /** True once a resize has replaced the blob (it must never be loaded again: its file is about to disappear). */
    public boolean isReplaced(String blobId) {
        return redirects.containsKey(blobId);
    }

    /** Follows the replacement chain of a blob id (the id itself if it was never replaced). */
    public String resolveRedirect(String blobId) {
        String id = blobId;
        for (int i = 0; i < 16; i++) {
            String next = redirects.get(id);
            if (next == null) return id;
            id = next;
        }
        return id;
    }

    /**
     * Pins the table of a blob, loading it from disk if it is not resident. Close the handle when done.
     *
     * @throws BlobService.BlobGoneException if a resize replaced the blob
     */
    public ResidentHandle<CuckooHashTable> acquire(BlobFileEntity entity) throws IOException {
        if (entity.getKind() != org.example.sectoriadb.model.BlobKind.CUCKOO) {
            throw new IllegalArgumentException("Blob " + entity.getId() + " is a " + entity.getKind()
                    + " blob, not a cuckoo table");
        }
        if (isReplaced(entity.getId())) {
            throw new BlobService.BlobGoneException(entity.getId());
        }
        return cache.acquire(entity.getId(), id -> {
            CuckooHashTable t = load(entity);
            evictedStats.remove(id);   // from now on the table can change: the remembered statistics are stale
            return t;
        });
    }

    /**
     * Makes a table that the caller built itself (a resize migrates into one) the resident table of its blob, so the first
     * reader of the new blob does not have to load it from disk. If the blob is resident already the given table is closed.
     */
    public void install(BlobFileEntity entity, CuckooHashTable table) {
        boolean[] used = {false};
        try (ResidentHandle<CuckooHashTable> ignored = cache.acquire(entity.getId(), id -> {
            used[0] = true;
            evictedStats.remove(id);
            return table;
        })) {
            // pinned only to put it into the cache; the LRU policy takes it from here
        } catch (IOException e) {
            throw new IllegalStateException(e);   // the loader above cannot fail
        }
        if (!used[0]) {
            try {
                table.close();
            } catch (IOException e) {
                log.warn("Could not close the table of blob {}: {}", entity.getId(), e.getMessage());
            }
        }
    }

    /** Removes a blob file from the cache (call before deleting or replacing it); the table closes when its last handle is released. */
    public void evict(String blobId) {
        evictedStats.remove(blobId);
        cache.evict(blobId);
        log.info("Evicted table from cache: blobId={}", blobId);
    }

    /** Closes all tables (and their file channels) on shutdown. */
    @PreDestroy
    public void closeAll() {
        cache.closeAll();
    }

    private CuckooHashTable load(BlobFileEntity e) throws IOException {
        log.info("Loading CuckooHashTable: blobId={} path={}", e.getId(), e.getFilePath());
        BlobFile bf = new BlobFile(e.getId(), Path.of(e.getFilePath()), e.getTotalBytes());
        CuckooHashTable table = new CuckooHashTable(
                bf, new FileChannelStorageIOEngine(props.isFsync(), metrics), new XxHash64BytesHasher(),
                e.getNumBuckets(), e.getChunkSize(), props.getMaxEvictions(), metrics);
        try {
            table.loadMetadataFromDisk();
        } catch (IOException | RuntimeException ex) {
            try {
                table.close();
            } catch (IOException closing) {
                log.warn("Could not close table: blobId={}: {}", e.getId(), closing.getMessage());
            }
            throw ex;
        }
        log.debug("Loaded: blobId={} fill={}%", e.getId(), String.format("%.1f", table.getFillStats().fillPercent()));
        return table;
    }
}
