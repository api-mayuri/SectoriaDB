package org.example.sectoriadb.storage;

import jakarta.annotation.PreDestroy;
import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.BlobKind;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.engine.blob.CuckooHashTable;
import org.example.sectoriadb.engine.hash.XxHash64BytesHasher;
import org.example.sectoriadb.engine.io.BlobFile;
import org.example.sectoriadb.engine.io.FileChannelStorageIOEngine;
import org.example.sectoriadb.engine.io.StorageIOEngine;
import org.example.sectoriadb.engine.metrics.EngineListener;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.metrics.StorageMetricsEngineListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * placement weights and gauges need no reload. Call {@link #evict} before replacing or deleting a blob file. Whether a
 * blob may still be written to (freeze, redirects) is the business of {@link BlobGate}.
 */
public class HashTableCache {

    private static final Logger log = LoggerFactory.getLogger(HashTableCache.class);

    private final ResidentCache<CuckooHashTable> cache;
    /** Fill statistics at the moment a table was evicted: valid until the table is loaded (and can change) again. */
    private final ConcurrentHashMap<String, CuckooHashTable.FillStats> evictedStats = new ConcurrentHashMap<>();
    private final StorageProperties props;
    private final StorageMetrics metrics;
    private final EngineListener engineListener;
    private final BlobGate gate;
    private volatile Supplier<StorageIOEngine> engineFactory;

    public HashTableCache(StorageProperties props, StorageMetrics metrics, BlobGate gate) {
        this.props = props;
        this.gate = gate;
        this.metrics = metrics;
        this.engineListener = new StorageMetricsEngineListener(metrics);
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
    public Collection<CuckooHashTable> residentTables() {
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

    /** Test hook: the IO engine given to every table loaded or built from now on (default: a file channel engine). */
    public void setEngineFactory(Supplier<StorageIOEngine> factory) {
        this.engineFactory = factory;
    }

    /** A new IO engine for a table of this cache (also used by {@code ResizeService} for the table it builds). */
    public StorageIOEngine newEngine() {
        var f = engineFactory;
        return f != null ? f.get() : new FileChannelStorageIOEngine(props.isFsync(), engineListener);
    }

    /** Metrics receiver given to every table this cache creates (also used by ResizeService for its new tables). */
    public StorageMetrics metrics() {
        return metrics;
    }

    /** Engine events receiver given to every table this cache creates (also used by ResizeService). */
    public EngineListener engineListener() {
        return engineListener;
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
        if (memo != null && !gate.isReplaced(entity.getId())) return memo;
        try (ResidentHandle<CuckooHashTable> h = acquire(entity)) {
            return h.get().getFillStats();
        }
    }

    /**
     * Pins the table of a blob, loading it from disk if it is not resident. Close the handle when done.
     *
     * @throws BlobService.BlobGoneException if a resize replaced the blob
     */
    public ResidentHandle<CuckooHashTable> acquire(BlobFileEntity entity) throws IOException {
        if (entity.getKind() != BlobKind.CUCKOO) {
            throw new IllegalArgumentException("Blob " + entity.getId() + " is a " + entity.getKind()
                    + " blob, not a cuckoo table");
        }
        if (gate.isReplaced(entity.getId())) {
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
        gate.forget(blobId);
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
                bf, newEngine(), new XxHash64BytesHasher(),
                e.getNumBuckets(), e.getChunkSize(), props.getMaxEvictions(), engineListener);
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
