package org.example.sectoriadb.metrics;

import org.example.sectoriadb.engine.metrics.EngineListener;

/** Reports the events of the storage engine through {@link StorageMetrics}; names and values stay unchanged. */
public final class StorageMetricsEngineListener implements EngineListener {

    private final StorageMetrics metrics;

    public StorageMetricsEngineListener(StorageMetrics metrics) {
        this.metrics = metrics;
    }

    private static StorageMetrics.Target target(Target t) {
        return t == Target.CUCKOO ? StorageMetrics.Target.CUCKOO : StorageMetrics.Target.SMALL;
    }

    @Override public void onDiskRead(Target t, long nanos, long bytes) { metrics.diskRead(target(t), nanos, bytes); }
    @Override public void onDiskWrite(Target t, long nanos, long bytes) { metrics.diskWrite(target(t), nanos, bytes); }
    @Override public void onFsync(Target t, long nanos) { metrics.fsync(target(t), nanos); }
    @Override public void onEvictionPath(int moves) { metrics.evictionPath(moves); }
    @Override public void onTableFull() { metrics.tableFull(); }
    @Override public void onDedupHit() { metrics.dedupHit(); }
    @Override public void onHashCollisionRekey() { metrics.hashCollisionRekey(); }
    @Override public void onCuckooLockWait(long nanos) { metrics.cuckooLockWait(nanos); }

    @Override
    public void onChecksumFailure(ChecksumKind kind) {
        metrics.crcFailure(switch (kind) {
            case CHUNK -> StorageMetrics.CrcKind.CHUNK;
            case SMALL_RECORD -> StorageMetrics.CrcKind.SMALL_RECORD;
            case SLOT_META -> StorageMetrics.CrcKind.SLOT_META;
        });
    }

    @Override public void onSmallAppendLockWait(long nanos) { metrics.smallAppendLockWait(nanos); }
    @Override public void onSmallGroupFsync(int records) { metrics.smallGroupFsync(records); }
    @Override public void onSmallDurabilityWait(long nanos) { metrics.smallDurabilityWait(nanos); }

    @Override
    public void onCuckooBarrier(long nanos, boolean led, long coveredInserts) {
        metrics.cuckooBarrier(nanos, led, coveredInserts);
    }
}
