package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blob tables are loaded lazily, bounded by {@code sectoriadb.cache.max-resident-tables} and evicted LRU; a table that a
 * thread uses is never closed under it (doc 10, part B).
 */
class TableResidencyTest {

    static final int CHUNK = StorageRig.CHUNK;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.setDefaultNumBuckets(64);
        rig.props.getPool().setInitialBlobs(5);
        rig.props.getPool().setMaxBlobs(8);
        rig.props.getCache().setMaxResidentTables(2);
        rig.reopen();   // the caches are built from the properties
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

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    ManifestEntity put(String key, byte[] data) throws Exception {
        return rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
    }

    byte[] get(String key) throws Exception {
        ManifestEntity m = rig.files.findObject("bkt", key).orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    @Test
    void residentTablesStayWithinTheLimitAndEvictedOnesAreReloadedOnDemand() throws Exception {
        Map<String, byte[]> model = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            byte[] d = bytes(3 * CHUNK + 100, i);
            put("o" + i, d);
            model.put("o" + i, d);
        }
        assertEquals(5, rig.blobService.cuckooBlobsOf(pool).size());
        assertTrue(rig.cache.residentCount() <= 2, "resident: " + rig.cache.residentCount());
        assertTrue(rig.cache.evictions() > 0, "writing into five blobs through two slots of cache forced evictions");
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        assertTrue(rig.cache.residentCount() <= 2);
    }

    @Test
    void fillStatisticsOfAnEvictedTableNeedNoReload() throws Exception {
        for (int i = 0; i < 20; i++) put("o" + i, bytes(2 * CHUNK, 100 + i));
        long total = 0;
        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) total += rig.cache.fillStats(b).activeSlots();
        assertTrue(total >= 20 * 2 - 5, "stats of every blob (evicted ones from memory): " + total);
        int resident = rig.cache.residentCount();
        long evictionsBefore = rig.cache.evictions();
        for (int round = 0; round < 5; round++) {
            for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) rig.cache.fillStats(b);
        }
        assertEquals(evictionsBefore, rig.cache.evictions(), "asking for statistics loads nothing, so nothing is evicted");
        assertEquals(resident, rig.cache.residentCount());
        // and the statistics are exact, also after a table was written, evicted and asked for again
        long active = 0;
        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) active += rig.cache.fillStats(b).activeSlots();
        long indexed = 0;
        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) indexed += rig.chunks.countByBlob(b.getId());
        assertEquals(indexed, active);
    }

    /** Readers and writers on five blobs through a two-table cache: eviction churns constantly, nobody gets ClosedChannelException. */
    @Test
    void readersAndWritersDuringConstantEvictionNeverSeeAClosedTable() throws Exception {
        Map<String, byte[]> model = new ConcurrentHashMap<>();
        for (int i = 0; i < 20; i++) {
            byte[] d = bytes(4 * CHUNK, 500 + i);
            put("seed" + i, d);
            model.put("seed" + i, d);
        }
        int readers = 6, writers = 3;
        ExecutorService ex = Executors.newFixedThreadPool(readers + writers);
        Set<Throwable> failures = ConcurrentHashMap.newKeySet();
        List<Future<?>> fs = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            final int seed = r;
            fs.add(ex.submit(() -> {
                Random rnd = new Random(seed);
                try {
                    for (int i = 0; i < 150; i++) {
                        String key = "seed" + rnd.nextInt(20);
                        assertArrayEquals(model.get(key), get(key), key);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (int w = 0; w < writers; w++) {
            final int id = w;
            fs.add(ex.submit(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        byte[] d = bytes(2 * CHUNK + i, 9000 + id * 100 + i);
                        put("w" + id + "-" + i, d);
                        model.put("w" + id + "-" + i, d);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        ex.shutdown();
        assertTrue(failures.isEmpty(), "no failures: " + failures);
        assertTrue(rig.cache.evictions() > 50, "constant churn: " + rig.cache.evictions());
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        ChunkInvariants.check(rig.store);
        rig.reopen();
        pool = rig.poolService.findByName("bkt").orElseThrow();
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    /** One small-object blob per bucket: with a limit of two open files, writes and reads over six buckets keep working. */
    @Test
    void smallObjectBlobsAreBoundedToo() throws Exception {
        rig.props.getCache().setMaxOpenSmallBlobs(2);
        rig.reopen();
        Map<String, byte[]> model = new HashMap<>();
        List<PoolEntity> buckets = new ArrayList<>();
        for (int b = 0; b < 6; b++) buckets.add(rig.bucket("s" + b));
        for (int round = 0; round < 3; round++) {
            for (int b = 0; b < 6; b++) {
                byte[] d = bytes(300 + round, b * 10 + round);
                rig.files.storeStream(new ByteArrayInputStream(d), buckets.get(b), "k" + round, "application/octet-stream");
                model.put("s" + b + "/k" + round, d);
            }
        }
        assertTrue(rig.smallCache.openCount() <= 2, "open: " + rig.smallCache.openCount());
        assertTrue(rig.smallCache.evictions() > 0);
        for (var e : model.entrySet()) {
            String[] kv = e.getKey().split("/");
            ManifestEntity m = rig.files.findObject(kv[0], kv[1]).orElseThrow();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            rig.files.streamToOutput(m, out);
            assertArrayEquals(e.getValue(), out.toByteArray(), e.getKey());
        }
    }

    /** A table with writes that no fsync has covered is not evicted (a reloaded table would not know it owes a barrier). */
    @Test
    void aTableWithUnforcedWritesStaysResidentUntilItsBarrier() throws Exception {
        List<BlobFileEntity> blobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) blobs.add(rig.blobService.create(pool, 64, CHUNK));
        CuckooHashTable dirty;
        try (var h = rig.cache.acquire(blobs.get(0))) {
            dirty = h.get();
            dirty.insertPreservingKey(1L, java.nio.ByteBuffer.wrap(bytes(CHUNK, 1)));
            assertTrue(dirty.hasUnforcedWrites());
        }
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i < 4; i++) rig.cache.acquire(blobs.get(i)).close();   // pressure: the limit is two
        }
        try (var h = rig.cache.acquireResident(blobs.get(0).getId())) {
            assertNotNull(h, "still resident");
            assertSame(dirty, h.get(), "the same table");
        }
        dirty.barrier();
        for (int i = 1; i < 4; i++) rig.cache.acquire(blobs.get(i)).close();
        for (int i = 1; i < 4; i++) rig.cache.acquire(blobs.get(i)).close();
        assertNull(rig.cache.acquireResident(blobs.get(0).getId()), "evictable once it has been forced");
        assertEquals(1, rig.cache.fillStats(blobs.get(0)).activeSlots(), "its statistics were remembered");
    }
}
