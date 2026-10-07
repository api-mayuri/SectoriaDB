package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ResizeService {

    private static final Logger log = LoggerFactory.getLogger(ResizeService.class);

    /** Guard: a table reaches >90% fill before an insert fails (doc 03), but insert cost grows steeply near the limit. */
    private static final double MAX_SAFE_FILL = 0.70;

    private final BlobFileRepository blobRepo;
    private final PoolRepository poolRepo;
    private final HashTableCache cache;
    private final StorageProperties props;
    private final OperationLogService opLog;

    public ResizeService(BlobFileRepository blobRepo,
                         PoolRepository poolRepo, HashTableCache cache,
                         StorageProperties props, OperationLogService opLog) {
        this.blobRepo     = blobRepo;
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
        long n0 = System.nanoTime();
        boolean ok = false;
        try {
            BlobFileEntity result = doResize(blobId, newNumBuckets);
            ok = true;
            return result;
        } finally {
            cache.metrics().resize(System.nanoTime() - n0, ok);
        }
    }

    private BlobFileEntity doResize(String blobId, int newNumBuckets) throws IOException {
        long t0 = System.currentTimeMillis();
        BlobFileEntity oldEntity = blobRepo.findById(blobId)
                .orElseThrow(() -> new IllegalArgumentException("Blob not found: " + blobId));

        if (oldEntity.getKind() != org.example.sectoriadb.model.BlobKind.CUCKOO) {
            throw new IllegalArgumentException("Blob " + blobId + " is a " + oldEntity.getKind()
                    + " blob; only cuckoo blobs can be resized");
        }
        try (ResidentHandle<CuckooHashTable> oldHandle = cache.acquire(oldEntity)) {
            return doResize(oldEntity, oldHandle.get(), newNumBuckets, t0);
        }
    }

    private BlobFileEntity doResize(BlobFileEntity oldEntity, CuckooHashTable oldTable, int newNumBuckets, long t0)
            throws IOException {
        String blobId = oldEntity.getId();
        CuckooHashTable.FillStats stats = oldTable.getFillStats();
        int activeSlots = stats.activeSlots();
        if (stats.quarantinedSlots() > 0) {
            // Their chunks cannot be read reliably; migrating would silently drop them.
            throw new IllegalStateException("Blob " + blobId + " has " + stats.quarantinedSlots()
                    + " quarantined slot(s) (damaged metadata); run 'scrub' and repair before resizing");
        }

        // Guard: a table reaches >90% fill before an insert fails (see docs/architecture/03-chunk-integrity.md),
        // but insert cost (BFS path length) grows steeply near the limit — keep 70% as headroom after migration.
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

        BlobLayout.createFile(newPath, newNumBuckets, chunkSize);

        // Stop placing new chunks into the old blob and wait for the inserts in flight: what the migration copies is
        // then everything the blob will ever hold. Writers of a multi-blob pool rank the other blobs meanwhile (a
        // single-blob pool grows by one blob). The blob stays frozen for good once replaced: a stale handle can
        // never write into the dead file.
        cache.freeze(blobId);
        boolean replaced = false;
        try {
            int activeNow = oldTable.getFillStats().activeSlots();   // writers were still active before the freeze
            if (activeNow > activeSlots && activeNow > newTotalSlots * MAX_SAFE_FILL) {
                Files.deleteIfExists(newPath);
                throw new IllegalArgumentException(String.format(
                        "The blob received chunks while the resize started: %d active chunks would fill %.0f%% of the"
                        + " new blob (max safe: %.0f%%); retry with more buckets",
                        activeNow, 100.0 * activeNow / newTotalSlots, MAX_SAFE_FILL * 100));
            }
            BlobFileEntity result = migrateAndCommit(oldEntity, oldTable, newId, newName, newPath, poolId,
                    newNumBuckets, chunkSize, newSize, activeSlots, t0);
            replaced = true;
            return result;
        } finally {
            if (!replaced) cache.unfreeze(blobId);
        }
    }

    private BlobFileEntity migrateAndCommit(BlobFileEntity oldEntity, CuckooHashTable oldTable, String newId,
                                            String newName, Path newPath, String poolId, int newNumBuckets,
                                            int chunkSize, long newSize, int activeSlots, long t0) throws IOException {
        String blobId = oldEntity.getId();

        BlobFile newBlobFile = new BlobFile(newId, newPath, newSize);
        CuckooHashTable newTable = new CuckooHashTable(
                newBlobFile, new FileChannelStorageIOEngine(props.isFsync(), cache.metrics()), new XxHash64BytesHasher(),
                newNumBuckets, chunkSize, props.getMaxEvictions(), cache.metrics());

        // Migrate every active chunk from old → new
        AtomicInteger migrated = new AtomicInteger();
        int[] lastPct = {-1};
        try {
            oldTable.forEachActiveChunk((key, data) -> {
                try {
                    newTable.insertPreservingKey(key, data); // manifests reference these keys
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                int done = migrated.incrementAndGet();
                if (activeSlots > 0) {
                    int pct = (int)(100.0 * done / activeSlots);
                    if (pct / 10 > lastPct[0] / 10) {
                        lastPct[0] = pct;
                        log.debug("Migration: {}% ({}/{} chunks)", pct, done, activeSlots);
                    }
                }
            });
        } catch (UncheckedIOException | IOException e) {
            IOException cause = (e instanceof UncheckedIOException u) ? u.getCause() : (IOException) e;
            try { newTable.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(newPath); } catch (IOException ignored) {}
            opLog.failure("RESIZE", blobId, oldEntity.getFileName(), cause,
                    System.currentTimeMillis() - t0);
            throw cause;
        }

        // The cache opens its own table for the new blob on first use; release our channel.
        newTable.close();

        // Persist new entity and rewire manifests
        BlobFileEntity newEntity = commitResize(oldEntity, newId, newName, newPath.toString(),
                poolId, newNumBuckets, chunkSize, newSize);

        cache.redirect(blobId, newId);   // uploads that placed chunks into the old blob are re-pointed at commit
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

    /**
     * ONE metastore transaction: registers the new blob, repoints every chunk index entry of the old blob (found
     * through {@code chunks_by_blob}) to it, and removes the old blob record. A reader sees either the old blob
     * with all its chunks or the new one with all of them, never a mix. Manifests are untouched: they name chunks,
     * not blobs. The caller deletes the old file after the commit; a crash before that leaves an unreferenced file.
     */
    private BlobFileEntity commitResize(BlobFileEntity old, String newId, String newName,
                                         String newPath, String poolId, int newBuckets,
                                         int chunkSize, long newSize) {
        BlobFileEntity newEntity = new BlobFileEntity();
        newEntity.setId(newId);
        newEntity.setPoolId(poolId);
        poolRepo.findById(poolId).ifPresent(newEntity::setPool);
        newEntity.setFileName(newName);
        newEntity.setFilePath(newPath);
        newEntity.setNumBuckets(newBuckets);
        newEntity.setChunkSize(chunkSize);
        newEntity.setTotalBytes(newSize);
        newEntity.setCreatedAt(Instant.now());

        int moved = blobRepo.replaceBlob(old.getId(), newEntity);
        log.debug("Committed resize in the metastore: oldId={} newId={} chunkEntriesMoved={}", old.getId(), newId, moved);
        return newEntity;
    }
}
