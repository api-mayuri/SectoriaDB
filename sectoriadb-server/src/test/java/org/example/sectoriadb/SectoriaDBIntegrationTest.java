package org.example.sectoriadb;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.ResizeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SectoriaDBIntegrationTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("sectoriadb.meta-dir",                  () -> tempRoot.resolve("meta").toString());
        registry.add("sectoriadb.data-dir",                  () -> tempRoot.resolve("data").toString());
        registry.add("sectoriadb.auto-resize.enabled",       () -> "false");
        registry.add("sectoriadb.default-num-buckets",       () -> "32");
        registry.add("sectoriadb.default-chunk-size",        () -> "1024");
        registry.add("sectoriadb.s3.auth.enabled",           () -> "false");
        registry.add("spring.shell.interactive.enabled",     () -> "false");
        registry.add("spring.main.web-application-type",     () -> "servlet");
    }

    @Autowired PoolService poolService;
    @Autowired BlobService blobService;
    @Autowired FileStorageService fileService;
    @Autowired ResizeService resizeService;

    private PoolEntity newPool(String name) throws IOException {
        Path base = tempRoot.resolve(name);
        Files.createDirectories(base);
        return poolService.create(name, base.toString());
    }

    private byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    // ── Test 1: full restore is byte-perfect ─────────────────────────────────

    @Test
    void fullRestoreMatchesOriginal() throws IOException {
        PoolEntity pool = newPool("pool-full");

        // 3 KB file → 3 chunks of 1024 bytes each
        byte[] original = randomBytes(3 * 1024, 1L);
        Path src = tempRoot.resolve("full-src.bin");
        Files.write(src, original);

        ManifestEntity m = fileService.store(src, pool);

        assertEquals(original.length, m.getTotalBytes());
        assertEquals(3, m.getTotalChunks());
        assertEquals(1024, m.getChunkSize());
        assertEquals(1024, m.getLastChunkSize());

        Path restored = tempRoot.resolve("full-out.bin");
        fileService.restore(m.getId(), restored);

        assertArrayEquals(original, Files.readAllBytes(restored),
                "Full restore must produce byte-identical output");
    }

    // ── Test 2: byte-range restore matches sub-range of original ─────────────

    @Test
    void rangeRestoreMatchesSubset() throws IOException {
        PoolEntity pool = newPool("pool-range");

        // 5 KB file → 5 chunks of 1024 bytes
        byte[] original = randomBytes(5 * 1024, 2L);
        Path src = tempRoot.resolve("range-src.bin");
        Files.write(src, original);

        ManifestEntity m = fileService.store(src, pool);
        assertEquals(5, m.getTotalChunks());

        // Range [1200, +1800] spans chunks 1-2 (byte offsets 1024-3072)
        long startByte = 1200L;
        long length    = 1800L;
        Path out = tempRoot.resolve("range-out.bin");
        fileService.restoreRange(m.getId(), out, startByte, length);

        byte[] extracted = Files.readAllBytes(out);
        assertEquals(length, extracted.length, "Extracted length must match requested length");

        byte[] expected = new byte[(int) length];
        System.arraycopy(original, (int) startByte, expected, 0, (int) length);
        assertArrayEquals(expected, extracted,
                "Byte-range restore must match original bytes at [startByte, startByte+length)");
    }

    // ── Test 3: soft-delete blocks subsequent access ──────────────────────────

    @Test
    void softDeleteBlocksAccess() throws IOException {
        PoolEntity pool = newPool("pool-del");

        byte[] data = randomBytes(512, 3L);
        Path src = tempRoot.resolve("del-src.bin");
        Files.write(src, data);

        ManifestEntity m = fileService.store(src, pool);
        String id = m.getId();

        // sanity: accessible before delete
        assertNotNull(fileService.getActiveManifest(id));

        fileService.delete(id);

        // must throw after soft-delete
        assertThrows(IllegalArgumentException.class, () -> fileService.getActiveManifest(id),
                "getActiveManifest must throw after soft-delete");
    }

    // ── Test 4: blob resize preserves all stored data ─────────────────────────

    @Test
    void blobResizePreservesData() throws IOException {
        PoolEntity pool = newPool("pool-resize");

        // Store 2 KB — 2 chunks
        byte[] original = randomBytes(2 * 1024, 4L);
        Path src = tempRoot.resolve("resize-src.bin");
        Files.write(src, original);

        ManifestEntity m = fileService.store(src, pool);
        String blobId = m.getBlobFileId();

        // Resize blob from 32 → 64 buckets
        resizeService.resizeBlobFile(blobId, 64);

        // Manifest should still be valid (auto-rewired to new blob)
        Path restored = tempRoot.resolve("resize-out.bin");
        fileService.restore(m.getId(), restored);

        assertArrayEquals(original, Files.readAllBytes(restored),
                "After blob resize, full restore must still be byte-identical");
    }
}
