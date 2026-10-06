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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Objects smaller than the chunk size (4096 here) are stored whole in a small-object blob; 0-byte objects need no
 * blob at all. All S3 operations must behave exactly as for chunked objects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SmallObjectsTest {

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

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private void mkBucket(String name) throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/" + name)).PUT(BodyPublishers.noBody())).statusCode());
    }

    private HttpResponse<byte[]> put(String bucket, String key, byte[] body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key)).PUT(BodyPublishers.ofByteArray(body)));
    }

    private HttpResponse<byte[]> get(String bucket, String key) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key)).GET());
    }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    private static long count(Path dir, String suffix) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }

    @Test
    void putGetHeadRangeDeleteOfSmallObjects() throws Exception {
        mkBucket("small-crud");
        for (int n : new int[]{1, 100, 4095}) {
            byte[] data = random(n, n);
            HttpResponse<byte[]> p = put("small-crud", "obj" + n, data);
            assertEquals(200, p.statusCode());
            assertNotNull(p.headers().firstValue("ETag").orElse(null));

            HttpResponse<byte[]> g = get("small-crud", "obj" + n);
            assertEquals(200, g.statusCode());
            assertArrayEquals(data, g.body());
            assertEquals(p.headers().firstValue("ETag").get(), g.headers().firstValue("ETag").get());

            HttpResponse<byte[]> h = send(HttpRequest.newBuilder(uri("/small-crud/obj" + n))
                    .method("HEAD", BodyPublishers.noBody()));
            assertEquals(200, h.statusCode());
            assertEquals(String.valueOf(n), h.headers().firstValue("Content-Length").orElse(""));
        }
        // the whole bucket lives in one small-object blob, no cuckoo table was created
        Path dir = tempRoot.resolve("data/small-crud");
        assertEquals(1, count(dir, ".sob"));
        assertEquals(0, count(dir, ".raw"));

        byte[] data = random(4095, 4095);
        var r = send(HttpRequest.newBuilder(uri("/small-crud/obj4095")).header("Range", "bytes=10-99").GET());
        assertEquals(206, r.statusCode());
        assertEquals("bytes 10-99/4095", r.headers().firstValue("Content-Range").orElse(""));
        assertArrayEquals(Arrays.copyOfRange(data, 10, 100), r.body());
        var tail = send(HttpRequest.newBuilder(uri("/small-crud/obj4095")).header("Range", "bytes=4090-99999").GET());
        assertEquals(206, tail.statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 4090, 4095), tail.body());
        var suffix = send(HttpRequest.newBuilder(uri("/small-crud/obj4095")).header("Range", "bytes=-5").GET());
        assertEquals(206, suffix.statusCode());
        assertArrayEquals(Arrays.copyOfRange(data, 4090, 4095), suffix.body());
        assertEquals(416, send(HttpRequest.newBuilder(uri("/small-crud/obj100")).header("Range", "bytes=100-").GET()).statusCode());

        // overwrite keeps working (the old record is marked deleted), delete removes the object
        byte[] v2 = random(50, 99);
        assertEquals(200, put("small-crud", "obj100", v2).statusCode());
        assertArrayEquals(v2, get("small-crud", "obj100").body());
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-crud/obj100")).DELETE()).statusCode());
        assertEquals(404, get("small-crud", "obj100").statusCode());
        assertArrayEquals(random(1, 1), get("small-crud", "obj1").body());
    }

    @Test
    void chunkSizeBoundaryStaysChunked() throws Exception {
        mkBucket("small-edge");
        byte[] exact = random(4096, 7);
        assertEquals(200, put("small-edge", "exact", exact).statusCode());
        assertArrayEquals(exact, get("small-edge", "exact").body());
        assertEquals(200, put("small-edge", "tiny", random(4095, 8)).statusCode());
        Path dir = tempRoot.resolve("data/small-edge");
        assertEquals(1, count(dir, ".raw"), "4096 bytes is a chunk, not a small object");
        assertEquals(1, count(dir, ".sob"));
    }

    @Test
    void zeroByteObjectsNeedNoBlob() throws Exception {
        mkBucket("small-empty");
        assertEquals(200, put("small-empty", "e", new byte[0]).statusCode());
        HttpResponse<byte[]> g = get("small-empty", "e");
        assertEquals(200, g.statusCode());
        assertEquals(0, g.body().length);
        var h = send(HttpRequest.newBuilder(uri("/small-empty/e")).method("HEAD", BodyPublishers.noBody()));
        assertEquals("0", h.headers().firstValue("Content-Length").orElse(""));
        Path dir = tempRoot.resolve("data/small-empty");
        assertEquals(0, count(dir, ".sob"));
        assertEquals(0, count(dir, ".raw"));
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-empty/e")).DELETE()).statusCode());
        assertEquals(404, get("small-empty", "e").statusCode());
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-empty"))
                .DELETE()).statusCode(), "bucket with only empty objects can be deleted");
    }

    @Test
    void copyObjectOfSmallObject() throws Exception {
        mkBucket("small-copy");
        byte[] data = random(777, 5);
        put("small-copy", "src", data);
        HttpResponse<byte[]> c = send(HttpRequest.newBuilder(uri("/small-copy/dst"))
                .header("x-amz-copy-source", "/small-copy/src").PUT(BodyPublishers.noBody()));
        assertEquals(200, c.statusCode(), new String(c.body()));
        assertArrayEquals(data, get("small-copy", "dst").body());
        // independent lifetimes: deleting the source does not touch the copy
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-copy/src")).DELETE()).statusCode());
        assertArrayEquals(data, get("small-copy", "dst").body());

        // copy of an empty object
        put("small-copy", "empty", new byte[0]);
        assertEquals(200, send(HttpRequest.newBuilder(uri("/small-copy/empty2"))
                .header("x-amz-copy-source", "/small-copy/empty").PUT(BodyPublishers.noBody())).statusCode());
        assertEquals(0, get("small-copy", "empty2").body().length);
    }

    @Test
    void multipartUploadCompletingIntoSmallObject() throws Exception {
        mkBucket("small-mpu");
        var init = send(HttpRequest.newBuilder(uri("/small-mpu/m.bin?uploads")).POST(BodyPublishers.noBody()));
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(new String(init.body()));
        assertTrue(m.find());
        String uploadId = m.group(1);

        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 1; i <= 3; i++) {                      // 3 x 1000 B = 3000 B < 4096: a small object
            byte[] part = random(1000, i);
            var r = send(HttpRequest.newBuilder(uri("/small-mpu/m.bin?partNumber=" + i + "&uploadId=" + uploadId))
                    .PUT(BodyPublishers.ofByteArray(part)));
            assertEquals(200, r.statusCode());
            xml.append("<Part><PartNumber>").append(i).append("</PartNumber><ETag>")
               .append(r.headers().firstValue("ETag").orElseThrow()).append("</ETag></Part>");
            expected.write(part);
        }
        xml.append("</CompleteMultipartUpload>");
        var done = send(HttpRequest.newBuilder(uri("/small-mpu/m.bin?uploadId=" + uploadId))
                .POST(BodyPublishers.ofString(xml.toString())));
        assertEquals(200, done.statusCode(), new String(done.body()));
        assertArrayEquals(expected.toByteArray(), get("small-mpu", "m.bin").body());
        Path dir = tempRoot.resolve("data/small-mpu");
        assertEquals(1, count(dir, ".sob"));
        assertEquals(0, count(dir, ".raw"));
    }

    @Test
    void deleteBucketRemovesSmallObjectBlob() throws Exception {
        mkBucket("small-bucket-del");
        put("small-bucket-del", "a", random(10, 1));
        Path dir = tempRoot.resolve("data/small-bucket-del");
        assertEquals(1, count(dir, ".sob"));
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-bucket-del/a")).DELETE()).statusCode());
        assertEquals(204, send(HttpRequest.newBuilder(uri("/small-bucket-del")).DELETE()).statusCode());
        assertFalse(Files.exists(dir), "pool directory is gone together with its small-object blob");
    }
}
