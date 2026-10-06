package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Downloads must survive slow/paused readers (video players) and must work with small chunks (16 KiB).
 * Regression for "video can not be played because the file is corrupt": Spring's async streaming timeout
 * cut long responses in the middle. The async timeout is set far below the pause here to prove that the
 * download no longer depends on it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3StreamingTest {

    static final int CHUNK = 16 * 1024;

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",             () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",             () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled",  () -> "false");
        r.add("sectoriadb.default-chunk-size",   () -> String.valueOf(CHUNK));
        r.add("sectoriadb.default-num-buckets",  () -> "8192");          // 65 536 slots × 16 KiB
        r.add("sectoriadb.s3.auth.enabled",      () -> "false");
        r.add("spring.mvc.async.request-timeout", () -> "500ms");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    private void upload(String bucket, String key, byte[] data) throws Exception {
        http.send(HttpRequest.newBuilder(uri("/" + bucket)).PUT(BodyPublishers.noBody()).build(), BodyHandlers.discarding());
        var r = http.send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key)).PUT(BodyPublishers.ofByteArray(data)).build(),
                BodyHandlers.discarding());
        assertEquals(200, r.statusCode());
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void slowReaderGetsTheWholeFileIntact() throws Exception {
        byte[] data = random(20 * 1024 * 1024 + 123, 7);        // ~1280 chunks of 16 KiB
        upload("slow-bkt", "movie.bin", data);

        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(uri("/slow-bkt/movie.bin")).GET().build(),
                BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertEquals(data.length, resp.headers().firstValueAsLong("Content-Length").orElse(-1));

        ByteArrayOutputStream got = new ByteArrayOutputStream(data.length);
        try (InputStream in = resp.body()) {
            got.write(in.readNBytes(256 * 1024));
            Thread.sleep(2_000);                                // player paused: 4× the async timeout
            in.transferTo(got);
        }
        assertEquals(data.length, got.size(), "download was cut in the middle");
        assertEquals(sha(data), sha(got.toByteArray()));
    }

    @Test
    void manySimultaneousSlowReadersDoNotStarveOtherRequests() throws Exception {
        byte[] data = random(8 * 1024 * 1024, 9);
        upload("many-bkt", "f.bin", data);

        // 12 paused readers (more than the old 8-thread async pool) hold their connections open...
        var open = new java.util.ArrayList<HttpResponse<InputStream>>();
        for (int i = 0; i < 12; i++) {
            var r = http.send(HttpRequest.newBuilder(uri("/many-bkt/f.bin")).GET().build(), BodyHandlers.ofInputStream());
            r.body().readNBytes(1024);
            open.add(r);
        }
        // ...and an unrelated request must still be answered promptly
        CompletableFuture<HttpResponse<String>> other = http.sendAsync(
                HttpRequest.newBuilder(uri("/many-bkt?list-type=2")).GET().build(), BodyHandlers.ofString());
        assertEquals(200, other.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());

        for (var r : open) {
            byte[] rest = r.body().readAllBytes();
            assertEquals(data.length - 1024, rest.length);
        }
    }

    @Test
    void rangesAreExactAcrossSmallChunkBoundaries() throws Exception {
        byte[] data = random(10 * CHUNK + 777, 11);
        upload("rng-bkt", "r.bin", data);
        long[][] cases = {
                {0, 0}, {0, CHUNK - 1}, {CHUNK - 1, CHUNK}, {CHUNK, CHUNK},
                {3 * CHUNK - 5, 5 * CHUNK + 5}, {data.length - 1, data.length - 1}, {data.length - 900, data.length - 1}};
        for (long[] c : cases) {
            var r = http.send(HttpRequest.newBuilder(uri("/rng-bkt/r.bin"))
                    .header("Range", "bytes=" + c[0] + "-" + c[1]).GET().build(), BodyHandlers.ofByteArray());
            assertEquals(206, r.statusCode(), "range " + Arrays.toString(c));
            assertArrayEquals(Arrays.copyOfRange(data, (int) c[0], (int) c[1] + 1), r.body(), "range " + Arrays.toString(c));
            assertEquals("bytes " + c[0] + "-" + c[1] + "/" + data.length,
                    r.headers().firstValue("Content-Range").orElse(""));
        }
        // open-ended and suffix ranges (what players send when seeking)
        var open = http.send(HttpRequest.newBuilder(uri("/rng-bkt/r.bin")).header("Range", "bytes=" + (5 * CHUNK) + "-")
                .GET().build(), BodyHandlers.ofByteArray());
        assertArrayEquals(Arrays.copyOfRange(data, 5 * CHUNK, data.length), open.body());
        var suffix = http.send(HttpRequest.newBuilder(uri("/rng-bkt/r.bin")).header("Range", "bytes=-1000")
                .GET().build(), BodyHandlers.ofByteArray());
        assertArrayEquals(Arrays.copyOfRange(data, data.length - 1000, data.length), suffix.body());
    }

    @Test
    void clientDisconnectMidDownloadLeavesServerHealthy() throws Exception {
        byte[] data = random(8 * 1024 * 1024, 13);
        upload("drop-bkt", "d.bin", data);
        for (int i = 0; i < 5; i++) {
            var r = http.send(HttpRequest.newBuilder(uri("/drop-bkt/d.bin")).GET().build(), BodyHandlers.ofInputStream());
            r.body().readNBytes(4096);
            r.body().close();                                    // player seeks away / closes the tab
        }
        var ok = http.send(HttpRequest.newBuilder(uri("/drop-bkt/d.bin")).GET().build(), BodyHandlers.ofByteArray());
        assertEquals(200, ok.statusCode());
        assertArrayEquals(data, ok.body());
    }
}
