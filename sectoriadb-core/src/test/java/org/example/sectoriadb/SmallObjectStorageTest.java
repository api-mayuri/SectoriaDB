package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.repository.*;
import org.example.sectoriadb.service.*;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** FileStorageService routing: empty / small (whole record) / chunked, and all read paths for each kind. */
class SmallObjectStorageTest {

    private static final int CHUNK = 4096;

    @TempDir
    Path root;

    StorageProperties props;
    BlobFileRepository blobRepo;
    ManifestRepository manifestRepo;
    PoolRepository poolRepo;
    HashTableCache cache;
    SmallBlobCache smallCache;
    BlobService blobService;
    FileStorageService files;
    PoolEntity pool;
    ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        props = new StorageProperties();
        props.setMetaDir(root.resolve("meta").toString());
        props.setDataDir(root.resolve("data").toString());
        props.setDefaultChunkSize(CHUNK);
        props.setDefaultNumBuckets(64);
        props.setFsync(false);
        wire();
        pool = new PoolEntity("pool-1", "bkt", root.resolve("data/bkt").toString(), java.time.Instant.now());
        Files.createDirectories(Path.of(pool.getBasePath()));
        poolRepo.save(pool);
    }

    /** (Re)builds the whole object graph, as after a process restart. */
    private void wire() {
        mapper = new ObjectMapper().findAndRegisterModules();
        poolRepo = new JsonPoolRepository(mapper, props);
        blobRepo = new JsonBlobFileRepository(mapper, poolRepo, props);
        manifestRepo = new JsonManifestRepository(mapper, blobRepo, props);
        cache = new HashTableCache(props);
        smallCache = new SmallBlobCache(props);
        OperationLogService opLog = new OperationLogService(new JsonOperationLogRepository(mapper, props), mapper);
        blobService = new BlobService(blobRepo, manifestRepo, cache, smallCache, props, opLog);
        files = new FileStorageService(manifestRepo, blobService, cache, smallCache, opLog, props);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private ManifestEntity put(byte[] data) throws Exception {
        return files.storeStream(new java.io.ByteArrayInputStream(data), pool, "k" + data.length, null);
    }

    private byte[] get(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.streamToOutput(manifestRepo.findById(m.getId()).orElseThrow(), out);
        return out.toByteArray();
    }

    @Test
    void sizesAreRoutedByChunkSize() throws Exception {
        assertEquals(StorageKind.EMPTY, put(new byte[0]).getStorageKind());
        assertEquals(StorageKind.SMALL, put(bytes(1)).getStorageKind());
        assertEquals(StorageKind.SMALL, put(bytes(CHUNK - 1)).getStorageKind());
        assertEquals(StorageKind.CHUNKED, put(bytes(CHUNK)).getStorageKind());
        assertEquals(StorageKind.CHUNKED, put(bytes(CHUNK + 1)).getStorageKind());

        for (int n : new int[]{0, 1, CHUNK - 1, CHUNK, CHUNK + 1}) {
            ManifestEntity m = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse("bkt", "k" + n).orElseThrow();
            assertEquals(n, m.getTotalBytes());
            assertArrayEquals(n == 0 ? new byte[0] : bytes(n), get(m), "size " + n);
        }
    }

    @Test
    void emptyObjectReferencesNoBlobAndSmallOnesNeverTouchCuckooTables() throws Exception {
        ManifestEntity empty = put(new byte[0]);
        assertNull(empty.getBlobFileId());
        assertNull(empty.getSmallBlobId());
        assertEquals(0, blobRepo.findByPoolId(pool.getId()).size(), "no blob needed for empty objects");

        ManifestEntity small = put(bytes(100));
        assertNull(small.getBlobFileId());
        assertNotNull(small.getSmallBlobId());
        assertEquals(1, small.getTotalBytes() / 100);
        var blobs = blobRepo.findByPoolId(pool.getId());
        assertEquals(1, blobs.size());
        assertEquals(BlobKind.SMALL, blobs.get(0).getKind());
        assertTrue(blobs.get(0).getFileName().matches("small_[0-9a-f]{8}\\.sob"));

        // the first chunked object creates a cuckoo blob; chooseBlobFileForWrite never returns the SOB
        put(bytes(CHUNK * 3));
        assertEquals(BlobKind.CUCKOO, blobService.chooseBlobFileForWrite(pool).getKind());
        assertEquals(2, blobRepo.findByPoolId(pool.getId()).size());
    }

    @Test
    void rangeReadsOnEveryKind() throws Exception {
        byte[] small = bytes(3000);
        ManifestEntity m = put(small);
        for (long[] r : new long[][]{{0, 1}, {0, 3000}, {2999, 1}, {100, 2000}, {1, 2998}}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            files.streamRange(manifestRepo.findById(m.getId()).orElseThrow(), out, r[0], r[1]);
            assertArrayEquals(Arrays.copyOfRange(small, (int) r[0], (int) (r[0] + r[1])), out.toByteArray());
        }
        assertThrows(IllegalArgumentException.class,
                () -> files.streamRange(m, new ByteArrayOutputStream(), 2999, 2));

        // restore / restoreRange to disk for SMALL, EMPTY and CHUNKED
        Path out = root.resolve("out.bin");
        files.restore(m.getId(), out);
        assertArrayEquals(small, Files.readAllBytes(out));
        files.restoreRange(m.getId(), out, 10, 20);
        assertArrayEquals(Arrays.copyOfRange(small, 10, 30), Files.readAllBytes(out));

        ManifestEntity empty = put(new byte[0]);
        files.restore(empty.getId(), out);
        assertEquals(0, Files.size(out));
        assertThrows(IllegalArgumentException.class, () -> files.restoreRange(empty.getId(), out, 0, 1));

        byte[] big = bytes(CHUNK * 3 + 5);
        ManifestEntity chunked = put(big);
        files.restore(chunked.getId(), out);
        assertArrayEquals(big, Files.readAllBytes(out));
        files.restoreRange(chunked.getId(), out, CHUNK - 3, 10);
        assertArrayEquals(Arrays.copyOfRange(big, CHUNK - 3, CHUNK + 7), Files.readAllBytes(out));
    }

    @Test
    void deleteMarksTheRecordDeleted() throws Exception {
        ManifestEntity a = put(bytes(100));
        ManifestEntity b = files.storeStream(new java.io.ByteArrayInputStream(bytes(200)), pool, "other", null);
        BlobFileEntity sob = manifestRepo.findById(a.getId()).orElseThrow().getSmallBlob();
        assertEquals(2, blobService.getSmallStats(sob).liveRecords());

        files.delete(a.getId());
        var stats = blobService.getSmallStats(sob);
        assertEquals(1, stats.liveRecords());
        assertEquals(1, stats.deadRecords());
        assertThrows(SmallObjectCorruptedException.class,
                () -> smallCache.get(sob).read(a.getSmallOffset(), a.getSmallLength(), a.getSmallCrc32c()),
                "a deleted record is no longer readable");
        assertArrayEquals(bytes(200), get(b));

        // empty and chunked deletes still work
        files.delete(put(new byte[0]).getId());
        files.delete(put(bytes(CHUNK)).getId());
    }

    @Test
    void everythingSurvivesARestart() throws Exception {
        ManifestEntity a = put(bytes(100));
        ManifestEntity b = put(bytes(2000));
        files.delete(a.getId());
        smallCache.closeAll();
        cache.closeAll();

        wire();                                    // fresh caches and repositories: SOB is recovered from disk
        assertArrayEquals(bytes(2000), get(b));
        BlobFileEntity sob = manifestRepo.findById(b.getId()).orElseThrow().getSmallBlob();
        var s = blobService.getSmallStats(sob);
        assertEquals(1, s.liveRecords());
        assertEquals(1, s.deadRecords());
        // appends continue into the same blob
        ManifestEntity c = put(bytes(300));
        assertEquals(sob.getId(), c.getSmallBlobId());
        assertEquals(0, blobService.scrubSmall(sob).corrupt());
    }

    @Test
    void rolloverCreatesAnotherSmallBlob() throws Exception {
        props.getSmallObject().setMaxFileBytes(4096 + 3 * 144);       // header + three 100-byte records
        ManifestEntity[] ms = new ManifestEntity[7];
        for (int i = 0; i < ms.length; i++) {
            ms[i] = files.storeStream(new java.io.ByteArrayInputStream(bytes(100 + i)), pool, "r" + i, null);
        }
        long soBlobs = blobRepo.findByPoolId(pool.getId()).stream().filter(b -> b.getKind() == BlobKind.SMALL).count();
        assertTrue(soBlobs >= 2, "expected rollover, got " + soBlobs + " blob(s)");
        for (int i = 0; i < ms.length; i++) {
            assertArrayEquals(bytes(100 + i), get(ms[i]));
        }
    }

    @Test
    void oldManifestsWithoutNewFieldsDeserializeAsChunked() throws Exception {
        String json = "{\"id\":\"x\",\"blobFileId\":\"b\",\"sourceFileName\":\"f\",\"chunkSize\":4096,\"totalChunks\":1,"
                + "\"totalBytes\":10,\"chunkKeys\":\"0000000000000001\",\"lastChunkSize\":10,\"deleted\":false}";
        ManifestEntity m = mapper.readValue(json, ManifestEntity.class);
        assertEquals(StorageKind.CHUNKED, m.getStorageKind());
        assertNull(m.getSmallBlobId());
        BlobFileEntity b = mapper.readValue("{\"id\":\"b\",\"poolId\":\"p\",\"numBuckets\":8,\"chunkSize\":4096}",
                BlobFileEntity.class);
        assertEquals(BlobKind.CUCKOO, b.getKind());
    }
}
