package org.example.sectoriadb;

import org.example.sectoriadb.service.gc.GarbageCollector;
import org.example.sectoriadb.service.gc.GcReports;
import org.example.sectoriadb.shell.GcCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.shell.command.CommandCatalog;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Garbage collection through the S3 API and the shell (doc 10): space comes back, the shell commands work, a collected chunk is a retryable 503. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GarbageCollectionS3Test {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.gc.enabled",          () -> "false");   // the test drives the collector itself
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "false");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired GcCommands commands;
    @Autowired GarbageCollector gc;
    @Autowired CommandCatalog catalog;
    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> put(String bucket, String key, byte[] body) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key)).PUT(BodyPublishers.ofByteArray(body)));
    }

    private HttpResponse<byte[]> get(String bucket, String key) throws Exception {
        return send(HttpRequest.newBuilder(uri("/" + bucket + "/" + key)).GET());
    }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    @Test
    void shellCommandsAreRegisteredUnderTheirTwoWordNames() {
        for (String name : new String[]{"gc status", "gc run", "gc sweep", "gc compact"}) {
            assertTrue(catalog.getRegistrations().containsKey(name), name + " in " + catalog.getRegistrations().keySet());
        }
    }

    @Test
    void deletedObjectsGiveTheirSpaceBackThroughTheShell() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/gc-bkt")).PUT(BodyPublishers.noBody())).statusCode());
        byte[] big = random(20_000, 1);            // 5 chunks of 4096
        byte[] small = random(300, 2);
        assertEquals(200, put("gc-bkt", "big", big).statusCode());
        assertEquals(200, put("gc-bkt", "small", small).statusCode());
        assertEquals(204, send(HttpRequest.newBuilder(uri("/gc-bkt/big")).DELETE()).statusCode());
        assertEquals(204, send(HttpRequest.newBuilder(uri("/gc-bkt/small")).DELETE()).statusCode());

        String status = commands.gcStatus();
        assertTrue(status.contains("5 chunk(s) with no reference, 0 past the grace period"), status);
        assertTrue(status.contains("2 retired manifest(s)"), status);

        String normal = commands.gcRun("gc-bkt", null, null);                 // within the 15 minute grace period
        assertTrue(normal.contains("chunks freed=0"), normal);
        String run = commands.gcRun("gc-bkt", "0s", null);                    // explicit --grace 0s
        assertTrue(run.contains("chunks freed=5 (20000 bytes)"), run);
        assertTrue(run.contains("tombstones=2 (small records marked=1)"), run);
        assertTrue(commands.gcStatus().contains("0 chunk(s) with no reference"));
        assertEquals(404, get("gc-bkt", "big").statusCode());

        assertTrue(commands.gcSweep("gc-bkt").contains("strays=0"));
        assertTrue(commands.gcCompact("gc-bkt", false).contains("Nothing to compact"));
    }

    @Test
    void durationsOfTheShellAreParsed() {
        assertEquals(java.time.Duration.ZERO, parse("0s"));
        assertEquals(java.time.Duration.ofMinutes(5), parse("5m"));
        assertEquals(java.time.Duration.ofMillis(250), parse("250ms"));
        assertEquals(java.time.Duration.ofHours(2), parse("PT2H"));
        assertThrows(IllegalArgumentException.class, () -> parse("soon"));
    }

    private static java.time.Duration parse(String s) {
        try {
            var m = GcCommands.class.getDeclaredMethod("parseDuration", String.class);
            m.setAccessible(true);
            return (java.time.Duration) m.invoke(null, s);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Uploads of content that sits in the collection queue race a collector that ignores the grace period: every PUT either
     * commits (and then reads back exactly) or is told to retry with 503, never anything else.
     */
    @Test
    void uploadsRacingAGraceFreeCollectorGetOkOrARetryable503AndNeverCorruptData() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(uri("/race-bkt")).PUT(BodyPublishers.noBody())).statusCode());
        byte[] data = random(40_000, 7);          // 10 chunks
        AtomicBoolean stop = new AtomicBoolean();
        Thread collector = new Thread(() -> {
            while (!stop.get()) gc.run(GcReports.Options.defaults().withGrace(0));
        });
        collector.start();
        int ok = 0, retried = 0;
        try {
            for (int i = 0; i < 40; i++) {
                for (int attempt = 0; ; attempt++) {
                    HttpResponse<byte[]> p = put("race-bkt", "k", data);
                    if (p.statusCode() == 200) {
                        ok++;
                        break;
                    }
                    assertEquals(503, p.statusCode(), new String(p.body()));
                    assertTrue(new String(p.body()).contains("ServiceUnavailable"));
                    retried++;
                    assertTrue(attempt < 50, "a retry must eventually succeed");
                }
                HttpResponse<byte[]> g = get("race-bkt", "k");
                assertEquals(200, g.statusCode());
                assertArrayEquals(data, g.body(), "what the PUT reported as stored reads back intact");
                assertEquals(204, send(HttpRequest.newBuilder(uri("/race-bkt/k")).DELETE()).statusCode());
            }
        } finally {
            stop.set(true);
            collector.join();
        }
        assertEquals(40, ok);
        System.out.println("PUTs racing the collector: ok=" + ok + " retried(503)=" + retried);
    }
}
