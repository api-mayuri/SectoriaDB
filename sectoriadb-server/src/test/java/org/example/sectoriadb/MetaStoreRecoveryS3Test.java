package org.example.sectoriadb;

import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.repository.metastore.MetaStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Doc 10, part B: the metadata store recovers in place. While its disk fails, reads keep answering (200), writes are
 * refused (503 SlowDown, 507 for a full disk), and once the disk works again the next write succeeds without a restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetaStoreRecoveryS3Test {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.gc.enabled",          () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "false");
        r.add("sectoriadb.metastore.recovery-backoff", () -> "0s");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired MetaStoreProvider stores;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<byte[]> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> put(String path, byte[] body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).PUT(BodyPublishers.ofByteArray(body)));
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET());
    }

    @Test
    void readsKeepWorkingWritesAreRefusedAndTheNextWriteAfterTheFaultSucceeds() throws Exception {
        assertEquals(200, send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/rec-bkt")).PUT(BodyPublishers.noBody())).statusCode());
        byte[] data = new byte[10_000];
        new Random(1).nextBytes(data);
        assertEquals(200, put("/rec-bkt/before", data).statusCode());

        MetaStore store = stores.get();
        store.injectFaultsForTesting(Integer.MAX_VALUE, 0, "No space left on device");
        HttpResponse<byte[]> first = put("/rec-bkt/during", data);        // fails in its own commit
        assertTrue(first.statusCode() >= 500, "status " + first.statusCode());
        HttpResponse<byte[]> refused = put("/rec-bkt/during2", data);     // refused by the read-only store
        assertEquals(507, refused.statusCode(), new String(refused.body()));
        assertTrue(new String(refused.body()).contains("InsufficientStorage"));

        HttpResponse<byte[]> read = get("/rec-bkt/before");               // reads are not affected
        assertEquals(200, read.statusCode());
        assertArrayEquals(data, read.body());
        assertEquals(404, get("/rec-bkt/during").statusCode());

        store.injectFaultsForTesting(Integer.MAX_VALUE, 0, "Input/output error");
        HttpResponse<byte[]> other = put("/rec-bkt/during3", data);
        assertEquals(503, other.statusCode());
        assertTrue(new String(other.body()).contains("SlowDown"), new String(other.body()));

        store.injectFaultsForTesting(0, 0, "");                           // the disk works again: no restart
        assertEquals(200, put("/rec-bkt/after", data).statusCode());
        assertArrayEquals(data, get("/rec-bkt/after").body());
        assertArrayEquals(data, get("/rec-bkt/before").body());
        assertFalse(store.isReadOnly());
        store.verify();
    }
}
