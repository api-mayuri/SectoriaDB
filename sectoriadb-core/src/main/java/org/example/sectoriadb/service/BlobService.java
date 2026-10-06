package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BlobService {

    private static final Logger log = LoggerFactory.getLogger(BlobService.class);

    private final BlobFileRepository blobRepo;
    private final HashTableCache cache;
    private final SmallBlobCache smallCache;
    private final Object smallChooseLock = new Object();
    private final StorageProperties props;
    private final OperationLogService opLog;

    public BlobService(BlobFileRepository blobRepo,
                       HashTableCache cache, SmallBlobCache smallCache,
                       StorageProperties props, OperationLogService opLog) {
        this.blobRepo     = blobRepo;
        this.cache        = cache;
        this.smallCache   = smallCache;
        this.props        = props;
        this.opLog        = opLog;
    }

    public BlobFileEntity create(PoolEntity pool, int numBuckets, int chunkSize) throws IOException {
        long t0 = System.currentTimeMillis();
        String blobId  = UUID.randomUUID().toString();
        String fileName = "blob_" + blobId.substring(0, 8) + ".raw";
        Path filePath   = Path.of(pool.getBasePath()).resolve(fileName);

        log.info("Creating blob file: pool={} buckets={} chunkSize={} path={}",
                pool.getName(), numBuckets, chunkSize, filePath);

        long size = CuckooHashTable.computeRequiredBlobSize(numBuckets, chunkSize);
        BlobLayout.createFile(filePath, numBuckets, chunkSize);

        BlobFileEntity entity = new BlobFileEntity();
        entity.setId(blobId);
        entity.setPool(pool);          // also sets poolId
        entity.setFileName(fileName);
        entity.setFilePath(filePath.toString());
        entity.setNumBuckets(numBuckets);
        entity.setChunkSize(chunkSize);
        entity.setTotalBytes(size);
        entity.setCreatedAt(Instant.now());
        blobRepo.save(entity);

        opLog.success("BLOB_CREATE", blobId, fileName,
                Map.of("poolId", pool.getId(), "numBuckets", numBuckets,
                        "chunkSize", chunkSize, "totalBytes", size),
                System.currentTimeMillis() - t0);
        log.info("Blob created: id={} size={} (sparse)", blobId, humanSize(size));
        return entity;
    }

    /** Creates an empty small-object blob (append-only log of whole small objects) in the pool. */
    public BlobFileEntity createSmall(PoolEntity pool) throws IOException {
        long t0 = System.currentTimeMillis();
        String blobId   = UUID.randomUUID().toString();
        String fileName = "small_" + blobId.substring(0, 8) + ".sob";
        Path filePath   = Path.of(pool.getBasePath()).resolve(fileName);

        SmallObjectBlob.create(filePath);

        BlobFileEntity entity = new BlobFileEntity();
        entity.setId(blobId);
        entity.setKind(BlobKind.SMALL);
        entity.setPool(pool);
        entity.setFileName(fileName);
        entity.setFilePath(filePath.toString());
        entity.setNumBuckets(0);
        entity.setChunkSize(0);
        entity.setTotalBytes(org.example.sectoriadb.format.SmallBlobLayout.HEADER_SIZE);
        entity.setCreatedAt(Instant.now());
        blobRepo.save(entity);

        opLog.success("BLOB_CREATE", blobId, fileName,
                Map.of("poolId", pool.getId(), "kind", "SMALL"),
                System.currentTimeMillis() - t0);
        log.info("Small-object blob created: id={} path={}", blobId, filePath);
        return entity;
    }

    /**
     * Returns an open, writable small-object blob of the pool that still has room for a record of
     * {@code dataLength} bytes (newest first); creates a new one when none has (rollover).
     */
    public BlobFileEntity chooseSmallBlobForWrite(PoolEntity pool, int dataLength) throws IOException {
        synchronized (smallChooseLock) {
            List<BlobFileEntity> blobs = blobRepo.findByPoolId(pool.getId()).stream()
                    .filter(b -> b.getKind() == BlobKind.SMALL)
                    .sorted(Comparator.comparing(BlobFileEntity::getCreatedAt).reversed())
                    .toList();
            for (BlobFileEntity b : blobs) {
                try {
                    if (smallCache.get(b).hasRoom(dataLength)) {
                        return b;
                    }
                } catch (IOException e) {
                    log.error("Small-object blob {} cannot be opened and is skipped for writes: {}",
                            b.getId(), e.getMessage());
                }
            }
            return createSmall(pool);
        }
    }

    public SmallObjectBlob.Stats getSmallStats(BlobFileEntity entity) throws IOException {
        return smallCache.get(entity).stats();
    }

    public SmallObjectBlob.ScrubReport scrubSmall(BlobFileEntity entity) throws IOException {
        return smallCache.get(entity).scrub();
    }

    public List<BlobFileEntity> listByPool(PoolEntity pool) {
        return blobRepo.findByPoolId(pool.getId());
    }

    public BlobFileEntity getById(String id) {
        return blobRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Blob file not found: " + id));
    }

    public CuckooHashTable.FillStats getFillStats(BlobFileEntity entity) throws IOException {
        return cache.get(entity).getFillStats();
    }

    /**
     * Returns the cuckoo blob file in this pool with the most free slots (small-object blobs are ignored).
     * Auto-creates one with default settings if the pool has no blobs yet.
     */
    public BlobFileEntity chooseBlobFileForWrite(PoolEntity pool) throws IOException {
        List<BlobFileEntity> blobs = blobRepo.findByPoolId(pool.getId()).stream()
                .filter(b -> b.getKind() == BlobKind.CUCKOO).toList();
        if (blobs.isEmpty()) {
            log.info("No blob files in pool '{}' — auto-creating one with defaults", pool.getName());
            return create(pool, props.getDefaultNumBuckets(), props.getDefaultChunkSize());
        }
        BlobFileEntity best = null;
        int maxFree = -1;
        for (BlobFileEntity b : blobs) {
            CuckooHashTable.FillStats stats = cache.get(b).getFillStats();
            int free = stats.totalSlots() - stats.activeSlots();
            if (free > maxFree) {
                maxFree = free;
                best = b;
            }
        }
        return best;
    }

    public void delete(String blobId) throws IOException {
        long t0 = System.currentTimeMillis();
        BlobFileEntity entity = getById(blobId);

        try {
            // one transaction: re-checks that no live manifest references the blob, then drops the record and the
            // dead manifests that still point at it
            blobRepo.deleteUnreferenced(blobId);
        } catch (BlobFileRepository.BlobInUseException e) {
            throw new IllegalStateException(
                    "Cannot delete blob '" + entity.getFileName() + "' — " + e.liveManifests() +
                    " file(s) still reference it. Soft-delete those files first with 'file-delete'.");
        }
        // the record is gone; a crash before the file is removed leaves an unreferenced file (garbage)
        cache.evict(blobId);
        smallCache.evict(blobId);
        Files.deleteIfExists(Path.of(entity.getFilePath()));

        opLog.success("BLOB_DELETE", blobId, entity.getFileName(),
                Map.of("poolId", entity.getPoolId()),
                System.currentTimeMillis() - t0);
        log.info("Blob deleted: id={} path={}", blobId, entity.getFilePath());
    }

    // ── Static helpers (also used by ResizeService) ───────────────────────────

    public static BlobFile toEngineBlob(BlobFileEntity entity) {
        return new BlobFile(entity.getId(), Path.of(entity.getFilePath()), entity.getTotalBytes());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1_073_741_824) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}
