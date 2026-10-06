package org.example.sectoriadb;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.shell.FileCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Shell commands 'verify' and 'verify-all' (output format and RESULT line). */
@SpringBootTest
class VerifyCommandsTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "false");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @Autowired FileCommands commands;
    @Autowired FileStorageService files;
    @Autowired PoolService poolService;
    @Autowired ManifestRepository manifestRepo;

    private ManifestEntity put(PoolEntity pool, String key, int size) throws Exception {
        byte[] b = new byte[size];
        new Random(size).nextBytes(b);
        return files.storeStream(new ByteArrayInputStream(b), pool, key, null);
    }

    @Test
    void verifyAndVerifyAllReportOkThenFailure() throws Exception {
        PoolEntity pool = poolService.existsByName("vbkt") ? poolService.getByName("vbkt") : poolService.createBucket("vbkt");
        ManifestEntity small = put(pool, "small", 100);
        ManifestEntity big = put(pool, "big", 20_000);

        String one = commands.verify(big.getId());
        assertTrue(one.startsWith("OK: vbkt/big"), one);
        assertTrue(one.contains("whole-object CRC32C: ok"), one);
        assertTrue(one.contains("MD5 vs ETag: ok"), one);

        String all = commands.verifyAll("vbkt");
        assertTrue(all.contains("Verified 2 object(s): ok=2 corrupt=0 missing=0"), all);
        assertTrue(all.endsWith("RESULT: OK"), all);

        ManifestEntity m = manifestRepo.findById(small.getId()).orElseThrow();
        m.setCrc32c("AAAAAA==");
        manifestRepo.save(m);
        String bad = commands.verify(small.getId());
        assertTrue(bad.startsWith("CORRUPT: vbkt/small"), bad);
        assertTrue(bad.contains("MISMATCH whole-object CRC32C"), bad);

        String failed = commands.verifyAll("vbkt");
        assertTrue(failed.contains("Verified 2 object(s): ok=1 corrupt=1 missing=0"), failed);
        assertTrue(failed.contains("CORRUPT: vbkt/small"), failed);
        assertTrue(failed.endsWith("RESULT: FAILED (corrupt=1, missing=0)"), failed);
    }

    /** `java -jar sectoriadb.jar verify-all` must run as a one-shot CLI command (no web server, JVM exits). */
    @Test
    void verifyCommandsRunInCliMode() {
        assertTrue(Application.isCliMode(new String[]{"verify-all"}));
        assertTrue(Application.isCliMode(new String[]{"--foo=bar", "verify-all", "--pool", "b"}));
        assertTrue(Application.isCliMode(new String[]{"verify", "--id", "x"}));
        assertFalse(Application.isCliMode(new String[]{"--server.port=1"}));
    }
}
