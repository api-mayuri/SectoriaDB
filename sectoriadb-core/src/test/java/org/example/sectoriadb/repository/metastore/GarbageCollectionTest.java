package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.repository.GcRepository;
import org.example.sectoriadb.service.UploadGate;
import org.example.sectoriadb.service.gc.GcReports;
import org.example.sectoriadb.service.gc.GcReports.Options;
import org.example.sectoriadb.service.gc.GcReports.RunReport;
import org.example.sectoriadb.service.gc.GcReports.SweepReport;
import org.example.sectoriadb.service.impl.ChunkNotFoundException;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.example.sectoriadb.service.impl.TableFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Garbage collection end to end (doc 10): the chunk queue, grace period, the per-chunk protocol and its crash cases, orphans,
 * stray copies and the upload gate, tombstones and the deferred marking of small records.
 */
class GarbageCollectionTest {

    static final int CHUNK = StorageRig.CHUNK;

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

    // ------------------------------------------------------------------ helpers

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] content(int chunks, long seed) {
        return bytes(chunks * CHUNK, seed);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    ManifestEntity put(String key, byte[] data) throws Exception {
        return rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
    }

    ManifestEntity stage(String key, byte[] data) throws Exception {
        return rig.files.stageStream(new ByteArrayInputStream(data), pool, key, null, UploadChecksums.none());
    }

    byte[] get(String key) throws Exception {
        return read(rig.files.findObject("bkt", key).orElseThrow());
    }

    byte[] read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    RunReport gc0() {
        return rig.gc.run(Options.defaults().withGrace(0));
    }

    long physical() throws Exception {
        long n = 0;
        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) n += rig.cache.get(b).getFillStats().activeSlots();
        return n;
    }

    ChunkRepository.Stats stats() {
        return rig.chunks.stats();
    }

    // ------------------------------------------------------------------ the chunk queue

    @Test
    void exactlyTheUnreferencedChunksAreFreedAndLiveObjectsStayIntact() throws Exception {
        byte[] a = content(6, 1);
        byte[] b = concat(Arrays.copyOfRange(a, 3 * CHUNK, 6 * CHUNK), content(3, 2));   // shares a's last three chunks
        put("a", a);
        put("b", b);
        put("c", content(2, 3));
        assertEquals(11, stats().chunks());
        assertEquals(11, physical());

        rig.files.deleteObject("bkt", "a");
        assertEquals(3, stats().gcQueue(), "only a's three private chunks became garbage, the shared ones are still referenced by b");
        assertEquals(11, physical(), "nothing is freed by the delete itself");

        RunReport r = gc0();
        assertEquals(3, r.chunksFreed);
        assertEquals(3L * CHUNK, r.bytesFreed);
        assertEquals(0, r.errors);
        assertEquals(8, physical());
        assertEquals(8, stats().chunks());
        assertEquals(0, stats().gcQueue());
        assertArrayEquals(b, get("b"));
        assertArrayEquals(content(2, 3), get("c"));

        // overwrite b: its three private chunks and the three shared ones are garbage now
        put("b", content(4, 4));
        assertEquals(6, stats().gcQueue());
        r = gc0();
        assertEquals(6, r.chunksFreed);
        assertEquals(6, physical(), "c (2) + the new b (4)");
        assertArrayEquals(content(4, 4), get("b"));
        assertArrayEquals(content(2, 3), get("c"));

        // a second pass has nothing to do
        r = gc0();
        assertEquals(0, r.chunksFreed + r.revived + r.gone + r.deferred + r.tooNew);
        assertEquals(rig.metrics.gcChunksFreed.get(), 9);
        assertTrue(rig.metrics.gcRuns.get() >= 3);
    }

    @Test
    void freedSlotsLowerTheFillStatisticsAndAreReusedByNewInserts() throws Exception {
        rig.props.setDefaultNumBuckets(16);                 // 128 slots
        rig.props.getPool().setMaxBlobs(1);                 // the pool cannot grow around the problem
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; ; i++) {
                put("o" + i, content(8, 100 + i));
                keys.add("o" + i);
            }
        } catch (TableFullException | org.example.sectoriadb.service.impl.ChunkCorruptedException e) {
            // the pool is full
        } catch (Exception e) {
            assertTrue(e instanceof TableFullException || e.getCause() instanceof TableFullException, "unexpected: " + e);
        }
        assertTrue(keys.size() >= 10, "128 slots hold at least 10 objects of 8 chunks: " + keys.size());
        BlobFileEntity blob = rig.blobService.cuckooBlobsOf(pool).get(0);
        double fullPercent = rig.cache.get(blob).getFillStats().fillPercent();
        double weightFull = rig.blobService.placementWeight(blob);
        assertTrue(fullPercent > 85, "driven close to the limit: " + fullPercent);
        assertThrows(Exception.class, () -> put("overflow", content(8, 999)), "a full pool refuses");

        for (String k : keys) rig.files.deleteObject("bkt", k);
        RunReport r = gc0();
        assertEquals(keys.size() * 8L, r.chunksFreed);
        // the uploads that were refused by the full pool wrote part of their chunks before they failed: strays
        assertTrue(rig.cache.get(blob).getFillStats().activeSlots() > 0);
        SweepReport swept = rig.gc.sweep(pool);
        assertTrue(swept.freed > 0);
        assertEquals(0, rig.cache.get(blob).getFillStats().activeSlots());
        assertTrue(rig.blobService.placementWeight(blob) > weightFull * 10,
                "the placement weight sees the freed space: " + weightFull + " -> " + rig.blobService.placementWeight(blob));

        // the full pool accepts new data after delete + GC, as much as before
        for (int i = 0; i < keys.size() - 2; i++) put("again" + i, content(8, 5000 + i));
        for (int i = 0; i < keys.size() - 2; i++) assertArrayEquals(content(8, 5000 + i), get("again" + i));
        assertEquals(1, rig.blobService.cuckooBlobsOf(pool).size(), "no growth was needed");
    }

    @Test
    void gracePeriodIsHonored() throws Exception {
        put("a", content(4, 11));
        rig.files.deleteObject("bkt", "a");
        assertEquals(4, stats().gcQueue());

        RunReport r = rig.gc.run(Options.defaults());           // default grace: 15 minutes
        assertEquals(0, r.chunksFreed);
        assertEquals(4, stats().gcQueue());
        assertEquals(4, physical());

        rig.props.getGc().setGrace(Duration.ofMillis(300));
        r = rig.gc.run(Options.defaults());
        assertEquals(0, r.chunksFreed, "the chunks have been garbage for a few milliseconds only");
        Thread.sleep(400);
        r = rig.gc.run(Options.defaults());
        assertEquals(4, r.chunksFreed);
        assertEquals(0, physical());
    }

    @Test
    void rateLimitBoundsOnePass() throws Exception {
        put("a", content(10, 12));
        rig.files.deleteObject("bkt", "a");
        RunReport r = rig.gc.run(Options.defaults().withGrace(0).withMaxChunks(4));
        assertEquals(4, r.chunksFreed);
        assertEquals(6, stats().gcQueue());
        r = rig.gc.run(Options.defaults().withGrace(0));
        assertEquals(6, r.chunksFreed);
    }

    @Test
    void aChunkRevivedBetweenSelectionAndCollectionIsKept() throws Exception {
        byte[] data = content(3, 13);
        put("a", data);
        rig.files.deleteObject("bkt", "a");
        long cutoff = System.currentTimeMillis() + 1000;
        List<GcRepository.ChunkRef> due = rig.gcRepo.dueChunks(null, cutoff, 100);
        assertEquals(3, due.size());

        put("a2", data);                                        // revives all three entries, removes their queue rows
        GcRepository.ChunkBatchResult res = rig.gcRepo.collectChunks(due, cutoff, (p, b, k) -> {
            throw new AssertionError("a referenced chunk must never reach the slot free");
        });
        assertEquals(0, res.freed());
        assertEquals(3, physical());
        assertArrayEquals(data, get("a2"));
    }

    @Test
    void aChunkThatWentThroughZeroAgainIsNotCollectedEarly() throws Exception {
        byte[] data = content(2, 14);
        put("a", data);
        rig.files.deleteObject("bkt", "a");
        Thread.sleep(50);
        long cutoff = System.currentTimeMillis();               // the first zero is older than this
        List<GcRepository.ChunkRef> due = rig.gcRepo.dueChunks(null, cutoff, 100);
        assertEquals(2, due.size());
        put("a2", data);                                        // revived
        rig.files.deleteObject("bkt", "a2");                    // zero again, a fresh queue row, younger than the cutoff
        GcRepository.ChunkBatchResult res = rig.gcRepo.collectChunks(due, cutoff, (p, b, k) -> {
            throw new AssertionError("must not free a chunk that has been garbage for less than the grace period");
        });
        assertEquals(2, res.tooNew());
        assertEquals(2, physical());
    }

    // ------------------------------------------------------------------ crash cases of the per-chunk protocol

    @Test
    void crashAfterTheSlotWasFreedButBeforeTheCommitIsRepairedByTheNextPass() throws Exception {
        byte[] data = content(4, 21);
        ManifestEntity m = put("a", data);
        long[] keys = m.chunkKeyArray();
        rig.files.deleteObject("bkt", "a");
        // the collector freed two slots and died before its transaction committed: entries and queue rows remain
        BlobFileEntity blob = rig.blobService.cuckooBlobsOf(pool).get(0);
        assertTrue(rig.cache.get(blob).freeSlot(keys[0]) > 0);
        assertTrue(rig.cache.get(blob).freeSlot(keys[1]) > 0);
        assertEquals(2, physical());

        RunReport r = gc0();
        assertEquals(2, r.chunksFreed);
        assertEquals(2, r.chunksAbsent, "the entries whose slot was already gone are removed as well");
        assertEquals(0, stats().chunks());
        assertEquals(0, physical());
    }

    @Test
    void anUploadThatFindsAnEntryWhoseSlotWasFreedRestoresItInPlace() throws Exception {
        byte[] data = content(4, 22);
        ManifestEntity m = put("a", data);
        long[] keys = m.chunkKeyArray();
        rig.files.deleteObject("bkt", "a");
        BlobFileEntity blob = rig.blobService.cuckooBlobsOf(pool).get(0);
        for (long k : keys) rig.cache.get(blob).freeSlot(k);    // all slots gone, entries (zero references) remain

        put("b", data);                                         // finds the entries, sees the slots missing, rewrites them
        assertEquals(4, physical());
        assertArrayEquals(data, get("b"));
        RunReport r = gc0();
        assertEquals(0, r.chunksFreed, "the chunks are referenced again");
        assertArrayEquals(data, get("b"));
    }

    @Test
    void anUploadThatDeduplicatedAgainstACollectedEntryIsToldToRetry() throws Exception {
        byte[] data = content(3, 23);
        put("a", data);
        rig.files.deleteObject("bkt", "a");

        ManifestEntity staged = stage("b", data);               // dedups against the three zero-reference entries
        assertTrue(staged.getStagedChunks().stream().allMatch(p -> p.indexed()), "found in the index, nothing written");
        assertEquals(1, rig.chunkStore.gate().inFlight());

        RunReport r = gc0();                                    // grace 0: collects them under the upload's feet
        assertEquals(3, r.chunksFreed);

        ChunkRepository.ChunkPlacementException e = assertThrows(ChunkRepository.ChunkPlacementException.class,
                () -> rig.files.commitObject(staged));
        assertEquals(ChunkRepository.ChunkPlacementException.Reason.COLLECTED, e.reason());
        assertEquals(0, rig.chunkStore.gate().inFlight(), "the hold is released after a failed commit");
        assertTrue(rig.files.findObject("bkt", "b").isEmpty(), "nothing was committed");

        put("b", data);                                         // the retry stages afresh
        assertArrayEquals(data, get("b"));
    }

    @Test
    void aFailingSlotFreeEndsTheBatchButKeepsWhatWasDone() throws Exception {
        byte[] data = content(6, 24);
        put("a", data);
        rig.files.deleteObject("bkt", "a");
        long cutoff = System.currentTimeMillis() + 1000;
        List<GcRepository.ChunkRef> due = rig.gcRepo.dueChunks(null, cutoff, 100);
        int[] calls = {0};
        GcRepository.ChunkBatchResult res = rig.gcRepo.collectChunks(due, cutoff, (p, blob, k) -> {
            if (++calls[0] == 4) throw new IOException("disk says no");
            var table = rig.blobService.tableOf(blob);
            return GcRepository.SlotFree.freed(table.freeSlot(k));
        });
        assertEquals(3, res.freed());
        assertNotNull(res.error());
        assertEquals(3, stats().chunks(), "the three untouched ones stay indexed");
        assertEquals(3, physical());
        assertEquals(3, stats().gcQueue());
        RunReport r = gc0();
        assertEquals(3, r.chunksFreed);
        assertEquals(0, physical());
    }

    // ------------------------------------------------------------------ orphans, stray copies, the upload gate

    @Test
    void orphanCopiesAreFreedAndTheirRecordsDropped() throws Exception {
        rig.blobService.create(pool, 16, CHUNK);
        rig.blobService.create(pool, 16, CHUNK);
        byte[] data = content(6, 31);
        ManifestEntity first = stage("a", data);
        Set<String> used = new HashSet<>();
        first.getStagedChunks().forEach(p -> used.add(p.blobId()));
        used.forEach(rig.cache::freeze);                       // steer the second upload to the other blob
        ManifestEntity second;
        try {
            second = stage("b", data);
        } finally {
            used.forEach(rig.cache::unfreeze);
        }
        rig.files.commitObject(first);
        rig.files.commitObject(second);
        long orphans = stats().orphans();
        assertTrue(orphans > 0, "the test needs chunks that were written twice");
        assertEquals(6 + orphans, physical());

        RunReport r = gc0();
        assertEquals(orphans, r.orphansFreed);
        assertEquals(orphans * CHUNK, r.orphanBytes);
        assertEquals(0, stats().orphans());
        assertEquals(6, physical());
        assertArrayEquals(data, get("a"));
        assertArrayEquals(data, get("b"));
        assertEquals(12, stats().refs());
    }

    @Test
    void strayCopiesOfAnAbandonedUploadAreSweptButNotWhileTheUploadIsInFlight() throws Exception {
        rig.props.getGc().setSweepGateWait(Duration.ofMillis(100));
        byte[] data = content(5, 32);
        put("keep", content(3, 33));
        ManifestEntity staged = stage("x", data);               // chunks written, no index entries yet
        assertEquals(8, physical());

        SweepReport s = rig.gc.sweep(pool);
        assertTrue(s.deferred, "an upload is in flight: the sweep must not free its copies");
        assertEquals(0, s.freed);
        assertEquals(8, physical());

        rig.files.commitObject(staged);                         // the upload was never harmed
        assertArrayEquals(data, get("x"));
        s = rig.gc.sweep(pool);
        assertEquals(0, s.strays);
        assertEquals(8, s.slotsScanned);

        // an abandoned upload: its copies are strays once it lets go
        ManifestEntity abandoned = stage("y", content(4, 34));
        assertEquals(12, physical());
        s = rig.gc.sweep(pool);
        assertTrue(s.deferred);
        rig.files.abortStaged(abandoned);
        s = rig.gc.sweep(pool);
        assertFalse(s.deferred);
        assertEquals(4, s.strays);
        assertEquals(4, s.freed);
        assertEquals(8, physical());
        assertArrayEquals(data, get("x"));
        assertArrayEquals(content(3, 33), get("keep"));
        assertEquals(0, rig.chunkStore.gate().inFlight());
    }

    @Test
    void aSweepWaitsForAnUploadThatCommitsInTime() throws Exception {
        rig.props.getGc().setSweepGateWait(Duration.ofSeconds(10));
        byte[] data = content(5, 35);
        ManifestEntity staged = stage("x", data);

        CompletableFuture<SweepReport> sweep = CompletableFuture.supplyAsync(() -> rig.gc.sweep(pool));
        Thread.sleep(300);
        assertFalse(sweep.isDone(), "the sweep waits for the in-flight upload");
        // while the sweep drains, a new upload is held back: it starts after the sweep's batch
        CompletableFuture<ManifestEntity> late = CompletableFuture.supplyAsync(() -> {
            try {
                return stage("late", content(1, 36));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(100);
        assertFalse(late.isDone(), "new uploads wait while the gate is being drained");

        rig.files.commitObject(staged);
        SweepReport s = sweep.get(10, TimeUnit.SECONDS);
        assertEquals(0, s.freed, "the candidates were committed meanwhile: the in-transaction re-check keeps them");
        assertTrue(s.notStray > 0, "the sweep had seen the in-flight chunks as candidates");
        assertFalse(s.deferred);
        rig.files.commitObject(late.get(10, TimeUnit.SECONDS));
        assertArrayEquals(data, get("x"));
        assertEquals(6, physical());
    }

    @Test
    void copiesLeftByACrashBetweenTheDataWriteAndTheCommitAreSweptAfterARestart() throws Exception {
        put("keep", content(3, 37));
        stage("lost", content(6, 38));                          // the process dies here
        assertEquals(9, physical());
        rig.reopen();
        pool = rig.bucket("bkt");
        rig.props.getGc().setSweepGateWait(Duration.ofMillis(100));

        SweepReport s = rig.gc.sweep(pool);
        assertEquals(6, s.freed);
        assertEquals(3, physical());
        assertArrayEquals(content(3, 37), get("keep"));
        put("lost", content(6, 38));                           // and the same upload works afterwards
        assertArrayEquals(content(6, 38), get("lost"));
    }

    @Test
    void anAbandonedHoldIsRevokedAfterItsTimeToLiveAndTheLateCommitFailsSafely() throws Exception {
        rig.props.getGc().setSweepGateWait(Duration.ofMillis(500));
        rig.props.getGc().setUploadTicketTtl(Duration.ZERO);
        byte[] data = content(4, 39);
        ManifestEntity staged = stage("slow", data);
        Thread.sleep(5);
        SweepReport s = rig.gc.sweep(pool);
        assertEquals(4, s.freed, "the hold was too old: revoked, its copies are strays");
        assertEquals(0, physical());
        ChunkRepository.ChunkPlacementException e = assertThrows(ChunkRepository.ChunkPlacementException.class,
                () -> rig.files.commitObject(staged));
        assertEquals(ChunkRepository.ChunkPlacementException.Reason.COLLECTED, e.reason());
        assertTrue(rig.files.findObject("bkt", "slow").isEmpty());
        put("slow", data);
        assertArrayEquals(data, get("slow"));
    }

    @Test
    void sweepAndUploadsRunTogetherWithoutLosingData() throws Exception {
        rig.props.getGc().setSweepGateWait(Duration.ofSeconds(5));
        int uploads = 40;
        var uploaders = new ArrayList<CompletableFuture<Void>>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            uploaders.add(CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < uploads / 4; i++) put("o" + id + "-" + i, content(3, 1000L * id + i));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        var sweeper = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 20; i++) rig.gc.sweep(pool);
        });
        for (var f : uploaders) f.get(60, TimeUnit.SECONDS);
        sweeper.get(60, TimeUnit.SECONDS);
        for (int t = 0; t < 4; t++) {
            for (int i = 0; i < uploads / 4; i++) assertArrayEquals(content(3, 1000L * t + i), get("o" + t + "-" + i));
        }
        assertEquals(uploads * 3L, physical(), "no live copy was swept");
        assertEquals(0, rig.chunkStore.gate().inFlight());
    }

    // ------------------------------------------------------------------ readers of old versions

    @Test
    void aReaderThatHoldsAnOldChunkedVersionKeepsReadingItWithinTheGracePeriod() throws Exception {
        byte[] v1 = content(5, 41);
        ManifestEntity old = put("k", v1);
        ManifestEntity holder = rig.files.findObject("bkt", "k").orElseThrow();   // the reader's snapshot of the manifest
        put("k", content(5, 42));                                // overwrite: v1's chunks go to the queue
        assertEquals(5, stats().gcQueue());

        rig.gc.run(Options.defaults());                          // grace 15 min: nothing is freed
        assertArrayEquals(v1, read(holder), "the old version is still readable within G");

        gc0();                                                   // G = 0: documented, the old version is gone for readers
        assertThrows(ChunkNotFoundException.class, () -> read(holder));
        assertArrayEquals(content(5, 42), get("k"));
        assertNotNull(old);
    }

    @Test
    void smallRecordsAreMarkedDeletedOnlyByTheCollectorAfterTheGracePeriod() throws Exception {
        byte[] v1 = bytes(300, 51);
        put("s", v1);
        ManifestEntity holder = rig.files.findObject("bkt", "s").orElseThrow();
        BlobFileEntity sob = holder.getSmallBlob();
        put("s", bytes(400, 52));                                // overwrite
        rig.files.deleteObject("bkt", "s");                      // and delete
        var st = rig.blobService.getSmallStats(sob);
        assertEquals(2, st.liveRecords(), "nothing is marked DELETED at commit / delete time any more");
        assertEquals(0, st.deadRecords());
        assertEquals(2, rig.manifests.gcQueueSize());

        rig.gc.run(Options.defaults());                          // within G
        assertArrayEquals(v1, read(holder), "a reader holding the old version can still read it");
        assertEquals(2, rig.manifests.gcQueueSize());

        RunReport r = gc0();
        assertEquals(2, r.tombstones);
        assertEquals(2, r.smallRecordsMarked);
        assertEquals(0, rig.manifests.gcQueueSize());
        st = rig.blobService.getSmallStats(sob);
        assertEquals(0, st.liveRecords());
        assertEquals(2, st.deadRecords());
        assertThrows(SmallObjectCorruptedException.class, () -> read(holder), "with G = 0 the old record is gone");
        assertEquals(0, rig.manifests.count());
    }

    @Test
    void chunkedAndEmptyTombstonesAreRemovedWithoutSideEffects() throws Exception {
        put("e", new byte[0]);
        put("c", content(2, 61));
        rig.files.deleteObject("bkt", "e");
        rig.files.deleteObject("bkt", "c");
        assertEquals(2, rig.manifests.gcQueueSize());
        RunReport r = gc0();
        assertEquals(2, r.tombstones);
        assertEquals(0, r.smallRecordsMarked);
        assertEquals(0, rig.manifests.count());
    }

    @Test
    void passesAreRepeatableAfterARestart() throws Exception {
        put("a", content(4, 71));
        put("b", content(4, 72));
        rig.files.deleteObject("bkt", "a");
        rig.reopen();
        pool = rig.bucket("bkt");
        RunReport r = gc0();
        assertEquals(4, r.chunksFreed);
        assertEquals(1, r.tombstones);
        rig.reopen();
        pool = rig.bucket("bkt");
        assertEquals(0, gc0().chunksFreed);
        assertArrayEquals(content(4, 72), get("b"));
        assertEquals(4, physical());
    }

    @Test
    void theLocationCacheForgetsFreedChunks() throws Exception {
        put("a", content(4, 82));
        put("b", content(3, 83));
        get("a");
        assertEquals(7, rig.chunkStore.cachedLocations(), "stage and reads fill the hint cache");
        rig.files.deleteObject("bkt", "a");
        gc0();
        assertEquals(3, rig.chunkStore.cachedLocations(), "the hints of the freed chunks are dropped, the live ones stay");
        assertArrayEquals(content(3, 83), get("b"));
    }

    @Test
    void statusReportsTheQueues() throws Exception {
        put("a", content(4, 81));
        rig.files.deleteObject("bkt", "a");
        var st = rig.gc.status();
        assertEquals(4, st.chunkQueue());
        assertEquals(0, st.chunkQueueDue(), "grace period not over");
        assertEquals(1, st.tombstones());
        rig.props.getGc().setGrace(Duration.ZERO);
        st = rig.gc.status();
        assertEquals(4, st.chunkQueueDue());
        assertEquals(1, st.tombstonesDue());
    }

    @Test
    void deletingAPoolWhileItsGarbageWaitsLeavesNothingBehind() throws Exception {
        put("a", content(4, 91));
        rig.files.deleteObject("bkt", "a");
        rig.files.deleteObject("bkt", "nothing");
        for (var x : List.of("a")) assertNotNull(x);
        // an empty bucket can be deleted with its garbage still queued; the collector then finds nothing to do
        rig.poolService.deleteBucket("bkt");
        RunReport r = gc0();
        assertEquals(0, r.errors);
        assertEquals(0, r.chunksFreed);
        pool = rig.bucket("bkt");
    }
}
