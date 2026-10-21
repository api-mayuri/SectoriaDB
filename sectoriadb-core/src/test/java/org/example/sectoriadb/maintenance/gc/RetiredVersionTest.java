package org.example.sectoriadb.maintenance.gc;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.metastore.MetaInvariants;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.example.sectoriadb.maintenance.gc.GcReports.RunReport;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A retired version (overwritten or deleted) leaves no manifest record behind, only a compact queue row with the
 * small-object records the collector marks DELETED after the grace period. Keeping whole retired manifests for the grace
 * period made the manifests tree hold one record per overwrite of the last 15 minutes: under churn most of sectoria.db.
 */
class RetiredVersionTest {

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    private byte[] put(String key, int size, long seed) throws Exception {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        rig.writer.storeStream(new ByteArrayInputStream(b), pool, key, "application/octet-stream");
        return b;
    }

    private byte[] read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.reader.streamToOutput(m, out);
        return out.toByteArray();
    }

    @Test
    void overwritesKeepOneManifestPerKeyNotOnePerVersion() throws Exception {
        int keys = 40, rounds = 15;
        for (int round = 0; round < rounds; round++) {
            for (int k = 0; k < keys; k++) {
                // small and chunked objects alike
                put("k" + k, k % 4 == 0 ? StorageRig.CHUNK * 2 + round : 200 + round, round * 1000L + k);
            }
        }
        for (int k = 0; k < 10; k++) rig.catalog.deleteObject("bkt", "k" + k);

        assertEquals(keys - 10, rig.manifests.count(), "only the current versions have a manifest record");
        assertEquals((long) keys * rounds - (keys - 10), rig.manifests.gcQueueSize(), "every retired version is queued");

        RunReport r = rig.gc.run(Options.defaults().withGrace(0));
        assertEquals(0, r.errors, r.toString());
        assertEquals(0, rig.manifests.gcQueueSize());
        assertTrue(r.smallRecordsMarked >= 30L * (rounds - 1), "the small records of retired versions are marked: " + r.smallRecordsMarked);
        assertEquals(keys - 10, rig.manifests.count());
    }

    @Test
    void aReaderThatResolvedTheOldVersionFinishesWithinTheGracePeriod() throws Exception {
        byte[] small = put("s", 700, 1);
        byte[] chunked = put("c", StorageRig.CHUNK * 3 + 5, 2);
        ManifestEntity oldSmall = rig.catalog.findObject("bkt", "s").orElseThrow();
        ManifestEntity oldChunked = rig.catalog.findObject("bkt", "c").orElseThrow();

        put("s", 800, 3);
        rig.catalog.deleteObject("bkt", "c");
        assertTrue(rig.manifests.findById(oldSmall.getId()).isEmpty(), "the retired record is gone at once");
        assertTrue(rig.manifests.findById(oldChunked.getId()).isEmpty());

        RunReport r = rig.gc.run(Options.defaults());   // within the grace period: nothing of the old versions is reclaimed
        assertEquals(0, r.errors, r.toString());
        assertEquals(2, rig.manifests.gcQueueSize());
        assertArrayEquals(small, read(oldSmall), "the old small version is still readable");
        assertArrayEquals(chunked, read(oldChunked), "the old chunked version is still readable");
    }
}
