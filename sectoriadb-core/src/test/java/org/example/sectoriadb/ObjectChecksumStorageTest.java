package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.repository.*;
import org.example.sectoriadb.service.*;
import org.example.sectoriadb.service.impl.ObjectCorruptedException;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.repository.metastore.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Upload-side checksum verification, stored whole-object checksums, read-side verification, verify / verify-all. */
class ObjectChecksumStorageTest {

    private static final int CHUNK = 4096;

    @TempDir
    Path root;

    StorageProperties props;
    BlobFileRepository blobRepo;
    ManifestRepository manifestRepo;
    PoolRepository poolRepo;
    FileStorageService files;
    ObjectVerificationService verifier;
    PoolEntity pool;
    ObjectMapper mapper;
    MetaStore store;

    @AfterEach
    void closeStore() {
        store.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        props = new StorageProperties();
        props.setMetaDir(root.resolve("meta").toString());
        props.setDataDir(root.resolve("data").toString());
        props.setDefaultChunkSize(CHUNK);
        props.setDefaultNumBuckets(64);
        props.setFsync(false);
        mapper = new ObjectMapper().findAndRegisterModules();
        Files.createDirectories(root.resolve("meta"));
        store = MetaStore.open(root.resolve("meta/sectoria.db"), MetaStoreOptions.defaults().fsync(false));
        MetaStoreProvider stores = MetaStoreProvider.of(store);
        poolRepo = new MetaStorePoolRepository(stores);
        blobRepo = new MetaStoreBlobFileRepository(stores);
        manifestRepo = new MetaStoreManifestRepository(stores);
        var cache = new HashTableCache(props);
        var smallCache = new SmallBlobCache(props);
        OperationLogService opLog = new OperationLogService(new JsonOperationLogRepository(mapper, props), mapper);
        BlobService blobService = new BlobService(blobRepo, cache, smallCache, props, opLog);
        files = new FileStorageService(manifestRepo, blobService, cache, smallCache, opLog, props);
        verifier = new ObjectVerificationService(files);
        pool = new PoolEntity("pool-1", "bkt", root.resolve("data/bkt").toString(), java.time.Instant.now());
        Files.createDirectories(Path.of(pool.getBasePath()));
        poolRepo.save(pool);
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private ManifestEntity put(String key, byte[] data, UploadChecksums c) throws Exception {
        return files.storeStream(new ByteArrayInputStream(data), pool, key, null, c);
    }

    private byte[] get(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.streamToOutput(manifestRepo.findById(m.getId()).orElseThrow(), out);
        return out.toByteArray();
    }

    private static final int[] SIZES = {0, 1, CHUNK - 1, CHUNK, 3 * CHUNK + 17};

    @Test
    void everyManifestStoresCrc32cAndClientChecksum() throws Exception {
        for (int n : SIZES) {
            byte[] data = bytes(n, n);
            byte[] sha = ChecksumAlgorithm.SHA256.compute(data);
            ManifestEntity m = put("k" + n, data, UploadChecksums.none().algorithm(ChecksumAlgorithm.SHA256, sha));
            ManifestEntity stored = manifestRepo.findById(m.getId()).orElseThrow();
            assertEquals(ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.CRC32C.compute(data)), stored.getCrc32c(), "size " + n);
            assertEquals(ChecksumAlgorithm.SHA256, stored.getChecksumAlgorithm());
            assertEquals(ChecksumAlgorithm.SHA256.encode(sha), stored.getChecksumValue());
            assertEquals(ChecksumType.FULL_OBJECT, stored.getChecksumType());
        }
    }

    @Test
    void objectWithoutClientChecksumHasOnlyCrc32c() throws Exception {
        ManifestEntity m = manifestRepo.findById(put("plain", bytes(100, 1), UploadChecksums.none()).getId()).orElseThrow();
        assertNotNull(m.getCrc32c());
        assertNull(m.getChecksumAlgorithm());
        assertNull(m.getChecksumValue());
        assertNull(m.getChecksumType());
    }

    @Test
    void computeOnlyStoresTheValueWithoutComparing() throws Exception {
        byte[] data = bytes(500, 3);
        ManifestEntity m = put("c", data, UploadChecksums.compute(ChecksumAlgorithm.CRC64NVME));
        assertEquals(ChecksumAlgorithm.CRC64NVME.encode(ChecksumAlgorithm.CRC64NVME.compute(data)), m.getChecksumValue());
    }

    @Test
    void mismatchOfAnyAlgorithmAbortsBeforeAnythingIsCommitted() throws Exception {
        for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
            for (int n : SIZES) {
                byte[] data = bytes(n, 10 + n);
                byte[] wrong = ChecksumAlgorithm.valueOf(alg.name()).compute(bytes(n + 1, 99));
                var ex = assertThrows(ChecksumMismatchException.class,
                        () -> put("bad-" + alg + n, data, UploadChecksums.none().algorithm(alg, wrong)), alg + " size " + n);
                assertEquals(alg.name(), ex.getLabel());
                assertEquals("The " + alg + " you specified did not match the calculated checksum.", ex.getMessage());
            }
        }
        assertEquals(0, manifestRepo.count(), "no manifest");
        assertEquals(0, blobRepo.findByPoolId(pool.getId()).size(), "no blob: no small record, no chunk was written");
    }

    @Test
    void contentMd5MismatchAbortsBeforeAnythingIsCommitted() throws Exception {
        byte[] data = bytes(3 * CHUNK, 5);
        byte[] wrongMd5 = MessageDigest.getInstance("MD5").digest(bytes(3 * CHUNK, 6));
        var ex = assertThrows(ChecksumMismatchException.class, () -> put("md5", data, UploadChecksums.none().contentMd5(wrongMd5)));
        assertEquals("Content-MD5", ex.getLabel());
        assertEquals(0, manifestRepo.count());
        assertEquals(0, blobRepo.findByPoolId(pool.getId()).size());
        // and the right one is accepted
        put("md5ok", data, UploadChecksums.none().contentMd5(MessageDigest.getInstance("MD5").digest(data)));
    }

    @Test
    void matchingChecksumAndLateExpectedValueAreAccepted() throws Exception {
        byte[] data = bytes(2 * CHUNK + 1, 8);
        UploadChecksums late = UploadChecksums.compute(ChecksumAlgorithm.CRC32C);
        // the trailer value is supplied while the stream is being read
        var in = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                int n = super.read(b, off, len);
                if (n < 0) late.expected(ChecksumAlgorithm.CRC32C.compute(data));
                return n;
            }
        };
        files.storeStream(in, pool, "late", null, late);
        assertEquals(1, manifestRepo.count());
    }

    // ── read side ─────────────────────────────────────────────────────────────

    @Test
    void tamperedManifestCrcFailsEveryFullRead_ofEveryKind() throws Exception {
        for (int n : SIZES) {
            ManifestEntity m = put("t" + n, bytes(n, 20 + n), UploadChecksums.none());
            assertArrayEquals(bytes(n, 20 + n), get(m));
            ManifestEntity stored = manifestRepo.findById(m.getId()).orElseThrow();
            stored.setCrc32c(ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.crc32Bytes(0x12345678)));
            manifestRepo.save(stored);
            assertThrows(ObjectCorruptedException.class, () -> get(m), "size " + n);
            Path out = root.resolve("restore-" + n);
            assertThrows(ObjectCorruptedException.class, () -> files.restore(m.getId(), out), "restore size " + n);
            assertFalse(Files.exists(out), "a corrupt restore must not leave a file behind");
        }
    }

    @Test
    void failedFullReadNeverDeliversTheLastChunk() throws Exception {
        byte[] data = bytes(3 * CHUNK + 100, 30);
        ManifestEntity m = put("last", data, UploadChecksums.none());
        ManifestEntity stored = manifestRepo.findById(m.getId()).orElseThrow();
        stored.setCrc32c("AAAAAA==");
        manifestRepo.save(stored);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(ObjectCorruptedException.class, () -> files.streamToOutput(manifestRepo.findById(m.getId()).orElseThrow(), out));
        assertEquals(3 * CHUNK, out.size(), "the final chunk is withheld until the whole-object CRC is verified");
    }

    @Test
    void rangeReadsIgnoreTheWholeObjectCrc() throws Exception {
        ManifestEntity m = put("range", bytes(3 * CHUNK, 31), UploadChecksums.none());
        ManifestEntity stored = manifestRepo.findById(m.getId()).orElseThrow();
        stored.setCrc32c("AAAAAA==");
        manifestRepo.save(stored);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.streamRange(manifestRepo.findById(m.getId()).orElseThrow(), out, 10, 20);
        assertArrayEquals(Arrays.copyOfRange(bytes(3 * CHUNK, 31), 10, 30), out.toByteArray());
    }

    @Test
    void manifestWithoutWholeObjectCrcStillReadsAndVerifies() throws Exception {
        byte[] data = bytes(100, 40);
        ManifestEntity m = put("old", data, UploadChecksums.none());
        ManifestEntity old = manifestRepo.findById(m.getId()).orElseThrow();
        assertNotNull(old.getCrc32c());
        old.setCrc32c(null);   // a manifest that carries no whole-object checksum
        manifestRepo.save(old);
        old = manifestRepo.findById(m.getId()).orElseThrow();
        assertNull(old.getCrc32c());
        assertArrayEquals(data, get(old));
        var r = verifier.verify(old);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.details().stream().anyMatch(s -> s.contains("not stored")));
    }

    // ── verify / verify-all ───────────────────────────────────────────────────

    private void flipDataByte(ManifestEntity m, byte[] data) throws Exception {
        BlobFileEntity blob = m.getPhysicalBlob();
        byte[] file = Files.readAllBytes(Path.of(blob.getFilePath()));
        byte[] needle = Arrays.copyOfRange(data, 0, 32);
        int at = -1;
        outer:
        for (int i = 0; i <= file.length - 32; i++) {
            for (int k = 0; k < 32; k++) if (file[i + k] != needle[k]) continue outer;
            at = i;
            break;
        }
        assertTrue(at >= 0, "object data not found in blob file");
        try (RandomAccessFile f = new RandomAccessFile(blob.getFilePath(), "rw")) {
            f.seek(at + 5);
            f.write(file[at + 5] ^ 0x40);
        }
    }

    @Test
    void verifyReportsOkWithAllChecks() throws Exception {
        byte[] data = bytes(2 * CHUNK, 50);
        ManifestEntity m = put("v", data, UploadChecksums.none().algorithm(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.SHA1.compute(data)));
        var r = verifier.verify(manifestRepo.findById(m.getId()).orElseThrow());
        assertEquals(ObjectVerificationService.Status.OK, r.status(), r.toString());
        String all = String.join("\n", r.details());
        assertTrue(all.contains("whole-object CRC32C: ok"), all);
        assertTrue(all.contains("client checksum SHA1: ok"), all);
        assertTrue(all.contains("MD5 vs ETag: ok"), all);
    }

    @Test
    void verifyFindsWholeObjectAndClientChecksumAndMd5Mismatches() throws Exception {
        byte[] data = bytes(CHUNK + 5, 51);
        ManifestEntity m = put("m", data, UploadChecksums.none().algorithm(ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32.compute(data)));
        ManifestEntity stored = manifestRepo.findById(m.getId()).orElseThrow();
        stored.setCrc32c("AAAAAA==");
        stored.setChecksumValue("AAAAAA==");
        stored.setEtag("\"00000000000000000000000000000000\"");
        manifestRepo.save(stored);
        var r = verifier.verify(manifestRepo.findById(m.getId()).orElseThrow());
        assertEquals(ObjectVerificationService.Status.CORRUPT, r.status());
        String all = String.join("\n", r.details());
        assertTrue(all.contains("MISMATCH whole-object CRC32C"), all);
        assertTrue(all.contains("MISMATCH client checksum CRC32"), all);
        assertTrue(all.contains("MISMATCH MD5"), all);
    }

    @Test
    void verifyAllSummarizesOkCorruptAndMissing() throws Exception {
        byte[] okData = bytes(100, 60);
        put("ok", okData, UploadChecksums.none());
        byte[] chunked = bytes(3 * CHUNK, 61);
        ManifestEntity corruptChunk = put("corrupt-chunk", chunked, UploadChecksums.none());
        ManifestEntity corruptSmall = put("corrupt-small", bytes(200, 62), UploadChecksums.none());
        ManifestEntity missing = put("missing", bytes(2 * CHUNK, 63), UploadChecksums.none());

        flipDataByte(manifestRepo.findById(corruptChunk.getId()).orElseThrow(), chunked);
        flipDataByte(manifestRepo.findById(corruptSmall.getId()).orElseThrow(), bytes(200, 62));
        ManifestEntity mm = manifestRepo.findById(missing.getId()).orElseThrow();
        mm.setChunkKeys(java.util.List.of(0x1111L, 0x2222L));   // keys not in the blob
        manifestRepo.save(mm);

        var s = verifier.verifyAll(m -> true);
        assertEquals(4, s.total());
        assertEquals(1, s.ok());
        assertEquals(2, s.corrupt(), s.problems().toString());
        assertEquals(1, s.missing(), s.problems().toString());
        assertFalse(s.allOk());
        assertEquals(3, s.problems().size());

        var onlyOk = verifier.verifyAll(m -> "ok".equals(m.getObjectKey()));
        assertTrue(onlyOk.allOk());
        assertEquals(1, onlyOk.total());
        assertEquals(StorageKind.SMALL, manifestRepo.findById(corruptSmall.getId()).orElseThrow().getStorageKind());
    }
}
