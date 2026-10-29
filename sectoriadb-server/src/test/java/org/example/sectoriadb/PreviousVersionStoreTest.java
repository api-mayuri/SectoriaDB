package org.example.sectoriadb;

import org.example.sectoriadb.catalog.metastore.MetaStoreHolder;
import org.example.sectoriadb.catalog.metastore.StoreFormat;
import org.example.sectoriadb.shell.FileCommands;
import org.example.sectoriadb.storage.ObjectCatalog;
import org.example.sectoriadb.storage.ObjectReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A volume written by the previous version (commit f3916cb: Spring Boot 3.3, Java 21, Jackson 2, store format 1,
 * mounted at /data in its container) is opened by this build from another directory: the store is migrated to the
 * current format, its file paths are repointed, and every object reads back byte for byte.
 *
 * <p>Fixture {@code compat/store-f3916cb.tar.gz}: bucket {@code fixture-b} with a small object (700 B, .sob), a chunked
 * one (20 kB), a multipart one (6.25 MiB, two parts), a key in Cyrillic, an overwritten and a deleted object; an admin
 * key from the environment and a key {@code FIXTURETENANT0001} with a read-only policy. When the store format changes,
 * add the store written by the version before the change as another fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreviousVersionStoreTest {

    @TempDir
    static Path root;

    static final Map<String, String> SHA256 = Map.of(
            "small.bin", "1ecc4b2a54e2b1e4dc62b4ba74ff205fd02ebf66fe41d78ffadd8473bd2a85a7",
            "mid.bin", "8b25640abc40f5f8436348c0bc6f6d86d1c8862f22318023f9f218132369d91d",
            "big.bin", "fea173946e373f71b582fa9abbfd42ea35194b850d4fb336d7c969d8d6fc49a1",
            "файл.txt", "e58f1e8c55fa105bdd3f40e5037eb0b039b5998d52c05e6cd98878dd2da5cab2",
            "over.txt", "09386e9a4901ff692da8175b21b6b85793a25ec219de9af53ea1f5c863c84767");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        try (InputStream in = PreviousVersionStoreTest.class.getResourceAsStream("/compat/store-f3916cb.tar.gz")) {
            untar(new GZIPInputStream(in), root.resolve("volume"));
        }
        r.add("sectoriadb.meta-dir", () -> root.resolve("volume/meta").toString());
        r.add("sectoriadb.data-dir", () -> root.resolve("volume/storage").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.s3.credentials", () -> "FIXTUREACCESSKEY01:fixturesecretkey0123456789abc|fixture");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    /** Regular files and directories of a ustar archive. */
    static void untar(InputStream in, Path to) throws IOException {
        byte[] header = new byte[512];
        while (in.readNBytes(header, 0, 512) == 512) {
            String name = field(header, 0, 100);
            if (name.isEmpty()) break;
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = Long.parseLong(field(header, 124, 12).trim(), 8);
            Path target = to.resolve(name).normalize();
            if (!target.startsWith(to)) throw new IOException("bad entry " + name);
            if (header[156] == '5') {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    long left = size;
                    byte[] buf = new byte[1 << 16];
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("truncated " + name);
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
            long pad = (512 - size % 512) % 512;
            in.readNBytes((int) pad);
        }
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    @LocalServerPort int port;
    @Autowired ObjectCatalog catalog;
    @Autowired ObjectReader reader;
    @Autowired FileCommands files;
    @Autowired MetaStoreHolder holder;

    @Test
    void everyObjectReadsBackAndTheStoreIsAtTheCurrentFormatInItsNewDirectory() throws Exception {
        assertEquals(Optional.of(StoreFormat.CURRENT), StoreFormat.stored(holder.get()));
        assertEquals(Optional.of(root.resolve("volume/storage").toAbsolutePath().normalize()), StoreFormat.recordedDataDir(holder.get()),
                "written at /data/storage, opened elsewhere: repointed");
        for (var e : SHA256.entrySet()) {
            var m = catalog.findObject("fixture-b", e.getKey()).orElseThrow(() -> new AssertionError("missing " + e.getKey()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.streamToOutput(m, out);
            assertEquals(e.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray())), e.getKey());
        }
        assertTrue(catalog.findObject("fixture-b", "deleted.txt").isEmpty(), "the deleted object stays deleted");
        String verify = files.verifyAll(null);
        assertTrue(verify.contains("RESULT: OK") && verify.contains("Verified 5 object(s)"), verify);
    }

    @Test
    void theKeysAndThePolicyOfThePreviousVersionStillApply() throws Exception {
        SigV4TestClient tenant = new SigV4TestClient(port, "FIXTURETENANT0001", "fixturetenant0123456789");
        String empty = SigV4TestClient.sha256(new byte[0]);
        var got = tenant.request("GET", "/fixture-b/over.txt", null, empty, null);
        assertEquals(200, got.statusCode());
        assertEquals("v2-overwritten", got.body());
        assertEquals(403, tenant.request("DELETE", "/fixture-b/over.txt", null, empty, null).statusCode(), "read-only policy");
        SigV4TestClient admin = new SigV4TestClient(port, "FIXTUREACCESSKEY01", "fixturesecretkey0123456789abc");
        assertEquals(200, admin.request("GET", "/fixture-b", "list-type=2", empty, null).statusCode());
    }
}
