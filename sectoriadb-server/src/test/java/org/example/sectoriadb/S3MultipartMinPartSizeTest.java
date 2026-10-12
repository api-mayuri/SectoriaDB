package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The default minimum part size (5 MiB) is enforced at Complete, for every part but the last. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3MultipartMinPartSizeTest {

    static final int MIN = 5 * 1024 * 1024;

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "false");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private URI uri(String p) { return URI.create("http://localhost:" + port + p); }

    private String part(String key, String id, int n, int size) throws Exception {
        var r = http.send(HttpRequest.newBuilder(uri("/minbkt/" + key + "?partNumber=" + n + "&uploadId=" + id))
                .PUT(BodyPublishers.ofByteArray(new byte[size])).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        return r.headers().firstValue("ETag").orElseThrow();
    }

    private String xml(String... etags) {
        StringBuilder sb = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < etags.length; i++) {
            sb.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>").append(etags[i]).append("</ETag></Part>");
        }
        return sb.append("</CompleteMultipartUpload>").toString();
    }

    private String begin(String key) throws Exception {
        send(HttpRequest.newBuilder(uri("/minbkt")).PUT(BodyPublishers.noBody()));   // created once, later calls are no-ops
        var r = send(HttpRequest.newBuilder(uri("/minbkt/" + key + "?uploads")).POST(BodyPublishers.noBody()));
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(r.body());
        assertTrue(m.find());
        return m.group(1);
    }

    @Test
    void aSmallPartThatIsNotTheLastIsRejectedWithSizesAndNothingIsCommitted() throws Exception {
        String id = begin("small-first");
        String e1 = part("small-first", id, 1, MIN - 1);
        String e2 = part("small-first", id, 2, 10);
        var r = send(HttpRequest.newBuilder(uri("/minbkt/small-first?uploadId=" + id)).POST(BodyPublishers.ofString(xml(e1, e2))));
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("<Code>EntityTooSmall</Code>"), r.body());
        assertTrue(r.body().contains("<ProposedSize>" + (MIN - 1) + "</ProposedSize>"), r.body());
        assertTrue(r.body().contains("<MinSizeAllowed>" + MIN + "</MinSizeAllowed>"), r.body());
        assertEquals(404, send(HttpRequest.newBuilder(uri("/minbkt/small-first")).GET()).statusCode(), "nothing committed");
        // the upload is still open: replace the small part and complete
        String fixed = part("small-first", id, 1, MIN);
        assertEquals(200, send(HttpRequest.newBuilder(uri("/minbkt/small-first?uploadId=" + id))
                .POST(BodyPublishers.ofString(xml(fixed, e2)))).statusCode());
    }

    @Test
    void theLastPartAndASinglePartMayBeSmall() throws Exception {
        String id = begin("last-small");
        String e1 = part("last-small", id, 1, MIN);
        String e2 = part("last-small", id, 2, 1);
        assertEquals(200, send(HttpRequest.newBuilder(uri("/minbkt/last-small?uploadId=" + id))
                .POST(BodyPublishers.ofString(xml(e1, e2)))).statusCode());
        String id2 = begin("only-small");
        String s = part("only-small", id2, 1, 3);
        assertEquals(200, send(HttpRequest.newBuilder(uri("/minbkt/only-small?uploadId=" + id2))
                .POST(BodyPublishers.ofString(xml(s)))).statusCode());
    }

    @Test
    void aSmallPartLeftOutOfTheListDoesNotMatter() throws Exception {
        String id = begin("skipped");
        String tiny = part("skipped", id, 1, 5);
        String big = part("skipped", id, 2, MIN);
        String last = part("skipped", id, 3, 7);
        String body = "<CompleteMultipartUpload><Part><PartNumber>2</PartNumber><ETag>" + big + "</ETag></Part><Part><PartNumber>3</PartNumber><ETag>"
                + last + "</ETag></Part></CompleteMultipartUpload>";
        assertEquals(200, send(HttpRequest.newBuilder(uri("/minbkt/skipped?uploadId=" + id)).POST(BodyPublishers.ofString(body))).statusCode());
        assertNotNull(tiny);
    }
}
