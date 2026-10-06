package org.example.sectoriadb.service;

import jakarta.annotation.PreDestroy;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of open {@link SmallObjectBlob}s keyed by blob file id (the counterpart of {@link HashTableCache}).
 * Opening a blob runs recovery, so each is opened once per process and closed on {@link #evict}/shutdown.
 */
@Service
public class SmallBlobCache {

    private static final Logger log = LoggerFactory.getLogger(SmallBlobCache.class);

    private final ConcurrentHashMap<String, SmallObjectBlob> cache = new ConcurrentHashMap<>();
    private final StorageProperties props;

    public SmallBlobCache(StorageProperties props) {
        this.props = props;
    }

    public SmallObjectBlob get(BlobFileEntity entity) throws IOException {
        if (entity.getKind() != BlobKind.SMALL) {
            throw new IllegalArgumentException("Blob " + entity.getId() + " is a " + entity.getKind()
                    + " blob, not a small-object blob");
        }
        try {
            return cache.computeIfAbsent(entity.getId(), id -> {
                try {
                    log.info("Opening small-object blob: blobId={} path={}", id, entity.getFilePath());
                    return SmallObjectBlob.open(id, Path.of(entity.getFilePath()), props.isFsync(),
                            props.getSmallObject().getMaxFileBytes(),
                            props.getSmallObject().getCheckpointIntervalBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public void evict(String blobId) {
        SmallObjectBlob b = cache.remove(blobId);
        if (b != null) {
            try {
                b.close();
            } catch (IOException e) {
                log.warn("Could not close small-object blob {}: {}", blobId, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void closeAll() {
        for (String id : List.copyOf(cache.keySet())) {
            evict(id);
        }
    }
}
