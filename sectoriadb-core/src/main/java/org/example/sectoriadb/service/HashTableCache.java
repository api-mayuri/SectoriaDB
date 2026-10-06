package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of live CuckooHashTable instances keyed by blob file id.
 *
 * Loading a hash table reads the entire metadata section from disk
 * (up to several hundred MB for large blobs), so we cache each table
 * for the process lifetime. Call {@link #evict} before replacing a blob file.
 */
@Service
public class HashTableCache {

    private static final Logger log = LoggerFactory.getLogger(HashTableCache.class);

    private final ConcurrentHashMap<String, CuckooHashTable> cache = new ConcurrentHashMap<>();
    private final StorageProperties props;

    public HashTableCache(StorageProperties props) {
        this.props = props;
    }

    /** Returns a cached or freshly-loaded CuckooHashTable. */
    public CuckooHashTable get(BlobFileEntity entity) throws IOException {
        if (entity.getKind() != org.example.sectoriadb.model.BlobKind.CUCKOO) {
            throw new IllegalArgumentException("Blob " + entity.getId() + " is a " + entity.getKind()
                    + " blob, not a cuckoo table");
        }
        try {
            return cache.computeIfAbsent(entity.getId(), id -> {
                try {
                    return load(entity);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Removes a blob file from the cache (call before deleting or replacing it). */
    public void evict(String blobId) {
        CuckooHashTable table = cache.remove(blobId);
        if (table != null) {
            log.info("Evicted table from cache: blobId={}", blobId);
            closeQuietly(table, blobId);
        }
    }

    /** Closes all cached tables (and their file channels) on shutdown. */
    @PreDestroy
    public void closeAll() {
        for (String id : List.copyOf(cache.keySet())) {
            evict(id);
        }
    }

    private void closeQuietly(CuckooHashTable table, String blobId) {
        try {
            table.close();
        } catch (IOException e) {
            log.warn("Could not close table: blobId={}: {}", blobId, e.getMessage());
        }
    }

    private CuckooHashTable load(BlobFileEntity e) throws IOException {
        log.info("Loading CuckooHashTable: blobId={} path={}", e.getId(), e.getFilePath());
        BlobFile bf = new BlobFile(e.getId(), Path.of(e.getFilePath()), e.getTotalBytes());
        CuckooHashTable table = new CuckooHashTable(
                bf, new FileChannelStorageIOEngine(props.isFsync()), new XxHash64BytesHasher(),
                e.getNumBuckets(), e.getChunkSize(), props.getMaxEvictions());
        try {
            table.loadMetadataFromDisk();
        } catch (IOException | RuntimeException ex) {
            closeQuietly(table, e.getId());
            throw ex;
        }
        log.debug("Loaded: blobId={} fill={}%", e.getId(), String.format("%.1f", table.getFillStats().fillPercent()));
        return table;
    }
}
