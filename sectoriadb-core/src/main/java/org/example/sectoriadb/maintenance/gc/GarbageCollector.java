package org.example.sectoriadb.maintenance.gc;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.BlobFileRepository;
import org.example.sectoriadb.catalog.BlobKind;
import org.example.sectoriadb.catalog.ChunkRepository;
import org.example.sectoriadb.catalog.GcRepository;
import org.example.sectoriadb.catalog.GcRepository.ChunkBatchResult;
import org.example.sectoriadb.catalog.GcRepository.ChunkRef;
import org.example.sectoriadb.catalog.GcRepository.SlotFree;
import org.example.sectoriadb.catalog.GcRepository.StrayBatchResult;
import org.example.sectoriadb.catalog.GcRepository.Tombstone;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.PoolRepository;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.engine.blob.CuckooHashTable;
import org.example.sectoriadb.engine.blob.SmallObjectBlob;
import org.example.sectoriadb.engine.error.SmallObjectCorruptedException;
import org.example.sectoriadb.maintenance.BlobFileReaper;
import org.example.sectoriadb.maintenance.gc.GcReports.CompactionReport;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.example.sectoriadb.maintenance.gc.GcReports.RunReport;
import org.example.sectoriadb.maintenance.gc.GcReports.SweepReport;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.storage.BlobGate;
import org.example.sectoriadb.storage.BlobService;
import org.example.sectoriadb.storage.ChunkStore;
import org.example.sectoriadb.storage.HashTableCache;
import org.example.sectoriadb.storage.ResidentHandle;
import org.example.sectoriadb.storage.SmallBlobCache;
import org.example.sectoriadb.storage.UploadGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Garbage collection of the storage engine: gives the space of deleted and overwritten objects back. The protocol
 * is described in docs/architecture/garbage-collection.md.
 *
 * <ul>
 *   <li>{@link #run}: the chunk queue {@code chunk_gc} (zero-reference chunks older than the grace period G: slot freed
 *       and index rows removed in one exclusive metastore transaction, with every chunk re-checked inside it), the orphan
 *       records {@code chunk_orphans} (stray copies: freed under the upload gate) and the tombstones
 *       {@code deleted_manifests} (manifest rows removed after G; a small-object record is marked DELETED first);</li>
 *   <li>{@link #sweep}: every blob of a pool is walked for copies that no index entry points at (a crash between the
 *       data write and the commit, an aborted upload): freed under the upload gate, in short bounded batches;</li>
 *   <li>{@link #compact}: small-object blobs with many dead bytes are rewritten ({@link SmallBlobCompactor}).</li>
 * </ul>
 * Every step is idempotent and safe to repeat after a crash; nothing here removes data that a live manifest needs.
 */
public class GarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollector.class);
    private static final int SWEEP_PAGE = 512;

    private final GcRepository gcRepo;
    private final ChunkRepository chunkRepo;
    private final ManifestRepository manifestRepo;
    private final BlobFileRepository blobRepo;
    private final PoolRepository poolRepo;
    private final BlobService blobs;
    private final HashTableCache cache;
    private final BlobGate blobGate;
    private final SmallBlobCache smallCache;
    private final ChunkStore chunkStore;
    private final SmallBlobCompactor compactor;
    private final BlobFileReaper reaper;
    private final StorageProperties props;
    private final StorageMetrics metrics;

    private final ReentrantLock runLock = new ReentrantLock();
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile RunReport lastRun;
    private volatile SweepReport lastSweep;
    private volatile CompactionReport lastCompaction;
    private volatile long lastRunEpochMillis;

    public GarbageCollector(GcRepository gcRepo, ChunkRepository chunkRepo, ManifestRepository manifestRepo,
                            BlobFileRepository blobRepo, PoolRepository poolRepo, BlobService blobs, HashTableCache cache, BlobGate blobGate,
                            SmallBlobCache smallCache, ChunkStore chunkStore, SmallBlobCompactor compactor,
                            BlobFileReaper reaper, StorageProperties props, StorageMetrics metrics) {
        this.gcRepo = gcRepo;
        this.chunkRepo = chunkRepo;
        this.manifestRepo = manifestRepo;
        this.blobRepo = blobRepo;
        this.poolRepo = poolRepo;
        this.blobs = blobs;
        this.cache = cache;
        this.blobGate = blobGate;
        this.smallCache = smallCache;
        this.chunkStore = chunkStore;
        this.compactor = compactor;
        this.reaper = reaper;
        this.props = props;
        this.metrics = metrics;
    }

    public SmallBlobCompactor compactor() {
        return compactor;
    }

    // ------------------------------------------------------------------ status

    /** Queue lengths and the last results (shell {@code gc status}). */
    public record Status(long chunkQueue, long chunkQueueDue, long orphans, long tombstones, long tombstonesDue,
                         int uploadsInFlight, int oldFilesPending, long graceMillis, long lastRunAtMillis,
                         RunReport lastRun, SweepReport lastSweep, CompactionReport lastCompaction) {
    }

    public Status status() {
        long grace = props.getGc().getGrace().toMillis();
        long cutoff = System.currentTimeMillis() - grace;
        ChunkRepository.Stats cs = chunkRepo.stats();
        long tombstones = manifestRepo.gcQueueSize();
        return new Status(cs.gcQueue(), gcRepo.dueChunks(null, cutoff, 100_000).size(), cs.orphans(), tombstones,
                gcRepo.dueTombstones(cutoff, 100_000).size(), chunkStore.gate().inFlight(), compactor.pendingFiles() + reaper.pending(),
                grace, lastRunEpochMillis, lastRun, lastSweep, lastCompaction);
    }

    // ------------------------------------------------------------------ one pass

    /** One pass over the chunk queue, the orphan records and the tombstones. Passes do not overlap. */
    public RunReport run(Options o) {
        runLock.lock();
        long n0 = System.nanoTime();
        RunReport r = new RunReport();
        try {
            long now = System.currentTimeMillis();
            r.startedAtMillis = now;
            r.graceMillis = o.graceMillis() != null ? Math.max(0, o.graceMillis()) : props.getGc().getGrace().toMillis();
            long cutoff = now - r.graceMillis;
            int max = o.maxChunks() != null ? o.maxChunks() : props.getGc().getMaxChunksPerRun();
            collectChunks(o.poolId(), cutoff, max, r);
            collectOrphans(o.poolId(), max, r);
            collectTombstones(o.poolId(), cutoff, o.maxChunks(), r);
            // only an explicit grace of 0 releases every pending old file at once; any other grace keeps the files that are not yet due
            long releaseAt = o.graceMillis() != null && o.graceMillis() <= 0 ? Long.MAX_VALUE : now;
            r.filesDeleted += compactor.releaseOldFiles(releaseAt);
            r.filesDeleted += reaper.releaseDue(releaseAt);
        } catch (RuntimeException e) {
            r.error("pass failed: " + e);
            metrics.gcError();
            log.error("Garbage collection pass failed: {}", e.toString(), e);
        } finally {
            r.durationMillis = (System.nanoTime() - n0) / 1_000_000;
            lastRun = r;
            lastRunEpochMillis = System.currentTimeMillis();
            metrics.gcRun(System.nanoTime() - n0, r.errors == 0);
            runLock.unlock();
        }
        if (r.chunksFreed + r.orphansFreed + r.tombstones + r.errors > 0) log.info("GC pass: {}", r);
        return r;
    }

    // ------------------------------------------------------------------ chunk_gc

    private void collectChunks(String poolId, long cutoff, int max, RunReport r) {
        List<ChunkRef> due = gcRepo.dueChunks(poolId, cutoff, max);
        if (due.size() >= max) r.more = true;           // the cap cut the pass short: the scheduler comes back at once
        // The upload gate is per pool: group the due chunks by pool and run every batch under its pool's gate.
        Map<String, List<ChunkRef>> byPool = new LinkedHashMap<>();
        for (ChunkRef c : due) byPool.computeIfAbsent(c.poolId(), k -> new ArrayList<>()).add(c);
        int batch = props.getGc().getBatchSize();
        for (Map.Entry<String, List<ChunkRef>> e : byPool.entrySet()) {
            List<ChunkRef> list = e.getValue();
            for (int i = 0; i < list.size(); i += batch) {
                List<ChunkRef> part = list.subList(i, Math.min(list.size(), i + batch));
                // Freeing a slot happens before the metadata commit. If the commit failed after the slot was freed, an
                // upload that deduplicated against the chunk meanwhile could commit a reference to a freed slot. The
                // gate drains such uploads and blocks new ones for the duration of the batch, so a failed commit
                // leaves nothing that relies on the chunk (the next pass repairs the index entry via the ABSENT path).
                UploadGate.Sweep gate = chunkStore.gate().tryBegin(e.getKey(),
                        props.getGc().getSweepGateWait().toMillis(), props.getGc().getUploadTicketTtl().toMillis());
                if (gate == null) {
                    metrics.gcDeferred(StorageMetrics.GcDeferral.UPLOADS_IN_FLIGHT);
                    r.deferred += list.size() - i;
                    break;                                    // this pool's remaining chunks wait for the next pass
                }
                ChunkBatchResult res;
                try {
                    res = gcRepo.collectChunks(part, cutoff, this::freeSlot);
                } catch (RuntimeException ex) {
                    r.error("chunk batch failed: " + ex);
                    metrics.gcError();
                    return;
                } finally {
                    gate.close();
                }
                for (ChunkRef k : res.removed()) chunkStore.forget(k.poolId(), k.chunkKey());
                r.chunksFreed += res.freed();
                r.bytesFreed += res.bytes();
                r.chunksAbsent += res.absent();
                r.revived += res.revived();
                r.gone += res.gone();
                r.tooNew += res.tooNew();
                r.deferred += res.deferred();
                if (res.freed() > 0) metrics.gcChunksFreed(res.freed(), res.bytes());
                if (res.deferred() > 0) metrics.gcDeferred(StorageMetrics.GcDeferral.BLOB_BUSY);
                if (res.error() != null) {
                    r.error("freeing a slot failed: " + res.error());
                    metrics.gcError();
                    return;
                }
            }
        }
    }

    /**
     * Frees the slot of a chunk. Runs inside the exclusive metastore transaction of the collector, under the blob's
     * writer gate (the one inserts and the resize freeze use), so a resize that froze the blob never has a slot freed
     * under its migration. A frozen or replaced blob is left alone (DEFERRED).
     */
    private SlotFree freeSlot(String poolId, String blobId, long key) throws IOException {
        if (blobGate.isFrozen(blobId) || blobGate.isReplaced(blobId)) return SlotFree.DEFERRED;
        ResidentHandle<CuckooHashTable> handle;
        try {
            handle = blobs.acquireTable(blobId);
        } catch (BlobService.BlobGoneException gone) {
            return SlotFree.ABSENT;
        }
        try (handle) {
            var gate = blobGate.writerGate(blobId);
            gate.lock();
            try {
                if (blobGate.isFrozen(blobId)) return SlotFree.DEFERRED;
                int len = handle.get().freeSlot(key);
                return len < 0 ? SlotFree.ABSENT : SlotFree.freed(len);
            } catch (ClosedChannelException replaced) {
                return SlotFree.DEFERRED;
            } finally {
                gate.unlock();
            }
        }
    }

    // ------------------------------------------------------------------ orphans and stray copies

    /** Outcome of freeing a list of stray copies of one blob under the gate. */
    private record StrayOutcome(int freed, long bytes, int notStray, int deferred, boolean gateBusy, IOException error) {
    }

    /**
     * Frees stray copies of one blob in bounded batches. Each batch takes the upload gate of the pool (drains the uploads
     * in flight, blocks new ones for the duration of one exclusive transaction), re-checks every key against the
     * committed index inside the transaction and frees the slots. When the uploads do not drain within the gate wait the
     * rest is left for the next pass.
     */
    private StrayOutcome freeStrays(String blobId, String poolId, long[] keys, String source) {
        int freed = 0, notStray = 0, deferred = 0;
        long bytes = 0;
        int batch = props.getGc().getBatchSize();
        for (int i = 0; i < keys.length; i += batch) {
            long[] part = Arrays.copyOfRange(keys, i, Math.min(keys.length, i + batch));
            UploadGate.Sweep gate = poolId == null ? null : chunkStore.gate().tryBegin(poolId,
                    props.getGc().getSweepGateWait().toMillis(), props.getGc().getUploadTicketTtl().toMillis());
            if (poolId != null && gate == null) {
                metrics.gcDeferred(StorageMetrics.GcDeferral.UPLOADS_IN_FLIGHT);
                return new StrayOutcome(freed, bytes, notStray, deferred + (keys.length - i), true, null);
            }
            StrayBatchResult res;
            try {
                res = gcRepo.freeStrays(blobId, part, this::freeSlot);
            } finally {
                if (gate != null) gate.close();
            }
            freed += res.freed();
            bytes += res.bytes();
            notStray += res.notStray();
            deferred += res.deferred();
            if (res.freed() > 0) metrics.gcStraysFreed(source, res.freed(), res.bytes());
            if (res.deferred() > 0) metrics.gcDeferred(StorageMetrics.GcDeferral.BLOB_BUSY);
            if (res.error() != null) {
                return new StrayOutcome(freed, bytes, notStray, deferred, false, res.error());
            }
        }
        return new StrayOutcome(freed, bytes, notStray, deferred, false, null);
    }

    private void collectOrphans(String poolId, int max, RunReport r) {
        List<ChunkRepository.Orphan> orphans = gcRepo.orphans(max);
        Map<String, List<Long>> byBlob = new LinkedHashMap<>();
        for (ChunkRepository.Orphan o : orphans) byBlob.computeIfAbsent(o.blobId(), k -> new ArrayList<>()).add(o.chunkKey());
        for (Map.Entry<String, List<Long>> e : byBlob.entrySet()) {
            Optional<BlobFileEntity> blob = blobRepo.findById(e.getKey());
            String pool = blob.map(BlobFileEntity::getPoolId).orElse(null);
            if (poolId != null && !poolId.equals(pool)) continue;
            long[] keys = e.getValue().stream().mapToLong(Long::longValue).toArray();
            try {
                StrayOutcome o = freeStrays(e.getKey(), pool, keys, "orphan");
                r.orphansFreed += o.freed();
                r.orphanBytes += o.bytes();
                r.orphanRowsDropped += o.freed() + o.notStray();
                r.orphansDeferred += o.deferred();
                if (o.error() != null) {
                    r.error("freeing an orphan copy failed: " + o.error());
                    metrics.gcError();
                }
            } catch (RuntimeException ex) {
                r.error("orphan batch failed: " + ex);
                metrics.gcError();
            }
        }
    }

    // ------------------------------------------------------------------ sweep

    /**
     * Walks every cuckoo blob of the pool for ACTIVE slots whose key has no index entry pointing at that blob, and frees
     * them (under the upload gate, see {@link #freeStrays}). Incremental: pages of {@value #SWEEP_PAGE} slots, a gate
     * per batch of strays, and when uploads stay in flight too long the sweep stops and reports {@code deferred}.
     */
    public SweepReport sweep(PoolEntity pool) {
        sweepLock.lock();
        long n0 = System.nanoTime();
        SweepReport rep = new SweepReport();
        rep.startedAtMillis = System.currentTimeMillis();
        try {
            for (BlobFileEntity b : blobs.cuckooBlobsOf(pool)) {
                if (blobGate.isFrozen(b.getId()) || blobGate.isReplaced(b.getId())) {
                    metrics.gcDeferred(StorageMetrics.GcDeferral.BLOB_BUSY);
                    continue;
                }
                int from = 0;
                boolean opened = false;
                while (from >= 0) {
                    CuckooHashTable.KeyPage page;
                    // pinned per page: a sweep of a big pool does not keep the table resident (or evictable) for hours
                    try (ResidentHandle<CuckooHashTable> th = cache.acquire(b)) {
                        page = th.get().activeKeys(from, SWEEP_PAGE);
                    } catch (IOException | RuntimeException e) {
                        rep.errors++;
                        rep.messages.add("cannot open blob " + b.getId() + ": " + e.getMessage());
                        metrics.gcError();
                        break;
                    }
                    if (!opened) {
                        opened = true;
                        rep.blobs++;
                    }
                    from = page.next();
                    rep.slotsScanned += page.keys().length;
                    long[] candidates = gcRepo.unindexed(b.getId(), page.keys());
                    if (candidates.length == 0) continue;
                    rep.strays += candidates.length;
                    StrayOutcome o = freeStrays(b.getId(), pool.getId(), candidates, "sweep");
                    rep.freed += o.freed();
                    rep.bytesFreed += o.bytes();
                    rep.notStray += o.notStray();
                    if (o.error() != null) {
                        rep.errors++;
                        rep.messages.add("freeing a stray copy failed: " + o.error());
                        metrics.gcError();
                        return rep;
                    }
                    if (o.gateBusy()) {
                        rep.deferred = true;
                        return rep;
                    }
                }
            }
            // small-object blobs: records that no manifest references are strays too; they are reclaimed by rewriting the blob
            for (BlobFileEntity b : blobRepo.findByPoolId(pool.getId())) {
                if (b.getKind() != BlobKind.SMALL || !compactor.strayHeavy(b)) continue;
                CompactionReport cr = compactor.compact(pool, b, true);
                lastCompaction = cr;
                if (cr.compacted()) rep.smallBlobsCompacted++;
            }
            compactor.deleteUnregisteredFiles(props.getGc().getGrace().toMillis());
            reaper.deleteUnregistered(props.getGc().getGrace().toMillis());
        } catch (RuntimeException e) {
            rep.errors++;
            rep.messages.add("sweep failed: " + e);
            metrics.gcError();
            log.error("Sweep of pool {} failed: {}", pool.getName(), e.toString(), e);
        } finally {
            rep.durationMillis = (System.nanoTime() - n0) / 1_000_000;
            lastSweep = rep;
            metrics.gcSweep(System.nanoTime() - n0, rep.slotsScanned, rep.strays);
            sweepLock.unlock();
        }
        if (rep.freed > 0 || rep.errors > 0) log.info("Sweep of pool {}: {}", pool.getName(), rep);
        return rep;
    }

    /** {@link #sweep(PoolEntity)} for every pool (the scheduler). */
    public List<SweepReport> sweepAll() {
        List<SweepReport> out = new ArrayList<>();
        for (PoolEntity p : poolRepo.findAll()) out.add(sweep(p));
        return out;
    }

    // ------------------------------------------------------------------ tombstones

    /** Tombstones read per round of a pass: a short prefix scan of the queue, then batches of {@code batch-size * 8}. */
    private static final int TOMBSTONE_SLICE = 2048;

    /**
     * Removes every due tombstone, slice by slice, until none is due (or, for a manual pass with an explicit limit, up
     * to that many). The queue grows at the pace of deletes and overwrites, so a fixed number per pass would let it
     * (and the manifests, indexes and dead small records behind it) grow without bound once that pace passed the cap.
     * The work is split into short transactions, writers run in between.
     */
    private void collectTombstones(String poolId, long cutoff, Integer explicitMax, RunReport r) {
        int batch = props.getGc().getBatchSize() * 8;
        long budget = explicitMax != null ? explicitMax : Long.MAX_VALUE;
        long taken = 0;
        while (taken < budget) {
            List<Tombstone> due = gcRepo.dueTombstones(cutoff, (int) Math.min(TOMBSTONE_SLICE, budget - taken));
            if (due.isEmpty()) return;
            long progress = 0;
            for (int i = 0; i < due.size(); i += batch) {
                List<Tombstone> part = due.subList(i, Math.min(due.size(), i + batch));
                List<Tombstone> ready = new ArrayList<>(part.size());
                Map<String, List<Tombstone>> byBlob = new HashMap<>();   // tombstones whose marks wait for a sync of the blob
                Map<String, BlobFileEntity> blobsToSync = new LinkedHashMap<>();
                long markedBefore = r.smallRecordsMarked;
                for (Tombstone t : part) {
                    Optional<ManifestEntity> m = manifestRepo.findById(t.manifestId());
                    if (m.isPresent() && poolId != null && !poolId.equals(m.get().getPoolId())) continue;
                    if (m.isPresent() && m.get().isDeleted()
                            && (m.get().getStorageKind() == StorageKind.SMALL || m.get().getStorageKind() == StorageKind.MULTIPART)) {
                        Set<String> touched = new LinkedHashSet<>();
                        switch (markSmallDeleted(m.get(), touched, blobsToSync)) {
                            case MARKED -> r.smallRecordsMarked++;
                            case NOTHING_TO_DO -> { }
                            case RETRY_LATER -> {
                                r.error("could not mark the small-object record of " + t.manifestId() + " DELETED, retrying later");
                                metrics.gcError();
                                continue;
                            }
                        }
                        for (String b : touched) byBlob.computeIfAbsent(b, k -> new ArrayList<>()).add(t);
                    }
                    ready.add(t);
                }
                // one forced write per blob for the whole batch; the manifest rows go only after the marks are durable
                for (Map.Entry<String, BlobFileEntity> e : blobsToSync.entrySet()) {
                    try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(e.getValue())) {
                        h.get().sync();
                    } catch (IOException | RuntimeException ex) {
                        if (blobRepo.findById(e.getKey()).isPresent()) {
                            log.warn("Could not sync the DELETED marks of small-object file {}: {}", e.getKey(), ex.toString());
                            r.error("could not sync " + e.getKey() + ", retrying later");
                            metrics.gcError();
                            ready.removeAll(byBlob.getOrDefault(e.getKey(), List.of()));
                        }
                    }
                }
                try {
                    int removed = gcRepo.deleteTombstones(ready);
                    r.tombstones += removed;
                    progress += ready.size();
                    if (removed > 0) metrics.gcTombstones(removed, r.smallRecordsMarked - markedBefore);
                } catch (RuntimeException e) {
                    r.error("tombstone batch failed: " + e);
                    metrics.gcError();
                    return;
                }
            }
            taken += due.size();
            if (progress == 0) return;   // everything left is filtered out or must wait: do not spin on the same rows
        }
    }

    private enum Marked { MARKED, NOTHING_TO_DO, RETRY_LATER }

    /**
     * The record of a retired small object is flipped to DELETED (one byte, forced) BEFORE its manifest row goes: a
     * crash in between repeats the (idempotent) flip, never leaves an ACTIVE record with no manifest to find it.
     */
    private Marked markSmallDeleted(ManifestEntity m, Set<String> touched, Map<String, BlobFileEntity> blobsToSync) {
        if (m.getStorageKind() != StorageKind.MULTIPART) {
            return markRecordDeleted(m.getId(), m.getSmallBlob(), m.getSmallOffset(), touched, blobsToSync);
        }
        // a retired multipart object: every SMALL segment is a record of its own
        Marked result = Marked.NOTHING_TO_DO;
        for (PartSegment sg : m.getSegments()) {
            if (sg.kind() != StorageKind.SMALL) continue;
            Marked one = markRecordDeleted(m.getId(), m.getSegmentBlobs().get(sg.smallBlobId()), sg.smallOffset(), touched, blobsToSync);
            if (one == Marked.RETRY_LATER) return one;
            if (one == Marked.MARKED) result = Marked.MARKED;
        }
        return result;
    }

    private Marked markRecordDeleted(String manifestId, BlobFileEntity blob, long offset, Set<String> touched,
                                     Map<String, BlobFileEntity> blobsToSync) {
        if (blob == null) return Marked.NOTHING_TO_DO;   // the blob is gone (compacted away, bucket deleted)
        try {
            try (ResidentHandle<SmallObjectBlob> h = smallCache.acquire(blob)) {
                if (!h.get().markDeletedUnsynced(offset)) return Marked.NOTHING_TO_DO;
                touched.add(blob.getId());
                blobsToSync.putIfAbsent(blob.getId(), blob);
                return Marked.MARKED;
            }
        } catch (SmallObjectCorruptedException e) {
            log.error("Small-object record of retired manifest {} is damaged and cannot be marked DELETED: {}", manifestId, e.getMessage());
            return Marked.NOTHING_TO_DO;   // nothing readable lives there; compaction skips it
        } catch (IOException | RuntimeException e) {
            // closed file after a compaction swapped the blob out: nothing to mark; any other failure is retried
            if (blobRepo.findById(blob.getId()).isEmpty()) return Marked.NOTHING_TO_DO;
            log.warn("Could not mark the record of {} DELETED: {}", manifestId, e.toString());
            return Marked.RETRY_LATER;
        }
    }

    // ------------------------------------------------------------------ compaction

    /** Compacts the eligible small-object blobs of a pool ({@code force}: whatever their dead ratio). */
    public List<CompactionReport> compact(PoolEntity pool, boolean force) {
        List<CompactionReport> reports = compactor.compactEligible(pool == null ? null : pool.getId(), force);
        if (!reports.isEmpty()) lastCompaction = reports.get(reports.size() - 1);
        return reports;
    }
}
