package org.example.sectoriadb;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.storage.PoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 11 server hardening: signature rules, bounded bodies, upload admission, conditional writes, multipart safety. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3HardeningTest {

    static final String AK = "TESTACCESSKEY0000001";
    static final String SK = "test-secret-key-for-unit-tests";
    static final String EMPTY = SigV4TestClient.sha256(new byte[0]);

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
        if (!poolService.existsByName("hbkt")) poolService.createBucket("hbkt");
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private HttpResponse<String> put(String path, String body, Map<String, String> extra) throws Exception {
        byte[] b = bytes(body);
        return client.request("PUT", path, null, SigV4TestClient.sha256(b), b, extra);
    }

    // ── 2: non-ASCII header values, signed like the AWS SDK (UTF-8 bytes) ────────────────────────────────

    @Test
    void utf8UserMetadataSignedLikeAnSdkIsAccepted() throws Exception {
        String value = "Zoë 日本";
        SigV4TestClient.Signed s = client.sign("PUT", "/hbkt/meta.txt", null, EMPTY, Instant.now(), Map.of("x-amz-meta-name", value));
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(("PUT /hbkt/meta.txt HTTP/1.1\r\nHost: localhost:" + port + "\r\nAuthorization: " + s.authorization()
                + "\r\nx-amz-date: " + s.timestamp() + "\r\nx-amz-content-sha256: " + EMPTY + "\r\nContent-Length: 0\r\n"
                + "Connection: close\r\nx-amz-meta-name: ").getBytes(StandardCharsets.ISO_8859_1));
        req.write(value.getBytes(StandardCharsets.UTF_8));   // raw UTF-8 bytes on the wire, as the SDKs send them
        req.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        String response = raw(req.toByteArray());
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        ManifestEntity m = manifestRepo.findCurrent("hbkt", "meta.txt").orElseThrow();
        // stored as received (the container's ISO-8859-1 view of the UTF-8 bytes) so that it is returned byte-exact
        assertEquals(value, new String(m.getUserMetadata().get("x-amz-meta-name").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
    }

    private String raw(byte[] request) throws Exception {
        try (Socket sock = new Socket("localhost", port)) {
            sock.getOutputStream().write(request);
            sock.getOutputStream().flush();
            InputStream in = sock.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    // ── 12: signature rules ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anAmzHeaderThatIsNotSignedIsRejected() throws Exception {
        SigV4TestClient.Signed s = client.sign("PUT", "/hbkt/unsigned.txt", null, EMPTY, Instant.now());
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hbkt/unsigned.txt"))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", EMPTY)
                .header("x-amz-acl", "public-read")          // injected by an on-path party, not covered by the signature
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        var resp = client.http.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("not signed"), resp.body());
        assertTrue(manifestRepo.findCurrent("hbkt", "unsigned.txt").isEmpty());
    }

    @Test
    void aCredentialForAnotherRegionIsRejected() throws Exception {
        client.region = "eu-west-1";
        var r = client.request("GET", "/hbkt", null, EMPTY, null);
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("AuthorizationHeaderMalformed"), r.body());
    }

    @Test
    void aLonePercentInTheQueryStringIs400NotAServerError() throws Exception {
        // anonymous, presign detection by parameter name (raw socket: java.net.URI refuses a lone '%')
        String anon = raw(bytes("GET /hbkt?X-Amz-Signature=a&b=% HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
        assertTrue(anon.startsWith("HTTP/1.1 400"), anon);
        // a known access key id with a bogus signature reaches the canonicalization
        String day = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        String signed = raw(bytes("GET /hbkt?x=% HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\nAuthorization: AWS4-HMAC-SHA256 Credential="
                + AK + "/" + day + "/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + "0".repeat(64)
                + "\r\nx-amz-date: " + ts + "\r\nx-amz-content-sha256: " + EMPTY + "\r\n\r\n"));
        assertTrue(signed.startsWith("HTTP/1.1 400"), signed);
    }

    @Test
    void aQueryParameterThatMerelyContainsTheSignatureNameIsNotAPresignedRequest() throws Exception {
        var r = client.request("GET", "/hbkt", "list-type=2&prefix=X-Amz-Signature", EMPTY, null);
        assertEquals(200, r.statusCode(), r.body());
    }

    // ── 5: upload admission ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aPutWithoutAnyDeclaredLengthIs411() throws Exception {
        SigV4TestClient.Signed s = client.sign("PUT", "/hbkt/nolength.bin", null, "UNSIGNED-PAYLOAD", Instant.now());
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hbkt/nolength.bin"))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes("streamed")))).build();   // Transfer-Encoding: chunked
        var resp = client.http.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(411, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("MissingContentLength"), resp.body());
        assertTrue(manifestRepo.findCurrent("hbkt", "nolength.bin").isEmpty());
    }

    @Test
    void aDeclaredSizeOverFiveGibIsRefusedBeforeTheBodyIsRead() throws Exception {
        SigV4TestClient.Signed s = client.sign("PUT", "/hbkt/huge.bin", null, "UNSIGNED-PAYLOAD", Instant.now(),
                Map.of("x-amz-decoded-content-length", String.valueOf(6L * 1024 * 1024 * 1024)));
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hbkt/huge.bin"))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                .header("x-amz-decoded-content-length", String.valueOf(6L * 1024 * 1024 * 1024))
                .PUT(HttpRequest.BodyPublishers.ofString("x")).build();
        var resp = client.http.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("EntityTooLarge"), resp.body());
    }

    @Test
    void theDecodedLengthMustMatchTheChunkedBody() throws Exception {
        for (long declared : new long[]{5, 50}) {   // too short / too long for a 10 byte body
            String path = "/hbkt/decoded-" + declared + ".bin";
            SigV4TestClient.Signed s = client.sign("PUT", path, null, "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", Instant.now(),
                    Map.of("x-amz-decoded-content-length", String.valueOf(declared)));
            byte[] body = SigV4TestClient.chunkedBody(s, new byte[][]{bytes("0123456789")}, -1, false, null, false);
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                    .header("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD").header("Content-Encoding", "aws-chunked")
                    .header("x-amz-decoded-content-length", String.valueOf(declared))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var resp = client.http.send(r, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, resp.statusCode(), resp.body());
            assertTrue(manifestRepo.findCurrent("hbkt", "decoded-" + declared + ".bin").isEmpty());
        }
        // the matching length is fine
        String path = "/hbkt/decoded-ok.bin";
        SigV4TestClient.Signed s = client.sign("PUT", path, null, "STREAMING-AWS4-HMAC-SHA256-PAYLOAD", Instant.now(),
                Map.of("x-amz-decoded-content-length", "10"));
        byte[] body = SigV4TestClient.chunkedBody(s, new byte[][]{bytes("0123456789")}, -1, false, null, false);
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", s.authorization()).header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD").header("Content-Encoding", "aws-chunked")
                .header("x-amz-decoded-content-length", "10")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        assertEquals(200, client.http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // ── 3: bounded bodies ───────────────────────────────────────────────────────────────────────────────

    @Test
    void anOversizedPolicyAclAndXmlBodyAreRefusedWithoutBeingBuffered() throws Exception {
        byte[] policy = new byte[25 * 1024];
        java.util.Arrays.fill(policy, (byte) ' ');
        var p = client.request("PUT", "/hbkt", "policy", SigV4TestClient.sha256(policy), policy);
        assertEquals(400, p.statusCode(), p.body());
        assertTrue(p.body().contains("PolicyTooLarge"), p.body());

        byte[] acl = new byte[80 * 1024];
        java.util.Arrays.fill(acl, (byte) ' ');
        var a = client.request("PUT", "/hbkt", "acl", SigV4TestClient.sha256(acl), acl);
        assertEquals(400, a.statusCode(), a.body());

        // just over the 2 MiB limit: the rest of the body stays under Tomcat's swallow size, so the server drains
        // it and the client reliably reads the 400 instead of racing a closed connection
        byte[] xml = new byte[2 * 1024 * 1024 + 64 * 1024];
        java.util.Arrays.fill(xml, (byte) ' ');
        var d = client.request("POST", "/hbkt", "delete", SigV4TestClient.sha256(xml), xml);
        assertEquals(400, d.statusCode(), d.body());
        assertTrue(d.body().contains("MalformedXML"), d.body());
    }

    // ── 13: response-* overrides need a verified identity ───────────────────────────────────────────────

    @Test
    void responseHeaderOverridesAreRefusedForAnonymousReadsOfPublicObjects() throws Exception {
        assertEquals(200, put("/hbkt/pub.html", "<script>1</script>", Map.of("x-amz-acl", "public-read")).statusCode());
        var anon = client.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hbkt/pub.html?response-content-type=text/html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, anon.statusCode(), anon.body());
        var plain = client.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hbkt/pub.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, plain.statusCode());
        var signed = client.request("GET", "/hbkt/pub.html", "response-content-type=text%2Fplain", EMPTY, null);
        assertEquals(200, signed.statusCode(), signed.body());
        assertTrue(signed.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    }

    // ── 14: conditional writes, ACL errors, tagging ─────────────────────────────────────────────────────

    @Test
    void ifNoneMatchStarCreatesOnlyAndIfMatchReplacesOnlyThatVersion() throws Exception {
        assertEquals(200, put("/hbkt/cond.txt", "one", Map.of("if-none-match", "*")).statusCode());
        var again = put("/hbkt/cond.txt", "two", Map.of("if-none-match", "*"));
        assertEquals(412, again.statusCode(), again.body());
        assertTrue(again.body().contains("PreconditionFailed"));
        assertEquals("one", new String(readObject("cond.txt")));

        String etag = manifestRepo.findCurrent("hbkt", "cond.txt").orElseThrow().getEtag();
        assertEquals(412, put("/hbkt/cond.txt", "three", Map.of("if-match", "\"deadbeef\"")).statusCode());
        assertEquals(200, put("/hbkt/cond.txt", "three", Map.of("if-match", etag)).statusCode());
        assertEquals("three", new String(readObject("cond.txt")));
        assertEquals(404, put("/hbkt/cond-missing.txt", "x", Map.of("if-match", etag)).statusCode());
        assertEquals(501, put("/hbkt/cond.txt", "x", Map.of("if-none-match", "\"abc\"")).statusCode());
    }

    @Test
    void racingCreatesWithIfNoneMatchHaveExactlyOneWinner() throws Exception {
        int n = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < n; i++) {
                String body = "writer-" + i;
                futures.add(pool.submit(() -> put("/hbkt/race.txt", body, Map.of("if-none-match", "*")).statusCode()));
            }
            int ok = 0, failed = 0;
            for (var f : futures) {
                int code = f.get();
                if (code == 200) ok++; else if (code == 412) failed++;
            }
            assertEquals(1, ok);
            assertEquals(n - 1, failed);
        } finally {
            pool.shutdownNow();
        }
    }

    private byte[] readObject(String key) throws Exception {
        var r = client.http.send(HttpRequest.newBuilder(URI.create(client.presign("/hbkt/" + key, Instant.now(), 60).toString())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        return r.body();
    }

    @Test
    void badAclsAreErrorsNotSilentNoOps() throws Exception {
        var unknown = put("/hbkt/acl1.txt", "x", Map.of("x-amz-acl", "bogus-acl"));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertTrue(unknown.body().contains("InvalidArgument"));
        assertTrue(manifestRepo.findCurrent("hbkt", "acl1.txt").isEmpty());

        assertEquals(200, put("/hbkt/acl2.txt", "x", Map.of()).statusCode());
        byte[] garbage = bytes("this is not xml");
        var bad = client.request("PUT", "/hbkt/acl2.txt", "acl", SigV4TestClient.sha256(garbage), garbage);
        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("MalformedACLError"), bad.body());

        var bucket = client.request("PUT", "/acl-bad-bucket", null, EMPTY, null, Map.of("x-amz-acl", "bogus"));
        assertEquals(400, bucket.statusCode());
        assertFalse(poolService.existsByName("acl-bad-bucket"), "a rejected ACL must not leave a bucket behind");
    }

    @Test
    void taggingIsNotSilentlyDiscarded() throws Exception {
        assertEquals(200, put("/hbkt/tag.txt", "x", Map.of()).statusCode());
        byte[] tags = bytes("<Tagging><TagSet><Tag><Key>a</Key><Value>b</Value></Tag></TagSet></Tagging>");
        var r = client.request("PUT", "/hbkt/tag.txt", "tagging", SigV4TestClient.sha256(tags), tags);
        assertEquals(501, r.statusCode(), r.body());
    }

    // ── multipart safety ────────────────────────────────────────────────────────────────────────────────

    private static final Pattern UPLOAD_ID = Pattern.compile("<UploadId>([^<]+)</UploadId>");

    private String initiate(String key) throws Exception {
        var r = client.request("POST", "/hbkt/" + key, "uploads", EMPTY, null);
        assertEquals(200, r.statusCode(), r.body());
        Matcher m = UPLOAD_ID.matcher(r.body());
        assertTrue(m.find());
        return m.group(1);
    }

    private String uploadPart(String key, String uploadId, int n, String data) throws Exception {
        byte[] b = bytes(data);
        var r = client.request("PUT", "/hbkt/" + key, "partNumber=" + n + "&uploadId=" + uploadId, SigV4TestClient.sha256(b), b);
        assertEquals(200, r.statusCode(), r.body());
        return r.headers().firstValue("ETag").orElseThrow();
    }

    private HttpResponse<String> complete(String key, String uploadId, String... partsXml) throws Exception {
        byte[] xml = bytes("<CompleteMultipartUpload>" + String.join("", partsXml) + "</CompleteMultipartUpload>");
        return client.request("POST", "/hbkt/" + key, "uploadId=" + uploadId, SigV4TestClient.sha256(xml), xml);
    }

    private static String part(int n, String etag) { return "<Part><PartNumber>" + n + "</PartNumber><ETag>" + etag + "</ETag></Part>"; }

    @Test
    void completeIsIdempotentAndTheUploadIsClosedAfterwards() throws Exception {
        String id = initiate("mp1.bin");
        String e1 = uploadPart("mp1.bin", id, 1, "hello ");
        String e2 = uploadPart("mp1.bin", id, 2, "world");
        var first = complete("mp1.bin", id, part(1, e1), part(2, e2));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("hello world", new String(readObject("mp1.bin")));

        // the client lost the response and retries: same answer, not NoSuchUpload
        var retry = complete("mp1.bin", id, part(1, e1), part(2, e2));
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(extract(first.body(), "ETag"), extract(retry.body(), "ETag"));
        // a different part list is not the same request
        assertEquals(404, complete("mp1.bin", id, part(1, e1)).statusCode());
        // parts can no longer be added or the upload aborted
        byte[] late = bytes("late");
        assertEquals(404, client.request("PUT", "/hbkt/mp1.bin", "partNumber=3&uploadId=" + id, SigV4TestClient.sha256(late), late).statusCode());
        assertEquals(404, client.request("DELETE", "/hbkt/mp1.bin", "uploadId=" + id, EMPTY, null).statusCode());
        // and the tombstone is not an open upload
        var list = client.request("GET", "/hbkt", "uploads", EMPTY, null);
        assertFalse(list.body().contains(id), list.body());
    }

    private static String extract(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        assertTrue(m.find(), xml);
        return m.group(1);
    }

    @Test
    void partsMustBeListedInAscendingOrderAndAFailedCompleteLeavesTheUploadUsable() throws Exception {
        String id = initiate("mp2.bin");
        String e1 = uploadPart("mp2.bin", id, 1, "aaa");
        String e2 = uploadPart("mp2.bin", id, 2, "bbb");
        var unordered = complete("mp2.bin", id, part(2, e2), part(1, e1));
        assertEquals(400, unordered.statusCode());
        assertTrue(unordered.body().contains("InvalidPartOrder"), unordered.body());
        var badPart = complete("mp2.bin", id, part(1, e1), part(3, "\"00000000000000000000000000000000\""));
        assertEquals(400, badPart.statusCode());
        assertTrue(badPart.body().contains("InvalidPart"), badPart.body());
        // the failure did not close the upload
        assertEquals(200, complete("mp2.bin", id, part(1, e1), part(2, e2)).statusCode());
        assertEquals("aaabbb", new String(readObject("mp2.bin")));
    }

    @Test
    void anUploadIdBelongsToItsKeyOnly() throws Exception {
        String id = initiate("mp3.bin");
        byte[] b = bytes("x");
        var r = client.request("PUT", "/hbkt/other.bin", "partNumber=1&uploadId=" + id, SigV4TestClient.sha256(b), b);
        assertEquals(404, r.statusCode(), r.body());
        assertEquals(404, complete("other.bin", id, part(1, "\"x\"")).statusCode());
    }

    @Test
    void aMalformedCopyRangeIs400NotNoSuchKey() throws Exception {
        assertEquals(200, put("/hbkt/src.bin", "0123456789", Map.of()).statusCode());
        String id = initiate("mp4.bin");
        for (String range : new String[]{"bytes=abc-def", "bytes=5", "bytes=9-2"}) {
            var r = client.request("PUT", "/hbkt/mp4.bin", "partNumber=1&uploadId=" + id, EMPTY, null,
                    Map.of("x-amz-copy-source", "/hbkt/src.bin", "x-amz-copy-source-range", range));
            assertEquals(400, r.statusCode(), range + " " + r.body());
            assertTrue(r.body().contains("InvalidArgument"), r.body());
        }
        var beyond = client.request("PUT", "/hbkt/mp4.bin", "partNumber=1&uploadId=" + id, EMPTY, null,
                Map.of("x-amz-copy-source", "/hbkt/src.bin", "x-amz-copy-source-range", "bytes=0-99"));
        assertEquals(416, beyond.statusCode(), beyond.body());
        var ok = client.request("PUT", "/hbkt/mp4.bin", "partNumber=1&uploadId=" + id, EMPTY, null,
                Map.of("x-amz-copy-source", "/hbkt/src.bin", "x-amz-copy-source-range", "bytes=2-5"));
        assertEquals(200, ok.statusCode(), ok.body());
    }

    @Test
    void anonymousCopyAndTheSignedCopyDirectiveAreCaseInsensitive() throws Exception {
        assertEquals(200, put("/hbkt/dir.txt", "same", Map.of("content-type", "text/plain")).statusCode());
        var self = client.request("PUT", "/hbkt/dir.txt", null, EMPTY, null,
                Map.of("x-amz-copy-source", "/hbkt/dir.txt", "x-amz-metadata-directive", "replace", "content-type", "text/csv"));
        assertEquals(200, self.statusCode(), self.body());
        var cond = client.request("PUT", "/hbkt/dir2.txt", null, EMPTY, null,
                Map.of("x-amz-copy-source", "/hbkt/dir.txt", "x-amz-copy-source-if-match", "\"x\""));
        assertEquals(501, cond.statusCode(), cond.body());
    }
}
