package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PlacedChunk;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The pool-wide chunk index: deduplication, reference counts, retirement, revival, orphans (doc 09). */
class ChunkIndexTest {

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

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** {@code chunks} distinct chunks of random content, deterministic in {@code seed}. */
    static byte[] content(int chunks, long seed) {
        return bytes(chunks * CHUNK, seed);
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

    ChunkRepository.Stats stats() {
        return rig.chunks.stats();
    }

    long physicalChunks() throws Exception {
        long n = 0;
        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) n += rig.cache.fillStats(b).activeSlots();
        return n;
    }

    @Test
    void sameChunkInManyObjectsIsStoredOnceWithAnExactRefcount() throws Exception {
        byte[] data = content(3, 1);
        for (int i = 0; i < 5; i++) put("same-" + i, data);
        assertEquals(3, physicalChunks(), "pool-wide deduplication: 15 chunk positions, 3 physical chunks");
        assertEquals(3, stats().chunks());
        assertEquals(15, stats().refs());
        assertEquals(0, stats().gcQueue());
        for (long k : rig.manifests.findCurrent("bkt", "same-0").orElseThrow().chunkKeyArray()) {
            assertEquals(5, rig.chunks.find(pool.getId(), k).orElseThrow().refcount());
        }
        assertArrayEquals(data, get("same-3"));

        rig.files.deleteObject("bkt", "same-0");
        rig.files.deleteObject("bkt", "same-1");
        assertEquals(9, stats().refs());
        assertEquals(0, stats().gcQueue(), "still referenced: not garbage");

        for (int i = 2; i < 5; i++) rig.files.deleteObject("bkt", "same-" + i);
        assertEquals(0, stats().refs());
        assertEquals(3, stats().gcQueue(), "all three chunks are now garbage, queued once each");
        assertEquals(3, stats().chunks(), "the index entries and the bytes stay until the collector runs");
        assertEquals(3, physicalChunks(), "nothing is freed by a delete");
    }

    @Test
    void aChunkRepeatedInsideOneObjectCountsOncePerPosition() throws Exception {
        byte[] one = bytes(CHUNK, 7);
        byte[] data = new byte[4 * CHUNK];
        for (int i = 0; i < 4; i++) System.arraycopy(one, 0, data, i * CHUNK, CHUNK);
        ManifestEntity m = put("rep", data);
        assertEquals(1, stats().chunks());
        assertEquals(4, stats().refs());
        assertEquals(1, physicalChunks());
        assertEquals(4, rig.chunks.find(pool.getId(), m.chunkKeyArray()[0]).orElseThrow().refcount());
        assertArrayEquals(data, get("rep"));
        rig.files.deleteObject("bkt", "rep");
        assertEquals(0, stats().refs());
        assertEquals(1, stats().gcQueue());
    }

    @Test
    void overwriteKeepsSharedChunksAndQueuesOnlyTheDroppedOnes() throws Exception {
        byte[] a = bytes(CHUNK, 1), b = bytes(CHUNK, 2), c = bytes(CHUNK, 3);
        put("k", concat(a, b));
        ManifestEntity v2 = put("k", concat(b, c));   // b is shared by both versions
        assertEquals(3, stats().chunks());
        assertEquals(2, stats().refs());
        List<Long> queued = rig.chunks.gcQueue(10).stream().map(ChunkRepository.GcRow::chunkKey).toList();
        assertEquals(1, queued.size(), "only a lost its reference; b never passed through zero");
        assertFalse(java.util.Arrays.stream(v2.chunkKeyArray()).anyMatch(k -> k == queued.get(0)));
        assertArrayEquals(concat(b, c), get("k"));

        // overwriting with identical content changes nothing
        put("k", concat(b, c));
        assertEquals(2, stats().refs());
        assertEquals(1, stats().gcQueue());
    }

    @Test
    void anUploadThatDeduplicatesAgainstAZeroReferenceChunkRevivesIt() throws Exception {
        byte[] data = content(2, 11);
        put("x", data);
        rig.files.deleteObject("bkt", "x");
        assertEquals(2, stats().gcQueue());
        assertEquals(0, stats().refs());

        put("y", data);   // finds the zombie entries, verifies the bytes, commits references back
        assertEquals(0, stats().gcQueue(), "revived: no longer queued for collection");
        assertEquals(2, stats().refs());
        assertEquals(2, physicalChunks());
        assertArrayEquals(data, get("y"));
    }

    @Test
    void anUploadWhoseDeduplicatedChunkWasCollectedBeforeTheCommitFailsClosed() throws Exception {
        byte[] data = content(2, 12);
        put("x", data);
        // the upload finds the chunks in the index (indexed = true) and does not write them
        ManifestEntity staged = rig.files.stageStream(new ByteArrayInputStream(data), pool, "y", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertTrue(staged.getStagedChunks().stream().allMatch(PlacedChunk::indexed));
        // ... then the object that held them goes away and a (future) collector removes the entries
        rig.files.deleteObject("bkt", "x");
        rig.store.writeVoid(tx -> {
            for (long k : staged.chunkKeyArray()) {
                ChunkEntry e = Chunks.get(tx, pool.getId(), k).orElseThrow();
                tx.tree(Chunks.CHUNKS).delete(Chunks.key(pool.getId(), k));
                tx.tree(Chunks.CHUNKS_BY_BLOB).delete(Keys.builder().string(e.blobId()).longUnsigned(k).build());
                tx.tree(Chunks.CHUNK_GC).scanPrefix(Keys.builder().string(pool.getId()).longUnsigned(k).build());
            }
            var gc = tx.tree(Chunks.CHUNK_GC);
            List<byte[]> rows = new ArrayList<>();
            try (var c = gc.scan()) {
                while (c.next()) rows.add(c.key());
            }
            rows.forEach(gc::delete);
        });
        var e = assertThrows(ChunkRepository.ChunkPlacementException.class, () -> rig.files.commitObject(staged));
        assertTrue(e.getMessage().contains("collected meanwhile"), e.getMessage());
        assertTrue(rig.files.findObject("bkt", "y").isEmpty(), "nothing was committed");
        assertEquals(0, stats().chunks());
    }

    @Test
    void secondCopyWrittenToAnotherBlobIsRecordedAsAnOrphan() throws Exception {
        rig.blobService.create(pool, 16, CHUNK);
        rig.blobService.create(pool, 16, CHUNK);
        List<BlobFileEntity> blobs = rig.blobService.cuckooBlobsOf(pool);
        assertEquals(2, blobs.size());
        byte[] data = content(6, 21);

        // two uploads of the same new content stage concurrently: neither sees the other's chunks in the index
        ManifestEntity first = rig.files.stageStream(new ByteArrayInputStream(data), pool, "a", null,
                org.example.sectoriadb.checksum.UploadChecksums.none());
        // the second one is steered to the other blob(s): the blobs the first used are frozen for its placement
        java.util.Set<String> used = new java.util.HashSet<>();
        first.getStagedChunks().forEach(p -> used.add(p.blobId()));
        used.forEach(rig.cache::freeze);
        ManifestEntity second;
        try {
            second = rig.files.stageStream(new ByteArrayInputStream(data), pool, "b", null,
                    org.example.sectoriadb.checksum.UploadChecksums.none());
        } finally {
            used.forEach(rig.cache::unfreeze);
        }
        // equal content, but the physical copies of the second upload went to a different blob than the first's
        int differing = 0;
        for (PlacedChunk p1 : first.getStagedChunks()) {
            PlacedChunk p2 = second.getStagedChunks().stream().filter(x -> x.key() == p1.key()).findFirst().orElseThrow();
            if (!p1.blobId().equals(p2.blobId())) differing++;
        }
        assertTrue(differing > 0, "the test needs chunks that were written twice");

        rig.files.commitObject(first);
        rig.files.commitObject(second);

        assertEquals(6, stats().chunks());
        assertEquals(12, stats().refs());
        assertEquals(differing, stats().orphans(), "every second physical copy is recorded");
        for (ChunkRepository.Orphan o : rig.chunks.orphans(100)) {
            ChunkEntry e = rig.chunks.find(pool.getId(), o.chunkKey()).orElseThrow();
            assertNotEquals(o.blobId(), e.blobId());
        }
        assertArrayEquals(data, get("a"));
        assertArrayEquals(data, get("b"));
    }

    @Test
    void concurrentUploadsOfIdenticalContentKeepTheRefcountExact() throws Exception {
        rig.blobService.create(pool, 16, CHUNK);
        rig.blobService.create(pool, 16, CHUNK);
        byte[] data = content(8, 31);
        int threads = 16;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            fs.add(ex.submit(() -> {
                go.await();
                put("same-" + id, data);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();

        assertEquals(8, stats().chunks());
        assertEquals(8L * threads, stats().refs());
        for (long k : rig.manifests.findCurrent("bkt", "same-0").orElseThrow().chunkKeyArray()) {
            assertEquals(threads, rig.chunks.find(pool.getId(), k).orElseThrow().refcount());
        }
        // physical copies = indexed chunks + recorded orphans (a copy written twice into one blob is one copy)
        assertTrue(physicalChunks() >= 8 && physicalChunks() <= 8 + stats().orphans(),
                "physical " + physicalChunks() + " orphans " + stats().orphans());
        for (int i = 0; i < threads; i++) assertArrayEquals(data, get("same-" + i));
    }

    @Test
    void randomConcurrentPutsOverwritesAndDeletesKeepEveryReferenceCountExact() throws Exception {
        rig.props.setDefaultNumBuckets(32);
        rig.blobService.create(pool, 32, CHUNK);
        rig.blobService.create(pool, 32, CHUNK);
        int threads = 8;
        // a small alphabet of chunks so that objects share them heavily
        byte[][] alphabet = new byte[6][];
        for (int i = 0; i < alphabet.length; i++) alphabet[i] = bytes(CHUNK, 100 + i);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            fs.add(ex.submit(() -> {
                Random r = new Random(id);
                for (int i = 0; i < 40; i++) {
                    String key = "k" + r.nextInt(10);
                    if (r.nextInt(4) == 0) {
                        rig.files.deleteObject("bkt", key);
                    } else {
                        int n = 1 + r.nextInt(4);
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        for (int c = 0; c < n; c++) o.write(alphabet[r.nextInt(alphabet.length)]);
                        put(key, o.toByteArray());
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        ex.shutdown();
        // @AfterEach runs the invariants: refcount == positions in live manifests, zero refs == queued, orphans consistent
        assertTrue(stats().chunks() <= alphabet.length);
        for (var m : rig.manifests.findAllLive()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            rig.files.streamToOutput(m, out);
            assertEquals(m.getTotalBytes(), out.size());
        }
    }

    @Test
    void chunkOfAnObjectIsReadThroughTheIndexEvenWhenPlacementWouldNowChooseAnotherBlob() throws Exception {
        byte[] data = content(10, 41);
        put("a", data);
        rig.blobService.create(pool, 16, CHUNK);   // an empty blob now outranks the first for new chunks
        assertArrayEquals(data, get("a"));
        long before = physicalChunks();
        put("b", data);                              // all chunks are deduplicated against the first blob
        assertEquals(before, physicalChunks());
        assertEquals(20, stats().refs());
    }

    @Test
    void deletingTheBucketRemovesItsChunkIndex() throws Exception {
        put("a", content(4, 51));
        put("b", content(4, 52));
        rig.files.deleteObject("bkt", "a");
        rig.files.deleteObject("bkt", "b");
        assertEquals(8, stats().chunks());
        rig.poolService.deleteBucket("bkt");
        assertEquals(new ChunkRepository.Stats(0, 0, 0, 0), stats());
        pool = rig.bucket("bkt");   // the same name again starts clean
        put("c", content(2, 53));
        assertEquals(2, stats().chunks());
    }

    @Test
    void unreferencedBlobsCanBeDeletedAndReferencedOnesCannot() throws Exception {
        rig.blobService.create(pool, 16, CHUNK);
        BlobFileEntity second = rig.blobService.create(pool, 16, CHUNK);
        put("a", content(20, 61));
        List<BlobFileEntity> blobs = rig.blobService.cuckooBlobsOf(pool);
        assertEquals(2, blobs.size());
        // both blobs hold chunks of the object (the placement spreads them)
        for (BlobFileEntity b : blobs) {
            assertTrue(rig.chunks.countByBlob(b.getId()) > 0, "blob " + b.getId() + " holds chunks");
            assertThrows(IllegalStateException.class, () -> rig.blobService.delete(b.getId()));
        }
        rig.files.deleteObject("bkt", "a");
        for (BlobFileEntity b : blobs) rig.blobService.delete(b.getId());
        assertEquals(new ChunkRepository.Stats(0, 0, 0, 0), stats(), "the zero-reference entries went with their blobs");
        assertNotNull(second);
    }

    @Test
    void referencesOfAHandBuiltCommitAreCreatedFromThePlacementsAndRequireThem() {
        BlobFileEntity b = new BlobFileEntity();
        b.setId("blob-1");
        b.setPoolId(pool.getId());
        b.setFileName("blob-1.raw");
        b.setFilePath("/x/blob-1.raw");
        b.setNumBuckets(16);
        b.setChunkSize(CHUNK);
        rig.blobs.save(b);

        ManifestEntity m = new ManifestEntity();
        m.setId("m1");
        m.setPoolId(pool.getId());
        m.setBucketName("bkt");
        m.setObjectKey("k");
        m.setChunkSize(CHUNK);
        m.setChunkKeyArray(new long[]{5, 6, 5});
        m.setTotalChunks(3);
        m.setTotalBytes(3L * CHUNK);
        m.setLastChunkSize(CHUNK);
        // no placement for chunk 6 and nothing in the index: refused, nothing written
        m.setStagedChunks(List.of(new PlacedChunk(5, "blob-1", CHUNK, 1, false)));
        var e = assertThrows(ChunkRepository.ChunkPlacementException.class, () -> rig.manifests.commitObject(m));
        assertTrue(e.getMessage().contains("no placement"), e.getMessage());
        assertEquals(0, rig.manifests.count());
        assertEquals(0, stats().chunks());

        m.setStagedChunks(List.of(new PlacedChunk(5, "blob-1", CHUNK, 1, false), new PlacedChunk(6, "blob-1", CHUNK, 2, false)));
        rig.manifests.commitObject(m);
        assertEquals(2, rig.chunks.find(pool.getId(), 5).orElseThrow().refcount(), "chunk 5 appears twice");
        assertEquals(1, rig.chunks.find(pool.getId(), 6).orElseThrow().refcount());

        // a blob that does not exist (resized away) is refused
        ManifestEntity m2 = new ManifestEntity();
        m2.setId("m2");
        m2.setPoolId(pool.getId());
        m2.setBucketName("bkt");
        m2.setObjectKey("k2");
        m2.setChunkKeyArray(new long[]{9});
        m2.setTotalChunks(1);
        m2.setStagedChunks(List.of(new PlacedChunk(9, "ghost-blob", CHUNK, 3, false)));
        assertThrows(ChunkRepository.ChunkPlacementException.class, () -> rig.manifests.commitObject(m2));

        // same key, different length/CRC than the indexed chunk: refused (a collision between uncommitted uploads)
        m2.setChunkKeyArray(new long[]{5});
        m2.setStagedChunks(List.of(new PlacedChunk(5, "blob-1", CHUNK, 999, false)));
        assertThrows(ChunkRepository.ChunkPlacementException.class, () -> rig.manifests.commitObject(m2));
        assertEquals(2, rig.chunks.find(pool.getId(), 5).orElseThrow().refcount(), "unchanged by the failed commits");
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }
}
