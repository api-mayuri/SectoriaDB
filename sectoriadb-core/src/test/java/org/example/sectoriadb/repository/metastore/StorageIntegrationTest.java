package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.ObjectVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** The storage stack over the MetaStore: concurrent overwrites, restart persistence, blob-written-but-not-committed. */
class StorageIntegrationTest {

    @TempDir
    Path dir;

    StorageRig rig;

    @BeforeEach
    void start() {
        rig = new StorageRig(dir);
    }

    @AfterEach
    void stop() {
        rig.close();
    }

    private static byte[] body(long seed, int size) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static String md5(byte[] b) throws Exception {
        return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b)) + "\"";
    }

    private ManifestEntity put(PoolEntity pool, String key, byte[] data) throws Exception {
        return rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
    }

    private byte[] get(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    private void assertClean() {
        MetaInvariants.check(rig.store);
        ObjectVerificationService.Summary s = rig.verifier.verifyAll(m -> true);
        assertTrue(s.allOk(), "verify-all: " + s.problems());
    }

    @Test
    void sixteenThreadsOverwriteTheSameKeyLeavingExactlyOneIntactVersion() throws Exception {
        PoolEntity pool = rig.bucket("race");
        put(pool, "other", body(999, 50));      // an unrelated key must survive too
        int threads = 16;
        // mix of small (single record) and chunked bodies
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < threads; i++) bodies.add(body(i, i % 2 == 0 ? 100 + i : StorageRig.CHUNK * 2 + 37 * i));

        ExecutorService pool16 = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ManifestEntity>> futures = new ArrayList<>();
        for (byte[] data : bodies) {
            futures.add(pool16.submit(() -> {
                go.await();
                return put(pool, "same-key", data);
            }));
        }
        go.countDown();
        Set<String> committedIds = new HashSet<>();
        for (Future<ManifestEntity> f : futures) committedIds.add(f.get().getId());
        pool16.shutdown();
        assertEquals(threads, committedIds.size());

        ManifestEntity live = rig.files.findObject("race", "same-key").orElseThrow();
        assertTrue(committedIds.contains(live.getId()));
        byte[] content = get(live);
        int winner = -1;
        for (int i = 0; i < threads; i++) if (java.util.Arrays.equals(bodies.get(i), content)) winner = i;
        assertTrue(winner >= 0, "the body is exactly one of the uploaded bodies, intact");
        assertEquals(md5(bodies.get(winner)), live.getEtag(), "ETag belongs to the returned body");

        // one live version, everything else queued for GC
        assertEquals(1, rig.manifests.listObjects("race", "same-key", null, null, 100).objects().size());
        Set<String> queued = new HashSet<>();
        rig.manifests.gcQueue(1000).forEach(g -> queued.add(g.manifestId()));
        Set<String> expected = new HashSet<>(committedIds);
        expected.remove(live.getId());
        assertEquals(expected, queued, "the 15 losers are retired exactly once");
        assertEquals(2, rig.manifests.listObjects("race", "", null, null, 100).objects().size(), "no lost index entries");
        assertEquals(1, rig.manifests.countAndSize("race").objects() - 1);
        assertEquals(1, rig.files.findObject("race", "other").map(m -> 1).orElse(0));
        MetaInvariants.check(rig.store);
    }

    @Test
    void everythingSurvivesARestart() throws Exception {
        PoolEntity pool = rig.bucket("persist");
        byte[] small = body(1, 300);
        byte[] big = body(2, StorageRig.CHUNK * 3 + 5);
        put(pool, "dir/small", small);
        put(pool, "dir/big", big);
        put(pool, "dir/big", big);                  // an overwrite: one retired version
        long queued = rig.manifests.gcQueueSize();
        assertEquals(1, queued);

        rig.reopen();

        PoolEntity again = rig.poolService.findByName("persist").orElseThrow();
        assertEquals(pool.getId(), again.getId());
        assertArrayEquals(small, get(rig.files.findObject("persist", "dir/small").orElseThrow()));
        assertArrayEquals(big, get(rig.files.findObject("persist", "dir/big").orElseThrow()));
        assertEquals(List.of("dir/"), rig.manifests.listObjects("persist", "", "/", null, 10).commonPrefixes());
        assertEquals(queued, rig.manifests.gcQueueSize());
        assertClean();
    }

    @Test
    void blobWrittenButNotCommittedLeavesNoObject() throws Exception {
        PoolEntity pool = rig.bucket("crash");
        byte[] old = body(5, 400);
        ManifestEntity before = put(pool, "k", old);

        // the data of a replacement reaches the blobs, then "the process dies" before commitObject
        ManifestEntity staged = rig.files.stageStream(new ByteArrayInputStream(body(6, StorageRig.CHUNK * 2)), pool, "k",
                "application/octet-stream", org.example.sectoriadb.checksum.UploadChecksums.none());
        ManifestEntity staged2 = rig.files.stageStream(new ByteArrayInputStream(body(7, 500)), pool, "never", "x",
                org.example.sectoriadb.checksum.UploadChecksums.none());
        assertNotNull(staged.getId());
        assertNotNull(staged2.getId());

        rig.reopen();

        assertEquals(before.getId(), rig.files.findObject("crash", "k").orElseThrow().getId(), "the old version is still current");
        assertArrayEquals(old, get(rig.files.findObject("crash", "k").orElseThrow()));
        assertTrue(rig.files.findObject("crash", "never").isEmpty(), "uncommitted object is absent");
        assertEquals(List.of("k"), rig.manifests.listObjects("crash", "", null, null, 10).objects().stream()
                .map(ManifestRepository.ObjectSummary::key).toList());
        assertEquals(0, rig.manifests.gcQueueSize(), "nothing was retired");
        assertTrue(rig.manifests.findById(staged.getId()).isEmpty(), "no manifest was saved for the staged data");
        assertClean();
    }
}
