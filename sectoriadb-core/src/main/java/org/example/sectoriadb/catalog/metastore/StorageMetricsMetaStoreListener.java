package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.metastore.MetaStoreListener;
import org.example.sectoriadb.metrics.StorageMetrics;

/** Reports the events of the metadata store through {@link StorageMetrics}; names and values stay unchanged. */
public final class StorageMetricsMetaStoreListener implements MetaStoreListener {

    private final StorageMetrics metrics;

    public StorageMetricsMetaStoreListener(StorageMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onPageRead(long nanos, long bytes) {
        metrics.diskRead(StorageMetrics.Target.METASTORE, nanos, bytes);
    }

    @Override
    public void onPageCacheHit() {
        metrics.metaPageCacheHit();
    }

    @Override
    public void onPageWrite(long nanos, long bytes) {
        metrics.diskWrite(StorageMetrics.Target.METASTORE, nanos, bytes);
    }

    @Override
    public void onFsync(long nanos) {
        metrics.fsync(StorageMetrics.Target.METASTORE, nanos);
    }

    @Override
    public void onChecksumFailure() {
        metrics.crcFailure(StorageMetrics.CrcKind.METASTORE_PAGE);
    }

    @Override
    public void onWriterLockWait(long nanos) {
        metrics.metaWriterLockWait(nanos);
    }

    @Override
    public void onCommit(long nanos) {
        metrics.metaCommit(nanos);
    }

    @Override
    public void onCommitDetail(String label, long pages, long nanos) {
        metrics.metaCommitDetail(label, pages, nanos);
    }

    @Override
    public void onRecovery(boolean success) {
        metrics.metaRecovery(success);
    }

    @Override
    public void onGroupBatch(int bodies, int pages) {
        metrics.metaGroupBatch(bodies, pages);
    }

    @Override
    public void onGroupQueueWait(long nanos) {
        metrics.metaGroupQueueWait(nanos);
    }

    @Override
    public void onGroupBodyRollback() {
        metrics.metaGroupBodyRollback();
    }
}
