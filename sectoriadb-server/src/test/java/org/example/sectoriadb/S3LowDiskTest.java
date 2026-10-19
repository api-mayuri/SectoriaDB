package org.example.sectoriadb;

import org.example.sectoriadb.storage.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With less free space than {@code sectoriadb.storage.min-free-bytes} writes get 507; reads and deletes keep working. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3LowDiskTest {

    static final String AK = "TESTACCESSKEY0000001";
    static final String SK = "test-secret-key-for-unit-tests";

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
        r.add("sectoriadb.s3.access-key",       () -> AK);
        r.add("sectoriadb.s3.secret-key",       () -> SK);
        r.add("sectoriadb.storage.min-free-bytes", () -> "9000000000000000000");   // more than any volume has
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;

    private HttpResponse<String> send(String method, String path, String query, String body, Map<String, String> headers) throws Exception {
        var c = new SigV4TestClient(port, AK, SK);
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return c.request(method, path, query, SigV4TestClient.sha256(bytes == null ? new byte[0] : bytes), bytes, headers);
    }

    @Test
    void writesAreRefusedWith507ButReadsAndDeletesWork() throws Exception {
        HttpResponse<String> createBucket = send("PUT", "/lowdisk-new", null, null, Map.of());
        assertEquals(507, createBucket.statusCode(), createBucket.body());
        assertTrue(createBucket.body().contains("<Code>InsufficientStorage</Code>"), createBucket.body());

        poolService.createBucket("lowdisk");
        HttpResponse<String> put = send("PUT", "/lowdisk/k", null, "data", Map.of());
        assertEquals(507, put.statusCode(), put.body());
        assertEquals(507, send("POST", "/lowdisk/k", "uploads", null, Map.of()).statusCode());

        assertEquals(200, send("GET", "/lowdisk", "list-type=2", null, Map.of()).statusCode());
        assertEquals(204, send("DELETE", "/lowdisk/k", null, null, Map.of()).statusCode());
    }
}
