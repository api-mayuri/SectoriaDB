package org.example.sectoriadb.service;

import jakarta.annotation.PreDestroy;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Cache of open {@link SmallObjectBlob}s keyed by blob file id (the counterpart of {@link HashTableCache}): reference
 * counted handles and LRU eviction bounded by {@code sectoriadb.cache.max-open-small-blobs}, so a server with thousands of
 * buckets does not hold one file descriptor per bucket forever. Opening a blob runs recovery (a scan of the log tail),
 * so an idle blob is the one that is closed. A blob that is sealed (a compaction is working on it) is not evicted: the
 * seal is in-memory state.
 */
@Service
public class SmallBlobCache {

    private final ResidentCache<SmallObjectBlob> cache;
    private final StorageProperties props;
    private final StorageMetrics metrics;

    public SmallBlobCache(StorageProperties props) {
        this(props, StorageMetrics.NOOP);
    }

    @Autowired
    public SmallBlobCache(StorageProperties props, StorageMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        this.cache = new ResidentCache<>("small-object blob", props.getCache().getMaxOpenSmallBlobs(), 0,
                b -> 1, b -> !b.isSealed(), SmallObjectBlob::close, null);
    }

    /** The small-object blobs currently open (a snapshot, not pinned: for aggregate gauges and path checks only). */
    public Collection<SmallObjectBlob> openBlobs() {
        return cache.snapshot();
    }

    /** Pins the blob, opening it (and running its recovery) if it is not open. Close the handle when done. */
    public ResidentHandle<SmallObjectBlob> acquire(BlobFileEntity entity) throws IOException {
        if (entity.getKind() != BlobKind.SMALL) {
            throw new IllegalArgumentException("Blob " + entity.getId() + " is a " + entity.getKind()
                    + " blob, not a small-object blob");
        }
        return cache.acquire(entity.getId(), id -> {
            org.slf4j.LoggerFactory.getLogger(SmallBlobCache.class).info("Opening small-object blob: blobId={} path={}",
                    id, entity.getFilePath());
            return SmallObjectBlob.open(id, Path.of(entity.getFilePath()), props.isFsync(),
                    props.getSmallObject().getMaxFileBytes(),
                    props.getSmallObject().getCheckpointIntervalBytes(), metrics);
        });
    }

    /** Number of blobs open right now. */
    public int openCount() {
        return cache.size();
    }

    /** Blobs closed by the LRU policy since the start of the process. */
    public long evictions() {
        return cache.evictions();
    }

    /** Removes the blob from the cache (before its file is deleted); it closes when its last handle is released. */
    public void evict(String blobId) {
        cache.evict(blobId);
    }

    @PreDestroy
    public void closeAll() {
        cache.closeAll();
    }
}
