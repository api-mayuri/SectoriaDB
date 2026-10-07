package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.ChunkStore;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.tools.BytesHasher;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Several cuckoo blobs per pool: placement, growth, restart, resize of one blob (doc 09). */
class MultiBlobPoolTest {

    static final int CHUNK = StorageRig.CHUNK;
    /** 16 buckets x 2 tables x 4 slots = 128 slots (512 KiB) per blob. */
    static final int TINY_BUCKETS = 16;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.setDefaultNumBuckets(TINY_BUCKETS);
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

    List<BlobFileEntity> cuckoo() {
        return rig.blobService.cuckooBlobsOf(pool);
    }

    @Test
    void poolGrowsToSeveralBlobsAndEverythingIsReadableAfterARestart() throws Exception {
        rig.props.getPool().setGrowThresholdPercent(75);
        int objects = 40, chunksEach = 8;   // 320 distinct chunks into blobs of 128 slots
        Map<String, byte[]> expected = new HashMap<>();
        for (int i = 0; i < objects; i++) {
            byte[] data = bytes(chunksEach * CHUNK, 1000 + i);
            put("o" + i, data);
            expected.put("o" + i, data);
        }
        List<BlobFileEntity> blobs = cuckoo();
        assertTrue(blobs.size() >= 3, "320 chunks cannot fit under the 75 % threshold in fewer than 3 blobs of 128: " + blobs.size());
        assertTrue(blobs.size() <= rig.props.getPool().getMaxBlobs());
        for (BlobFileEntity b : blobs) {
            long chunks = rig.chunks.countByBlob(b.getId());
            assertTrue(chunks > 0, "every blob holds chunks");
            assertEquals(chunks, rig.cache.fillStats(b).activeSlots(), "index and table agree on the blob content");
            assertTrue(rig.cache.fillStats(b).fillPercent() < 98, "no blob is driven to its limit");
        }
        assertEquals(320, rig.chunks.stats().chunks());
        assertTrue(rig.metrics.poolGrown.get() >= 2);
        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());

        rig.reopen();
        pool = rig.bucket("bkt");
        assertEquals(blobs.size(), cuckoo().size());
        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after restart: " + e.getKey());
        // and new writes after the restart keep working and deduplicate against what is there
        long refsBefore = rig.chunks.stats().refs();
        put("again", expected.get("o7"));
        assertEquals(refsBefore + chunksEach, rig.chunks.stats().refs());
        assertEquals(320, rig.chunks.stats().chunks());
    }

    @Test
    void initialBlobsAreCreatedTogetherAndUnderConcurrentFirstWrites() throws Exception {
        rig.props.getPool().setInitialBlobs(3);
        int threads = 24;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            fs.add(ex.submit(() -> {
                go.await();
                put("k" + id, bytes(2 * CHUNK, 5000 + id));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(3, cuckoo().size(), "exactly initial-blobs blobs, not one set per racing writer");
        for (int i = 0; i < threads; i++) assertArrayEquals(bytes(2 * CHUNK, 5000 + i), get("k" + i));
    }

    @Test
    void concurrentGrowthCreatesExactlyOneBlob() throws Exception {
        rig.props.getPool().setGrowThresholdPercent(50);
        put("seed", bytes(80 * CHUNK, 1));          // 80 of 128 slots: over the threshold
        assertEquals(1, cuckoo().size());
        int threads = 16;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(ex.submit(() -> {
                go.await();
                return rig.blobService.growIfOverThreshold(pool).isPresent();
            }));
        }
        go.countDown();
        int created = 0;
        for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) created++;
        ex.shutdown();
        assertEquals(1, created, "one winner");
        assertEquals(2, cuckoo().size(), "80 of 256 slots is under the threshold: nobody grows again");

        // "no blob accepts" growth: every blob refuses, many writers ask, one creates
        Set<String> refused = new HashSet<>();
        cuckoo().forEach(b -> refused.add(b.getId()));
        // make both blobs unusable by weight (full) so that the answer cannot be USABLE_EXISTS
        for (BlobFileEntity b : cuckoo()) fill(b);
        ExecutorService ex2 = Executors.newFixedThreadPool(threads);
        CountDownLatch go2 = new CountDownLatch(1);
        List<Future<BlobService.Growth.Kind>> gs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            gs.add(ex2.submit(() -> {
                go2.await();
                return rig.blobService.growIfNeeded(pool, refused).kind();
            }));
        }
        go2.countDown();
        int c = 0, usable = 0;
        for (var f : gs) {
            var k = f.get(30, TimeUnit.SECONDS);
            if (k == BlobService.Growth.Kind.CREATED) c++;
            else if (k == BlobService.Growth.Kind.USABLE_EXISTS) usable++;
        }
        ex2.shutdown();
        assertEquals(1, c);
        assertEquals(threads - 1, usable);
        assertEquals(3, cuckoo().size());
    }

    /** Fills a blob's table until it refuses more, with chunks that no manifest knows. */
    private void fill(BlobFileEntity b) throws Exception {
        CuckooHashTable t = rig.table(b);
        Random r = new Random(b.getId().hashCode());
        try {
            while (true) t.insertPreservingKey(r.nextLong(), ByteBuffer.wrap(bytes(CHUNK, r.nextLong())));
        } catch (TableFullException expected) {
            // full
        }
    }

    @Test
    void aFullBlobIsSkippedAndNothingIsLost() throws Exception {
        BlobFileEntity a = rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        BlobFileEntity b = rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        fill(a);
        long aBefore = rig.cache.fillStats(a).activeSlots();
        assertTrue(aBefore > 100);
        byte[] data = bytes(40 * CHUNK, 77);
        put("x", data);
        assertEquals(2, cuckoo().size(), "40 chunks fit in the other blob: no growth needed");
        assertEquals(aBefore, rig.cache.fillStats(a).activeSlots(), "the full blob received nothing");
        assertEquals(40, rig.chunks.countByBlob(b.getId()));
        assertArrayEquals(data, get("x"));
    }

    /** A BlobService that lies about the weight of one blob, so that the placement ranks a full blob first. */
    static final class LyingBlobService extends BlobService {
        volatile String favourite;

        LyingBlobService(StorageRig r) {
            super(r.blobs, r.cache, r.smallCache, r.props, r.opLog);
        }

        @Override
        public double placementWeight(BlobFileEntity b) throws IOException {
            return b.getId().equals(favourite) ? 1e15 : super.placementWeight(b);
        }
    }

    @Test
    void whenTheTopChoiceRefusesTheNextInTheSameOrderTakesTheChunk() throws Exception {
        LyingBlobService svc = new LyingBlobService(rig);
        BlobFileEntity full = svc.create(pool, TINY_BUCKETS, CHUNK);
        BlobFileEntity other = svc.create(pool, TINY_BUCKETS, CHUNK);
        fill(full);
        svc.favourite = full.getId();
        ChunkStore store = new ChunkStore(rig.chunks, svc, rig.cache);
        FileStorageService files = new FileStorageService(rig.manifests, svc, rig.cache, rig.smallCache, store, rig.opLog, rig.props);
        byte[] data = bytes(20 * CHUNK, 88);
        files.storeStream(new ByteArrayInputStream(data), pool, "y", null);
        // a "full" cuckoo table still takes the keys that happen to have a free slot in reach; the rest fall through
        long inOther = rig.chunks.countByBlob(other.getId());
        assertEquals(20, inOther + rig.chunks.countByBlob(full.getId()));
        assertTrue(inOther >= 10, "most chunks went to the next blob in the order: " + inOther);
        assertEquals(inOther, rig.metrics.placementFallbacks.get(), "each one counted as a fallback of the top choice");
        ManifestEntity m = rig.manifests.findCurrent("bkt", "y").orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.streamToOutput(m, out);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void aPoolAtMaxBlobsReportsFullWithoutCommittingAnything() throws Exception {
        rig.props.getPool().setMaxBlobs(1);
        put("fits", bytes(60 * CHUNK, 3));
        assertThrows(TableFullException.class, () -> put("too-much", bytes(120 * CHUNK, 4)));
        assertEquals(1, cuckoo().size(), "max-blobs is a hard limit");
        assertTrue(rig.files.findObject("bkt", "too-much").isEmpty());
        assertEquals(60, rig.chunks.stats().chunks(), "the failed upload indexed nothing");
        assertArrayEquals(bytes(60 * CHUNK, 3), get("fits"));
    }

    @Test
    void resizingOneBlobOfAPoolKeepsEveryObjectReadableAndRepointsTheIndex() throws Exception {
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        Map<String, byte[]> expected = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            byte[] data = bytes(10 * CHUNK, 2000 + i);
            put("o" + i, data);
            expected.put("o" + i, data);
        }
        BlobFileEntity victim = cuckoo().get(1);
        long chunksInVictim = rig.chunks.countByBlob(victim.getId());
        assertTrue(chunksInVictim > 10);
        Map<String, Long> others = new HashMap<>();
        for (BlobFileEntity b : cuckoo()) if (!b.getId().equals(victim.getId())) others.put(b.getId(), rig.chunks.countByBlob(b.getId()));

        BlobFileEntity replacement = rig.resizeService.resizeBlobFile(victim.getId(), TINY_BUCKETS * 4);

        assertEquals(0, rig.chunks.countByBlob(victim.getId()), "no index row points at the replaced blob");
        assertEquals(chunksInVictim, rig.chunks.countByBlob(replacement.getId()));
        others.forEach((id, n) -> assertEquals(n, rig.chunks.countByBlob(id), "other blobs untouched"));
        assertTrue(rig.blobs.findById(victim.getId()).isEmpty());
        assertEquals(3, cuckoo().size());
        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        rig.reopen();
        pool = rig.bucket("bkt");
        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after restart: " + e.getKey());
    }

    @Test
    void uploadsRunningDuringAResizeAreNotLost() throws Exception {
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        for (int i = 0; i < 6; i++) put("pre" + i, bytes(8 * CHUNK, 3000 + i));
        BlobFileEntity victim = cuckoo().get(0);

        int writers = 6, perWriter = 6;
        ExecutorService ex = Executors.newFixedThreadPool(writers + 1);
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            fs.add(ex.submit(() -> {
                for (int i = 0; i < perWriter; i++) {
                    put("w" + id + "-" + i, bytes(4 * CHUNK, 4000 + id * 100 + i));
                    done.incrementAndGet();
                }
                return null;
            }));
        }
        Future<BlobFileEntity> resize = ex.submit(() -> rig.resizeService.resizeBlobFile(victim.getId(), TINY_BUCKETS * 4));
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        resize.get(120, TimeUnit.SECONDS);
        ex.shutdown();

        for (int i = 0; i < 6; i++) assertArrayEquals(bytes(8 * CHUNK, 3000 + i), get("pre" + i));
        for (int w = 0; w < writers; w++) {
            for (int i = 0; i < perWriter; i++) assertArrayEquals(bytes(4 * CHUNK, 4000 + w * 100 + i), get("w" + w + "-" + i));
        }
        // every indexed chunk is physically present in the blob the index names
        for (BlobFileEntity b : cuckoo()) {
            assertEquals(rig.chunks.countByBlob(b.getId()), rig.cache.fillStats(b).activeSlots() - orphansIn(b));
        }
    }

    private long orphansIn(BlobFileEntity b) {
        return rig.chunks.orphans(10_000).stream().filter(o -> o.blobId().equals(b.getId())).count();
    }

    // ── key collisions, pool-wide ─────────────────────────────────────────────

    /** Every chunk gets the key 42 at seed 0 (a collision for any two different chunks); later seeds are real hashes. */
    static final BytesHasher COLLIDING = new BytesHasher() {
        private final XxHash64BytesHasher real = new XxHash64BytesHasher();

        @Override
        public long hash64(ByteBuffer buffer, long seed) {
            return seed == 0 ? 42L : real.hash64(buffer, seed);
        }
    };

    private FileStorageService collidingFiles() {
        ChunkStore store = new ChunkStore(rig.chunks, rig.blobService, rig.cache, COLLIDING);
        return new FileStorageService(rig.manifests, rig.blobService, rig.cache, rig.smallCache, store, rig.opLog, rig.props);
    }

    @Test
    void collidingKeysAreResolvedAgainstTheCommittedIndexAndWithinOneUpload() throws Exception {
        FileStorageService files = collidingFiles();
        byte[] x = bytes(CHUNK, 1), y = bytes(CHUNK, 2), z = bytes(CHUNK, 3);
        files.storeStream(new ByteArrayInputStream(x), pool, "x", null);
        files.storeStream(new ByteArrayInputStream(y), pool, "y", null);   // key 42 is taken by x in the index
        byte[] two = new byte[2 * CHUNK];                                  // two different chunks in one upload
        System.arraycopy(y, 0, two, 0, CHUNK);
        System.arraycopy(z, 0, two, CHUNK, CHUNK);
        files.storeStream(new ByteArrayInputStream(two), pool, "two", null);
        assertEquals(3, rig.chunks.stats().chunks());
        assertTrue(rig.metrics.rekeys.get() >= 3);
        for (var e : Map.of("x", x, "y", y, "two", two).entrySet()) {
            ManifestEntity m = rig.manifests.findCurrent("bkt", e.getKey()).orElseThrow();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            files.streamToOutput(m, out);
            assertArrayEquals(e.getValue(), out.toByteArray(), e.getKey());
        }
        assertEquals(1 + 1 + 2, rig.chunks.stats().refs());
    }

    @Test
    void aCollisionWithAnUncommittedCopyInABlobIsRekeyedToo() throws Exception {
        FileStorageService files = collidingFiles();
        byte[] x = bytes(CHUNK, 1), y = bytes(CHUNK, 2);
        // x is written (key 42 now sits in a blob table) but not committed: the index does not know it
        ManifestEntity sx = files.stageStream(new ByteArrayInputStream(x), pool, "x", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        ManifestEntity sy = files.stageStream(new ByteArrayInputStream(y), pool, "y", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertNotEquals(sx.chunkKeyArray()[0], sy.chunkKeyArray()[0]);
        files.commitObject(sx);
        files.commitObject(sy);
        for (var e : Map.of("x", x, "y", y).entrySet()) {
            ManifestEntity m = rig.manifests.findCurrent("bkt", e.getKey()).orElseThrow();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            files.streamToOutput(m, out);
            assertArrayEquals(e.getValue(), out.toByteArray());
        }
    }

    /** A ChunkRepository that counts the lookups the read path makes. */
    private ChunkRepository counting(AtomicInteger lookups) {
        return (ChunkRepository) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ChunkRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("find") || method.getName().equals("findAll")) lookups.incrementAndGet();
                    try {
                        return method.invoke(rig.chunks, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void hotChunkLocationsAreServedFromTheCacheAndSurviveAResize() throws Exception {
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        AtomicInteger lookups = new AtomicInteger();
        ChunkStore store = new ChunkStore(counting(lookups), rig.blobService, rig.cache);
        FileStorageService files = new FileStorageService(rig.manifests, rig.blobService, rig.cache, rig.smallCache, store, rig.opLog, rig.props);
        byte[] data = bytes(30 * CHUNK, 321);
        files.storeStream(new ByteArrayInputStream(data), pool, "hot", null);
        int afterPut = lookups.get();
        assertTrue(store.cachedLocations() >= 30);

        ManifestEntity m = rig.manifests.findCurrent("bkt", "hot").orElseThrow();
        for (int i = 0; i < 3; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            files.streamToOutput(m, out);
            assertArrayEquals(data, out.toByteArray());
        }
        assertEquals(afterPut, lookups.get(), "reads of chunks written by this process do not touch the index");

        // the cache follows a resize: the old blob id resolves to its replacement
        BlobFileEntity victim = cuckoo().get(0);
        rig.resizeService.resizeBlobFile(victim.getId(), TINY_BUCKETS * 4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.streamToOutput(m, out);
        assertArrayEquals(data, out.toByteArray());
        assertEquals(afterPut, lookups.get(), "still no index lookups: hints are re-pointed through the replacement table");

        // a cold process (new store) resolves through the index, one batched lookup per 256 positions
        AtomicInteger cold = new AtomicInteger();
        ChunkStore coldStore = new ChunkStore(counting(cold), rig.blobService, rig.cache);
        FileStorageService coldFiles = new FileStorageService(rig.manifests, rig.blobService, rig.cache, rig.smallCache, coldStore, rig.opLog, rig.props);
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        coldFiles.streamToOutput(m, out2);
        assertArrayEquals(data, out2.toByteArray());
        assertEquals(1, cold.get(), "30 chunks = one window = one lookup");
        // a disabled cache is correct too
        rig.props.getPool().setLocationCacheEntries(0);
        ChunkStore noCache = new ChunkStore(rig.chunks, rig.blobService, rig.cache, rig.props);
        FileStorageService nf = new FileStorageService(rig.manifests, rig.blobService, rig.cache, rig.smallCache, noCache, rig.opLog, rig.props);
        ByteArrayOutputStream out3 = new ByteArrayOutputStream();
        nf.streamToOutput(m, out3);
        assertArrayEquals(data, out3.toByteArray());
        assertEquals(0, noCache.cachedLocations());
    }

    @Test
    void poolsHaveOneChunkSize() throws Exception {
        rig.blobService.create(pool, TINY_BUCKETS, CHUNK);
        assertThrows(IllegalArgumentException.class, () -> rig.blobService.create(pool, TINY_BUCKETS, CHUNK * 2));
        assertEquals(1, cuckoo().size());
    }
}
