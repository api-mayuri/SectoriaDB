package org.example.sectoriadb.maintenance.gc;

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
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collector must reclaim at least as fast as a workload produces garbage. A fixed number of tombstones or chunks
 * per pass is a ceiling on the reclaim rate: above it the queue, the retired manifests behind it and with them the
 * metastore file grow for as long as the workload runs although the number of objects stays the same (seen in the
 * stage-18 soak: 1.2 GB in 54 minutes).
 */
class GcKeepsUpTest {

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        pool = rig.bucket("bkt");
        rig.props.getGc().setMaxChunksPerRun(20);
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    private void put(String key, int size, long seed) throws Exception {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        rig.writer.storeStream(new ByteArrayInputStream(b), pool, key, "application/octet-stream");
    }

    private RunReport gc0() {
        return rig.gc.run(Options.defaults().withGrace(0));
    }

    @Test
    void oneAutomaticPassRemovesAllDueTombstonesWhateverTheChunkCap() throws Exception {
        for (int i = 0; i < 300; i++) put("s" + i, 300 + i, i);
        for (int i = 0; i < 300; i++) rig.catalog.deleteObject("bkt", "s" + i);
        assertEquals(300, rig.manifests.gcQueueSize());

        RunReport r = gc0();
        assertEquals(300, r.tombstones, "the cap of 20 limits freed chunks, not the retired manifests");
        assertEquals(0, rig.manifests.gcQueueSize());
        assertEquals(0, r.errors);
    }

    @Test
    void aPassThatStopsAtTheChunkCapSaysThereIsMoreAndRepeatingItDrainsTheQueue() throws Exception {
        for (int i = 0; i < 90; i++) put("c" + i, StorageRig.CHUNK * 2 + 17, 1000 + i);   // chunked, three chunks each
        for (int i = 0; i < 90; i++) rig.catalog.deleteObject("bkt", "c" + i);
        long queued = rig.chunks.stats().gcQueue();
        assertTrue(queued >= 90, "queued " + queued);

        RunReport first = gc0();
        assertEquals(20, first.chunksFreed);
        assertTrue(first.more, "the cap was reached with more chunks due");

        int passes = 1;
        RunReport last = first;
        while (last.more && passes < 100) {
            last = gc0();
            passes++;
        }
        assertFalse(last.more);
        assertEquals(0, rig.chunks.stats().gcQueue());
        assertEquals(0, rig.chunks.stats().chunks());
        assertTrue(passes > 1);
    }

    @Test
    void theMetastoreFileStopsGrowingUnderChurnWithAConstantObjectCount() throws Exception {
        int keys = 100;
        long sizeAfterWarmUp = 0;
        long sizeAtEnd = 0;
        for (int round = 0; round < 60; round++) {
            for (int k = 0; k < keys; k++) {
                // half small-object records, half chunked objects; a PUT over an existing key retires the old manifest
                put("k" + k, k % 2 == 0 ? 400 + k : StorageRig.CHUNK + 100 + k, round * 1000L + k);
            }
            // like the scheduler: passes back to back while the chunk cap (20) cuts them short; 100 retired manifests per round
            for (int i = 0; i < 20 && gc0().more; i++) {
                // the next pass follows at once
            }
            if (round == 14) sizeAfterWarmUp = rig.store.stats().fileSize();
            sizeAtEnd = rig.store.stats().fileSize();
        }
        assertEquals(0, rig.manifests.gcQueueSize(), "nothing is left waiting");
        assertEquals(keys, rig.manifests.count(), "one manifest per live object");
        assertTrue(sizeAtEnd <= sizeAfterWarmUp * 1.2,
                "the file kept growing: " + sizeAfterWarmUp + " bytes after 15 rounds, " + sizeAtEnd + " after 60");
    }
}
