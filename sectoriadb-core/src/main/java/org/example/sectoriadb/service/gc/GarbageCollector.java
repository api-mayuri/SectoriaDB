package org.example.sectoriadb.service.gc;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.repository.GcRepository;
import org.example.sectoriadb.repository.GcRepository.ChunkBatchResult;
import org.example.sectoriadb.repository.GcRepository.ChunkRef;
import org.example.sectoriadb.repository.GcRepository.SlotFree;
import org.example.sectoriadb.repository.GcRepository.StrayBatchResult;
import org.example.sectoriadb.repository.GcRepository.Tombstone;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.ChunkStore;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.SmallBlobCache;
import org.example.sectoriadb.service.UploadGate;
import org.example.sectoriadb.service.gc.GcReports.CompactionReport;
import org.example.sectoriadb.service.gc.GcReports.Options;
import org.example.sectoriadb.service.gc.GcReports.RunReport;
import org.example.sectoriadb.service.gc.GcReports.SweepReport;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Garbage collection of the storage engine (doc 10, ROADMAP H4): gives the space of deleted and overwritten objects
 * back. It implements the contract of doc 09 ("contract for stage 10").
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
@Service
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
    private final SmallBlobCache smallCache;
    private final ChunkStore chunkStore;
    private final SmallBlobCompactor compactor;
    private final StorageProperties props;
    private final StorageMetrics metrics;

    private final ReentrantLock runLock = new ReentrantLock();
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile RunReport lastRun;
    private volatile SweepReport lastSweep;
    private volatile CompactionReport lastCompaction;
    private volatile long lastRunEpochMillis;

    @Autowired
    public GarbageCollector(GcRepository gcRepo, ChunkRepository chunkRepo, ManifestRepository manifestRepo,
                            BlobFileRepository blobRepo, PoolRepository poolRepo, BlobService blobs, HashTableCache cache,
                            SmallBlobCache smallCache, ChunkStore chunkStore, SmallBlobCompactor compactor,
                            StorageProperties props, StorageMetrics metrics) {
        this.gcRepo = gcRepo;
        this.chunkRepo = chunkRepo;
        this.manifestRepo = manifestRepo;
        this.blobRepo = blobRepo;
        this.poolRepo = poolRepo;
        this.blobs = blobs;
        this.cache = cache;
        this.smallCache = smallCache;
        this.chunkStore = chunkStore;
        this.compactor = compactor;
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
                gcRepo.dueTombstones(cutoff, 100_000).size(), chunkStore.gate().inFlight(), compactor.pendingFiles(),
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
            collectTombstones(o.poolId(), cutoff, max, r);
            r.filesDeleted += compactor.releaseOldFiles(o.graceMillis() != null ? Long.MAX_VALUE : now);
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
        int batch = props.getGc().getBatchSize();
        for (int i = 0; i < due.size(); i += batch) {
            List<ChunkRef> part = due.subList(i, Math.min(due.size(), i + batch));
            ChunkBatchResult res;
            try {
                res = gcRepo.collectChunks(part, cutoff, this::freeSlot);
            } catch (RuntimeException e) {
                r.error("chunk batch failed: " + e);
                metrics.gcError();
                return;
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

    /**
     * Frees the slot of a chunk. Runs inside the exclusive metastore transaction of the collector, under the blob's
     * writer gate (the one inserts and the resize freeze use), so a resize that froze the blob never has a slot freed
     * under its migration. A frozen or replaced blob is left alone (DEFERRED).
     */
    private SlotFree freeSlot(String poolId, String blobId, long key) throws IOException {
        if (cache.isFrozen(blobId) || cache.isReplaced(blobId)) return SlotFree.DEFERRED;
        CuckooHashTable table;
        try {
            table = blobs.tableOf(blobId);
        } catch (BlobService.BlobGoneException gone) {
            return SlotFree.ABSENT;
        }
        var gate = cache.writerGate(blobId);
        gate.lock();
        try {
            if (cache.isFrozen(blobId)) return SlotFree.DEFERRED;
            int len = table.freeSlot(key);
            return len < 0 ? SlotFree.ABSENT : SlotFree.freed(len);
        } catch (ClosedChannelException replaced) {
            return SlotFree.DEFERRED;
        } finally {
            gate.unlock();
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
            long[] part = java.util.Arrays.copyOfRange(keys, i, Math.min(keys.length, i + batch));
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
                if (cache.isFrozen(b.getId()) || cache.isReplaced(b.getId())) {
                    metrics.gcDeferred(StorageMetrics.GcDeferral.BLOB_BUSY);
                    continue;
                }
                CuckooHashTable table;
                try {
                    table = cache.get(b);
                } catch (IOException | RuntimeException e) {
                    rep.errors++;
                    rep.messages.add("cannot open blob " + b.getId() + ": " + e.getMessage());
                    metrics.gcError();
                    continue;
                }
                rep.blobs++;
                int from = 0;
                while (from >= 0) {
                    CuckooHashTable.KeyPage page = table.activeKeys(from, SWEEP_PAGE);
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
            compactor.deleteUnregisteredFiles(props.getGc().getGrace().toMillis());
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

    private void collectTombstones(String poolId, long cutoff, int max, RunReport r) {
        List<Tombstone> due = gcRepo.dueTombstones(cutoff, max);
        int batch = props.getGc().getBatchSize() * 8;
        for (int i = 0; i < due.size(); i += batch) {
            List<Tombstone> part = due.subList(i, Math.min(due.size(), i + batch));
            List<Tombstone> ready = new ArrayList<>(part.size());
            for (Tombstone t : part) {
                Optional<ManifestEntity> m = manifestRepo.findById(t.manifestId());
                if (m.isPresent() && poolId != null && !poolId.equals(m.get().getPoolId())) continue;
                if (m.isPresent() && m.get().isDeleted() && m.get().getStorageKind() == StorageKind.SMALL) {
                    switch (markSmallDeleted(m.get())) {
                        case MARKED -> r.smallRecordsMarked++;
                        case NOTHING_TO_DO -> { }
                        case RETRY_LATER -> {
                            r.error("could not mark the small-object record of " + t.manifestId() + " DELETED, retrying later");
                            metrics.gcError();
                            continue;
                        }
                    }
                }
                ready.add(t);
            }
            try {
                int removed = gcRepo.deleteTombstones(ready);
                r.tombstones += removed;
                if (removed > 0) metrics.gcTombstones(removed, r.smallRecordsMarked);
            } catch (RuntimeException e) {
                r.error("tombstone batch failed: " + e);
                metrics.gcError();
                return;
            }
        }
    }

    private enum Marked { MARKED, NOTHING_TO_DO, RETRY_LATER }

    /**
     * The record of a retired small object is flipped to DELETED (one byte, forced) BEFORE its manifest row goes: a
     * crash in between repeats the (idempotent) flip, never leaves an ACTIVE record with no manifest to find it.
     */
    private Marked markSmallDeleted(ManifestEntity m) {
        BlobFileEntity blob = m.getSmallBlob();
        if (blob == null) return Marked.NOTHING_TO_DO;   // the blob is gone (compacted away, bucket deleted)
        try {
            return smallCache.get(blob).markDeleted(m.getSmallOffset()) ? Marked.MARKED : Marked.NOTHING_TO_DO;
        } catch (SmallObjectCorruptedException e) {
            log.error("Small-object record of retired manifest {} is damaged and cannot be marked DELETED: {}", m.getId(), e.getMessage());
            return Marked.NOTHING_TO_DO;   // nothing readable lives there; compaction skips it
        } catch (IOException | RuntimeException e) {
            // closed file after a compaction swapped the blob out: nothing to mark; any other failure is retried
            if (blobRepo.findById(blob.getId()).isEmpty()) return Marked.NOTHING_TO_DO;
            log.warn("Could not mark the record of {} DELETED: {}", m.getId(), e.toString());
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
