package org.example.service;

import org.example.config.StorageProperties;
import org.example.entities.BlobFile;
import org.example.entity.BlobFileEntity;
import org.example.services.impl.CuckooHashTable;
import org.example.services.impl.FileChannelStorageIOEngine;
import org.example.tools.MurmurBytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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
        if (cache.remove(blobId) != null) {
            log.info("Evicted table from cache: blobId={}", blobId);
        }
    }

    private CuckooHashTable load(BlobFileEntity e) throws IOException {
        log.info("Loading CuckooHashTable: blobId={} path={}", e.getId(), e.getFilePath());
        BlobFile bf = new BlobFile(e.getId(), Path.of(e.getFilePath()), e.getTotalBytes());
        CuckooHashTable table = new CuckooHashTable(
                bf, new FileChannelStorageIOEngine(), new MurmurBytesHasher(),
                e.getNumBuckets(), e.getChunkSize(), props.getMaxEvictions());
        table.loadMetadataFromDisk();
        log.debug("Loaded: blobId={} fill={}%", e.getId(), String.format("%.1f", table.getFillStats().fillPercent()));
        return table;
    }
}
