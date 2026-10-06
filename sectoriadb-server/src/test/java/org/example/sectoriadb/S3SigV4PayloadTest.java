package org.example.sectoriadb;

import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.PoolService;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** SigV4 with auth ENABLED: payload hash verification, chunk signatures, presigned URL limits, clock skew. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SigV4PayloadTest {

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
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;
    @Autowired ManifestRepository manifestRepo;

    SigV4TestClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new SigV4TestClient(port, AK, SK);
        if (!poolService.existsByName("sigbkt")) poolService.createBucket("sigbkt");
    }

    private boolean exists(String key) {
        return manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse("sigbkt", key).isPresent();
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    // ── payload hash ─────────────────────────────────────────────────────────

    @Test
    void correctPayloadHashIsAccepted() throws Exception {
        byte[] body = bytes("hello signed world");
        var r = client.put("/sigbkt/ok.txt", body, SigV4TestClient.sha256(body));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(exists("ok.txt"));
        var g = client.request("GET", "/sigbkt/ok.txt", null, SigV4TestClient.sha256(new byte[0]), null);
        assertEquals("hello signed world", g.body());
    }

    @Test
    void wrongPayloadHashIsRejectedAndNothingIsCommitted() throws Exception {
        byte[] declared = bytes("what the client signed");
        byte[] actual = bytes("what the client really sent!!");
        var r = client.put("/sigbkt/bad.txt", actual, SigV4TestClient.sha256(declared));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("XAmzContentSHA256Mismatch"), r.body());
        assertFalse(exists("bad.txt"), "mismatching upload must not create a manifest");
    }

    @Test
    void largerThanOneChunkBodyIsVerifiedToo() throws Exception {
        byte[] body = new byte[50_000];
        new java.util.Random(7).nextBytes(body);
        assertEquals(200, client.put("/sigbkt/big.bin", body, SigV4TestClient.sha256(body)).statusCode());
        body[49_999] ^= 1;   // one flipped byte at the very end
        byte[] orig = body.clone();
        orig[49_999] ^= 1;
        var r = client.put("/sigbkt/big2.bin", body, SigV4TestClient.sha256(orig));
        assertEquals(400, r.statusCode());
        assertFalse(exists("big2.bin"));
    }

    @Test
    void unsignedPayloadIsAllowedByDefault() throws Exception {
        var r = client.put("/sigbkt/unsigned.txt", bytes("x"), "UNSIGNED-PAYLOAD");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(exists("unsigned.txt"));
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        var s = client.sign("PUT", "/sigbkt/t.txt", null, SigV4TestClient.sha256(bytes("a")), Instant.now());
        String forged = s.authorization().replaceAll("Signature=.*", "Signature=" + "0".repeat(64));
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/sigbkt/t.txt"))
                .header("Authorization", forged).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", SigV4TestClient.sha256(bytes("a")))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("a")).build();
        assertEquals(403, client.http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // ── streaming (aws-chunked) ──────────────────────────────────────────────

    @Test
    void signedChunkedUploadIsAccepted() throws Exception {
        byte[][] chunks = { bytes("first chunk,"), bytes(" second chunk,"), bytes(" third") };
        var r = client.putChunked("/sigbkt/chunked.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", chunks, -1, false, null, false);
        assertEquals(200, r.statusCode(), r.body());
        var g = client.request("GET", "/sigbkt/chunked.txt", null, SigV4TestClient.sha256(new byte[0]), null);
        assertEquals("first chunk, second chunk, third", g.body());
    }

    @Test
    void tamperedChunkIsRejectedAndNothingIsCommitted() throws Exception {
        byte[][] chunks = { bytes("first chunk,"), bytes(" second chunk,") };
        var r = client.putChunked("/sigbkt/tampered.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", chunks, 1, false, null, false);
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("SignatureDoesNotMatch"), r.body());
        assertFalse(exists("tampered.txt"));
    }

    @Test
    void truncatedChunkedUploadWithoutFinalChunkIsRejected() throws Exception {
        byte[][] chunks = { bytes("only chunk") };
        var r = client.putChunked("/sigbkt/trunc.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", chunks, -1, true, null, false);
        assertTrue(r.statusCode() >= 400, "status " + r.statusCode());
        assertFalse(exists("trunc.txt"));
    }

    @Test
    void signedChunkedUploadWithSignedTrailer() throws Exception {
        byte[][] chunks = { bytes("trailer payload") };
        String trailer = "x-amz-checksum-crc32:AAAAAA==";
        var ok = client.putChunked("/sigbkt/trailer.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER",
                chunks, -1, false, trailer, true);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(exists("trailer.txt"));
        // trailer without a valid signature must fail
        var bad = client.putChunked("/sigbkt/trailer2.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER",
                chunks, -1, false, trailer, false);
        assertEquals(403, bad.statusCode());
        assertFalse(exists("trailer2.txt"));
    }

    @Test
    void unsignedTrailerStreamingIsAccepted() throws Exception {
        // STREAMING-UNSIGNED-PAYLOAD-TRAILER: chunk framing without signatures, checksum in the trailer
        var s = client.sign("PUT", "/sigbkt/ut.txt", null, "STREAMING-UNSIGNED-PAYLOAD-TRAILER", Instant.now());
        String body = "7\r\nunsigne\r\n2\r\nd!\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n";
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/sigbkt/ut.txt"))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
                .header("Content-Encoding", "aws-chunked")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var r = client.http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        var g = client.request("GET", "/sigbkt/ut.txt", null, SigV4TestClient.sha256(new byte[0]), null);
        assertEquals("unsigned!", g.body());
    }

    // ── clock skew & presigned URLs ──────────────────────────────────────────

    @Test
    void requestWithLargeClockSkewIsRejected() throws Exception {
        byte[] body = bytes("x");
        var r = client.request("PUT", "/sigbkt/skew.txt", null, SigV4TestClient.sha256(body), body,
                Instant.now().minusSeconds(3600));
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("RequestTimeTooSkewed"), r.body());
        assertFalse(exists("skew.txt"));
    }

    @Test
    void presignedUrlWorksThenExpires() throws Exception {
        byte[] body = bytes("presigned content");
        assertEquals(200, client.put("/sigbkt/pre.txt", body, SigV4TestClient.sha256(body)).statusCode());

        HttpResponse<String> ok = client.get(client.presign("/sigbkt/pre.txt", Instant.now(), 300));
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("presigned content", ok.body());

        HttpResponse<String> expired = client.get(client.presign("/sigbkt/pre.txt", Instant.now().minusSeconds(600), 60));
        assertEquals(403, expired.statusCode());
        assertTrue(expired.body().contains("ExpiredToken"), expired.body());
    }

    @Test
    void presignedUrlExpiresLimitsAndFutureDatesAreRejected() throws Exception {
        assertEquals(400, client.get(client.presign("/sigbkt/pre.txt", Instant.now(), 604801)).statusCode());
        assertEquals(400, client.get(client.presign("/sigbkt/pre.txt", Instant.now(), 0)).statusCode());
        var future = client.get(client.presign("/sigbkt/pre.txt", Instant.now().plusSeconds(7200), 300));
        assertEquals(403, future.statusCode());
    }

    @Test
    void presignedUrlWithTamperedPathIsRejected() throws Exception {
        byte[] body = bytes("secret");
        client.put("/sigbkt/a.txt", body, SigV4TestClient.sha256(body));
        client.put("/sigbkt/b.txt", body, SigV4TestClient.sha256(body));
        var uri = client.presign("/sigbkt/a.txt", Instant.now(), 300);
        var swapped = java.net.URI.create(uri.toString().replace("/sigbkt/a.txt", "/sigbkt/b.txt"));
        assertEquals(403, client.get(swapped).statusCode());
    }
}
