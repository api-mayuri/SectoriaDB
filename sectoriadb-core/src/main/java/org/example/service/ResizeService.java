package org.example.service;

import org.example.config.StorageProperties;
import org.example.model.BlobFileEntity;
import org.example.model.ManifestEntity;
import org.example.model.BlobFile;
import org.example.repository.BlobFileRepository;
import org.example.repository.ManifestRepository;
import org.example.repository.PoolRepository;
import org.example.service.impl.CuckooHashTable;
import org.example.service.impl.FileChannelStorageIOEngine;
import org.example.tools.MurmurBytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ResizeService {

    private static final Logger log = LoggerFactory.getLogger(ResizeService.class);

    private final BlobFileRepository blobRepo;
    private final ManifestRepository manifestRepo;
    private final PoolRepository poolRepo;
    private final HashTableCache cache;
    private final StorageProperties props;
    private final OperationLogService opLog;

    public ResizeService(BlobFileRepository blobRepo, ManifestRepository manifestRepo,
                         PoolRepository poolRepo, HashTableCache cache,
                         StorageProperties props, OperationLogService opLog) {
        this.blobRepo     = blobRepo;
        this.manifestRepo = manifestRepo;
        this.poolRepo     = poolRepo;
        this.cache        = cache;
        this.props        = props;
        this.opLog        = opLog;
    }

    /**
     * Resizes a blob file by creating a new one with {@code newNumBuckets} and
     * migrating all active chunks via cuckoo re-insertion.
     *
     * Rejects if the new size cannot accommodate existing data.
     *
     * @return the replacement BlobFileEntity
     */
    public BlobFileEntity resizeBlobFile(String blobId, int newNumBuckets) throws IOException {
        long t0 = System.currentTimeMillis();
        BlobFileEntity oldEntity = blobRepo.findById(blobId)
                .orElseThrow(() -> new IllegalArgumentException("Blob not found: " + blobId));

        CuckooHashTable oldTable = cache.get(oldEntity);
        CuckooHashTable.FillStats stats = oldTable.getFillStats();
        int activeSlots = stats.activeSlots();

        // Guard: cuckoo hash degrades sharply above ~70% fill — enforce a safe headroom
        final double MAX_SAFE_FILL = 0.70;
        int newTotalSlots  = 2 * newNumBuckets * CuckooHashTable.SLOTS_PER_BUCKET;
        int minSlotsNeeded = (int) Math.ceil(activeSlots / MAX_SAFE_FILL);
        if (newTotalSlots < minSlotsNeeded) {
            int minBuckets = (int) Math.ceil(minSlotsNeeded / (2.0 * CuckooHashTable.SLOTS_PER_BUCKET)) + 1;
            throw new IllegalArgumentException(String.format(
                    "New blob would be %.0f%% full after migration (max safe: %.0f%%). " +
                    "For %d active chunks minimum buckets: %d",
                    100.0 * activeSlots / newTotalSlots, MAX_SAFE_FILL * 100,
                    activeSlots, minBuckets));
        }

        String poolId    = oldEntity.getPoolId();
        String poolBase  = poolRepo.findById(poolId)
                .map(p -> p.getBasePath())
                .orElseThrow(() -> new IllegalStateException("Pool not found for blob: " + blobId));

        int chunkSize = oldEntity.getChunkSize();
        String newId  = UUID.randomUUID().toString();
        String newName = "blob_" + newId.substring(0, 8) + ".raw";
        Path newPath   = Path.of(poolBase).resolve(newName);
        long newSize   = CuckooHashTable.computeRequiredBlobSize(newNumBuckets, chunkSize);

        log.info("Resize: blob={} {} → {} buckets activeSlots={} newSize={} GB",
                blobId, oldEntity.getNumBuckets(), newNumBuckets, activeSlots,
                String.format("%.2f", newSize / 1_073_741_824.0));

        BlobService.allocateSparseFile(newPath, newSize);

        BlobFile newBlobFile = new BlobFile(newId, newPath, newSize);
        CuckooHashTable newTable = new CuckooHashTable(
                newBlobFile, new FileChannelStorageIOEngine(), new MurmurBytesHasher(),
                newNumBuckets, chunkSize, props.getMaxEvictions());

        // Migrate every active chunk from old → new
        AtomicInteger migrated = new AtomicInteger();
        int[] lastPct = {-1};
        try {
            oldTable.forEachActiveChunk((key, data) -> {
                try {
                    newTable.insert(key, data);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                int done = migrated.incrementAndGet();
                if (activeSlots > 0) {
                    int pct = (int)(100.0 * done / activeSlots);
                    if (pct / 10 > lastPct[0] / 10) {
                        lastPct[0] = pct;
                        System.out.printf("  Migration: %d%% (%d/%d chunks)%n", pct, done, activeSlots);
                    }
                }
            });
        } catch (UncheckedIOException e) {
            try { Files.deleteIfExists(newPath); } catch (IOException ignored) {}
            opLog.failure("RESIZE", blobId, oldEntity.getFileName(), e.getCause(),
                    System.currentTimeMillis() - t0);
            throw e.getCause();
        }

        // Persist new entity and rewire manifests
        BlobFileEntity newEntity = commitResize(oldEntity, newId, newName, newPath.toString(),
                poolId, newNumBuckets, chunkSize, newSize);

        cache.evict(blobId);
        try {
            Files.deleteIfExists(Path.of(oldEntity.getFilePath()));
        } catch (IOException e) {
            log.warn("Could not delete old blob file {} — manual cleanup may be needed: {}",
                    oldEntity.getFilePath(), e.getMessage());
        }

        opLog.success("RESIZE", newId, newName,
                Map.of("oldBlobId", blobId, "oldBuckets", oldEntity.getNumBuckets(),
                        "newBuckets", newNumBuckets, "migratedChunks", migrated.get()),
                System.currentTimeMillis() - t0);
        log.info("Resize complete: old={} new={} chunks={} in {}ms",
                blobId, newId, migrated.get(), System.currentTimeMillis() - t0);
        return newEntity;
    }

    private BlobFileEntity commitResize(BlobFileEntity old, String newId, String newName,
                                         String newPath, String poolId, int newBuckets,
                                         int chunkSize, long newSize) {
        BlobFileEntity newEntity = new BlobFileEntity();
        newEntity.setId(newId);
        newEntity.setPoolId(poolId);
        // Resolve and set pool reference for convenience
        poolRepo.findById(poolId).ifPresent(newEntity::setPool);
        newEntity.setFileName(newName);
        newEntity.setFilePath(newPath);
        newEntity.setNumBuckets(newBuckets);
        newEntity.setChunkSize(chunkSize);
        newEntity.setTotalBytes(newSize);
        newEntity.setCreatedAt(Instant.now());
        blobRepo.save(newEntity);

        // Move all manifests (active + deleted) to new blob file
        List<ManifestEntity> manifests = manifestRepo.findByBlobFileId(old.getId());
        for (ManifestEntity m : manifests) {
            m.setBlobFile(newEntity);
        }
        manifestRepo.saveAll(manifests);

        blobRepo.delete(old);
        log.debug("Committed resize in JSON store: oldId={} newId={} manifestsMoved={}",
                old.getId(), newId, manifests.size());
        return newEntity;
    }

    public int computeExpandedBuckets(int currentBuckets) {
        return (int) Math.ceil(currentBuckets * (1.0 + props.getAutoResize().getExpandPercent() / 100.0));
    }
}
