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

/** Bucket policy {@code Bool aws:SecureTransport}: a Deny on plain HTTP and an Allow on HTTPS only, for anonymous callers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SecureTransportPolicyTest {

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

    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;

    private final HttpClient http = HttpClient.newHttpClient();

    private SigV4TestClient client() { return new SigV4TestClient(port, "TESTACCESSKEY0000001", "test-secret-key-for-unit-tests"); }

    private int anonymousGet(String path, boolean https) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (https) b.header("X-Forwarded-Proto", "https");
        return http.send(b.build(), BodyHandlers.discarding()).statusCode();
    }

    @Test
    void allowOnlyOverTls() throws Exception {
        poolService.createBucket("tls-allow");
        var put = client().put("/tls-allow/a.txt", "x".getBytes(), SigV4TestClient.sha256("x".getBytes()));
        assertEquals(200, put.statusCode(), put.body());
        poolService.setPolicy("tls-allow", "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::tls-allow/*\","
                + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"}}}]}");
        assertEquals(403, anonymousGet("/tls-allow/a.txt", false));
        assertEquals(200, anonymousGet("/tls-allow/a.txt", true));
    }

    @Test
    void denyPlainHttpBeatsPublicAcl() throws Exception {
        poolService.createBucket("tls-deny");
        var put = client().request("PUT", "/tls-deny/a.txt", null, SigV4TestClient.sha256("x".getBytes()), "x".getBytes(),
                Map.of("x-amz-acl", "public-read"));
        assertEquals(200, put.statusCode(), put.body());
        assertEquals(200, anonymousGet("/tls-deny/a.txt", false), "public-read object without a policy");
        poolService.setPolicy("tls-deny", "{\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\","
                + "\"Action\":\"s3:*\",\"Resource\":[\"arn:aws:s3:::tls-deny\",\"arn:aws:s3:::tls-deny/*\"],"
                + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}]}");
        assertEquals(403, anonymousGet("/tls-deny/a.txt", false));
        assertEquals(200, anonymousGet("/tls-deny/a.txt", true), "the Deny no longer bites over HTTPS");
    }

}
