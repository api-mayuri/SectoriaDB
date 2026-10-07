package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.placement.RendezvousPlacement;
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
    /** One lock per pool serialises every decision that adds blobs (first blobs, growth): exactly one creator wins. */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> poolLocks = new java.util.concurrent.ConcurrentHashMap<>();
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
        for (BlobFileEntity existing : cuckooBlobsOf(pool)) {
            if (existing.getChunkSize() != chunkSize) {
                // chunk keys, dedup and the chunk index are pool-wide: a pool has one chunk size
                throw new IllegalArgumentException("Pool '" + pool.getName() + "' already has blobs with chunk size "
                        + existing.getChunkSize() + "; all cuckoo blobs of a pool must use the same chunk size");
            }
        }
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

    // ── Pool growth (doc 09) ──────────────────────────────────────────────────

    /** Aggregate fill of the cuckoo blobs of a pool; {@code usedSlots} counts active and quarantined slots. */
    public record PoolFill(int blobs, long usedSlots, long totalSlots) {
        public double fillPercent() {
            return totalSlots == 0 ? 100.0 : 100.0 * usedSlots / totalSlots;
        }
    }

    /** Result of {@link #growIfNeeded}. */
    public record Growth(Kind kind, BlobFileEntity created) {
        public enum Kind {
            /** this call created the blob */
            CREATED,
            /** another blob can take chunks (a concurrent writer already grew the pool): just try again */
            USABLE_EXISTS,
            /** {@code sectoriadb.pool.max-blobs} reached and nothing has room */
            AT_MAX
        }
    }

    private Object poolLock(String poolId) {
        return poolLocks.computeIfAbsent(poolId, k -> new Object());
    }

    /** The cuckoo blobs of the pool (small-object blobs are not part of chunk placement). */
    public List<BlobFileEntity> cuckooBlobsOf(PoolEntity pool) {
        return blobRepo.findByPoolId(pool.getId()).stream().filter(b -> b.getKind() == BlobKind.CUCKOO).toList();
    }

    public PoolFill poolFill(PoolEntity pool) throws IOException {
        long used = 0, total = 0;
        List<BlobFileEntity> blobs = cuckooBlobsOf(pool);
        for (BlobFileEntity b : blobs) {
            CuckooHashTable.FillStats st = cache.get(b).getFillStats();
            used += st.activeSlots() + st.quarantinedSlots();
            total += st.totalSlots();
        }
        return new PoolFill(blobs.size(), used, total);
    }

    /**
     * The pool's cuckoo blobs; when it has none, creates {@code sectoriadb.pool.initial-blobs} of them first.
     * Concurrent first writes to a fresh bucket must not each create their own blob: every blob is a sparse file of
     * several GiB with an in-memory table of ~9 MiB that stays loaded (a 200-thread warp run created 15 blobs in one
     * bucket and exhausted a 1 GiB heap after about 25 buckets), so the check is repeated under the pool's lock.
     */
    public List<BlobFileEntity> ensureInitialBlobs(PoolEntity pool) throws IOException {
        List<BlobFileEntity> blobs = cuckooBlobsOf(pool);
        if (!blobs.isEmpty()) return blobs;
        synchronized (poolLock(pool.getId())) {
            blobs = cuckooBlobsOf(pool);   // another thread may have created them while this one waited
            if (!blobs.isEmpty()) return blobs;
            int n = Math.min(props.getPool().getInitialBlobs(), props.getPool().getMaxBlobs());
            log.info("No blob files in pool '{}': creating {} with defaults", pool.getName(), n);
            for (int i = 0; i < n; i++) {
                create(pool, props.getDefaultNumBuckets(), props.getDefaultChunkSize());
                if (i > 0) cache.metrics().poolGrown();
            }
            return cuckooBlobsOf(pool);
        }
    }

    /**
     * Adds a blob when the aggregate fill of the pool has reached {@code sectoriadb.pool.grow-threshold-percent}
     * (re-evaluated under the pool's lock, so concurrent callers create one blob, not one each). Empty when there is
     * nothing to do or the pool is at {@code max-blobs}.
     */
    public java.util.Optional<BlobFileEntity> growIfOverThreshold(PoolEntity pool) throws IOException {
        PoolFill quick = poolFill(pool);
        if (quick.blobs() == 0 || quick.fillPercent() < props.getPool().getGrowThresholdPercent()) {
            return java.util.Optional.empty();
        }
        synchronized (poolLock(pool.getId())) {
            PoolFill fill = poolFill(pool);
            if (fill.blobs() == 0 || fill.fillPercent() < props.getPool().getGrowThresholdPercent()
                    || fill.blobs() >= props.getPool().getMaxBlobs()) {
                return java.util.Optional.empty();
            }
            log.info("Pool '{}' is {}% full (threshold {}%): adding a blob ({} -> {})", pool.getName(),
                    String.format("%.1f", fill.fillPercent()), props.getPool().getGrowThresholdPercent(),
                    fill.blobs(), fill.blobs() + 1);
            return java.util.Optional.of(addBlob(pool));
        }
    }

    /**
     * No blob of the pool accepted a chunk ({@code refused} are the ones that said so). Under the pool's lock: if some
     * other blob can take chunks (a concurrent writer already grew the pool) the caller just retries; else a new blob
     * is created, unless the pool is at {@code max-blobs}. Exactly one of N concurrent callers creates the blob.
     */
    public Growth growIfNeeded(PoolEntity pool, java.util.Set<String> refused) throws IOException {
        synchronized (poolLock(pool.getId())) {
            List<BlobFileEntity> blobs = cuckooBlobsOf(pool);
            for (BlobFileEntity b : blobs) {
                if (refused.contains(b.getId()) || cache.isFrozen(b.getId())) continue;
                CuckooHashTable.FillStats st = cache.get(b).getFillStats();
                if (RendezvousPlacement.weightOf(st.totalSlots(), st.activeSlots() + st.quarantinedSlots(),
                        b.getChunkSize()) > 0) {
                    return new Growth(Growth.Kind.USABLE_EXISTS, null);
                }
            }
            if (blobs.size() >= props.getPool().getMaxBlobs()) {
                return new Growth(Growth.Kind.AT_MAX, null);
            }
            log.warn("Pool '{}': none of its {} blob(s) accepts a chunk, adding one", pool.getName(), blobs.size());
            return new Growth(Growth.Kind.CREATED, addBlob(pool));
        }
    }

    private BlobFileEntity addBlob(PoolEntity pool) throws IOException {
        BlobFileEntity created = create(pool, props.getDefaultNumBuckets(), props.getDefaultChunkSize());
        cache.metrics().poolGrown();
        return created;
    }

    /**
     * The cuckoo blob of this pool with the most free slots; creates the initial blobs if the pool has none.
     * (Chunk placement itself uses weighted rendezvous hashing, see {@code ChunkStore}; this is the "emptiest blob"
     * view used by tools and tests.)
     */
    public BlobFileEntity chooseBlobFileForWrite(PoolEntity pool) throws IOException {
        List<BlobFileEntity> blobs = ensureInitialBlobs(pool);
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

    /**
     * Placement weight of a cuckoo blob (see {@link RendezvousPlacement#weightOf}): its free capacity, scaled down
     * steeply when it is nearly full. A frozen blob (being resized) and a damaged one (more than 5 % of its slots
     * quarantined) get weight 0: they are used only after every other blob refused a chunk.
     */
    public double placementWeight(BlobFileEntity b) throws IOException {
        CuckooHashTable.FillStats st = cache.get(b).getFillStats();
        if (cache.isFrozen(b.getId()) || st.quarantinedSlots() * 20L > st.totalSlots()) return 0;
        return RendezvousPlacement.weightOf(st.totalSlots(), st.activeSlots() + st.quarantinedSlots(), b.getChunkSize());
    }

    /**
     * The table of a blob by id (chunk index entries name blobs by id): from the cache without any metastore access
     * when it is loaded, else the blob record is read and the table loaded.
     */
    public CuckooHashTable tableOf(String blobId) throws IOException {
        CuckooHashTable t = cache.getLoaded(blobId);
        if (t != null) return t;
        BlobFileEntity e = blobRepo.findById(blobId)
                .orElseThrow(() -> new BlobGoneException(blobId));
        return cache.get(e);
    }

    /** The blob named by a chunk index entry no longer exists (replaced by a resize or deleted since the lookup). */
    public static class BlobGoneException extends IOException {
        public BlobGoneException(String blobId) {
            super("Blob file not found: " + blobId);
        }
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
                    " live object(s) / referenced chunk(s) are still stored in it. Delete those objects first.");
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
