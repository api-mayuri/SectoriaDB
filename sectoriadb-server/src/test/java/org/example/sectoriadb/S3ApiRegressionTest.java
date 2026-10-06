package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for S3 API bugs found in real use (auth disabled, so requests need no signature).
 * Each test names the incident it guards against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ApiRegressionTest {

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

    private final HttpClient http = HttpClient.newHttpClient();

    // ── helpers ──────────────────────────────────────────────────────────────

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private static String enc(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/", -1)) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private void mkBucket(String name) throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/" + name)).PUT(BodyPublishers.noBody())).statusCode());
    }

    private HttpResponse<byte[]> put(String bucket, String key, byte[] body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + enc(key))).PUT(BodyPublishers.ofByteArray(body)));
    }

    private HttpResponse<byte[]> get(String bucket, String key) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + enc(key))).GET());
    }

    private byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    // ── incident: video stored with aws-chunked framing inside the file ───────

    /** minio-go / s3manager send STREAMING-AWS4-HMAC-SHA256-PAYLOAD without a Content-Encoding header. */
    @Test
    void streamingSignatureUploadStoresRawBytes() throws Exception {
        mkBucket("stream-test");
        byte[] data = random(200_000, 1);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += 65536) {
            int len = Math.min(65536, data.length - off);
            framed.write((Integer.toHexString(len) + ";chunk-signature=" + "a".repeat(64) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            framed.write(data, off, len);
            framed.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        framed.write(("0;chunk-signature=" + "b".repeat(64) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

        var req = HttpRequest.newBuilder(uri("/stream-test/obj.bin"))
                .header("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD")
                .header("x-amz-decoded-content-length", String.valueOf(data.length))
                .PUT(BodyPublishers.ofByteArray(framed.toByteArray()));
        assertEquals(200, send(req).statusCode());

        HttpResponse<byte[]> got = get("stream-test", "obj.bin");
        assertEquals(200, got.statusCode());
        assertArrayEquals(data, got.body(), "stored bytes must not contain chunk-signature framing");
    }

    // ── incident: keys with spaces / non-ASCII were stored percent-encoded ────

    @Test
    void unicodeAndSpacedKeysRoundTripAndList() throws Exception {
        mkBucket("names-test");
        String key = "папка/файл с пробелом+плюс.txt";
        assertEquals(200, put("names-test", key, "hello".getBytes()).statusCode());

        assertEquals("hello", new String(get("names-test", key).body()));
        String listing = new String(send(HttpRequest.newBuilder(uri("/names-test?list-type=2")).GET()).body(),
                StandardCharsets.UTF_8);
        assertTrue(listing.contains("<Key>" + key + "</Key>"), "key must be listed decoded, got: " + listing);
        assertFalse(listing.contains("%20") || listing.contains("%D0"));
    }

    // ── incident: aws cli folders / s3manager need delimiter + CommonPrefixes ─

    @Test
    void listingWithDelimiterReturnsCommonPrefixes() throws Exception {
        mkBucket("list-test");
        put("list-test", "a.txt", "1".getBytes());
        put("list-test", "dir/one.txt", "2".getBytes());
        put("list-test", "dir/sub/two.txt", "3".getBytes());
        put("list-test", "empty-folder/", new byte[0]);

        String root = new String(send(HttpRequest.newBuilder(uri("/list-test?list-type=2&delimiter=/")).GET()).body());
        assertTrue(root.contains("<Key>a.txt</Key>"));
        assertTrue(root.contains("<CommonPrefixes><Prefix>dir/</Prefix></CommonPrefixes>"), root);
        assertTrue(root.contains("<CommonPrefixes><Prefix>empty-folder/</Prefix></CommonPrefixes>"), root);
        assertFalse(root.contains("<Key>dir/one.txt</Key>"));

        String dir = new String(send(HttpRequest.newBuilder(uri("/list-test?list-type=2&delimiter=/&prefix=dir/")).GET()).body());
        assertTrue(dir.contains("<Key>dir/one.txt</Key>"));
        assertTrue(dir.contains("<Prefix>dir/sub/</Prefix>"));
    }

    // ── incident: GetObject returned 500 (wildcard ResponseEntity<?>) ─────────

    @Test
    void getObjectStreamsBodyAndSupportsRange() throws Exception {
        mkBucket("range-test");
        byte[] data = random(50_000, 2);       // spans several 4 KiB chunks
        put("range-test", "v.bin", data);

        HttpResponse<byte[]> full = get("range-test", "v.bin");
        assertEquals(200, full.statusCode());
        assertArrayEquals(data, full.body());

        var rangeReq = HttpRequest.newBuilder(uri("/range-test/v.bin")).header("Range", "bytes=4090-8200").GET();
        HttpResponse<byte[]> part = send(rangeReq);
        assertEquals(206, part.statusCode());
        assertEquals("bytes 4090-8200/50000", part.headers().firstValue("Content-Range").orElse(""));
        assertEquals("bytes", part.headers().firstValue("Accept-Ranges").orElse(""),
                "players need Accept-Ranges on 206 responses");
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 4090, 8201), part.body());

        // end beyond size is clamped, start beyond size is 416
        var tail = send(HttpRequest.newBuilder(uri("/range-test/v.bin")).header("Range", "bytes=49990-99999").GET());
        assertEquals(206, tail.statusCode());
        assertEquals(10, tail.body().length);
        var bad = send(HttpRequest.newBuilder(uri("/range-test/v.bin")).header("Range", "bytes=60000-").GET());
        assertEquals(416, bad.statusCode());
    }

    // ── incident: parallel UploadPart lost ETags ("Part 4 has not been uploaded") ──

    @Test
    void parallelMultipartUploadCompletes() throws Exception {
        mkBucket("mpu-test");
        var init = send(HttpRequest.newBuilder(uri("/mpu-test/big.bin?uploads")).POST(BodyPublishers.noBody()));
        assertEquals(200, init.statusCode());
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(new String(init.body()));
        assertTrue(m.find());
        String uploadId = m.group(1);

        int parts = 12;
        List<byte[]> chunks = new ArrayList<>();
        List<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
        for (int i = 1; i <= parts; i++) {
            byte[] data = random(30_000 + i, i);
            chunks.add(data);
            futures.add(http.sendAsync(HttpRequest.newBuilder(
                            uri("/mpu-test/big.bin?partNumber=" + i + "&uploadId=" + uploadId))
                    .PUT(BodyPublishers.ofByteArray(data)).build(), BodyHandlers.ofByteArray()));
        }
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < parts; i++) {
            HttpResponse<byte[]> r = futures.get(i).get();
            assertEquals(200, r.statusCode());
            xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
               .append(r.headers().firstValue("ETag").orElseThrow()).append("</ETag></Part>");
            expected.write(chunks.get(i));
        }
        xml.append("</CompleteMultipartUpload>");

        var done = send(HttpRequest.newBuilder(uri("/mpu-test/big.bin?uploadId=" + uploadId))
                .POST(BodyPublishers.ofString(xml.toString())));
        assertEquals(200, done.statusCode(), new String(done.body()));
        assertArrayEquals(expected.toByteArray(), get("mpu-test", "big.bin").body());
    }

    @Test
    void multipartRejectsPathTraversalUploadId() throws Exception {
        mkBucket("mpu-safe");
        var abort = send(HttpRequest.newBuilder(uri("/mpu-safe/x?uploadId=..%2F..")).DELETE());
        assertTrue(abort.statusCode() == 404 || abort.statusCode() == 400, "status " + abort.statusCode());
    }

    // ── incident: PUT ?policy silently became CreateBucket and was not saved ──

    @Test
    void bucketPolicyIsPersistedWhateverTheContentType() throws Exception {
        mkBucket("policy-test");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::policy-test/docs/*\"}]}";
        var put = send(HttpRequest.newBuilder(uri("/policy-test?policy"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(BodyPublishers.ofString(policy)));
        assertEquals(204, put.statusCode());
        var got = send(HttpRequest.newBuilder(uri("/policy-test?policy")).GET());
        assertEquals(200, got.statusCode());
        assertEquals(policy, new String(got.body()));
    }

    /** curl -d and many clients label raw bodies as form data; the body must still be stored as-is. */
    @Test
    void formContentTypeDoesNotSwallowRequestBody() throws Exception {
        mkBucket("form-test");
        var put = send(HttpRequest.newBuilder(uri("/form-test/data.txt"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(BodyPublishers.ofString("a=1&b=2")));
        assertEquals(200, put.statusCode());
        assertEquals("a=1&b=2", new String(get("form-test", "data.txt").body()));
    }

    @Test
    void emptyOrMalformedPolicyIsRejected() throws Exception {
        mkBucket("badpol-test");
        for (String body : new String[]{"", "not json", "{}", "[]"}) {
            var r = send(HttpRequest.newBuilder(uri("/badpol-test?policy")).PUT(BodyPublishers.ofString(body)));
            assertEquals(400, r.statusCode(), "policy body '" + body + "' must be rejected");
        }
        assertEquals(404, send(HttpRequest.newBuilder(uri("/badpol-test?policy")).GET()).statusCode());
    }

    /** S3 puts all elements in one namespace: a namespace-less child under a namespaced root breaks strict clients. */
    @Test
    void xmlResponsesDeclareOneDefaultNamespace() throws Exception {
        mkBucket("xml-test");
        put("xml-test", "k", "v".getBytes());
        String list = new String(send(HttpRequest.newBuilder(uri("/xml-test?list-type=2")).GET()).body());
        assertTrue(list.contains("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"), list);
        assertFalse(list.contains("xmlns=\"\""), "children must not reset the namespace: " + list);
        assertFalse(list.contains("<Owner/>"), "empty Owner must be omitted: " + list);
        String buckets = new String(send(HttpRequest.newBuilder(uri("/")).GET()).body());
        assertFalse(buckets.contains("xmlns=\"\""), buckets);
        String err = new String(get("xml-test", "missing").body());
        assertTrue(err.startsWith("<Error>") && err.contains("<Code>NoSuchKey</Code>"), err);
    }

    @Test
    void unknownBucketSubresourceIsNotTreatedAsCreateBucket() throws Exception {
        mkBucket("sub-test");
        var r = send(HttpRequest.newBuilder(uri("/sub-test?somethingunsupported")).PUT(BodyPublishers.ofString("x")));
        assertEquals(501, r.statusCode());
    }

    // ── incident: ACL set on an object was lost (saved before it was applied) ──

    @Test
    void putObjectWithCannedAclIsPersisted() throws Exception {
        mkBucket("acl-test");
        var put = send(HttpRequest.newBuilder(uri("/acl-test/p.txt")).header("x-amz-acl", "public-read")
                .PUT(BodyPublishers.ofString("pub")));
        assertEquals(200, put.statusCode());
        var acl = send(HttpRequest.newBuilder(uri("/acl-test/p.txt?acl")).GET());
        assertTrue(new String(acl.body()).contains("AllUsers"), "ACL must survive the save");
    }

    // ── error codes ──────────────────────────────────────────────────────────

    @Test
    void errorsUseProperS3Codes() throws Exception {
        mkBucket("err-test");
        put("err-test", "k", "v".getBytes());
        assertTrue(new String(get("nobucket-here", "k").body()).contains("NoSuchBucket"));
        assertTrue(new String(get("err-test", "missing").body()).contains("NoSuchKey"));
        var rb = send(HttpRequest.newBuilder(uri("/err-test")).DELETE());
        assertEquals(409, rb.statusCode());
        assertTrue(new String(rb.body()).contains("BucketNotEmpty"));
        var bad = send(HttpRequest.newBuilder(uri("/..")).PUT(BodyPublishers.noBody()));
        assertTrue(bad.statusCode() >= 400, "path-like bucket names must be rejected");
    }

    // ── incident: Spring MVC errors were reported as 500 InternalError (or Spring's JSON page) ──

    private static String code(HttpResponse<byte[]> r) {
        Matcher m = Pattern.compile("<Code>([^<]+)</Code>").matcher(new String(r.body(), StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : "?" + new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void unsupportedMethodOnKnownPathIsS3MethodNotAllowed() throws Exception {
        mkBucket("mvc-405");
        var r = send(HttpRequest.newBuilder(uri("/mvc-405/k")).method("PATCH", BodyPublishers.noBody()));
        assertEquals(405, r.statusCode());
        assertEquals("MethodNotAllowed", code(r));
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("xml"), "S3 XML, not Spring JSON");
        // service root accepts GET only
        var root = send(HttpRequest.newBuilder(uri("/")).DELETE());
        assertEquals(405, root.statusCode());
        assertEquals("MethodNotAllowed", code(root));
    }

    @Test
    void malformedQueryParameterIsInvalidArgumentNot500() throws Exception {
        mkBucket("mvc-400");
        var r = send(HttpRequest.newBuilder(uri("/mvc-400?max-keys=abc")).GET());
        assertEquals(400, r.statusCode());
        assertEquals("InvalidArgument", code(r));
        var v2 = send(HttpRequest.newBuilder(uri("/mvc-400?list-type=x")).GET());
        assertEquals(400, v2.statusCode());
        assertEquals("InvalidArgument", code(v2));
    }

    // ── ListObjectVersions on an unversioned bucket: objects as version "null" (SDK bucket cleanup relies on it) ──

    @Test
    void listObjectVersionsListsObjectsAsNullVersion() throws Exception {
        mkBucket("ver-list");
        put("ver-list", "a/1.txt", "one".getBytes());
        put("ver-list", "b.txt", "two".getBytes());
        var r = send(HttpRequest.newBuilder(uri("/ver-list?versions")).GET());
        assertEquals(200, r.statusCode());
        String xml = new String(r.body(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<ListVersionsResult"), xml);
        assertEquals(2, xml.split("<Version>", -1).length - 1, xml);
        assertTrue(xml.contains("<Key>a/1.txt</Key><VersionId>null</VersionId><IsLatest>true</IsLatest>"), xml);
        // paging: one key per page, then the marker
        var p1 = new String(send(HttpRequest.newBuilder(uri("/ver-list?versions&max-keys=1")).GET()).body(), StandardCharsets.UTF_8);
        assertTrue(p1.contains("<IsTruncated>true</IsTruncated>") && p1.contains("<NextKeyMarker>a/1.txt</NextKeyMarker>"), p1);
        var p2 = new String(send(HttpRequest.newBuilder(uri("/ver-list?versions&max-keys=1&key-marker=a%2F1.txt")).GET()).body(), StandardCharsets.UTF_8);
        assertTrue(p2.contains("<Key>b.txt</Key>") && !p2.contains("a/1.txt</Key>"), p2);
    }

    @Test
    void multiObjectDeleteQuietSuppressesDeletedEntriesAndAcceptsVersionId() throws Exception {
        mkBucket("del-quiet");
        put("del-quiet", "x", "1".getBytes());
        put("del-quiet", "y", "2".getBytes());
        String body = "<Delete><Quiet>true</Quiet><Object><Key>x</Key><VersionId>null</VersionId></Object>"
                + "<Object><Key>y</Key></Object></Delete>";
        var r = send(HttpRequest.newBuilder(uri("/del-quiet?delete")).POST(BodyPublishers.ofString(body)));
        assertEquals(200, r.statusCode());
        assertFalse(new String(r.body()).contains("<Deleted>"), new String(r.body()));
        assertEquals(404, get("del-quiet", "x").statusCode());
        assertEquals(404, get("del-quiet", "y").statusCode());
    }

    /** s3-tests test_bucket_create_special_key_names: keys that are only spaces were trimmed to "" and never deleted. */
    @Test
    void multiObjectDeleteKeepsWhitespaceInKeys() throws Exception {
        mkBucket("del-space");
        for (String k : new String[]{" ", "_ ", " lead", "trail ", "a  b"}) {
            assertEquals(200, put("del-space", k, "v".getBytes()).statusCode(), k);
        }
        String body = "<Delete><Object><Key> </Key></Object><Object><Key>_ </Key></Object><Object><Key> lead</Key></Object>"
                + "<Object><Key>trail </Key></Object><Object><Key>a  b</Key></Object></Delete>";
        assertEquals(200, send(HttpRequest.newBuilder(uri("/del-space?delete")).POST(BodyPublishers.ofString(body))).statusCode());
        var list = new String(send(HttpRequest.newBuilder(uri("/del-space")).GET()).body(), StandardCharsets.UTF_8);
        assertFalse(list.contains("<Key>"), "bucket must be empty after the multi-delete: " + list);
    }

    // ── s3-tests listing details ─────────────────────────────────────────────

    @Test
    void listObjectsHonoursMaxKeysZeroEncodingTypeAndEchoesMarkers() throws Exception {
        mkBucket("list-det");
        put("list-det", "asdf+b", "1".getBytes());
        put("list-det", "foo/bar", "2".getBytes());
        put("list-det", "quux ab/c", "3".getBytes());
        // max-keys=0 is an empty page, not "everything"
        String zero = new String(send(HttpRequest.newBuilder(uri("/list-det?max-keys=0")).GET()).body(), StandardCharsets.UTF_8);
        assertFalse(zero.contains("<Key>"), zero);
        assertTrue(zero.contains("<IsTruncated>false</IsTruncated>"), zero);
        // V1 always has <Marker/> and <Owner>, V2 echoes StartAfter
        String v1 = new String(send(HttpRequest.newBuilder(uri("/list-det")).GET()).body(), StandardCharsets.UTF_8);
        assertTrue(v1.contains("<Marker/>") || v1.contains("<Marker></Marker>"), v1);
        assertTrue(v1.contains("<Owner>"), v1);
        String v2 = new String(send(HttpRequest.newBuilder(uri("/list-det?list-type=2&start-after=asdf%2Bb")).GET()).body(), StandardCharsets.UTF_8);
        assertTrue(v2.contains("<StartAfter>asdf+b</StartAfter>"), v2);
        assertFalse(v2.contains("<Owner>"), v2);
        // encoding-type=url
        String enc = new String(send(HttpRequest.newBuilder(uri("/list-det?delimiter=/&encoding-type=url")).GET()).body(), StandardCharsets.UTF_8);
        assertTrue(enc.contains("<Key>asdf%2Bb</Key>"), enc);
        assertTrue(enc.contains("<Prefix>quux%20ab/</Prefix>") && enc.contains("<EncodingType>url</EncodingType>"), enc);
        assertEquals(400, send(HttpRequest.newBuilder(uri("/list-det?encoding-type=bogus")).GET()).statusCode());
    }

    @Test
    void multiObjectDeleteOfMoreThanThousandKeysIsRejectedAndBadBucketNamesAreInvalidBucketName() throws Exception {
        mkBucket("del-limit");
        StringBuilder sb = new StringBuilder("<Delete>");
        for (int i = 0; i < 1001; i++) sb.append("<Object><Key>k").append(i).append("</Key></Object>");
        var r = send(HttpRequest.newBuilder(uri("/del-limit?delete")).POST(BodyPublishers.ofString(sb.append("</Delete>").toString())));
        assertEquals(400, r.statusCode());
        assertEquals("MalformedXML", code(r));
        var bad = send(HttpRequest.newBuilder(uri("/ab")).PUT(BodyPublishers.noBody()));
        assertEquals(400, bad.statusCode());
        assertEquals("InvalidBucketName", code(bad));
    }

    /** warp "multipart" reads parts with GET ?partNumber=N; the whole object came back and was mistaken for the part. */
    @Test
    void getWithPartNumberDoesNotReturnTheWholeMultipartObject() throws Exception {
        mkBucket("partnum");
        put("partnum", "plain", "hello".getBytes());
        assertEquals(200, send(HttpRequest.newBuilder(uri("/partnum/plain?partNumber=1")).GET()).statusCode());
        assertEquals(416, send(HttpRequest.newBuilder(uri("/partnum/plain?partNumber=2")).GET()).statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/partnum/plain?partNumber=0")).GET()).statusCode());
        // a two-part upload
        var init = send(HttpRequest.newBuilder(uri("/partnum/mp?uploads")).POST(BodyPublishers.noBody()));
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(new String(init.body()));
        assertTrue(m.find());
        String uid = m.group(1);
        byte[] p1 = random(5 * 1024 * 1024, 1), p2 = random(1000, 2);
        var r1 = send(HttpRequest.newBuilder(uri("/partnum/mp?partNumber=1&uploadId=" + uid)).PUT(BodyPublishers.ofByteArray(p1)));
        var r2 = send(HttpRequest.newBuilder(uri("/partnum/mp?partNumber=2&uploadId=" + uid)).PUT(BodyPublishers.ofByteArray(p2)));
        String body = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + r1.headers().firstValue("ETag").get()
                + "</ETag></Part><Part><PartNumber>2</PartNumber><ETag>" + r2.headers().firstValue("ETag").get() + "</ETag></Part></CompleteMultipartUpload>";
        assertEquals(200, send(HttpRequest.newBuilder(uri("/partnum/mp?uploadId=" + uid)).POST(BodyPublishers.ofString(body))).statusCode());
        assertEquals(501, send(HttpRequest.newBuilder(uri("/partnum/mp?partNumber=1")).GET()).statusCode());
        assertEquals(416, send(HttpRequest.newBuilder(uri("/partnum/mp?partNumber=3")).GET()).statusCode());
        assertEquals(5 * 1024 * 1024 + 1000, get("partnum", "mp").body().length);
    }

    @Test
    void putWithUploadIdButNoPartNumberDoesNotOverwriteTheObject() throws Exception {
        mkBucket("put-uid");
        put("put-uid", "k", "original".getBytes());
        var r = send(HttpRequest.newBuilder(uri("/put-uid/k?uploadId=nope")).PUT(BodyPublishers.ofString("clobber")));
        assertEquals(400, r.statusCode());
        assertEquals("original", new String(get("put-uid", "k").body()));
    }
}
