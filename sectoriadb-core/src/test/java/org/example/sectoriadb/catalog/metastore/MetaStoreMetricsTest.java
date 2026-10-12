package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.metastore.StorageMetricsMetaStoreListener;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.metrics.StorageMetrics.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import org.example.sectoriadb.metrics.RecordingMetrics;

/** The metadata store's events reach {@link org.example.sectoriadb.metrics.StorageMetrics} through the adapter. */
class MetaStoreMetricsTest {

    @TempDir
    Path tmp;

    private final RecordingMetrics m = new RecordingMetrics();

    @Test
    void metastoreReportsWriterWaitCommitAndFsync() {
        MetaStore store = MetaStore.open(tmp.resolve("meta.db"), MetaStoreOptions.defaults().fsync(true).listener(new StorageMetricsMetaStoreListener(m)));
        try {
            store.writeVoid(tx -> tx.tree("t").put(new byte[]{1}, new byte[]{2}));
            assertEquals(1, m.metaWriterLockWaits.get());
            assertEquals(1, m.metaCommits.get());
            assertTrue(m.get(m.fsyncs, Target.METASTORE) >= 2, "data barrier + meta barrier");
            assertTrue(m.get(m.writes, Target.METASTORE) >= 1);
            store.read(tx -> tx.tree("t").get(new byte[]{1}));
            assertTrue(m.get(m.reads, Target.METASTORE) >= 1);
        } finally {
            store.close();
        }
    }

    @Test
    void groupCommitReportsBatchQueueWaitAndRollbacks() {
        MetaStore store = MetaStore.open(tmp.resolve("group.db"), MetaStoreOptions.defaults().fsync(false).listener(new StorageMetricsMetaStoreListener(m)));
        try {
            store.writeGrouped(tx -> {
                tx.tree("t").put(new byte[]{1}, new byte[]{2});
                return null;
            });
            assertThrows(IllegalStateException.class, () -> store.writeGrouped(tx -> {
                throw new IllegalStateException("x");
            }));
            assertEquals(1, m.metaCommits.get(), "a batch of failures commits nothing");
            assertEquals(2, m.groupBatches.get());
            assertEquals(2, m.groupBodies.get());
            assertEquals(2, m.groupQueueWaits.get());
            assertEquals(1, m.groupRollbacks.get());
        } finally {
            store.close();
        }
    }
}
