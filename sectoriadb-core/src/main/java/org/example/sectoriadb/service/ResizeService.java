package org.example.sectoriadb.service;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Expands one cuckoo blob by migrating it into a new, larger blob, while the pool keeps taking writes and reads
 * (doc 10, part B). The protocol, in order:
 *
 * <ol>
 *   <li><b>Prepare.</b> Create the new file (not registered anywhere: a crash leaves a leftover that the next start
 *       deletes).</li>
 *   <li><b>Freeze.</b> {@link HashTableCache#freeze} takes the blob's writer gate exclusively: it waits for every insert
 *       that is running (writers hold the gate shared from "is it frozen?" to "inserted") and from then on writers
 *       skip the blob. A chunk written before the freeze is therefore in the table the migration reads, and no chunk is
 *       written to the old blob afterwards. A single-blob pool grows by one blob, so writes do not wait for the resize.
 *       The collector leaves a frozen blob alone, dedup reads and ordinary reads continue on the old table.</li>
 *   <li><b>Migrate.</b> Every active chunk is copied under its own key into the new table, one batch of slots at a time
 *       (the old table is not locked while the new one is written), and the new file is forced once.</li>
 *   <li><b>Commit.</b> ONE metastore transaction registers the new blob, repoints the chunk index entries and orphan
 *       records of the old one and removes the old record. A reader sees the old blob with all its chunks or the new one
 *       with all of them.</li>
 *   <li><b>Switch.</b> Uploads that staged chunks into the old blob are redirected to the new id when they commit
 *       ({@link HashTableCache#redirect}), readers that hold an older index snapshot follow the same redirect; the new table
 *       becomes resident; the old table is evicted (it closes when its last user lets go). The old file is deleted after
 *       the grace period by the collector ({@link BlobFileReaper}).</li>
 * </ol>
 * The frozen state and the redirects are memory only: after a restart the pool is whichever blob the metastore
 * names, and leftover files are removed by {@link BlobFileReaper#reconcileAtStartup}.
 */
@Service
public class ResizeService {

    private static final Logger log = LoggerFactory.getLogger(ResizeService.class);

    /** Guard: a table reaches >90% fill before an insert fails (doc 03), but insert cost grows steeply near the limit. */
    private static final double MAX_SAFE_FILL = 0.70;
    /** Slots read per lock acquisition of the old table while migrating. */
    private static final int MIGRATION_BATCH_SLOTS = 512;

    /** Test hook: called at the named stages ({@code created, frozen, migrated, committed, redirected}); an exception aborts the stage. */
    @FunctionalInterface
    public interface StageHook {
        void at(String stage) throws IOException;
    }

    private final BlobFileRepository blobRepo;
    private final PoolRepository poolRepo;
    private final HashTableCache cache;
    private final StorageProperties props;
    private final OperationLogService opLog;
    private final BlobFileReaper reaper;
    private final Set<String> resizing = ConcurrentHashMap.newKeySet();
    private volatile StageHook stageHook;

    @Autowired
    public ResizeService(BlobFileRepository blobRepo, PoolRepository poolRepo, HashTableCache cache,
                         StorageProperties props, OperationLogService opLog, BlobFileReaper reaper) {
        this.blobRepo = blobRepo;
        this.poolRepo = poolRepo;
        this.cache = cache;
        this.props = props;
        this.opLog = opLog;
        this.reaper = reaper;
    }

    public void setStageHook(StageHook hook) {
        this.stageHook = hook;
    }

    private void stage(String name) throws IOException {
        StageHook h = stageHook;
        if (h != null) h.at(name);
    }

    /**
     * Resizes a blob file by creating a new one with {@code newNumBuckets} and migrating all active chunks via cuckoo
     * re-insertion. Rejects if the new size cannot accommodate existing data. The pool keeps accepting writes meanwhile.
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
        if (!resizing.add(blobId)) {
            throw new IllegalStateException("Blob " + blobId + " is already being resized");
        }
        try {
            BlobFileEntity oldEntity = blobRepo.findById(blobId)
                    .orElseThrow(() -> new IllegalArgumentException("Blob not found: " + blobId));
            if (oldEntity.getKind() != org.example.sectoriadb.model.BlobKind.CUCKOO) {
                throw new IllegalArgumentException("Blob " + blobId + " is a " + oldEntity.getKind()
                        + " blob; only cuckoo blobs can be resized");
            }
            try (ResidentHandle<CuckooHashTable> oldHandle = cache.acquire(oldEntity)) {
                return resize(oldEntity, oldHandle.get(), newNumBuckets, t0);
            }
        } finally {
            resizing.remove(blobId);
        }
    }

    private BlobFileEntity resize(BlobFileEntity oldEntity, CuckooHashTable oldTable, int newNumBuckets, long t0)
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

        AutoCloseable building = reaper.beginBuilding(newPath);   // the cleaners must not take the half-built file
        boolean frozen = false;
        boolean committed = false;
        CuckooHashTable newTable = null;
        try {
            BlobLayout.createFile(newPath, newNumBuckets, chunkSize);
            stage("created");

            // Stop placing new chunks into the old blob and wait for the inserts in flight: what the migration copies is
            // then everything the blob will ever hold. Writers rank the other blobs meanwhile (a single-blob pool grows by
            // one blob). The blob stays frozen for good once replaced: a stale handle can never write into the dead file.
            cache.freeze(blobId);
            frozen = true;
            stage("frozen");

            int activeNow = oldTable.getFillStats().activeSlots();   // writers were still active before the freeze
            if (activeNow > activeSlots && activeNow > newTotalSlots * MAX_SAFE_FILL) {
                throw new IllegalArgumentException(String.format(
                        "The blob received chunks while the resize started: %d active chunks would fill %.0f%% of the"
                        + " new blob (max safe: %.0f%%); retry with more buckets",
                        activeNow, 100.0 * activeNow / newTotalSlots, MAX_SAFE_FILL * 100));
            }

            newTable = new CuckooHashTable(
                    new BlobFile(newId, newPath, newSize), cache.newEngine(),
                    new XxHash64BytesHasher(), newNumBuckets, chunkSize, props.getMaxEvictions(), cache.metrics());
            int migrated = migrate(oldTable, newTable, activeNow);
            newTable.barrier();   // the new file is durable before the metastore names it
            stage("migrated");

            BlobFileEntity newEntity = commitResize(oldEntity, newId, newName, newPath.toString(),
                    poolId, newNumBuckets, chunkSize, newSize);
            committed = true;
            stage("committed");

            cache.redirect(blobId, newId);   // uploads that placed chunks into the old blob are re-pointed at commit
            cache.install(newEntity, newTable);
            newTable = null;                 // the cache owns it now
            cache.evict(blobId);             // closes when the last reader of the old table lets go
            reaper.scheduleDeletion(Path.of(oldEntity.getFilePath()),
                    System.currentTimeMillis() + props.getGc().getGrace().toMillis());
            stage("redirected");

            opLog.success("RESIZE", newId, newName,
                    Map.of("oldBlobId", blobId, "oldBuckets", oldEntity.getNumBuckets(),
                            "newBuckets", newNumBuckets, "migratedChunks", migrated),
                    System.currentTimeMillis() - t0);
            log.info("Resize complete: old={} new={} chunks={} in {}ms",
                    blobId, newId, migrated, System.currentTimeMillis() - t0);
            return newEntity;
        } catch (IOException | RuntimeException e) {
            if (!committed) {
                if (newTable != null) {
                    try { newTable.close(); } catch (IOException ignored) { }
                    newTable = null;
                }
                try { Files.deleteIfExists(newPath); } catch (IOException ignored) { }
                if (frozen) cache.unfreeze(blobId);
                frozen = false;
                opLog.failure("RESIZE", blobId, oldEntity.getFileName(), e instanceof IOException io ? io : new IOException(e),
                        System.currentTimeMillis() - t0);
            }
            throw e;
        } finally {
            // An Error (a simulated crash in tests) leaves the files as a dying process would; the in-memory state is
            // harmless either way: a blob that was replaced stays frozen, one that was not is released.
            if (frozen && !committed) cache.unfreeze(blobId);
            try {
                building.close();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    /** Copies every active chunk of the (frozen) old table into the new one under the same key. */
    private int migrate(CuckooHashTable oldTable, CuckooHashTable newTable, int expected) throws IOException {
        AtomicInteger migrated = new AtomicInteger();
        int[] lastPct = {-1};
        try {
            oldTable.forEachActiveChunkInBatches(MIGRATION_BATCH_SLOTS, (key, data) -> {
                try {
                    newTable.insertPreservingKey(key, data); // manifests reference these keys
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                int done = migrated.incrementAndGet();
                if (expected > 0) {
                    int pct = (int) (100.0 * done / expected);
                    if (pct / 10 > lastPct[0] / 10) {
                        lastPct[0] = pct;
                        log.debug("Migration: {}% ({}/{} chunks)", pct, done, expected);
                    }
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return migrated.get();
    }

    /**
     * ONE metastore transaction: registers the new blob, repoints every chunk index entry of the old blob (found
     * through {@code chunks_by_blob}) to it, and removes the old blob record. A reader sees either the old blob
     * with all its chunks or the new one with all of them, never a mix. Manifests are untouched: they name chunks,
     * not blobs. The old file is deleted after the grace period; a crash before that leaves an unreferenced file that
     * the next start removes.
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
