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
        return manifestRepo.findCurrent("sigbkt", key).isPresent();
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
    void multipartPartWithWrongPayloadHashIsRejectedAndLeavesNoFile() throws Exception {
        String emptyHash = SigV4TestClient.sha256(new byte[0]);
        var init = client.request("POST", "/sigbkt/mp.bin", "uploads", emptyHash, null);
        assertEquals(200, init.statusCode(), init.body());
        var m = java.util.regex.Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(init.body());
        assertTrue(m.find());
        String id = m.group(1);
        String q = "partNumber=1&uploadId=" + id;

        byte[] part = bytes("part body");
        var bad = client.request("PUT", "/sigbkt/mp.bin", q, SigV4TestClient.sha256(bytes("other")), part);
        assertEquals(400, bad.statusCode());
        try (var files = java.nio.file.Files.list(tempRoot.resolve("data").resolve(".multipart").resolve(id))) {
            assertEquals(java.util.List.of("meta.properties"),
                    files.map(f -> f.getFileName().toString()).sorted().toList(), "no part or temp file may remain");
        }
        var good = client.request("PUT", "/sigbkt/mp.bin", q, SigV4TestClient.sha256(part), part);
        assertEquals(200, good.statusCode(), good.body());
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
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(chunks[0]);
        String trailer = "x-amz-checksum-crc32:"
                + java.util.Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
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

    private static String trailerLine(org.example.sectoriadb.checksum.ChecksumAlgorithm alg, byte[] data) {
        return alg.headerName() + ":" + alg.encode(alg.compute(data));
    }

    @Test
    void signedTrailerChecksumIsVerified_forEveryAlgorithm() throws Exception {
        for (var alg : org.example.sectoriadb.checksum.ChecksumAlgorithm.values()) {
            byte[][] chunks = { bytes("first part of the body,"), bytes(" and the second one") };
            byte[] all = bytes("first part of the body, and the second one");
            String type = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
            var ok = client.putChunked("/sigbkt/tc-ok-" + alg, type, chunks, -1, false, trailerLine(alg, all), true);
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(exists("tc-ok-" + alg));

            // correctly signed trailer carrying the wrong checksum value: BadDigest, nothing committed
            var bad = client.putChunked("/sigbkt/tc-bad-" + alg, type, chunks, -1, false, trailerLine(alg, bytes("other")), true);
            assertEquals(400, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("<Code>BadDigest</Code>"), bad.body());
            assertTrue(bad.body().contains("The " + alg + " you specified did not match the calculated checksum."), bad.body());
            assertFalse(exists("tc-bad-" + alg));
        }
    }

    @Test
    void trailerChecksumOfLargerThanOneChunkBodyIsVerified() throws Exception {
        byte[] a = new byte[3000], b = new byte[3000], c = new byte[1500];
        new java.util.Random(1).nextBytes(a);
        new java.util.Random(2).nextBytes(b);
        new java.util.Random(3).nextBytes(c);
        byte[] all = java.nio.ByteBuffer.allocate(7500).put(a).put(b).put(c).array();
        var alg = org.example.sectoriadb.checksum.ChecksumAlgorithm.CRC32C;
        String type = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
        assertEquals(200, client.putChunked("/sigbkt/tbig.bin", type, new byte[][]{a, b, c}, -1, false, trailerLine(alg, all), true).statusCode());
        var bad = client.putChunked("/sigbkt/tbig2.bin", type, new byte[][]{a, b, c}, -1, false, trailerLine(alg, a), true);
        assertEquals(400, bad.statusCode());
        assertFalse(exists("tbig2.bin"));
        var get = client.request("GET", "/sigbkt/tbig.bin", null, SigV4TestClient.sha256(new byte[0]), null);
        assertEquals(200, get.statusCode());
    }

    @Test
    void trailerChecksumNotAnnouncedInXAmzTrailerIsRejected() throws Exception {
        byte[] data = bytes("announce me");
        var r = client.putChunked("/sigbkt/tr-undeclared.txt", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER",
                new byte[][]{data}, -1, false,
                trailerLine(org.example.sectoriadb.checksum.ChecksumAlgorithm.CRC32C, data), true, false);
        assertEquals(400, r.statusCode(), r.body());
        assertFalse(exists("tr-undeclared.txt"));
    }

    @Test
    void unsignedTrailerStreamingIsAccepted() throws Exception {
        // STREAMING-UNSIGNED-PAYLOAD-TRAILER: chunk framing without signatures, checksum in the trailer
        var s = client.sign("PUT", "/sigbkt/ut.txt", null, "STREAMING-UNSIGNED-PAYLOAD-TRAILER", Instant.now());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes("unsigned!"));
        String crcB64 = java.util.Base64.getEncoder().encodeToString(
                java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        String body = "7\r\nunsigne\r\n2\r\nd!\r\n0\r\nx-amz-checksum-crc32:" + crcB64 + "\r\n\r\n";
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/sigbkt/ut.txt"))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
                .header("Content-Encoding", "aws-chunked").header("x-amz-trailer", "x-amz-checksum-crc32")
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
