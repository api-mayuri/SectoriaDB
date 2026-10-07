package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.CrashSimulation;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cuckoo insert durability without fsync under the table lock (doc 10, part B): a chunk is written without an fsync and
 * forced by a barrier before the commit that references it. These tests show the ordering (nothing is committed before the
 * barrier) and the crash safety (a disk that drops unforced writes never makes a committed object lose data).
 */
class CuckooDurabilityTest {

    static final int CHUNK = StorageRig.CHUNK;

    @TempDir
    Path root;

    final CrashSimulation disk = new CrashSimulation();
    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.setDefaultNumBuckets(64);
        rig.props.getPool().setMaxBlobs(16);
        rig.engineFactory = disk::newEngine;
        rig.reopen();
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        disk.revive();
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

    byte[] get(String key) throws Exception {
        ManifestEntity m = rig.files.findObject("bkt", key).orElseThrow(() -> new AssertionError("no object " + key));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    /** Wraps a ManifestRepository so that a hook runs right before every commit. */
    static ManifestRepository spy(ManifestRepository real, Runnable beforeCommit) {
        return (ManifestRepository) java.lang.reflect.Proxy.newProxyInstance(ManifestRepository.class.getClassLoader(),
                new Class<?>[]{ManifestRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commitObject") || method.getName().equals("saveNew")) beforeCommit.run();
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ── ordering ──────────────────────────────────────────────────────────────

    @Test
    void nothingIsCommittedBeforeTheBarrierHasForcedTheChunks() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        List<String> violations = new ArrayList<>();
        ManifestRepository checking = spy(rig.manifests, () -> {
            commits.incrementAndGet();
            if (disk.unforced() != 0) violations.add("commit with " + disk.unforced() + " unforced write(s)");
        });
        FileStorageService files = new FileStorageService(checking, rig.blobService, rig.cache, rig.smallCache,
                rig.chunkStore, rig.opLog, rig.props);
        for (int i = 0; i < 25; i++) {
            // new chunks, a repeat of an earlier object (dedup against the index), and an object that repeats itself
            byte[] d = i % 5 == 4 ? bytes(6 * CHUNK, 0) : bytes(3 * CHUNK + i, i);
            files.storeStream(new ByteArrayInputStream(d), pool, "o" + i, null);
        }
        assertEquals(25, commits.get());
        assertEquals(List.of(), violations);
        assertTrue(disk.forces.get() > 0 && disk.writes.get() > 0);
        assertEquals(0, disk.unforced(), "everything that was written is durable at the end");
    }

    @Test
    void anUploadThatOnlyDeduplicatesAgainstAnUncommittedUploadForcesItsChunksToo() throws Exception {
        byte[] data = bytes(4 * CHUNK, 77);
        // upload A writes its chunks (no fsync) and has not committed
        ManifestEntity a = rig.files.stageStream(new ByteArrayInputStream(data), pool, "a", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertTrue(disk.unforced() > 0, "A's chunks sit in the page cache");
        // upload B has the same content: its chunks are found in the table (not in the index) and nothing is written
        int writesBefore = disk.writes.get();
        ManifestEntity b = rig.files.stageStream(new ByteArrayInputStream(data), pool, "b", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertEquals(writesBefore, disk.writes.get(), "B deduplicated against the table, it wrote nothing");
        assertTrue(disk.unforced() > 0);
        // B commits first: its barrier must make A's chunks durable, although it wrote none of them
        List<String> violations = new ArrayList<>();
        ManifestRepository checking = spy(rig.manifests, () -> {
            if (disk.unforced() != 0) violations.add("B committed with " + disk.unforced() + " unforced write(s)");
        });
        FileStorageService files = new FileStorageService(checking, rig.blobService, rig.cache, rig.smallCache,
                rig.chunkStore, rig.opLog, rig.props);
        files.commitObject(b);
        assertEquals(List.of(), violations);
        rig.files.commitObject(a);
        // crash now: both objects were committed
        disk.crash(1, 0.0);
        disk.revive();
        rig.reopen();
        pool = rig.poolService.findByName("bkt").orElseThrow();
        assertArrayEquals(data, get("a"));
        assertArrayEquals(data, get("b"));
    }

    // ── group force ───────────────────────────────────────────────────────────

    @Test
    void concurrentWritersShareTheFsyncOfTheBarrier() throws Exception {
        rig.blobService.create(pool, 64, CHUNK);
        BlobFileEntity b = rig.blobService.create(pool, 64, CHUNK);
        CuckooHashTable table = rig.table(b);
        int threads = 16, per = 16;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        disk.forceDelayMillis = 2;   // writers arriving during an fsync must share the next one
        int forcesBefore = disk.forces.get();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            fs.add(ex.submit(() -> {
                for (int i = 0; i < per; i++) {
                    table.insertPreservingKey(id * 1000L + i + 1, ByteBuffer.wrap(bytes(CHUNK, id * 1000L + i)));
                    table.barrier();
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        int forces = disk.forces.get() - forcesBefore;
        disk.forceDelayMillis = 0;
        assertTrue(forces >= 1 && forces < threads * per, "fsyncs: " + forces + " for " + (threads * per) + " barriers");
        assertEquals(0, disk.unforced());
        assertFalse(table.hasUnforcedWrites());
    }

    // ── crash safety ──────────────────────────────────────────────────────────

    /**
     * Writers upload while the power fails at a random moment (each unforced write survives or not, independently). After
     * the restart every object whose PUT had returned is byte-identical; slots that a lost fsync left half-written are
     * not referenced by anything, a repeated upload heals them, and the collector's sweep frees them.
     */
    @Test
    void aCrashDuringUploadsNeverLosesACommittedObject() throws Exception {
        Map<String, byte[]> committed = new ConcurrentHashMap<>();
        Map<String, byte[]> attempted = new ConcurrentHashMap<>();
        int rounds = 12;
        int strayChunksSeen = 0;
        for (int round = 0; round < rounds; round++) {
            int writers = 6;
            ExecutorService ex = Executors.newFixedThreadPool(writers);
            List<Future<?>> fs = new ArrayList<>();
            final int rd = round;
            for (int w = 0; w < writers; w++) {
                final int id = w;
                fs.add(ex.submit(() -> {
                    Random r = new Random(rd * 100L + id);
                    for (int i = 0; i < 40; i++) {
                        String key = "r" + rd + "w" + id + "-" + i;
                        // a third of the objects share their content with others (dedup against tables and the index)
                        byte[] d = r.nextInt(3) == 0 ? bytes(2 * CHUNK + 5, r.nextInt(7)) : bytes(2 * CHUNK + r.nextInt(3 * CHUNK), rd * 1000L + id * 100 + i);
                        attempted.put(key, d);
                        try {
                            rig.files.storeStream(new ByteArrayInputStream(d), pool, key, null);
                            committed.put(key, d);
                        } catch (Exception | AssertionError e) {
                            return null;   // the disk died under this writer
                        }
                    }
                    return null;
                }));
            }
            Thread.sleep(15 + (round * 7) % 40);
            int lost = disk.crash(round, 0.4);
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
            ex.shutdown();
            // the process restarts: fresh caches over the files as the disk kept them
            disk.revive();
            rig.reopen();
            pool = rig.poolService.findByName("bkt").orElseThrow();
            for (var e : committed.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "round " + round + " " + e.getKey());
            ChunkInvariants.check(rig.store);

            // strays: slots that are ACTIVE but unreferenced, some with a data CRC that does not match (lost fsync)
            int corruptBefore = 0;
            for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) corruptBefore += rig.table(b).scrub().corrupt();
            var sweep = rig.gc.sweep(pool);
            strayChunksSeen += sweep.strays;
            int corruptAfter = 0, quarantined = 0;
            for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) {
                var s = rig.table(b).scrub();
                corruptAfter += s.corrupt();
                quarantined += s.quarantined();
            }
            assertEquals(0, corruptAfter, "round " + round + ": after the sweep no damaged slot is left (before: " + corruptBefore + ")");
            assertEquals(0, quarantined);
            for (var e : committed.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after the sweep " + e.getKey());

            // an upload of content that was in flight at the crash works (heals / re-writes), whatever state its slots were in
            for (var e : attempted.entrySet()) {
                if (committed.containsKey(e.getKey())) continue;
                if (e.getKey().startsWith("r" + round)) {
                    rig.files.storeStream(new ByteArrayInputStream(e.getValue()), pool, e.getKey(), null);
                    committed.put(e.getKey(), e.getValue());
                }
            }
            for (var e : committed.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after the retry " + e.getKey());
        }
        assertTrue(committed.size() > 100, "enough objects survived to make the check meaningful: " + committed.size());
        assertTrue(strayChunksSeen > 0, "the crashes left unreferenced chunks that the sweep had to free");
    }

    /** A damaged slot (stale data under an ACTIVE meta entry) is detected on read and healed by a repeated upload. */
    @Test
    void aSlotWhoseDataWasLostIsDetectedAndHealedByTheNextUpload() throws Exception {
        byte[] data = bytes(CHUNK + 10, 3);
        rig.files.stageStream(new ByteArrayInputStream(data), pool, "x", null, org.example.sectoriadb.checksum.UploadChecksums.none());
        // the crash keeps the meta entries and drops the data: every unforced write is decided by the coin; try seeds until the
        // meta entry survived and the data did not
        boolean damaged = false;
        for (int seed = 0; seed < 200 && !damaged; seed++) {
            setUpFresh();
            rig.files.stageStream(new ByteArrayInputStream(data), pool, "x", null, org.example.sectoriadb.checksum.UploadChecksums.none());
            disk.crash(seed, 0.5);
            disk.revive();
            rig.reopen();
            pool = rig.poolService.findByName("bkt").orElseThrow();
            int corrupt = 0;
            for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) corrupt += rig.table(b).scrub().corrupt();
            damaged = corrupt > 0;
        }
        assertTrue(damaged, "some crash left an ACTIVE meta entry over stale data");
        // nothing references it, the same upload heals it (or rewrites it) and is readable
        rig.files.storeStream(new ByteArrayInputStream(data), pool, "x", null);
        assertArrayEquals(data, get("x"));
    }

    private void setUpFresh() {
        rig.close();
        deleteRecursively(root);
        rig = new StorageRig(root);
        rig.props.setDefaultNumBuckets(64);
        rig.engineFactory = disk::newEngine;
        disk.revive();
        rig.reopen();
        pool = rig.bucket("bkt");
    }

    private static void deleteRecursively(Path p) {
        try (var s = java.nio.file.Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).filter(x -> !x.equals(p)).forEach(x -> x.toFile().delete());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
