package org.example.sectoriadb;

import org.example.sectoriadb.metrics.StorageMetrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Test double: remembers every call of the {@link StorageMetrics} SPI. */
public class RecordingMetrics implements StorageMetrics {

    public final Map<Target, AtomicLong> reads = new ConcurrentHashMap<>();
    public final Map<Target, AtomicLong> readBytes = new ConcurrentHashMap<>();
    public final Map<Target, AtomicLong> writes = new ConcurrentHashMap<>();
    public final Map<Target, AtomicLong> writeBytes = new ConcurrentHashMap<>();
    public final Map<Target, AtomicLong> fsyncs = new ConcurrentHashMap<>();
    public final Map<CrcKind, AtomicLong> crcFailures = new ConcurrentHashMap<>();
    public final List<Integer> evictionPaths = new CopyOnWriteArrayList<>();
    public final AtomicLong tableFull = new AtomicLong();
    public final AtomicLong dedupHits = new AtomicLong();
    public final AtomicLong rekeys = new AtomicLong();
    public final AtomicLong cuckooLockWaits = new AtomicLong();
    public final AtomicLong smallLockWaits = new AtomicLong();
    public final AtomicLong placementFallbacks = new AtomicLong();
    public final AtomicLong poolDedupHits = new AtomicLong();
    public final AtomicLong poolGrown = new AtomicLong();
    public final AtomicLong smallForces = new AtomicLong();
    public final AtomicLong smallForcedRecords = new AtomicLong();
    public final AtomicLong metaWriterLockWaits = new AtomicLong();
    public final AtomicLong metaCommits = new AtomicLong();
    public final AtomicLong groupBodies = new AtomicLong();
    public final AtomicLong groupBatches = new AtomicLong();
    public final AtomicLong groupQueueWaits = new AtomicLong();
    public final AtomicLong groupRollbacks = new AtomicLong();
    public final List<Boolean> resizes = new CopyOnWriteArrayList<>();
    public final AtomicLong autoResizeRuns = new AtomicLong();

    private static void inc(Map<?, AtomicLong> m, Object k, long by) {
        @SuppressWarnings("unchecked")
        Map<Object, AtomicLong> mm = (Map<Object, AtomicLong>) m;
        mm.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(by);
    }

    public long get(Map<?, AtomicLong> m, Object k) {
        AtomicLong v = ((Map<Object, AtomicLong>) m).get(k);
        return v == null ? 0 : v.get();
    }

    @Override public void diskRead(Target t, long nanos, long bytes) { inc(reads, t, 1); inc(readBytes, t, bytes); }
    @Override public void diskWrite(Target t, long nanos, long bytes) { inc(writes, t, 1); inc(writeBytes, t, bytes); }
    @Override public void fsync(Target t, long nanos) { inc(fsyncs, t, 1); }
    @Override public void evictionPath(int moves) { evictionPaths.add(moves); }
    @Override public void tableFull() { tableFull.incrementAndGet(); }
    @Override public void dedupHit() { dedupHits.incrementAndGet(); }
    @Override public void hashCollisionRekey() { rekeys.incrementAndGet(); }
    @Override public void cuckooLockWait(long nanos) { cuckooLockWaits.incrementAndGet(); }
    @Override public void crcFailure(CrcKind kind) { inc(crcFailures, kind, 1); }
    @Override public void smallAppendLockWait(long nanos) { smallLockWaits.incrementAndGet(); }
    @Override public void placementFallback() { placementFallbacks.incrementAndGet(); }
    @Override public void poolDedupHit() { poolDedupHits.incrementAndGet(); }
    @Override public void poolGrown() { poolGrown.incrementAndGet(); }
    @Override public void smallGroupFsync(int records) { smallForces.incrementAndGet(); smallForcedRecords.addAndGet(records); }
    @Override public void metaWriterLockWait(long nanos) { metaWriterLockWaits.incrementAndGet(); }
    @Override public void metaCommit(long nanos) { metaCommits.incrementAndGet(); }
    @Override public void metaGroupBatch(int bodies, int pages) { groupBodies.addAndGet(bodies); groupBatches.incrementAndGet(); }
    @Override public void metaGroupQueueWait(long nanos) { groupQueueWaits.incrementAndGet(); }
    @Override public void metaGroupBodyRollback() { groupRollbacks.incrementAndGet(); }
    @Override public void resize(long nanos, boolean success) { resizes.add(success); }
    @Override public void autoResizeRun() { autoResizeRuns.incrementAndGet(); }
}
