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

    private byte[] data(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void reclamationGoesOnWhileAnUnrelatedUploadStaysInFlight() throws Exception {
        rig.props.getGc().setSweepGateWait(java.time.Duration.ofMillis(100));
        put("old", StorageRig.CHUNK * 3, 7);
        rig.catalog.deleteObject("bkt", "old");
        // a long upload: its chunks are written, the commit has not happened (it may take minutes for a big object)
        var staged = rig.writer.stageStream(new ByteArrayInputStream(data(StorageRig.CHUNK * 4, 8)), pool, "slow", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertEquals(1, rig.chunkStore.gate().inFlight());

        RunReport r = gc0();
        assertEquals(3, r.chunksFreed, "the upload never looked at the garbage chunks: no reason to wait for it");
        assertEquals(0, r.deferred);

        rig.writer.commitObject(staged);
        assertEquals(1, rig.manifests.count() - rig.manifests.gcQueueSize());
    }

    @Test
    void anUploadThatReliesOnAGarbageChunkKeepsItFromBeingFreedUntilItIsDone() throws Exception {
        rig.props.getGc().setSweepGateWait(java.time.Duration.ofMillis(100));
        byte[] same = data(StorageRig.CHUNK * 2, 9);
        rig.writer.storeStream(new ByteArrayInputStream(same), pool, "a", "x/y");
        rig.catalog.deleteObject("bkt", "a");
        // the new upload deduplicates against the zero-reference chunks that are due for collection
        var staged = rig.writer.stageStream(new ByteArrayInputStream(same), pool, "b", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());

        RunReport r = gc0();
        assertEquals(0, r.chunksFreed, "the chunks the upload relies on are not freed under it");
        assertTrue(r.deferred > 0);

        rig.writer.commitObject(staged);
        r = gc0();
        assertEquals(0, r.chunksFreed, "and after its commit they are referenced");
        var out = new java.io.ByteArrayOutputStream();
        rig.reader.streamToOutput(rig.catalog.findObject("bkt", "b").orElseThrow(), out);
        org.junit.jupiter.api.Assertions.assertArrayEquals(same, out.toByteArray());
    }
}
