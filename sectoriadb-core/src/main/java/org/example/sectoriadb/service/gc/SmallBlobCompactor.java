package org.example.sectoriadb.service.gc;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.GcRepository;
import org.example.sectoriadb.repository.GcRepository.SmallMove;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.ChunkStore;
import org.example.sectoriadb.service.ResidentHandle;
import org.example.sectoriadb.service.SmallBlobCache;
import org.example.sectoriadb.service.UploadGate;
import org.example.sectoriadb.service.gc.GcReports.CompactionReport;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Compaction of small-object blobs (doc 10): a {@code small_*.sob} whose dead bytes exceed a threshold is rewritten
 * into a new file that holds only the live records, the manifests are repointed in ONE metastore transaction and the
 * old file is deleted after the grace period.
 *
 * <ol>
 *   <li><b>Seal and drain.</b> The old blob is closed for ordinary appends and the upload gate of the pool is drained
 *       once: every record that was appended before is either referenced by a committed manifest or garbage, and none
 *       can be added any more. (A small upload holds the gate from its append to its commit, see {@code UploadGate}.)</li>
 *   <li><b>Copy.</b> The new blob is created already sealed (writers never see it). Each live record is read with its
 *       CRC32C verified (a damaged record aborts the compaction: nothing is lost) and appended; copies run on a few
 *       threads so that the appends share group fsyncs. Readers and writers carry on meanwhile: the old file is not touched.</li>
 *   <li><b>Swap.</b> One exclusive transaction re-checks that every live manifest is still at its old offset, repoints
 *       them to the new blob/offset, drops the dead manifests that named the old blob and deletes the old blob record.
 *       A reader that holds an old manifest still reads the old file, which stays until the grace period has passed.</li>
 *   <li><b>Release.</b> The new blob is unsealed (it takes appends like any other); copies of manifests that were retired
 *       between the snapshot and the swap are marked DELETED; the old file is closed and removed after the grace period
 *       ({@link #releaseOldFiles}); a restart finds leftovers by {@link #deleteUnregisteredFiles}.</li>
 * </ol>
 * A crash before the swap leaves the old blob authoritative and an unreferenced new file; after the swap the old file is
 * unreferenced. Both are harmless garbage that {@link #deleteUnregisteredFiles} removes.
 */
@Component
public class SmallBlobCompactor {

    private static final Logger log = LoggerFactory.getLogger(SmallBlobCompactor.class);
    private static final int COPY_THREADS = 8;
    /** An unregistered small file younger than this is never deleted (it may be a blob that is being created). */
    private static final long MIN_FILE_AGE_MILLIS = 60_000;

    private final BlobFileRepository blobRepo;
    private final ManifestRepository manifestRepo;
    private final PoolRepository poolRepo;
    private final GcRepository gcRepo;
    private final BlobService blobs;
    private final SmallBlobCache smallCache;
    private final ChunkStore chunkStore;
    private final StorageProperties props;
    private final StorageMetrics metrics;
    private final ReentrantLock running = new ReentrantLock();

    private record PendingFile(String blobId, Path path, long dueMillis) {
    }

    private final ConcurrentLinkedQueue<PendingFile> pending = new ConcurrentLinkedQueue<>();

    @Autowired
    public SmallBlobCompactor(BlobFileRepository blobRepo, ManifestRepository manifestRepo, PoolRepository poolRepo,
                              GcRepository gcRepo, BlobService blobs, SmallBlobCache smallCache, ChunkStore chunkStore,
                              StorageProperties props, StorageMetrics metrics) {
        this.blobRepo = blobRepo;
        this.manifestRepo = manifestRepo;
        this.poolRepo = poolRepo;
        this.gcRepo = gcRepo;
        this.blobs = blobs;
        this.smallCache = smallCache;
        this.chunkStore = chunkStore;
        this.props = props;
        this.metrics = metrics;
    }

    /** Old files waiting for their grace period (tests, status). */
    public int pendingFiles() {
        return pending.size();
    }

    // ------------------------------------------------------------------ selection

    /** Compacts every eligible small-object blob of the pool (all pools when null). Returns one report per attempt. */
    public List<CompactionReport> compactEligible(String poolId, boolean force) {
        List<CompactionReport> out = new ArrayList<>();
        for (PoolEntity pool : poolRepo.findAll()) {
            if (poolId != null && !pool.getId().equals(poolId)) continue;
            List<BlobFileEntity> smalls = blobRepo.findByPoolId(pool.getId()).stream()
                    .filter(b -> b.getKind() == BlobKind.SMALL).toList();
            for (BlobFileEntity b : smalls) {
                CompactionReport r = compact(pool, b, force);
                if (r.compacted() || !"not eligible".equals(r.reason())) out.add(r);
            }
        }
        return out;
    }

    /** True if the blob has enough dead space to be worth compacting. */
    public boolean eligible(BlobFileEntity blob) {
        try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blob)) {
            SmallObjectBlob.Stats st = h.get().stats();
            long total = st.liveBytes() + st.deadBytes();
            if (total == 0 || st.deadBytes() < props.getGc().getSmallCompactMinDeadBytes()) return false;
            return st.deadBytes() * 100 >= total * props.getGc().getSmallCompactDeadPercent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * True if the blob holds so many records that no manifest references (copies left by a compaction that crashed before
     * its swap, small uploads that wrote their record and never committed) that rewriting it pays: the same thresholds as
     * for dead records. Counts the live manifests of the blob, so it is a sweep-time check, not a per-minute one.
     */
    public boolean strayHeavy(BlobFileEntity blob) {
        try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blob)) {
            SmallObjectBlob.Stats st = h.get().stats();
            if (st.liveRecords() == 0) return false;
            long referenced = manifestRepo.countLiveByBlobId(blob.getId());
            long strays = Math.max(0, st.liveRecords() - referenced);
            long strayBytes = strays * (st.liveBytes() / st.liveRecords());
            long total = st.liveBytes() + st.deadBytes();
            return strayBytes > 0 && strayBytes >= props.getGc().getSmallCompactMinDeadBytes()
                    && strayBytes * 100 >= total * props.getGc().getSmallCompactDeadPercent();
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ compaction

    /**
     * Compacts one blob. {@code force} skips the dead-ratio threshold (admin command, tests) but never the append-target
     * rule. One compaction at a time.
     */
    public CompactionReport compact(PoolEntity pool, BlobFileEntity old, boolean force) {
        long t0 = System.nanoTime();
        running.lock();
        try {
            CompactionReport r = doCompact(pool, old, force);
            if (!"not eligible".equals(r.reason())) {
                metrics.gcCompaction(System.nanoTime() - t0, r.reclaimedBytes(), r.compacted());
            }
            return r;
        } finally {
            running.unlock();
        }
    }

    private CompactionReport doCompact(PoolEntity pool, BlobFileEntity old, boolean force) {
        long t0 = System.currentTimeMillis();
        if (old.getKind() != BlobKind.SMALL) return fail(old, "not a small-object blob", t0);
        if (blobRepo.findById(old.getId()).isEmpty()) return fail(old, "blob no longer exists", t0);
        if (!force && !eligible(old)) return fail(old, "not eligible", t0);
        if (blobs.smallAppendTarget(pool).map(b -> b.getId().equals(old.getId())).orElse(false)) {
            metrics.gcDeferred(StorageMetrics.GcDeferral.NOT_ELIGIBLE);
            return fail(old, "the blob is the append target of its pool", t0);
        }
        ResidentHandle<SmallObjectBlob> oldHandle;
        try {
            oldHandle = smallCache.acquire(old);
        } catch (IOException e) {
            return fail(old, "cannot open: " + e.getMessage(), t0);
        }
        try (oldHandle) {
            return compactOpened(pool, old, oldHandle.get(), t0);
        }
    }

    private CompactionReport compactOpened(PoolEntity pool, BlobFileEntity old, SmallObjectBlob oldBlob, long t0) {
        long bytesBefore = oldBlob.tail();
        boolean wasSealed = oldBlob.isSealed();
        oldBlob.seal();
        BlobFileEntity target = null;
        boolean swapped = false;
        try {
            // 1. drain: no upload that wrote into the old blob is still between "written" and "committed"
            UploadGate.Sweep gate = chunkStore.gate().tryBegin(pool.getId(),
                    props.getGc().getSweepGateWait().toMillis(), props.getGc().getUploadTicketTtl().toMillis());
            if (gate == null) {
                metrics.gcDeferred(StorageMetrics.GcDeferral.UPLOADS_IN_FLIGHT);
                return fail(old, "uploads are in flight, retry later", t0);
            }
            gate.close();   // sealed: nothing new can be appended, new uploads may go to other blobs

            // 2. the live records: after the drain this set is final
            List<ManifestEntity> live = new ArrayList<>(manifestRepo.findByBlobId(old.getId(), true));
            live.sort(Comparator.comparingLong(ManifestEntity::getSmallOffset));

            // 3. copy into a new, sealed blob
            target = blobs.createSmall(pool, true);
            try (ResidentHandle<SmallObjectBlob> targetHandle = smallCache.acquire(target)) {
                SmallObjectBlob targetBlob = targetHandle.get();
                Map<String, SmallMove> moves = copy(oldBlob, targetBlob, live);

                // 4. swap
                GcRepository.CompactionSwap swap = gcRepo.swapSmallBlob(old.getId(), target.getId(), moves);
                if (!swap.swapped()) {
                    log.warn("Compaction of small blob {} abandoned at the swap: {}", old.getId(), swap.reason());
                    abandonTarget(target);
                    target = null;
                    return fail(old, "swap refused: " + swap.reason(), t0);
                }
                swapped = true;

                // 5. release
                long bytesAfter = targetBlob.tail();
                targetBlob.unseal();
                for (String id : swap.retired()) {
                    try {
                        targetBlob.markDeleted(moves.get(id).newOffset());
                    } catch (IOException e) {
                        log.warn("Could not mark the copy of retired manifest {} DELETED: {}", id, e.getMessage());
                    }
                }
                pending.add(new PendingFile(old.getId(), Path.of(old.getFilePath()),
                        System.currentTimeMillis() + props.getGc().getGrace().toMillis()));
                log.info("Compacted small blob {} into {}: {} record(s), {} -> {} bytes", old.getId(), target.getId(),
                        swap.repointed(), bytesBefore, bytesAfter);
                return new CompactionReport(old.getId(), true, null, swap.repointed(), bytesBefore,
                        bytesAfter, target.getId(), System.currentTimeMillis() - t0);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Compaction of small blob {} failed (the old blob stays authoritative): {}", old.getId(), e.toString());
            metrics.gcError();
            if (target != null && !swapped) abandonTarget(target);
            return fail(old, "failed: " + e, t0);
        } finally {
            if (!swapped && !wasSealed) oldBlob.unseal();
        }
    }

    private Map<String, SmallMove> copy(SmallObjectBlob from, SmallObjectBlob to, List<ManifestEntity> live) throws IOException {
        Map<String, SmallMove> moves = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(COPY_THREADS, Math.max(1, live.size())), r -> {
            Thread t = new Thread(r, "small-compaction-copy");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<SmallMove>> futures = new ArrayList<>(live.size());
            for (ManifestEntity m : live) {
                futures.add(pool.submit(() -> {
                    byte[] data = from.read(m.getSmallOffset(), m.getSmallLength(), m.getSmallCrc32c());   // CRC verified
                    SmallObjectBlob.Location loc = to.appendWhileSealed(data, m.getId().hashCode());
                    if (loc == null) throw new IOException("the compaction target is full");
                    if (loc.crc32c() != m.getSmallCrc32c() || loc.length() != m.getSmallLength()) {
                        throw new IOException("copy of manifest " + m.getId() + " does not match the original");
                    }
                    return new SmallMove(m.getSmallOffset(), loc.offset(), loc.length(), loc.crc32c());
                }));
            }
            IOException failure = null;
            for (int i = 0; i < live.size(); i++) {
                try {
                    moves.put(live.get(i).getId(), futures.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    if (failure == null) {
                        failure = e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                        for (Future<SmallMove> f : futures) f.cancel(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                } catch (java.util.concurrent.CancellationException ignored) {
                    // cancelled after the first failure
                }
            }
            if (failure != null) throw failure;
        } finally {
            pool.shutdownNow();
        }
        return moves;
    }

    /** The compaction failed after the new blob was registered: remove it if nobody wrote into it meanwhile. */
    private void abandonTarget(BlobFileEntity target) {
        try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(target)) {
            h.get().unseal();
        } catch (IOException ignored) {
            // the blob stays registered and sealed in memory only; a restart reopens it
        }
        try {
            blobs.delete(target.getId());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove the abandoned compaction target {}: {}", target.getId(), e.getMessage());
        }
    }

    private CompactionReport fail(BlobFileEntity old, String reason, long t0) {
        return new CompactionReport(old.getId(), false, reason, 0, 0, 0, null, System.currentTimeMillis() - t0);
    }

    // ------------------------------------------------------------------ old files

    /**
     * Closes and deletes the files of compacted blobs whose grace period is over ({@code nowMillis} is compared with the
     * due time that was set when the blob was swapped out). Returns the number of files deleted.
     */
    public int releaseOldFiles(long nowMillis) {
        int n = 0;
        for (PendingFile f : List.copyOf(pending)) {
            if (f.dueMillis() > nowMillis) continue;
            if (!pending.remove(f)) continue;
            smallCache.evict(f.blobId());
            try {
                if (Files.deleteIfExists(f.path())) n++;
            } catch (IOException e) {
                log.warn("Could not delete the old small-object file {}: {}", f.path(), e.getMessage());
                pending.add(new PendingFile(f.blobId(), f.path(), nowMillis + 60_000));
            }
        }
        return n;
    }

    /**
     * Deletes {@code small_*.sob} files in the pools' directories that no blob record names, that are not open and not
     * waiting in the release queue, and that are older than {@code max(minAge, 60 s)}: leftovers of a crashed
     * compaction or of a swap whose process died before the old file was removed. After a restart no reader exists,
     * so nothing needs to be waited for.
     */
    public int deleteUnregisteredFiles(long minAgeMillis) {
        int n = 0;
        Set<Path> open = new HashSet<>();
        for (SmallObjectBlob b : smallCache.openBlobs()) open.add(b.path().toAbsolutePath().normalize());
        for (PendingFile f : pending) open.add(f.path().toAbsolutePath().normalize());
        long cutoff = System.currentTimeMillis() - Math.max(minAgeMillis, MIN_FILE_AGE_MILLIS);
        for (PoolEntity pool : poolRepo.findAll()) {
            Set<Path> registered = new HashSet<>();
            for (BlobFileEntity b : blobRepo.findByPoolId(pool.getId())) {
                registered.add(Path.of(b.getFilePath()).toAbsolutePath().normalize());
            }
            Path dir = Path.of(pool.getBasePath());
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "small_*.sob")) {
                for (Path f : files) {
                    Path p = f.toAbsolutePath().normalize();
                    if (registered.contains(p) || open.contains(p)) continue;
                    if (Files.getLastModifiedTime(f).toMillis() > cutoff) continue;
                    // re-check the registry: a blob registered after the listing must survive
                    boolean nowRegistered = blobRepo.findByPoolId(pool.getId()).stream()
                            .anyMatch(b -> Path.of(b.getFilePath()).toAbsolutePath().normalize().equals(p));
                    if (nowRegistered) continue;
                    if (Files.deleteIfExists(f)) {
                        n++;
                        log.info("Deleted unreferenced small-object file {}", f);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not scan {} for unreferenced small-object files: {}", dir, e.getMessage());
            }
        }
        return n;
    }
}
