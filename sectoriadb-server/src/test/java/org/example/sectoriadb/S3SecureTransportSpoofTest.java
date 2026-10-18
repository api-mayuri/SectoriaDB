package org.example.sectoriadb;

import org.example.sectoriadb.storage.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The caller is NOT a trusted proxy: a spoofed X-Forwarded-Proto: https must be ignored (require-secure-transport=true). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SecureTransportSpoofTest {

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
        r.add("sectoriadb.s3.access-key",       () -> "TESTACCESSKEY0000001");
        r.add("sectoriadb.s3.secret-key",       () -> "test-secret-key-for-unit-tests");
        r.add("spring.shell.interactive.enabled", () -> "false");
        r.add("sectoriadb.s3.require-secure-transport", () -> "true");
        r.add("server.tomcat.remoteip.internal-proxies", () -> "10\\.99\\.99\\.99");
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;

    private final HttpClient http = HttpClient.newHttpClient();

    private SigV4TestClient client() { return new SigV4TestClient(port, "TESTACCESSKEY0000001", "test-secret-key-for-unit-tests"); }

    @Test
    void spoofedForwardedProtoFromAnUntrustedPeerIsIgnored() throws Exception {
        var r = client().request("GET", "/", null, SigV4TestClient.sha256(new byte[0]), null, Map.of("x-forwarded-proto", "https"));
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("<Code>InsecureTransport</Code>"), r.body());
    }

}
