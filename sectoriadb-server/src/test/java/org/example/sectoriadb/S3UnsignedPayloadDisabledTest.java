package org.example.sectoriadb;

import org.example.sectoriadb.service.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sectoriadb.s3.auth.allow-unsigned-payload=false: only signed payload hashes are accepted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3UnsignedPayloadDisabledTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "true");
        r.add("sectoriadb.s3.auth.allow-unsigned-payload", () -> "false");
        r.add("sectoriadb.s3.access-key",       () -> "TESTACCESSKEY0000001");
        r.add("sectoriadb.s3.secret-key",       () -> "test-secret-key-for-unit-tests");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;

    @Test
    void unsignedPayloadRejectedButSignedPayloadAccepted() throws Exception {
        poolService.createBucket("nounsigned");
        var c = new SigV4TestClient(port, "TESTACCESSKEY0000001", "test-secret-key-for-unit-tests");
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);

        var unsigned = c.put("/nounsigned/a.txt", body, "UNSIGNED-PAYLOAD");
        assertEquals(400, unsigned.statusCode());
        assertTrue(unsigned.body().contains("allow-unsigned-payload"), unsigned.body());

        var unsignedTrailer = c.put("/nounsigned/a2.txt", body, "STREAMING-UNSIGNED-PAYLOAD-TRAILER");
        assertEquals(400, unsignedTrailer.statusCode());

        assertEquals(200, c.put("/nounsigned/b.txt", body, SigV4TestClient.sha256(body)).statusCode());
    }
}
