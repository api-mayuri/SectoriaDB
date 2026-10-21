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
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** sectoriadb.s3.auth.allow-open-setup=true restores the legacy "no keys, no checks" behaviour. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3OpenSetupOptInTest {

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
        r.add("sectoriadb.s3.auth.allow-open-setup", () -> "true");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;

    @Test
    void openSetupAllowsRequestsWhenExplicitlyEnabled() throws Exception {
        var http = HttpClient.newHttpClient();
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
    }
}
