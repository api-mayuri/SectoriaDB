package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.ChunkStore;
import org.example.sectoriadb.service.gc.GcReports;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safe resize under concurrent writes (ROADMAP C6, doc 10 part B): freeze ordered after the inserts in flight, writes
 * and dedup reads while the blob is frozen, uploads that straddle the resize, readers of old snapshots, the old file's
 * grace period, crashes at every stage, and a stress run with writers, readers and repeated resizes.
 */
class ResizeSafetyTest {

    static final int CHUNK = StorageRig.CHUNK;
    static final int BUCKETS = 64;      // 512 slots per blob

    /** A process death: not caught by the service, so its cleanup does not run (the files stay as they are). */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(String stage) {
            super("crash at " + stage);
        }
    }

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;
    final Map<String, byte[]> model = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.setDefaultNumBuckets(BUCKETS);
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
            ChunkInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    void put(String key, byte[] data) throws Exception {
        rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
        model.put(key, data);
    }

    byte[] get(String key) throws Exception {
        ManifestEntity m = rig.files.findObject("bkt", key).orElseThrow(() -> new AssertionError("no object " + key));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    void assertAllReadable() throws Exception {
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    List<BlobFileEntity> cuckoo() {
        return rig.blobService.cuckooBlobsOf(pool);
    }

    List<Path> rawFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(pool.getBasePath()), "blob_*.raw")) {
            ds.forEach(out::add);
        }
        return out;
    }

    // ── freeze ordering ───────────────────────────────────────────────────────

    @Test
    void theFreezeWaitsForTheInsertsInFlightAndBlocksNewOnes() throws Exception {
        put("seed", bytes(3 * CHUNK, 1));
        String blob = cuckoo().get(0).getId();
        var gate = rig.cache.writerGate(blob);
        gate.lock();                            // an insert that is running
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> freezing = ex.submit(() -> rig.cache.freeze(blob));
        Thread.sleep(300);
        assertFalse(freezing.isDone(), "the freeze waits for the insert in flight");
        assertFalse(rig.cache.isFrozen(blob), "and is not visible before it has the gate");
        gate.unlock();
        freezing.get(10, TimeUnit.SECONDS);
        assertTrue(rig.cache.isFrozen(blob));
        ex.shutdown();
        // an insert that starts now sees the freeze and goes elsewhere
        put("after", bytes(3 * CHUNK, 2));
        assertTrue(cuckoo().size() >= 2, "a single-blob pool grew a blob instead of writing into the frozen one");
        assertEquals(3, rig.cache.fillStats(cuckoo().stream().filter(b -> b.getId().equals(blob)).findFirst().orElseThrow()).activeSlots(),
                "the frozen blob took nothing");
    }

    // ── writes and reads while the blob is frozen ─────────────────────────────

    private void writesWhileFrozen(int maxBlobs) throws Exception {
        rig.props.getPool().setMaxBlobs(maxBlobs);
        for (int i = 0; i < 10; i++) put("pre" + i, bytes(4 * CHUNK, 100 + i));
        assertEquals(1, cuckoo().size());
        BlobFileEntity victim = cuckoo().get(0);
        AtomicInteger hooks = new AtomicInteger();
        rig.resizeService.setStageHook(stage -> {
            if (!stage.equals("frozen") && !stage.equals("migrated")) return;
            int n = hooks.incrementAndGet();
            try {
                // reads of the frozen blob (dedup reads included) keep working
                for (int i = 0; i < 10; i++) assertArrayEquals(model.get("pre" + i), get("pre" + i));
                // an upload of content that exists in the frozen blob only: dedup against it, nothing is written there
                put("dup" + n, model.get("pre3"));
                // new content: the single blob is frozen, so the pool grows a blob (even at max-blobs=1)
                put("new" + n, bytes(5 * CHUNK, 5000 + n));
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2);
        rig.resizeService.setStageHook(null);
        assertEquals(2, hooks.get());
        assertAllReadable();
        assertTrue(rig.cache.isReplaced(victim.getId()));
        assertEquals(2, cuckoo().size(), "the replacement and the blob that took the writes of the freeze");
        assertTrue(cuckoo().stream().anyMatch(b -> b.getId().equals(replacement.getId())));
        // the chunks of "new1" / "new2" are in the grown blob, the old content in the replacement
        long inReplacement = rig.chunks.countByBlob(replacement.getId());
        assertTrue(inReplacement >= 40, "the migrated chunks: " + inReplacement);
        rig.reopen();
        pool = rig.poolService.findByName("bkt").orElseThrow();
        assertAllReadable();
    }

    @Test
    void aSingleBlobPoolKeepsAcceptingWritesAndDedupReadsWhileItsBlobIsFrozen() throws Exception {
        writesWhileFrozen(16);
    }

    @Test
    void evenAPoolAtMaxBlobsOneAcceptsWritesWhileItsOnlyBlobIsFrozen() throws Exception {
        writesWhileFrozen(1);
    }

    // ── uploads and readers that straddle the resize ──────────────────────────

    @Test
    void anUploadThatStagedIntoTheOldBlobCommitsAfterTheResize() throws Exception {
        for (int i = 0; i < 6; i++) put("pre" + i, bytes(3 * CHUNK, 200 + i));
        BlobFileEntity victim = cuckoo().get(0);
        byte[] data = bytes(8 * CHUNK, 999);
        ManifestEntity staged = rig.files.stageStream(new ByteArrayInputStream(data), pool, "straddler", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertTrue(rig.chunks.countByBlob(victim.getId()) < 8 * 2 + 18, "sanity: index knows no chunk of the staged upload yet");
        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2);
        rig.files.commitObject(staged);          // placements name the old blob: redirected to the replacement
        model.put("straddler", data);
        assertAllReadable();
        long chunks = rig.chunks.countByBlob(replacement.getId());
        assertEquals(6 * 3 + 8, chunks, "every chunk, the straddler's too, is indexed in the replacement");
    }

    @Test
    void aReaderWithAnOlderIndexSnapshotKeepsReadingAcrossTheResize() throws Exception {
        byte[] data = bytes(40 * CHUNK, 31);
        put("big", data);
        ManifestEntity m = rig.files.findObject("bkt", "big").orElseThrow();
        BlobFileEntity victim = cuckoo().get(0);
        ChunkStore.Reader reader = rig.chunkStore.reader(m.getPoolId(), m.chunkKeyArray(), m.getChunkSize(), m.getLastChunkSize());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {           // the window of index entries (old blob id) is read here
            var b = reader.read(i);
            byte[] x = new byte[b.remaining()];
            b.get(x);
            out.write(x);
        }
        rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2);
        for (int i = 10; i < reader.count(); i++) {
            var b = reader.read(i);
            byte[] x = new byte[b.remaining()];
            b.get(x);
            out.write(x);
        }
        assertArrayEquals(data, out.toByteArray());
    }

    // ── the old file ──────────────────────────────────────────────────────────

    @Test
    void theOldFileIsKeptForTheGracePeriodAndDeletedByTheCollector() throws Exception {
        for (int i = 0; i < 5; i++) put("o" + i, bytes(4 * CHUNK, 300 + i));
        BlobFileEntity victim = cuckoo().get(0);
        Path oldFile = Path.of(victim.getFilePath());
        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2);
        assertTrue(Files.exists(oldFile), "not deleted at the commit");
        assertEquals(1, rig.reaper.pending());
        rig.gc.run(GcReports.Options.defaults());                 // within the grace period
        assertTrue(Files.exists(oldFile));
        assertAllReadable();
        rig.gc.run(GcReports.Options.defaults().withGrace(0));    // gc run --grace 0s
        assertFalse(Files.exists(oldFile));
        assertEquals(0, rig.reaper.pending());
        assertTrue(Files.exists(Path.of(replacement.getFilePath())));
        assertAllReadable();
    }

    // ── the collector ─────────────────────────────────────────────────────────

    /** Frees of slots are skipped for a frozen blob (a slot freed under the migration would come back in the new blob). */
    @Test
    void theCollectorLeavesAFrozenBlobAloneAndFreesItsChunksAfterTheResize() throws Exception {
        put("keep", bytes(4 * CHUNK, 1));
        put("gone", bytes(6 * CHUNK, 2));
        rig.files.deleteObject("bkt", "gone");
        model.remove("gone");
        BlobFileEntity victim = cuckoo().get(0);
        long[] freedDuringFreeze = new long[1];
        rig.resizeService.setStageHook(stage -> {
            if (stage.equals("frozen")) {
                var rep = rig.gc.run(GcReports.Options.defaults().withGrace(0));
                freedDuringFreeze[0] = rep.chunksFreed;
                assertTrue(rep.deferred >= 6, "the six chunks of the deleted object wait: " + rep);
            }
        });
        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2);
        rig.resizeService.setStageHook(null);
        assertEquals(0, freedDuringFreeze[0]);
        assertEquals(10, rig.cache.fillStats(replacement).activeSlots(), "the migration copied them, as it should");
        var rep = rig.gc.run(GcReports.Options.defaults().withGrace(0));
        assertEquals(6, rep.chunksFreed, "freed in the replacement now");
        assertEquals(4, rig.cache.fillStats(replacement).activeSlots());
        assertAllReadable();
    }

    /**
     * A chunk that the index names but the table lost (the collector crashed after freeing the slot) is restored by the next
     * upload of the same bytes. If its blob is frozen that waits for the resize and restores it in the replacement.
     */
    @Test
    void restoringAMissingChunkWaitsForTheFreezeAndHealsTheReplacement() throws Exception {
        byte[] data = bytes(3 * CHUNK, 5);
        put("victim", data);
        ManifestEntity m = rig.files.findObject("bkt", "victim").orElseThrow();
        BlobFileEntity blob = cuckoo().get(0);
        assertTrue(rig.table(blob).freeSlot(m.chunkKeyArray()[1]) > 0, "the table loses one chunk, the index keeps it");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?>[] healing = new Future<?>[1];
        rig.resizeService.setStageHook(stage -> {
            if (stage.equals("frozen")) {
                healing[0] = ex.submit(() -> {
                    put("again", data);   // dedups against the index, finds the chunk missing: restores it
                    return null;
                });
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                assertFalse(healing[0].isDone(), "the restore waits while the blob is frozen");
            }
        });
        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(blob.getId(), BUCKETS * 2);
        rig.resizeService.setStageHook(null);
        healing[0].get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(3, rig.cache.fillStats(replacement).activeSlots(), "all three chunks are in the replacement");
        assertAllReadable();
    }

    // ── crashes ───────────────────────────────────────────────────────────────

    private void crashAt(String crashStage) throws Exception {
        for (int i = 0; i < 8; i++) put("o" + i, bytes(4 * CHUNK, 400 + i));
        BlobFileEntity victim = cuckoo().get(0);
        assertEquals(1, rawFiles().size());
        rig.resizeService.setStageHook(stage -> {
            if (stage.equals(crashStage)) throw new SimulatedCrash(stage);
        });
        assertThrows(SimulatedCrash.class, () -> rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2));
        rig.resizeService.setStageHook(null);
        boolean committed = crashStage.equals("committed") || crashStage.equals("redirected");
        assertEquals(2, rawFiles().size(), "the crash left the second file behind (" + crashStage + ")");
        // the process is gone: a new one starts, reconciles, serves
        rig.reopen();
        pool = rig.poolService.findByName("bkt").orElseThrow();
        List<Path> after = rawFiles();
        assertEquals(1, after.size(), "startup reconciliation removed the unregistered file: " + after);
        assertEquals(committed ? 1 : 1, cuckoo().size());
        assertEquals(committed, !cuckoo().get(0).getId().equals(victim.getId()), "the metastore names the old or the new blob, whole");
        assertEquals(cuckoo().get(0).getFilePath(), after.get(0).toString());
        assertFalse(rig.cache.isFrozen(cuckoo().get(0).getId()), "freeze is memory only");
        assertAllReadable();
        put("after-restart", bytes(6 * CHUNK, 7));
        rig.resizeService.resizeBlobFile(cuckoo().get(0).getId(), BUCKETS * 2);   // and a resize works again
        assertAllReadable();
    }

    @Test
    void aCrashRightAfterTheNewFileWasCreatedRecovers() throws Exception {
        crashAt("created");
    }

    @Test
    void aCrashAfterTheFreezeRecovers() throws Exception {
        crashAt("frozen");
    }

    @Test
    void aCrashAfterTheMigrationBeforeTheCommitKeepsTheOldBlob() throws Exception {
        crashAt("migrated");
    }

    @Test
    void aCrashAfterTheCommitBeforeTheOldFileIsDeletedKeepsTheNewBlob() throws Exception {
        crashAt("committed");
    }

    @Test
    void aFailedMigrationLeavesNoFileAndNoFreeze() throws Exception {
        for (int i = 0; i < 8; i++) put("o" + i, bytes(4 * CHUNK, 400 + i));
        BlobFileEntity victim = cuckoo().get(0);
        rig.resizeService.setStageHook(stage -> {
            if (stage.equals("migrated")) throw new IOException("disk says no");
        });
        assertThrows(IOException.class, () -> rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2));
        rig.resizeService.setStageHook(null);
        assertEquals(1, rawFiles().size(), "the half-built file was removed");
        assertFalse(rig.cache.isFrozen(victim.getId()));
        put("still-writable", bytes(4 * CHUNK, 8));
        assertEquals(1, cuckoo().size(), "the old blob takes writes again, no growth needed");
        assertAllReadable();
    }

    @Test
    void twoResizesOfTheSameBlobDoNotOverlap() throws Exception {
        for (int i = 0; i < 5; i++) put("o" + i, bytes(4 * CHUNK, 400 + i));
        BlobFileEntity victim = cuckoo().get(0);
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        rig.resizeService.setStageHook(stage -> {
            if (stage.equals("frozen")) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        });
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<BlobFileEntity> first = ex.submit(() -> rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 2));
        inside.await();
        assertThrows(IllegalStateException.class, () -> rig.resizeService.resizeBlobFile(victim.getId(), BUCKETS * 4));
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        rig.resizeService.setStageHook(null);
        assertEquals(1, rawFiles().size() - rig.reaper.pending(), "one registered file (the old one waits for its grace period)");
        assertAllReadable();
    }

    // ── stress ────────────────────────────────────────────────────────────────

    /** 8 writers, 4 readers and repeated resizes of the same blob lineage; afterwards everything is byte-identical. */
    private void stress(int initialBlobs) throws Exception {
        rig.props.getPool().setInitialBlobs(initialBlobs);
        rig.props.getPool().setMaxBlobs(16);
        // the pool exists with its blobs before the run, so that the first resize finds the blob it wants
        put("seed", bytes(6 * CHUNK, 1));
        while (cuckoo().size() < initialBlobs) rig.blobService.create(pool, BUCKETS, CHUNK);
        String lineage = cuckoo().get(0).getId();

        // widen the windows: writers and readers run while the blob is frozen, while it is migrated and between commit and switch
        rig.resizeService.setStageHook(stage -> {
            try {
                if (!stage.equals("created")) Thread.sleep(stage.equals("frozen") ? 40 : 10);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        });
        int writers = 8, perWriter = 60, readers = 4;
        AtomicBoolean writersDone = new AtomicBoolean();
        AtomicInteger resizeCount = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(writers + readers + 1);
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<String> committed = new CopyOnWriteArrayList<>(List.of("seed"));
        List<Future<?>> writerFutures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            writerFutures.add(ex.submit(() -> {
                Random r = new Random(id);
                try {
                    for (int i = 0; i < perWriter; i++) {
                        String key = "w" + id + "-" + i;
                        // some content is shared between writers (dedup against chunks of other uploads and of the frozen blob)
                        byte[] d = i % 5 == 0 ? bytes(4 * CHUNK, 777 + (i % 3)) : bytes(2 * CHUNK + r.nextInt(3 * CHUNK), id * 1000L + i);
                        put(key, d);
                        committed.add(key);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        List<Future<?>> readerFutures = new ArrayList<>();
        for (int rd = 0; rd < readers; rd++) {
            final int seed = rd;
            readerFutures.add(ex.submit(() -> {
                Random r = new Random(seed);
                try {
                    while (!stop.get()) {
                        String key = committed.get(r.nextInt(committed.size()));
                        assertArrayEquals(model.get(key), get(key), key);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        Future<?> resizer = ex.submit(() -> {
            try {
                // the same blob lineage is resized again and again for as long as the writers run
                for (int i = 0; !writersDone.get() && i < 60 && failures.isEmpty(); i++) {
                    int buckets = 128 + 64 * (i % 3);
                    lineageHolder[0] = rig.resizeService.resizeBlobFile(lineageHolder[0] == null ? lineage : lineageHolder[0].getId(), buckets);
                    resizeCount.incrementAndGet();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        for (Future<?> f : writerFutures) f.get(240, TimeUnit.SECONDS);
        writersDone.set(true);
        resizer.get(240, TimeUnit.SECONDS);
        stop.set(true);
        for (Future<?> f : readerFutures) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        rig.resizeService.setStageHook(null);
        assertTrue(failures.isEmpty(), "no failures: " + failures);
        assertTrue(resizeCount.get() >= 2, "the resizes ran during the writes: " + resizeCount.get());

        assertAllReadable();
        ChunkInvariants.check(rig.store);
        for (BlobFileEntity b : cuckoo()) {
            // every indexed chunk is physically present in the blob that the index names, and nothing else but copies
            long active = rig.cache.fillStats(b).activeSlots();
            long indexed = rig.chunks.countByBlob(b.getId());
            assertTrue(active >= indexed, "blob " + b.getId() + ": " + active + " slots hold at least the " + indexed + " indexed chunks");
        }
        rig.reopen();
        pool = rig.poolService.findByName("bkt").orElseThrow();
        assertAllReadable();
        ChunkInvariants.check(rig.store);
        assertEquals(cuckoo().size(), rawFiles().size(), "after the restart one file per registered blob");
    }

    final BlobFileEntity[] lineageHolder = new BlobFileEntity[1];

    @Test
    void stressOneBlobPool() throws Exception {
        stress(1);
    }

    @Test
    void stressThreeBlobPool() throws Exception {
        stress(3);
    }
}
