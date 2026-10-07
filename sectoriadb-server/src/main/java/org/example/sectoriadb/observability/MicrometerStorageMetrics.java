package org.example.sectoriadb.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer adapter of the core {@link StorageMetrics} SPI. Every meter is created once in the constructor, so the
 * hot paths only do an array/map lookup and a record call.
 */
@Component
@Primary
public class MicrometerStorageMetrics implements StorageMetrics {

    private final Map<Target, Timer> readTimer = new EnumMap<>(Target.class);
    private final Map<Target, Timer> writeTimer = new EnumMap<>(Target.class);
    private final Map<Target, Timer> fsyncTimer = new EnumMap<>(Target.class);
    private final Map<Target, Counter> readBytes = new EnumMap<>(Target.class);
    private final Map<Target, Counter> writeBytes = new EnumMap<>(Target.class);
    private final Map<CrcKind, Counter> crc = new EnumMap<>(CrcKind.class);
    private final DistributionSummary evictionPath;
    private final Counter tableFull;
    private final Counter dedupHits;
    private final Counter rekeys;
    private final Timer cuckooLockWait;
    private final Timer smallLockWait;
    private final Timer metaWriterWait;
    private final Timer metaCommit;
    private final DistributionSummary groupBatchSize;
    private final DistributionSummary groupBatchPages;
    private final Timer groupQueueWait;
    private final Counter groupRollbacks;
    private final Timer resizeOk;
    private final Timer resizeFailed;
    private final Counter autoResizeRuns;
    private final Counter placementFallbacks;
    private final Counter poolDedupHits;
    private final Counter poolGrown;
    private final DistributionSummary smallForceRecords;
    private final Timer smallDurabilityWait;
    private final Timer gcRunOk;
    private final Timer gcRunFailed;
    private final Counter gcFreedChunksQueue, gcFreedChunksOrphan, gcFreedChunksSweep;
    private final Counter gcFreedBytesQueue, gcFreedBytesOrphan, gcFreedBytesSweep;
    private final Timer gcSweepTimer;
    private final Counter gcSweepSlots;
    private final Counter gcSweepStrays;
    private final Counter gcTombstones;
    private final Counter gcSmallMarked;
    private final Timer gcCompactionOk;
    private final Timer gcCompactionFailed;
    private final Counter metaRecoveryOk;
    private final Counter metaRecoveryFailed;
    private final Counter gcReclaimed;
    private final Map<GcDeferral, Counter> gcDeferred = new EnumMap<>(GcDeferral.class);
    private final Counter gcErrors;
    private final java.util.concurrent.atomic.AtomicLong gcLastRunSeconds = new java.util.concurrent.atomic.AtomicLong();

    public MicrometerStorageMetrics(MeterRegistry r) {
        for (Target t : Target.values()) {
            readTimer.put(t, timer(r, "sectoriadb.storage.disk.read", "Duration of one positional disk read", Buckets.DISK_IO, "target", t.label()));
            writeTimer.put(t, timer(r, "sectoriadb.storage.disk.write", "Duration of one disk write (into the page cache, before fsync)", Buckets.DISK_IO, "target", t.label()));
            fsyncTimer.put(t, timer(r, "sectoriadb.storage.fsync", "Duration of one fsync (FileChannel.force)", Buckets.FSYNC, "target", t.label()));
            readBytes.put(t, Counter.builder("sectoriadb.storage.disk.read.bytes").description("Bytes read from disk")
                    .baseUnit("bytes").tag("target", t.label()).register(r));
            writeBytes.put(t, Counter.builder("sectoriadb.storage.disk.write.bytes").description("Bytes written to disk")
                    .baseUnit("bytes").tag("target", t.label()).register(r));
        }
        for (CrcKind k : CrcKind.values()) {
            crc.put(k, Counter.builder("sectoriadb.integrity.crc.failures")
                    .description("CRC / checksum verification failures by kind").tag("kind", k.label()).register(r));
        }
        evictionPath = DistributionSummary.builder("sectoriadb.cuckoo.eviction.path.length")
                .description("Chunks moved by one cuckoo insert (0 = a candidate bucket had a free slot)")
                .serviceLevelObjectives(Buckets.EVICTION_PATH).register(r);
        tableFull = Counter.builder("sectoriadb.cuckoo.table.full")
                .description("Inserts rejected with TableFullException (no eviction path)").register(r);
        dedupHits = Counter.builder("sectoriadb.cuckoo.dedup.hits")
                .description("Chunk inserts that found the identical chunk already stored").register(r);
        rekeys = Counter.builder("sectoriadb.cuckoo.rekeys")
                .description("Genuine 64-bit hash collisions resolved by storing the chunk under a salted key").register(r);
        cuckooLockWait = timer(r, "sectoriadb.cuckoo.lock.wait", "Wait for the write lock of a cuckoo table before an insert", Buckets.LOCK_WAIT);
        smallLockWait = timer(r, "sectoriadb.small.append.lock.wait", "Wait for the append lock of a small-object blob", Buckets.LOCK_WAIT);
        metaWriterWait = timer(r, "sectoriadb.metastore.writer.lock.wait", "Wait for the metastore's single write-transaction slot", Buckets.LOCK_WAIT);
        metaCommit = timer(r, "sectoriadb.metastore.commit", "Duration of one metastore commit (pages, fsync, meta page, fsync)", Buckets.FSYNC);
        groupBatchSize = DistributionSummary.builder("sectoriadb.metastore.group.batch.size")
                .description("Write bodies sharing one metastore commit (group commit)")
                .serviceLevelObjectives(Buckets.GROUP_BATCH_SIZE).register(r);
        groupBatchPages = DistributionSummary.builder("sectoriadb.metastore.group.batch.pages")
                .description("Pages written by one group commit")
                .serviceLevelObjectives(Buckets.GROUP_BATCH_PAGES).register(r);
        groupQueueWait = timer(r, "sectoriadb.metastore.group.queue.wait", "Time a write body waited in the group commit queue before it started to run", Buckets.LOCK_WAIT);
        groupRollbacks = Counter.builder("sectoriadb.metastore.group.body.rollbacks")
                .description("Grouped write bodies that threw and were rolled back without affecting their batch").register(r);
        metaRecoveryOk = Counter.builder("sectoriadb.metastore.recoveries")
                .description("Attempts of the metadata store to recover in place after a failed commit")
                .tag("result", "success").register(r);
        metaRecoveryFailed = Counter.builder("sectoriadb.metastore.recoveries")
                .description("Attempts of the metadata store to recover in place after a failed commit")
                .tag("result", "failure").register(r);
        resizeOk = timer(r, "sectoriadb.resize", "Blob resize duration", Buckets.RESIZE, "result", "success");
        resizeFailed = timer(r, "sectoriadb.resize", "Blob resize duration", Buckets.RESIZE, "result", "failure");
        autoResizeRuns = Counter.builder("sectoriadb.auto.resize.runs")
                .description("Passes of the auto-resize scheduler").register(r);
        placementFallbacks = Counter.builder("sectoriadb.pool.placement.fallbacks")
                .description("Chunks whose first rendezvous choice could not take them (full or frozen) and went to a later blob").register(r);
        poolDedupHits = Counter.builder("sectoriadb.pool.dedup.hits")
                .description("Chunks found in the pool-wide chunk index and verified byte for byte (nothing written)").register(r);
        poolGrown = Counter.builder("sectoriadb.pool.grown")
                .description("Cuckoo blobs added to a pool because it reached the grow threshold or no blob accepted a chunk").register(r);
        smallForceRecords = DistributionSummary.builder("sectoriadb.small.group.fsync.records")
                .description("Small-object records made durable by one fsync (group fsync)")
                .serviceLevelObjectives(Buckets.GROUP_BATCH_SIZE).register(r);
        gcRunOk = timer(r, "sectoriadb.gc.run", "Duration of one collector pass (chunk queue, orphans, tombstones)", Buckets.RESIZE, "result", "success");
        gcRunFailed = timer(r, "sectoriadb.gc.run", "Duration of one collector pass (chunk queue, orphans, tombstones)", Buckets.RESIZE, "result", "failure");
        gcFreedChunksQueue = freed(r, "sectoriadb.gc.freed.chunks", "Chunk slots freed by the collector", null, "queue");
        gcFreedChunksOrphan = freed(r, "sectoriadb.gc.freed.chunks", "Chunk slots freed by the collector", null, "orphan");
        gcFreedChunksSweep = freed(r, "sectoriadb.gc.freed.chunks", "Chunk slots freed by the collector", null, "sweep");
        gcFreedBytesQueue = freed(r, "sectoriadb.gc.freed.bytes", "Chunk bytes freed by the collector", "bytes", "queue");
        gcFreedBytesOrphan = freed(r, "sectoriadb.gc.freed.bytes", "Chunk bytes freed by the collector", "bytes", "orphan");
        gcFreedBytesSweep = freed(r, "sectoriadb.gc.freed.bytes", "Chunk bytes freed by the collector", "bytes", "sweep");
        gcSweepTimer = timer(r, "sectoriadb.gc.sweep", "Duration of one sweep of a pool for stray chunk copies", Buckets.RESIZE);
        gcSweepSlots = Counter.builder("sectoriadb.gc.sweep.slots.scanned").description("Slots examined by sweeps").register(r);
        gcSweepStrays = Counter.builder("sectoriadb.gc.sweep.strays.found").description("Stray copies (no index entry) found by sweeps").register(r);
        gcTombstones = Counter.builder("sectoriadb.gc.tombstones.collected").description("Retired manifests removed by the collector").register(r);
        gcSmallMarked = Counter.builder("sectoriadb.gc.small.records.marked").description("Small-object records marked DELETED by the collector").register(r);
        gcCompactionOk = timer(r, "sectoriadb.gc.compaction", "Duration of one small-object blob compaction", Buckets.RESIZE, "result", "success");
        gcCompactionFailed = timer(r, "sectoriadb.gc.compaction", "Duration of one small-object blob compaction", Buckets.RESIZE, "result", "failure");
        gcReclaimed = Counter.builder("sectoriadb.gc.compaction.reclaimed.bytes").baseUnit("bytes")
                .description("File bytes given back by small-object blob compactions").register(r);
        for (GcDeferral d : GcDeferral.values()) {
            gcDeferred.put(d, Counter.builder("sectoriadb.gc.deferred")
                    .description("Collector work left for a later pass, by reason").tag("reason", d.label()).register(r));
        }
        gcErrors = Counter.builder("sectoriadb.gc.errors").description("Collector steps that failed and are retried").register(r);
        io.micrometer.core.instrument.Gauge.builder("sectoriadb.gc.last.run.timestamp.seconds", gcLastRunSeconds, java.util.concurrent.atomic.AtomicLong::get)
                .description("Unix time of the last finished collector pass (0 = none yet)").baseUnit("seconds").register(r);
        smallDurabilityWait = timer(r, "sectoriadb.small.durability.wait", "Time a small-object append waited from taking the lock until its record was durable", Buckets.FSYNC);
    }

    private static Counter freed(MeterRegistry r, String name, String description, String unit, String kind) {
        Counter.Builder b = Counter.builder(name).description(description).tag("kind", kind);
        if (unit != null) b.baseUnit(unit);
        return b.register(r);
    }

    private static Timer timer(MeterRegistry r, String name, String description, Duration[] slo, String... tags) {
        return Timer.builder(name).description(description).serviceLevelObjectives(slo).tags(tags).register(r);
    }

    @Override public void diskRead(Target t, long nanos, long bytes) {
        readTimer.get(t).record(nanos, TimeUnit.NANOSECONDS);
        readBytes.get(t).increment(bytes);
    }

    @Override public void diskWrite(Target t, long nanos, long bytes) {
        writeTimer.get(t).record(nanos, TimeUnit.NANOSECONDS);
        writeBytes.get(t).increment(bytes);
    }

    @Override public void fsync(Target t, long nanos) { fsyncTimer.get(t).record(nanos, TimeUnit.NANOSECONDS); }
    @Override public void evictionPath(int moves) { evictionPath.record(moves); }
    @Override public void tableFull() { tableFull.increment(); }
    @Override public void dedupHit() { dedupHits.increment(); }
    @Override public void hashCollisionRekey() { rekeys.increment(); }
    @Override public void cuckooLockWait(long nanos) { cuckooLockWait.record(nanos, TimeUnit.NANOSECONDS); }
    @Override public void crcFailure(CrcKind kind) { crc.get(kind).increment(); }
    @Override public void smallAppendLockWait(long nanos) { smallLockWait.record(nanos, TimeUnit.NANOSECONDS); }
    @Override public void metaWriterLockWait(long nanos) { metaWriterWait.record(nanos, TimeUnit.NANOSECONDS); }
    @Override public void metaCommit(long nanos) { metaCommit.record(nanos, TimeUnit.NANOSECONDS); }

    @Override public void metaGroupBatch(int bodies, int pages) {
        groupBatchSize.record(bodies);
        groupBatchPages.record(pages);
    }

    @Override public void metaGroupQueueWait(long nanos) { groupQueueWait.record(nanos, TimeUnit.NANOSECONDS); }
    @Override public void metaGroupBodyRollback() { groupRollbacks.increment(); }
    @Override public void metaRecovery(boolean success) { (success ? metaRecoveryOk : metaRecoveryFailed).increment(); }

    @Override public void resize(long nanos, boolean success) {
        (success ? resizeOk : resizeFailed).record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override public void placementFallback() { placementFallbacks.increment(); }
    @Override public void poolDedupHit() { poolDedupHits.increment(); }
    @Override public void poolGrown() { poolGrown.increment(); }
    @Override public void smallGroupFsync(int records) { smallForceRecords.record(records); }
    @Override public void smallDurabilityWait(long nanos) { smallDurabilityWait.record(nanos, TimeUnit.NANOSECONDS); }

    @Override public void gcRun(long nanos, boolean success) {
        (success ? gcRunOk : gcRunFailed).record(nanos, TimeUnit.NANOSECONDS);
        gcLastRunSeconds.set(System.currentTimeMillis() / 1000);
    }

    @Override public void gcChunksFreed(long chunks, long bytes) {
        gcFreedChunksQueue.increment(chunks);
        gcFreedBytesQueue.increment(bytes);
    }

    @Override public void gcStraysFreed(String source, long chunks, long bytes) {
        boolean orphan = "orphan".equals(source);
        (orphan ? gcFreedChunksOrphan : gcFreedChunksSweep).increment(chunks);
        (orphan ? gcFreedBytesOrphan : gcFreedBytesSweep).increment(bytes);
    }

    @Override public void gcSweep(long nanos, long slotsScanned, long strays) {
        gcSweepTimer.record(nanos, TimeUnit.NANOSECONDS);
        gcSweepSlots.increment(slotsScanned);
        gcSweepStrays.increment(strays);
    }

    @Override public void gcTombstones(long manifests, long smallRecords) {
        gcTombstones.increment(manifests);
        gcSmallMarked.increment(smallRecords);
    }

    @Override public void gcCompaction(long nanos, long reclaimedBytes, boolean success) {
        (success ? gcCompactionOk : gcCompactionFailed).record(nanos, TimeUnit.NANOSECONDS);
        if (success) gcReclaimed.increment(reclaimedBytes);
    }

    @Override public void gcDeferred(GcDeferral reason) { gcDeferred.get(reason).increment(); }
    @Override public void gcError() { gcErrors.increment(); }

    @Override public void autoResizeRun() { autoResizeRuns.increment(); }
}
