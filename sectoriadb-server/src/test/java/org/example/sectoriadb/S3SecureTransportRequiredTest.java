package org.example.sectoriadb;

import org.example.sectoriadb.storage.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sectoriadb.s3.require-secure-transport=true behind a trusted proxy (the loopback test client is an internal proxy). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SecureTransportRequiredTest {

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
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;

    private final HttpClient http = HttpClient.newHttpClient();

    private SigV4TestClient client() { return new SigV4TestClient(port, "TESTACCESSKEY0000001", "test-secret-key-for-unit-tests"); }

    @Test
    void plainHttpIsRejectedBeforeAuthentication() throws Exception {
        var signed = client().request("GET", "/", null, SigV4TestClient.sha256(new byte[0]), null);
        assertEquals(403, signed.statusCode());
        assertTrue(signed.body().contains("<Code>InsecureTransport</Code>"), signed.body());
        var anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(), BodyHandlers.ofString());
        assertEquals(403, anonymous.statusCode());
        assertTrue(anonymous.body().contains("<Code>InsecureTransport</Code>"), anonymous.body());
        var http1 = client().request("GET", "/", null, SigV4TestClient.sha256(new byte[0]), null, Map.of("x-forwarded-proto", "http"));
        assertEquals(403, http1.statusCode(), "X-Forwarded-Proto: http is not secure");
    }

    @Test
    void forwardedHttpsFromTheProxyIsAccepted() throws Exception {
        var r = client().request("GET", "/", null, SigV4TestClient.sha256(new byte[0]), null, Map.of("x-forwarded-proto", "https"));
        assertEquals(200, r.statusCode(), r.body());
        poolService.createBucket("tls-bkt");
        var put = client().request("PUT", "/tls-bkt/k", null, SigV4TestClient.sha256(new byte[]{1}), new byte[]{1}, Map.of("x-forwarded-proto", "https"));
        assertEquals(200, put.statusCode(), put.body());
    }

}
