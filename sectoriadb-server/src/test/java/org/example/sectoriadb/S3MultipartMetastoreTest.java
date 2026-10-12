package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 13 over HTTP: multipart state in the metadata store (auth disabled). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3MultipartMetastoreTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.multipart.min-part-size", () -> "0");   // these tests use tiny parts on purpose
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

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> r) { return new String(r.body()); }

    private void mkBucket(String name) throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/" + name)).PUT(BodyPublishers.noBody())).statusCode());
    }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    private String initiate(String bucket, String key) throws Exception {
        var r = send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key + "?uploads")).POST(BodyPublishers.noBody()));
        assertEquals(200, r.statusCode(), text(r));
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(text(r));
        assertTrue(m.find());
        return m.group(1);
    }

    private String part(String bucket, String key, String uploadId, int n, byte[] data) throws Exception {
        var r = send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key + "?partNumber=" + n + "&uploadId=" + uploadId))
                .PUT(BodyPublishers.ofByteArray(data)));
        assertEquals(200, r.statusCode(), text(r));
        return r.headers().firstValue("ETag").orElseThrow();
    }

    private static String completeXml(List<Integer> numbers, List<String> etags) {
        StringBuilder sb = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < numbers.size(); i++) {
            sb.append("<Part><PartNumber>").append(numbers.get(i)).append("</PartNumber><ETag>").append(etags.get(i))
              .append("</ETag></Part>");
        }
        return sb.append("</CompleteMultipartUpload>").toString();
    }

    private HttpResponse<byte[]> complete(String bucket, String key, String uploadId, String xml) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key + "?uploadId=" + uploadId)).POST(BodyPublishers.ofString(xml)));
    }

    private static String code(HttpResponse<byte[]> r) {
        Matcher m = Pattern.compile("<Code>([^<]+)</Code>").matcher(text(r));
        return m.find() ? m.group(1) : "";
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static String hex(byte[] d) { return HexFormat.of().formatHex(d); }

    @Test
    void mixedPartsCompleteReadAndRangeAcrossPartBoundaries() throws Exception {
        mkBucket("mp-main");
        byte[] p1 = random(3 * 4096 + 77, 1), p2 = random(500, 2), p3 = random(2 * 4096, 3);
        String id = initiate("mp-main", "dir/obj");
        List<String> etags = List.of(part("mp-main", "dir/obj", id, 1, p1), part("mp-main", "dir/obj", id, 2, p2),
                part("mp-main", "dir/obj", id, 3, p3));
        var done = complete("mp-main", "dir/obj", id, completeXml(List.of(1, 2, 3), etags));
        assertEquals(200, done.statusCode(), text(done));

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        for (byte[] p : List.of(p1, p2, p3)) md5.update(MessageDigest.getInstance("MD5").digest(p));
        String expectedEtag = "\"" + hex(md5.digest()) + "-3\"";
        assertTrue(text(done).contains(expectedEtag.replace("\"", "&quot;")) || text(done).contains(expectedEtag), text(done));

        byte[] whole = concat(p1, p2, p3);
        var got = send(HttpRequest.newBuilder(uri("/mp-main/dir/obj")).GET());
        assertEquals(200, got.statusCode());
        assertEquals(expectedEtag, got.headers().firstValue("ETag").orElseThrow());
        assertArrayEquals(whole, got.body());
        var head = send(HttpRequest.newBuilder(uri("/mp-main/dir/obj")).method("HEAD", BodyPublishers.noBody()));
        assertEquals(String.valueOf(whole.length), head.headers().firstValue("Content-Length").orElseThrow());

        Random rnd = new Random(5);
        for (int i = 0; i < 25; i++) {
            int from = rnd.nextInt(whole.length);
            int to = Math.min(whole.length - 1, from + rnd.nextInt(9000));
            var r = send(HttpRequest.newBuilder(uri("/mp-main/dir/obj")).header("Range", "bytes=" + from + "-" + to).GET());
            assertEquals(206, r.statusCode());
            assertArrayEquals(Arrays.copyOfRange(whole, from, to + 1), r.body(), "bytes " + from + "-" + to);
        }
        // the three parts by number
        byte[][] parts = {p1, p2, p3};
        for (int n = 1; n <= 3; n++) {
            var r = send(HttpRequest.newBuilder(uri("/mp-main/dir/obj?partNumber=" + n)).GET());
            assertEquals(206, r.statusCode());
            assertArrayEquals(parts[n - 1], r.body(), "part " + n);
            assertEquals("3", r.headers().firstValue("x-amz-mp-parts-count").orElseThrow());
        }
    }

    @Test
    void retriedCompleteGetsTheStoredAnswerAndADifferentListDoesNot() throws Exception {
        mkBucket("mp-idem");
        String id = initiate("mp-idem", "k");
        String e1 = part("mp-idem", "k", id, 1, random(6000, 11));
        String e2 = part("mp-idem", "k", id, 2, random(100, 12));
        String xml = completeXml(List.of(1, 2), List.of(e1, e2));
        var first = complete("mp-idem", "k", id, xml);
        var again = complete("mp-idem", "k", id, xml);
        assertEquals(200, first.statusCode(), text(first));
        assertEquals(200, again.statusCode(), text(again));
        assertEquals(text(first).replaceAll("<Location>.*?</Location>", ""), text(again).replaceAll("<Location>.*?</Location>", ""));
        var other = complete("mp-idem", "k", id, completeXml(List.of(1), List.of(e1)));
        assertEquals(404, other.statusCode());
        assertEquals("NoSuchUpload", code(other));
        var late = send(HttpRequest.newBuilder(uri("/mp-idem/k?partNumber=3&uploadId=" + id)).PUT(BodyPublishers.ofByteArray(new byte[5])));
        assertEquals(404, late.statusCode());
    }

    @Test
    void completeReportsInvalidPartsAndKeepsTheUploadOpen() throws Exception {
        mkBucket("mp-err");
        String id = initiate("mp-err", "k");
        String e1 = part("mp-err", "k", id, 1, random(5000, 21));
        var missing = complete("mp-err", "k", id, completeXml(List.of(1, 2), List.of(e1, e1)));
        assertEquals(400, missing.statusCode());
        assertEquals("InvalidPart", code(missing));
        var wrongEtag = complete("mp-err", "k", id, completeXml(List.of(1), List.of("\"" + "0".repeat(32) + "\"")));
        assertEquals("InvalidPart", code(wrongEtag));
        var order = complete("mp-err", "k", id, completeXml(List.of(2, 1), List.of(e1, e1)));
        assertEquals("InvalidPartOrder", code(order));
        assertEquals(404, send(HttpRequest.newBuilder(uri("/mp-err/k")).GET()).statusCode(), "nothing was created");
        var ok = complete("mp-err", "k", id, completeXml(List.of(1), List.of(e1)));
        assertEquals(200, ok.statusCode(), text(ok));
    }

    @Test
    void listPartsPaginatesAndAbortDiscardsTheUpload() throws Exception {
        mkBucket("mp-list");
        String id = initiate("mp-list", "k");
        for (int n : new int[]{1, 2, 4, 7}) part("mp-list", "k", id, n, random(100 * n, n));
        var page1 = send(HttpRequest.newBuilder(uri("/mp-list/k?uploadId=" + id + "&max-parts=2")).GET());
        assertEquals(200, page1.statusCode());
        String x1 = text(page1);
        assertTrue(x1.contains("<IsTruncated>true</IsTruncated>"), x1);
        assertTrue(x1.contains("<NextPartNumberMarker>2</NextPartNumberMarker>"), x1);
        assertTrue(x1.contains("<PartNumber>1</PartNumber>") && x1.contains("<PartNumber>2</PartNumber>") && !x1.contains("<PartNumber>4</PartNumber>"), x1);
        var page2 = send(HttpRequest.newBuilder(uri("/mp-list/k?uploadId=" + id + "&max-parts=2&part-number-marker=2")).GET());
        String x2 = text(page2);
        assertTrue(x2.contains("<IsTruncated>false</IsTruncated>"), x2);
        assertTrue(x2.contains("<PartNumber>4</PartNumber>") && x2.contains("<PartNumber>7</PartNumber>") && x2.contains("<Size>700</Size>"), x2);

        assertEquals(204, send(HttpRequest.newBuilder(uri("/mp-list/k?uploadId=" + id)).DELETE()).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/mp-list/k?uploadId=" + id)).GET()).statusCode());
        assertEquals("NoSuchUpload", code(send(HttpRequest.newBuilder(uri("/mp-list/k?uploadId=" + id)).DELETE())));
        var listed = send(HttpRequest.newBuilder(uri("/mp-list?uploads")).GET());
        assertFalse(text(listed).contains(id), "an aborted upload is not listed");
    }

    @Test
    void listMultipartUploadsHonoursPrefixDelimiterAndMarkers() throws Exception {
        mkBucket("mp-uploads");
        String a1 = initiate("mp-uploads", "logs/a"), a2 = initiate("mp-uploads", "logs/b"),
               c = initiate("mp-uploads", "data/c"), d = initiate("mp-uploads", "top");
        var all = text(send(HttpRequest.newBuilder(uri("/mp-uploads?uploads")).GET()));
        for (String id : List.of(a1, a2, c, d)) assertTrue(all.contains(id), all);
        assertTrue(all.indexOf("data/c") < all.indexOf("logs/a") && all.indexOf("logs/a") < all.indexOf("logs/b")
                && all.indexOf("logs/b") < all.indexOf("top"), "ordered by key: " + all);

        var prefixed = text(send(HttpRequest.newBuilder(uri("/mp-uploads?uploads&prefix=logs/")).GET()));
        assertTrue(prefixed.contains(a1) && prefixed.contains(a2) && !prefixed.contains(c) && !prefixed.contains(d), prefixed);

        var grouped = text(send(HttpRequest.newBuilder(uri("/mp-uploads?uploads&delimiter=/")).GET()));
        assertTrue(grouped.contains("<CommonPrefixes><Prefix>logs/</Prefix></CommonPrefixes>")
                && grouped.contains("<CommonPrefixes><Prefix>data/</Prefix></CommonPrefixes>") && grouped.contains(d), grouped);
        assertFalse(grouped.contains(a1), grouped);

        var page = text(send(HttpRequest.newBuilder(uri("/mp-uploads?uploads&max-uploads=2")).GET()));
        assertTrue(page.contains("<IsTruncated>true</IsTruncated>"), page);
        assertTrue(page.contains("<NextKeyMarker>logs/a</NextKeyMarker>") && page.contains("<NextUploadIdMarker>" + a1 + "</NextUploadIdMarker>"), page);
        var rest = text(send(HttpRequest.newBuilder(uri("/mp-uploads?uploads&key-marker=logs/a&upload-id-marker=" + a1)).GET()));
        assertTrue(!rest.contains("<UploadId>" + a1 + "</UploadId>") && !rest.contains(c)
                && rest.contains("<UploadId>" + a2 + "</UploadId>") && rest.contains(d), rest);
    }

    @Test
    void copiesFromAMultipartSourceWholeAndByRange() throws Exception {
        mkBucket("mp-copy");
        byte[] p1 = random(5000, 31), p2 = random(300, 32);
        String id = initiate("mp-copy", "src");
        String e1 = part("mp-copy", "src", id, 1, p1), e2 = part("mp-copy", "src", id, 2, p2);
        assertEquals(200, complete("mp-copy", "src", id, completeXml(List.of(1, 2), List.of(e1, e2))).statusCode());
        byte[] whole = concat(p1, p2);

        var copy = send(HttpRequest.newBuilder(uri("/mp-copy/dst")).header("x-amz-copy-source", "/mp-copy/src").PUT(BodyPublishers.noBody()));
        assertEquals(200, copy.statusCode(), text(copy));
        assertArrayEquals(whole, send(HttpRequest.newBuilder(uri("/mp-copy/dst")).GET()).body());

        String id2 = initiate("mp-copy", "dst2");
        var c1 = send(HttpRequest.newBuilder(uri("/mp-copy/dst2?partNumber=1&uploadId=" + id2))
                .header("x-amz-copy-source", "/mp-copy/src").header("x-amz-copy-source-range", "bytes=4900-5099").PUT(BodyPublishers.noBody()));
        assertEquals(200, c1.statusCode(), text(c1));
        Matcher m = Pattern.compile("<ETag>([^<]+)</ETag>").matcher(text(c1));
        assertTrue(m.find());
        String et = m.group(1).replace("&quot;", "\"");
        assertEquals(200, complete("mp-copy", "dst2", id2, completeXml(List.of(1), List.of(et))).statusCode());
        assertArrayEquals(Arrays.copyOfRange(whole, 4900, 5100), send(HttpRequest.newBuilder(uri("/mp-copy/dst2")).GET()).body());
    }

    @Test
    void deletingTheBucketDiscardsUploadsInProgress() throws Exception {
        mkBucket("mp-del");
        String id = initiate("mp-del", "k");
        part("mp-del", "k", id, 1, random(10_000, 41));
        assertEquals(204, send(HttpRequest.newBuilder(uri("/mp-del")).DELETE()).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/mp-del/k?uploadId=" + id)).GET()).statusCode());
        mkBucket("mp-del");
        assertFalse(text(send(HttpRequest.newBuilder(uri("/mp-del?uploads")).GET())).contains(id));
    }

    @Test
    void anUploadIdBelongsToItsBucketAndKey() throws Exception {
        mkBucket("mp-own");
        String id = initiate("mp-own", "right");
        var r = send(HttpRequest.newBuilder(uri("/mp-own/wrong?partNumber=1&uploadId=" + id)).PUT(BodyPublishers.ofByteArray(new byte[3])));
        assertEquals(404, r.statusCode());
        assertEquals("NoSuchUpload", code(r));
        assertEquals("NoSuchUpload", code(complete("mp-own", "wrong", id, completeXml(List.of(1), List.of("\"x\"")))));
    }
}
