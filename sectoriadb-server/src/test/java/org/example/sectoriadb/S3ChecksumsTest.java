package org.example.sectoriadb;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 additional checksums end to end (auth disabled; chunk size 4096): upload verification for every algorithm,
 * stored whole-object checksums, checksum mode on HEAD/GET, multipart composite checksums, and GET integrity checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ChecksumsTest {

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

    @LocalServerPort int port;
    @Autowired ManifestRepository manifestRepo;
    @Autowired org.example.sectoriadb.repository.ChunkRepository chunkRepo;
    @Autowired org.example.sectoriadb.repository.BlobFileRepository blobRepo;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String B = "ckbkt";
    private boolean bucketMade;

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private void mkBucket() throws Exception {
        if (bucketMade) return;
        assertEquals(200, send(HttpRequest.newBuilder(uri("/" + B)).PUT(BodyPublishers.noBody())).statusCode());
        bucketMade = true;
    }

    private HttpResponse<byte[]> put(String key, byte[] body, String... headers) throws Exception {
        mkBucket();
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key)).PUT(BodyPublishers.ofByteArray(body));
        if (headers.length > 0) b.headers(headers);
        return send(b);
    }

    private HttpResponse<byte[]> get(String key, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key)).GET();
        if (headers.length > 0) b.headers(headers);
        return send(b);
    }

    private HttpResponse<byte[]> head(String key, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key)).method("HEAD", BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return send(b);
    }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    private static String text(HttpResponse<byte[]> r) { return new String(r.body(), StandardCharsets.UTF_8); }

    private boolean exists(String key) {
        return manifestRepo.findCurrent(B, key).isPresent();
    }

    private ManifestEntity manifest(String key) {
        return manifestRepo.findCurrent(B, key).orElseThrow();
    }

    // ── upload verification ──────────────────────────────────────────────────

    @Test
    void everyAlgorithmIsVerifiedOnPut_small_and_chunked() throws Exception {
        for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
            for (int size : new int[]{0, 100, 4095, 4096, 50_000}) {
                byte[] body = random(size, size + alg.ordinal());
                String key = "ok-" + alg + "-" + size;
                String good = b64(alg.compute(body));
                var r = put(key, body, alg.headerName(), good);
                assertEquals(200, r.statusCode(), key + ": " + text(r));
                assertEquals(good, r.headers().firstValue(alg.headerName()).orElse(null), "checksum echoed on PUT");
                assertEquals("FULL_OBJECT", r.headers().firstValue("x-amz-checksum-type").orElse(null));
                assertArrayEquals(body, get(key).body());

                byte[] other = random(size + 1, 999);
                String bad = b64(alg.compute(other));
                String badKey = "bad-" + alg + "-" + size;
                var br = put(badKey, body, alg.headerName(), bad);
                assertEquals(400, br.statusCode(), badKey);
                assertTrue(text(br).contains("<Code>BadDigest</Code>"), text(br));
                assertTrue(text(br).contains("The " + alg + " you specified did not match the calculated checksum."), text(br));
                assertFalse(exists(badKey), "nothing is committed on a mismatch: " + badKey);
                assertEquals(404, get(badKey).statusCode());
            }
        }
    }

    @Test
    void failedOverwriteKeepsTheOldVersion() throws Exception {
        assertEquals(200, put("keep", "old content".getBytes(StandardCharsets.UTF_8)).statusCode());
        byte[] fresh = random(10_000, 3);
        var r = put("keep", fresh, "x-amz-checksum-crc32c", b64(ChecksumAlgorithm.CRC32C.compute(random(10_000, 4))));
        assertEquals(400, r.statusCode());
        assertEquals("old content", text(get("keep")));
    }

    @Test
    void multipleChecksumHeadersAreRejected() throws Exception {
        byte[] body = random(100, 1);
        var r = put("multi", body,
                "x-amz-checksum-crc32", b64(ChecksumAlgorithm.CRC32.compute(body)),
                "x-amz-checksum-sha256", b64(ChecksumAlgorithm.SHA256.compute(body)));
        assertEquals(400, r.statusCode());
        assertTrue(text(r).contains("<Code>InvalidRequest</Code>"), text(r));
        assertFalse(exists("multi"));
    }

    @Test
    void malformedValuesAndAlgorithmMismatchAreRejected() throws Exception {
        byte[] body = random(100, 2);
        var bad64 = put("m1", body, "x-amz-checksum-crc32c", "###");
        assertEquals(400, bad64.statusCode());
        assertTrue(text(bad64).contains("InvalidRequest"), text(bad64));
        var wrongLen = put("m2", body, "x-amz-checksum-crc32c", b64(new byte[3]));
        assertEquals(400, wrongLen.statusCode());
        var mismatch = put("m3", body, "x-amz-sdk-checksum-algorithm", "SHA256",
                "x-amz-checksum-crc32c", b64(ChecksumAlgorithm.CRC32C.compute(body)));
        assertEquals(400, mismatch.statusCode());
        var unknown = put("m4", body, "x-amz-sdk-checksum-algorithm", "MD5");
        assertEquals(400, unknown.statusCode());
        for (String k : new String[]{"m1", "m2", "m3", "m4"}) assertFalse(exists(k), k);
    }

    @Test
    void algorithmWithoutValueIsComputedAndStored() throws Exception {
        byte[] body = random(9000, 5);
        var r = put("compute", body, "x-amz-sdk-checksum-algorithm", "SHA1");
        assertEquals(200, r.statusCode(), text(r));
        var h = head("compute", "x-amz-checksum-mode", "ENABLED");
        assertEquals(b64(ChecksumAlgorithm.SHA1.compute(body)), h.headers().firstValue("x-amz-checksum-sha1").orElse(null));
    }

    @Test
    void contentMd5IsVerifiedBeforeCommit_small_and_chunked() throws Exception {
        for (int size : new int[]{100, 30_000}) {
            byte[] body = random(size, size);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(body);
            assertEquals(200, put("md5ok" + size, body, "Content-MD5", b64(md5)).statusCode());
            var bad = put("md5bad" + size, body, "Content-MD5", b64(MessageDigest.getInstance("MD5").digest(random(size, 77))));
            assertEquals(400, bad.statusCode());
            assertTrue(text(bad).contains("<Code>BadDigest</Code>"), text(bad));
            assertTrue(text(bad).contains("The Content-MD5 you specified did not match what we received."));
            assertFalse(exists("md5bad" + size));
        }
        var invalid = put("md5invalid", random(10, 1), "Content-MD5", "not-base64-md5!");
        assertEquals(400, invalid.statusCode());
        assertTrue(text(invalid).contains("InvalidDigest"), text(invalid));
        assertFalse(exists("md5invalid"));
    }

    // ── stored checksums and checksum mode ───────────────────────────────────

    @Test
    void everyManifestHasCrc32cAndClientChecksumIsStored() throws Exception {
        byte[] plain = random(5000, 8);
        put("stored-plain", plain);
        ManifestEntity m = manifest("stored-plain");
        CRC32C crc = new CRC32C();
        crc.update(plain);
        assertEquals(b64(ChecksumAlgorithm.crc32Bytes((int) crc.getValue())), m.getCrc32c());
        assertNull(m.getChecksumAlgorithm());

        byte[] body = random(5000, 9);
        put("stored-sha", body, "x-amz-checksum-sha256", b64(ChecksumAlgorithm.SHA256.compute(body)));
        ManifestEntity s = manifest("stored-sha");
        assertEquals(ChecksumAlgorithm.SHA256, s.getChecksumAlgorithm());
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(body)), s.getChecksumValue());
        assertEquals("FULL_OBJECT", s.getChecksumType().name());
    }

    @Test
    void checksumModeHeadersOnHeadAndFullGetButNotOnRange() throws Exception {
        byte[] body = random(20_000, 10);
        String crc32 = b64(ChecksumAlgorithm.CRC32.compute(body));
        put("mode", body, "x-amz-checksum-crc32", crc32);
        put("mode-plain", body);

        var h = head("mode", "x-amz-checksum-mode", "ENABLED");
        assertEquals(crc32, h.headers().firstValue("x-amz-checksum-crc32").orElse(null));
        assertEquals("FULL_OBJECT", h.headers().firstValue("x-amz-checksum-type").orElse(null));
        var g = get("mode", "x-amz-checksum-mode", "ENABLED");
        assertEquals(crc32, g.headers().firstValue("x-amz-checksum-crc32").orElse(null));
        assertEquals("FULL_OBJECT", g.headers().firstValue("x-amz-checksum-type").orElse(null));

        assertTrue(head("mode").headers().firstValue("x-amz-checksum-crc32").isEmpty(), "no mode, no header");
        assertTrue(get("mode").headers().firstValue("x-amz-checksum-crc32").isEmpty());

        var range = get("mode", "x-amz-checksum-mode", "ENABLED", "Range", "bytes=10-99");
        assertEquals(206, range.statusCode());
        assertTrue(range.headers().firstValue("x-amz-checksum-crc32").isEmpty(), "no checksum for Range GETs");
        assertTrue(range.headers().firstValue("x-amz-checksum-type").isEmpty());

        // object without a client checksum: the always-on CRC32C is offered
        var p = get("mode-plain", "x-amz-checksum-mode", "ENABLED");
        assertEquals(b64(ChecksumAlgorithm.CRC32C.compute(body)), p.headers().firstValue("x-amz-checksum-crc32c").orElse(null));
    }

    // ── multipart ────────────────────────────────────────────────────────────

    private String initiate(String key, String... headers) throws Exception {
        mkBucket();
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key + "?uploads")).POST(BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        var r = send(b);
        assertEquals(200, r.statusCode(), text(r));
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(text(r));
        assertTrue(m.find());
        return m.group(1);
    }

    private HttpResponse<byte[]> initiateRaw(String key, String... headers) throws Exception {
        mkBucket();
        return send(HttpRequest.newBuilder(uri("/" + B + "/" + key + "?uploads")).POST(BodyPublishers.noBody()).headers(headers));
    }

    private HttpResponse<byte[]> uploadPart(String key, String id, int n, byte[] body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key + "?partNumber=" + n + "&uploadId=" + id))
                .PUT(BodyPublishers.ofByteArray(body));
        if (headers.length > 0) b.headers(headers);
        return send(b);
    }

    private HttpResponse<byte[]> complete(String key, String id, String xml, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key + "?uploadId=" + id))
                .POST(BodyPublishers.ofString(xml));
        if (headers.length > 0) b.headers(headers);
        return send(b);
    }

    private static String completeXml(String[] etags, String checksumElement, String[] checksums) {
        StringBuilder sb = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < etags.length; i++) {
            sb.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>").append(etags[i]).append("</ETag>");
            if (checksums != null) sb.append('<').append(checksumElement).append('>').append(checksums[i])
                    .append("</").append(checksumElement).append('>');
            sb.append("</Part>");
        }
        return sb.append("</CompleteMultipartUpload>").toString();
    }

    private static String etag(HttpResponse<?> r) { return r.headers().firstValue("ETag").orElseThrow(); }

    @Test
    void compositeCrc32cChecksumOfMultipartUpload() throws Exception {
        String key = "mp-crc32c";
        String id = initiate(key, "x-amz-checksum-algorithm", "CRC32C");
        byte[] p1 = random(6000, 21), p2 = random(5000, 22), p3 = random(100, 23);
        byte[][] parts = {p1, p2, p3};
        String[] etags = new String[3], sums = new String[3];
        ByteArrayOutputStream concatOfCrcs = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            CRC32C c = new CRC32C();               // independent of the production code
            c.update(parts[i]);
            byte[] crcBytes = java.nio.ByteBuffer.allocate(4).putInt((int) c.getValue()).array();
            concatOfCrcs.write(crcBytes);
            sums[i] = b64(crcBytes);
            var r = uploadPart(key, id, i + 1, parts[i], "x-amz-checksum-crc32c", sums[i]);
            assertEquals(200, r.statusCode(), text(r));
            assertEquals(sums[i], r.headers().firstValue("x-amz-checksum-crc32c").orElse(null));
            etags[i] = etag(r);
        }
        CRC32C comp = new CRC32C();
        comp.update(concatOfCrcs.toByteArray());
        String expected = b64(java.nio.ByteBuffer.allocate(4).putInt((int) comp.getValue()).array()) + "-3";

        var done = complete(key, id, completeXml(etags, "ChecksumCRC32C", sums));
        assertEquals(200, done.statusCode(), text(done));
        assertTrue(text(done).contains("<ChecksumCRC32C>" + expected + "</ChecksumCRC32C>"), text(done));
        assertTrue(text(done).contains("<ChecksumType>COMPOSITE</ChecksumType>"), text(done));

        var h = head(key, "x-amz-checksum-mode", "ENABLED");
        assertEquals(expected, h.headers().firstValue("x-amz-checksum-crc32c").orElse(null));
        assertEquals("COMPOSITE", h.headers().firstValue("x-amz-checksum-type").orElse(null));
        ManifestEntity m = manifest(key);
        assertEquals(expected, m.getChecksumValue());
        // the always-on whole-object CRC32C is computed over the assembled object
        byte[] all = new byte[p1.length + p2.length + p3.length];
        System.arraycopy(p1, 0, all, 0, p1.length);
        System.arraycopy(p2, 0, all, p1.length, p2.length);
        System.arraycopy(p3, 0, all, p1.length + p2.length, p3.length);
        CRC32C whole = new CRC32C();
        whole.update(all);
        assertEquals(b64(java.nio.ByteBuffer.allocate(4).putInt((int) whole.getValue()).array()), m.getCrc32c());
        assertArrayEquals(all, get(key).body());
    }

    @Test
    void compositeSha256ChecksumAndRecordedPartChecksumsInListParts() throws Exception {
        String key = "mp-sha256";
        String id = initiate(key, "x-amz-checksum-algorithm", "SHA256");
        byte[] p1 = random(5000, 31), p2 = random(7000, 32);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] d1 = sha.digest(p1), d2 = sha.digest(p2);
        String e1 = etag(uploadPart(key, id, 1, p1, "x-amz-checksum-sha256", b64(d1)));
        // lenient: the second part carries no checksum, the server computes it
        String e2 = etag(uploadPart(key, id, 2, p2));
        ByteArrayOutputStream cat = new ByteArrayOutputStream();
        cat.write(d1);
        cat.write(d2);
        String expected = b64(sha.digest(cat.toByteArray())) + "-2";

        var list = send(HttpRequest.newBuilder(uri("/" + B + "/" + key + "?uploadId=" + id)).GET());
        assertTrue(text(list).contains("<ChecksumSHA256>" + b64(d1) + "</ChecksumSHA256>"), text(list));
        assertTrue(text(list).contains("<ChecksumSHA256>" + b64(d2) + "</ChecksumSHA256>"), text(list));

        var done = complete(key, id, completeXml(new String[]{e1, e2}, null, null));
        assertEquals(200, done.statusCode(), text(done));
        assertEquals(expected, manifest(key).getChecksumValue());
    }

    @Test
    void fullObjectChecksumTypeOfMultipart() throws Exception {
        String key = "mp-full";
        String id = initiate(key, "x-amz-checksum-algorithm", "CRC32", "x-amz-checksum-type", "FULL_OBJECT");
        byte[] p1 = random(5000, 41), p2 = random(5000, 42);
        String e1 = etag(uploadPart(key, id, 1, p1, "x-amz-checksum-crc32", b64(ChecksumAlgorithm.CRC32.compute(p1))));
        String e2 = etag(uploadPart(key, id, 2, p2, "x-amz-checksum-crc32", b64(ChecksumAlgorithm.CRC32.compute(p2))));
        byte[] all = new byte[10_000];
        System.arraycopy(p1, 0, all, 0, 5000);
        System.arraycopy(p2, 0, all, 5000, 5000);
        CRC32 ref = new CRC32();
        ref.update(all);
        String expected = b64(java.nio.ByteBuffer.allocate(4).putInt((int) ref.getValue()).array());

        // a wrong whole-object checksum in the Complete request is rejected, nothing committed
        var bad = complete(key, id, completeXml(new String[]{e1, e2}, null, null),
                "x-amz-checksum-crc32", b64(new byte[]{1, 2, 3, 4}));
        assertEquals(400, bad.statusCode(), text(bad));
        assertTrue(text(bad).contains("BadDigest"), text(bad));
        assertFalse(exists(key));

        var done = complete(key, id, completeXml(new String[]{e1, e2}, null, null), "x-amz-checksum-crc32", expected);
        assertEquals(200, done.statusCode(), text(done));
        ManifestEntity m = manifest(key);
        assertEquals(expected, m.getChecksumValue());
        assertEquals("FULL_OBJECT", m.getChecksumType().name());
        assertEquals(expected, head(key, "x-amz-checksum-mode", "ENABLED").headers().firstValue("x-amz-checksum-crc32").orElse(null));
    }

    @Test
    void invalidMultipartChecksumTypeCombinationsAreRejected() throws Exception {
        assertEquals(400, initiateRaw("bad1", "x-amz-checksum-algorithm", "SHA1", "x-amz-checksum-type", "FULL_OBJECT").statusCode());
        assertEquals(400, initiateRaw("bad2", "x-amz-checksum-algorithm", "CRC64NVME", "x-amz-checksum-type", "COMPOSITE").statusCode());
        assertEquals(400, initiateRaw("bad3", "x-amz-checksum-type", "FULL_OBJECT").statusCode());
        assertEquals(200, initiateRaw("ok", "x-amz-checksum-algorithm", "CRC64NVME").statusCode());
    }

    @Test
    void badPartChecksumIsRejectedAndNotRecorded_andBadCompleteChecksumCommitsNothing() throws Exception {
        String key = "mp-bad";
        String id = initiate(key, "x-amz-checksum-algorithm", "CRC32C");
        byte[] p1 = random(5000, 51), p2 = random(5000, 52);
        var bad = uploadPart(key, id, 1, p1, "x-amz-checksum-crc32c", b64(ChecksumAlgorithm.CRC32C.compute(p2)));
        assertEquals(400, bad.statusCode());
        assertTrue(text(bad).contains("BadDigest"), text(bad));
        var wrongAlg = uploadPart(key, id, 1, p1, "x-amz-checksum-sha1", b64(ChecksumAlgorithm.SHA1.compute(p1)));
        assertEquals(400, wrongAlg.statusCode());
        var list = send(HttpRequest.newBuilder(uri("/" + B + "/" + key + "?uploadId=" + id)).GET());
        assertFalse(text(list).contains("<Part>"), "rejected part must not be listed: " + text(list));

        String c1 = b64(ChecksumAlgorithm.CRC32C.compute(p1)), c2 = b64(ChecksumAlgorithm.CRC32C.compute(p2));
        String e1 = etag(uploadPart(key, id, 1, p1, "x-amz-checksum-crc32c", c1));
        String e2 = etag(uploadPart(key, id, 2, p2, "x-amz-checksum-crc32c", c2));
        var wrong = complete(key, id, completeXml(new String[]{e1, e2}, "ChecksumCRC32C", new String[]{c1, c1}));
        assertEquals(400, wrong.statusCode(), text(wrong));
        assertTrue(text(wrong).contains("BadDigest"), text(wrong));
        assertFalse(exists(key));
        var wrongComposite = complete(key, id, completeXml(new String[]{e1, e2}, null, null),
                "x-amz-checksum-crc32c", b64(new byte[]{9, 9, 9, 9}) + "-2");
        assertEquals(400, wrongComposite.statusCode(), text(wrongComposite));
        assertFalse(exists(key));
        // the upload is still usable
        assertEquals(200, complete(key, id, completeXml(new String[]{e1, e2}, "ChecksumCRC32C", new String[]{c1, c2})).statusCode());
        assertTrue(exists(key));
    }

    // ── GET integrity ────────────────────────────────────────────────────────

    private void tamperCrc(String key) {
        ManifestEntity m = manifest(key);
        m.setCrc32c(b64(new byte[]{0x12, 0x34, 0x56, 0x78}));
        manifestRepo.save(m);
    }

    /** Raw HTTP/1.1 GET: returns {declared Content-Length, body bytes actually received}. */
    private long[] rawGet(String key) throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(("GET /" + B + "/" + key + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = s.getInputStream().read(buf)) >= 0) all.write(buf, 0, n);
            } catch (IOException e) {
                // a reset is an acceptable way to see the abort
            }
            String raw = all.toString(StandardCharsets.ISO_8859_1);
            int sep = raw.indexOf("\r\n\r\n");
            assertTrue(sep > 0, "no response head: " + raw);
            Matcher m = Pattern.compile("(?i)content-length: (\\d+)").matcher(raw.substring(0, sep));
            assertTrue(m.find(), raw.substring(0, sep));
            return new long[]{Long.parseLong(m.group(1)), raw.length() - sep - 4};
        }
    }

    @Test
    void tamperedWholeObjectCrcAbortsLargeGetSoTheClientSeesAFailedTransfer() throws Exception {
        byte[] body = random(200_000, 61);
        assertEquals(200, put("tamper-big", body).statusCode());
        assertArrayEquals(body, get("tamper-big").body());
        tamperCrc("tamper-big");

        assertThrows(IOException.class, () -> get("tamper-big"), "client must see an I/O failure, not a clean body");
        long[] r = rawGet("tamper-big");
        assertEquals(200_000, r[0]);
        assertTrue(r[1] < r[0], "received " + r[1] + " of " + r[0] + " bytes: the response must be incomplete");
        // range reads rely on chunk CRCs only and still work
        var range = get("tamper-big", "Range", "bytes=0-99");
        assertEquals(206, range.statusCode());
        assertArrayEquals(Arrays.copyOfRange(body, 0, 100), range.body());
    }

    @Test
    void tamperedWholeObjectCrcOfSmallObjectGivesCleanInternalError() throws Exception {
        assertEquals(200, put("tamper-small", random(300, 62)).statusCode());
        tamperCrc("tamper-small");
        var r = get("tamper-small");
        assertEquals(500, r.statusCode());
        assertTrue(text(r).contains("<Code>InternalError</Code>"), text(r));
    }

    @Test
    void corruptedChunkOnDiskFailsFullGet() throws Exception {
        byte[] body = random(100_000, 63);
        assertEquals(200, put("disk-corrupt", body).statusCode());
        ManifestEntity m = manifest("disk-corrupt");
        String blobId = chunkRepo.find(m.getPoolId(), m.chunkKeyArray()[0]).orElseThrow().blobId();
        String blobPath = blobRepo.findById(blobId).orElseThrow().getFilePath();
        byte[] file = Files.readAllBytes(Path.of(blobPath));
        byte[] needle = Arrays.copyOfRange(body, 90_000, 90_032);   // late chunk: earlier bytes are already on the wire
        int at = indexOf(file, needle);
        assertTrue(at >= 0);
        try (RandomAccessFile f = new RandomAccessFile(blobPath, "rw")) {
            f.seek(at + 3);
            f.write(file[at + 3] ^ 0x01);
        }
        assertThrows(IOException.class, () -> get("disk-corrupt"));

        // damage near the start is detected before any byte is sent: a clean 500 instead of an aborted transfer
        byte[] early = Arrays.copyOfRange(body, 100, 132);
        int at2 = indexOf(Files.readAllBytes(Path.of(blobPath)), early);
        assertTrue(at2 >= 0);
        try (RandomAccessFile f = new RandomAccessFile(blobPath, "rw")) {
            f.seek(at2 + 3);
            f.write(body[103] ^ 0x01);
        }
        var r = get("disk-corrupt");
        assertEquals(500, r.statusCode());
        assertTrue(text(r).contains("InternalError"), text(r));
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int k = 0; k < needle.length; k++) if (hay[i + k] != needle[k]) continue outer;
            return i;
        }
        return -1;
    }

    @Test
    void copyOfCorruptSourceFailsWithoutCommitting() throws Exception {
        byte[] body = random(60_000, 64);
        assertEquals(200, put("copy-src", body).statusCode());
        tamperCrc("copy-src");
        var r = send(HttpRequest.newBuilder(uri("/" + B + "/copy-dst")).PUT(BodyPublishers.noBody())
                .header("x-amz-copy-source", "/" + B + "/copy-src"));
        assertEquals(500, r.statusCode(), text(r));
        assertTrue(text(r).contains("InternalError"), text(r));
        assertFalse(exists("copy-dst"));

        String id = initiate("copy-mp");
        var pc = send(HttpRequest.newBuilder(uri("/" + B + "/copy-mp?partNumber=1&uploadId=" + id)).PUT(BodyPublishers.noBody())
                .header("x-amz-copy-source", "/" + B + "/copy-src"));
        assertEquals(500, pc.statusCode(), text(pc));
        var list = send(HttpRequest.newBuilder(uri("/" + B + "/copy-mp?uploadId=" + id)).GET());
        assertFalse(text(list).contains("<Part>"), text(list));
    }

    @Test
    void copyKeepsTheClientChecksum() throws Exception {
        byte[] body = random(10_000, 65);
        String sum = b64(ChecksumAlgorithm.SHA256.compute(body));
        put("copy-ok-src", body, "x-amz-checksum-sha256", sum);
        var r = send(HttpRequest.newBuilder(uri("/" + B + "/copy-ok-dst")).PUT(BodyPublishers.noBody())
                .header("x-amz-copy-source", "/" + B + "/copy-ok-src"));
        assertEquals(200, r.statusCode(), text(r));
        assertEquals(sum, head("copy-ok-dst", "x-amz-checksum-mode", "ENABLED").headers().firstValue("x-amz-checksum-sha256").orElse(null));
    }

    // ── unsigned aws-chunked trailers (auth disabled: no signature involved) ──

    private HttpResponse<byte[]> putUnsignedTrailer(String key, byte[] data, String trailerName, String trailerValue,
                                                    boolean announce) throws Exception {
        mkBucket();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int half = data.length / 2;
        for (byte[] c : new byte[][]{Arrays.copyOfRange(data, 0, half), Arrays.copyOfRange(data, half, data.length)}) {
            if (c.length == 0) continue;
            body.write((Integer.toHexString(c.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            body.write(c);
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write("0\r\n".getBytes(StandardCharsets.US_ASCII));
        if (trailerName != null) body.write((trailerName + ":" + trailerValue + "\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        var b = HttpRequest.newBuilder(uri("/" + B + "/" + key)).PUT(BodyPublishers.ofByteArray(body.toByteArray()))
                .header("x-amz-content-sha256", "STREAMING-UNSIGNED-PAYLOAD-TRAILER")
                .header("Content-Encoding", "aws-chunked");
        if (announce && trailerName != null) b.header("x-amz-trailer", trailerName);
        return send(b);
    }

    @Test
    void unsignedTrailingChecksumIsVerified_forEveryAlgorithm_andSizes() throws Exception {
        for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
            for (int size : new int[]{200, 30_000}) {
                byte[] data = random(size, size);
                String good = b64(alg.compute(data));
                var ok = putUnsignedTrailer("tr-ok-" + alg + size, data, alg.headerName(), good, true);
                assertEquals(200, ok.statusCode(), text(ok));
                assertArrayEquals(data, get("tr-ok-" + alg + size).body());
                assertEquals(good, manifest("tr-ok-" + alg + size).getChecksumValue());

                var bad = putUnsignedTrailer("tr-bad-" + alg + size, data, alg.headerName(), b64(alg.compute(random(size, 5))), true);
                assertEquals(400, bad.statusCode(), text(bad));
                assertTrue(text(bad).contains("<Code>BadDigest</Code>"), text(bad));
                assertFalse(exists("tr-bad-" + alg + size));
            }
        }
    }

    @Test
    void trailerProblemsAreRejected() throws Exception {
        byte[] data = random(100, 1);
        String crc = b64(ChecksumAlgorithm.CRC32C.compute(data));
        // announced but missing
        var missing = putUnsignedTrailer("tr-missing", data, null, null, false);
        assertEquals(200, missing.statusCode(), "no trailer, nothing announced: fine");
        var undeclared = putUnsignedTrailer("tr-undeclared", data, "x-amz-checksum-crc32c", crc, false);
        assertEquals(400, undeclared.statusCode(), text(undeclared));
        assertFalse(exists("tr-undeclared"));
        var garbage = putUnsignedTrailer("tr-garbage", data, "x-amz-checksum-crc32c", "%%%", true);
        assertEquals(400, garbage.statusCode(), text(garbage));
        assertFalse(exists("tr-garbage"));
    }
}
