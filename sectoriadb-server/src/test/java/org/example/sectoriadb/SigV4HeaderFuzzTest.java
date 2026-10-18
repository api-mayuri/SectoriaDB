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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Seeded, time-bounded fuzzing of the SigV4 input of a server with authentication ON: the Authorization header, x-amz-date,
 * x-amz-content-sha256 and the presigned-URL query parameters are mutated. No mutation can carry a valid signature, so every
 * answer must be a 4xx S3 error document (400 / 403): never a success, a 5xx, a hang or a dropped connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SigV4HeaderFuzzTest {

    private static final long BUDGET_MS = 1_500;
    private static final String AK = "FUZZACCESSKEY0000001";

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
        r.add("sectoriadb.s3.secret-key",       () -> "fuzz-secret-key-for-the-tests");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Pattern errorCode = Pattern.compile("<Error>.*<Code>[A-Za-z]+</Code>", Pattern.DOTALL);

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + AK + "/20260101/us-east-1/s3/aws4_request, "
            + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + "0123456789abcdef".repeat(4);
    private static final String QUERY = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=" + AK + "%2F20260101%2Fus-east-1%2Fs3%2Faws4_request"
            + "&X-Amz-Date=20260101T000000Z&X-Amz-Expires=300&X-Amz-SignedHeaders=host&X-Amz-Signature=" + "0123456789abcdef".repeat(4);

    private void expectRejected(String what, HttpRequest.Builder b, String method, byte[] body) throws Exception {
        HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(10)).method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body))
                .build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        int s = r.statusCode();
        if (s < 400 || s >= 500) fail(what + " -> " + s + " " + r.body());
        if (!errorCode.matcher(r.body()).find()) fail(what + " -> " + s + " without an S3 error document: " + r.body());
    }

    @Test
    void mutatedAuthorizationHeaders() throws Exception {
        Random rnd = new Random(FuzzSupport.SEED + 20);
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        while (System.nanoTime() < end) {
            String auth = rnd.nextInt(6) == 0 ? FuzzSupport.tokens(rnd, 10) : FuzzSupport.mutate(AUTH, rnd);
            String date = FuzzSupport.mutate("20260101T000000Z", rnd);
            String sha = rnd.nextInt(3) == 0 ? FuzzSupport.tokens(rnd, 4) : FuzzSupport.mutate("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", rnd);
            try {
                var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/fuzzbkt/key")).header("Authorization", auth)
                        .header("x-amz-date", date).header("x-amz-content-sha256", sha);
                expectRejected("Authorization: " + auth + " | x-amz-date: " + date + " | x-amz-content-sha256: " + sha, b, rnd.nextBoolean() ? "GET" : "PUT", rnd.nextBoolean() ? null : new byte[]{1, 2, 3});
            } catch (IllegalArgumentException headerNotSendable) {
                // the JDK client refuses control characters in header values: nothing reached the server
            }
            runs++;
        }
        assertTrue(runs > 100, "too few iterations: " + runs);
    }

    @Test
    void mutatedPresignedQueries() throws Exception {
        Random rnd = new Random(FuzzSupport.SEED + 21);
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        while (System.nanoTime() < end) {
            String q = rnd.nextInt(6) == 0 ? FuzzSupport.tokens(rnd, 10) : FuzzSupport.mutate(QUERY, rnd);
            if (!q.matches("[A-Za-z0-9%&=._~+/:@!$'()*,;-]*")) continue;   // the servlet container itself answers 400 to an illegal request target
            URI uri;
            try {
                uri = URI.create("http://localhost:" + port + "/fuzzbkt/key?" + q);
            } catch (IllegalArgumentException notAUri) {
                continue;   // the client cannot even build this request line
            }
            expectRejected("presigned query " + q, HttpRequest.newBuilder(uri), "GET", null);
            runs++;
        }
        assertTrue(runs > 50, "too few iterations: " + runs);
    }
}
