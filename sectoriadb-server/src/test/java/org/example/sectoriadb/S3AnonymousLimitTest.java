package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anonymous requests share one small in-flight budget: slow anonymous readers of a public object cannot take every
 * worker thread. Budget of one here; a raw socket reads the status line of a big public GET and then stops reading.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3AnonymousLimitTest {

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
        r.add("sectoriadb.s3.limits.max-inflight-anonymous", () -> "1");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;

    @Test
    void aSlowAnonymousReaderHoldsTheOnlySlotAndTheNextAnonymousRequestGets503() throws Exception {
        var admin = new SigV4TestClient(port, AK, SK);
        String empty = SigV4TestClient.sha256(new byte[0]);
        assertEquals(200, admin.request("PUT", "/anon-lim", null, empty, null).statusCode());
        byte[] big = new byte[48 << 20];
        new java.util.Random(7).nextBytes(big);
        assertEquals(200, admin.request("PUT", "/anon-lim/big", null, SigV4TestClient.sha256(big), big,
                Map.of("x-amz-acl", "public-read")).statusCode());

        try (Socket slow = new Socket("localhost", port)) {
            slow.getOutputStream().write(("GET /anon-lim/big HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();
            InputStream in = slow.getInputStream();
            byte[] head = new byte[12];
            assertEquals(12, in.readNBytes(head, 0, 12));
            assertEquals("HTTP/1.1 200", new String(head, StandardCharsets.US_ASCII));
            // do not read on: the server blocks writing the body and keeps the slot

            int status = 0;
            for (int i = 0; i < 20 && status != 503; i++) {
                status = admin.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/anon-lim/big"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString()).statusCode();
                if (status != 503) Thread.sleep(100);
            }
            assertEquals(503, status, "the second anonymous request must be refused while the first one holds the slot");
        }
        // the slow reader is gone: the slot comes back
        int status = 503;
        for (int i = 0; i < 100 && status == 503; i++) {
            status = admin.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/anon-lim/big"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString()).statusCode();
            if (status == 503) Thread.sleep(100);
        }
        assertTrue(status == 200 || status == 403, "status " + status);
        assertEquals(200, status);
    }
}
