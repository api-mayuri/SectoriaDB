package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.GcRepository;
import org.example.sectoriadb.service.gc.GcReports.CompactionReport;
import org.example.sectoriadb.service.gc.GcReports.Options;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Compaction of small-object blobs (doc 10): live records are copied, manifests repointed in one transaction, the old file kept for readers. */
class SmallCompactionTest {

    static final int SIZE = 1000;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.getSmallObject().setMaxFileBytes(20_000);        // ~19 records of 1000 bytes per blob
        rig.props.getGc().setSmallCompactMinDeadBytes(0);
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    ManifestEntity put(String key, byte[] data) throws Exception {
        return rig.files.storeStream(new ByteArrayInputStream(data), pool, key, "application/octet-stream");
    }

    byte[] get(String key) throws Exception {
        return read(rig.files.findObject("bkt", key).orElseThrow());
    }

    byte[] read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.files.streamToOutput(m, out);
        return out.toByteArray();
    }

    String blobOf(String key) {
        return rig.files.findObject("bkt", key).orElseThrow().getSmallBlobId();
    }

    List<BlobFileEntity> smallBlobs() {
        return rig.blobs.findByPoolId(pool.getId()).stream().filter(b -> b.getKind() == BlobKind.SMALL).toList();
    }

    /** o0..o(n-1) of 1000 bytes; returns the key -> bytes model. */
    Map<String, byte[]> fill(int n) throws Exception {
        Map<String, byte[]> model = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byte[] d = bytes(SIZE, i);
            put("o" + i, d);
            model.put("o" + i, d);
        }
        return model;
    }

    /** Deletes every object of the blob of o0 except the first {@code keep}; returns that blob's id. */
    String deleteMostOfTheFirstBlob(Map<String, byte[]> model, int keep) {
        String first = blobOf("o0");
        int kept = 0;
        for (String k : List.copyOf(model.keySet())) {
            if (!blobOf(k).equals(first)) continue;
            if (kept++ < keep) continue;
            rig.files.deleteObject("bkt", k);
            model.remove(k);
        }
        return first;
    }

    @Test
    void liveObjectsSurviveCompactionAndTheFileShrinks() throws Exception {
        Map<String, byte[]> model = fill(50);
        assertTrue(smallBlobs().size() >= 3, "the objects span several small blobs: " + smallBlobs().size());
        String oldId = deleteMostOfTheFirstBlob(model, 3);
        BlobFileEntity old = rig.blobs.findById(oldId).orElseThrow();
        long before = Files.size(Path.of(old.getFilePath()));
        gc0();                                                           // marks the records DELETED (after the grace period)

        var stats = rig.blobService.getSmallStats(old);
        assertTrue(stats.deadBytes() * 100 >= (stats.liveBytes() + stats.deadBytes()) * 50, "dead ratio over the threshold");

        List<CompactionReport> reports = rig.gc.compact(pool, false);
        CompactionReport r = reports.stream().filter(x -> x.blobId().equals(oldId)).findFirst().orElseThrow();
        assertTrue(r.compacted(), r.toString());
        assertEquals(3, r.recordsMoved());
        assertTrue(r.reclaimedBytes() > 10_000, "reclaimed " + r.reclaimedBytes());
        assertTrue(rig.blobs.findById(oldId).isEmpty(), "the old blob record is retired");
        assertEquals(1, rig.compactor.pendingFiles());
        assertTrue(Files.exists(Path.of(old.getFilePath())), "the old file stays until the grace period is over");

        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        for (String k : model.keySet()) assertNotEquals(oldId, blobOf(k));
        assertEquals(1, rig.metrics.gcCompactions.get());
        assertTrue(rig.metrics.gcReclaimedBytes.get() > 10_000);

        // the grace period passes: the old file goes (and a restart finds a consistent store)
        var run = gc0();
        assertEquals(1, run.filesDeleted());
        assertFalse(Files.exists(Path.of(old.getFilePath())));
        assertTrue(Files.size(Path.of(rig.blobs.findById(blobOf("o0")).orElseThrow().getFilePath())) < before + 10_000);

        rig.reopen();
        pool = rig.bucket("bkt");
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after restart: " + e.getKey());
        put("fresh", bytes(SIZE, 777));
        assertArrayEquals(bytes(SIZE, 777), get("fresh"));
        for (BlobFileEntity b : smallBlobs()) assertEquals(0, rig.blobService.scrubSmall(b).corrupt());
    }

    @Test
    void aReaderOfAnOldSnapshotStillReadsTheMovedRecord() throws Exception {
        Map<String, byte[]> model = fill(50);
        deleteMostOfTheFirstBlob(model, 3);
        String survivor = model.keySet().iterator().next();
        ManifestEntity holder = rig.files.findObject("bkt", survivor).orElseThrow();   // snapshot from before the compaction
        String oldId = holder.getSmallBlobId();
        gc0();
        assertTrue(rig.gc.compact(pool, false).stream().anyMatch(CompactionReport::compacted));
        assertNotEquals(oldId, blobOf(survivor), "the live manifest points at the new blob");

        assertArrayEquals(model.get(survivor), read(holder), "the old snapshot reads the old file, which is still there");
        rig.gc.run(Options.defaults());                                // normal grace: the file is kept
        assertArrayEquals(model.get(survivor), read(holder));

        gc0();                                                         // grace 0: the old file is deleted
        assertThrows(Exception.class, () -> read(holder), "documented: a reader older than the grace period loses the old file");
        assertArrayEquals(model.get(survivor), get(survivor));
    }

    @Test
    void blobsBelowTheThresholdAndTheAppendTargetAreLeftAlone() throws Exception {
        Map<String, byte[]> model = fill(50);
        rig.files.deleteObject("bkt", "o0");                           // one dead record: far below 50 %
        model.remove("o0");
        gc0();
        assertTrue(rig.gc.compact(pool, false).isEmpty(), "nothing is eligible");

        // forced compaction ignores the ratio, but never touches the append target
        List<CompactionReport> forced = rig.gc.compact(pool, true);
        assertTrue(forced.stream().anyMatch(CompactionReport::compacted));
        BlobFileEntity target = rig.blobService.smallAppendTarget(pool).orElseThrow();
        assertTrue(forced.stream().noneMatch(x -> x.blobId().equals(target.getId()) && x.compacted()));
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    @Test
    void compactionWaitsForSmallUploadsInFlight() throws Exception {
        rig.props.getGc().setSweepGateWait(java.time.Duration.ofMillis(100));
        Map<String, byte[]> model = fill(50);
        deleteMostOfTheFirstBlob(model, 3);
        gc0();
        ManifestEntity staged = rig.files.stageStream(new ByteArrayInputStream(bytes(SIZE, 4242)), pool, "inflight", null,
                UploadChecksums.none());                                 // a small record written, not committed
        CompactionReport r = rig.gc.compact(pool, false).stream().filter(x -> !x.compacted()).findFirst().orElseThrow();
        assertTrue(r.reason().contains("in flight"), r.reason());
        rig.files.commitObject(staged);
        assertTrue(rig.gc.compact(pool, false).stream().anyMatch(CompactionReport::compacted));
        assertArrayEquals(bytes(SIZE, 4242), get("inflight"));
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    @Test
    void readsWritesAndDeletesDuringCompactionLoseNothing() throws Exception {
        rig.props.getSmallObject().setMaxFileBytes(400_000);
        Map<String, byte[]> model = new ConcurrentHashMap<>();
        for (int i = 0; i < 300; i++) {
            byte[] d = bytes(SIZE, i);
            put("o" + i, d);
            model.put("o" + i, d);
        }
        // a second blob so that the first is not the append target
        for (int i = 300; i < 700; i++) {
            byte[] d = bytes(SIZE, i);
            put("o" + i, d);
            model.put("o" + i, d);
        }
        String first = blobOf("o0");
        for (int i = 0; i < 300; i++) {
            if (i % 10 != 0 && blobOf("o" + i).equals(first)) {
                rig.files.deleteObject("bkt", "o" + i);
                model.remove("o" + i);
            }
        }
        gc0();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(4);   // not the common pool: spinning workers would starve it
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            final int id = t;
            workers.add(CompletableFuture.runAsync(() -> {
                Random rnd = new Random(id);
                try {
                    while (!stop.get()) {
                        List<String> keys = new ArrayList<>(model.keySet());
                        String k = keys.get(rnd.nextInt(keys.size()));
                        byte[] expected = model.get(k);
                        if (expected == null) continue;
                        try {
                            byte[] got = get(k);
                            if (model.get(k) == expected) assertArrayEquals(expected, got, k);
                            reads.incrementAndGet();
                        } catch (java.util.NoSuchElementException deletedMeanwhile) {
                            // deleted by the writer thread below
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, exec));
        }
        workers.add(CompletableFuture.runAsync(() -> {
            try {
                int n = 0;
                while (!stop.get()) {
                    String k = "w" + n++;
                    byte[] d = bytes(SIZE, 90_000 + n);
                    put(k, d);
                    model.put(k, d);
                    writes.incrementAndGet();
                    if (n % 3 == 0) {
                        String victim = "w" + (n - 2);
                        model.remove(victim);
                        rig.files.deleteObject("bkt", victim);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, exec));
        while (reads.get() < 20 || writes.get() < 20) Thread.sleep(5);
        List<CompactionReport> reports = new ArrayList<>(rig.gc.compact(pool, false));
        long until = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < until) reports.addAll(rig.gc.compact(pool, true));   // keep compacting under load
        stop.set(true);
        for (var w : workers) w.get(60, TimeUnit.SECONDS);
        exec.shutdown();
        assertTrue(reports.stream().filter(CompactionReport::compacted).count() >= 2, reports.toString());
        assertTrue(reads.get() > 0 && writes.get() > 0);

        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        gc0();
        rig.reopen();
        pool = rig.bucket("bkt");
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after restart: " + e.getKey());
    }

    @Test
    void aManifestRetiredDuringTheCopyIsNotRepointedAndItsCopyIsDeadAfterTheSwap() throws Exception {
        Map<String, byte[]> model = fill(50);
        String oldId = blobOf("o0");
        List<ManifestEntity> live = rig.manifests.findByBlobId(oldId, true);
        BlobFileEntity old = rig.blobs.findById(oldId).orElseThrow();
        BlobFileEntity target = rig.blobService.createSmall(pool, true);
        SmallObjectBlob to = rig.smallCache.get(target);
        Map<String, GcRepository.SmallMove> moves = new LinkedHashMap<>();
        for (ManifestEntity m : live) {
            byte[] data = rig.smallCache.get(old).read(m.getSmallOffset(), m.getSmallLength(), m.getSmallCrc32c());
            var loc = to.appendWhileSealed(data, 1);
            moves.put(m.getId(), new GcRepository.SmallMove(m.getSmallOffset(), loc.offset(), loc.length(), loc.crc32c()));
        }
        // after the snapshot: one of the objects is deleted, and the old blob can no longer take appends anyway
        String victimKey = live.get(2).getObjectKey();
        rig.files.deleteObject("bkt", victimKey);
        model.remove(victimKey);

        GcRepository.CompactionSwap swap = rig.gcRepo.swapSmallBlob(oldId, target.getId(), moves);
        assertTrue(swap.swapped(), swap.reason());
        assertEquals(live.size() - 1, swap.repointed());
        assertEquals(List.of(live.get(2).getId()), swap.retired());
        to.unseal();
        assertTrue(rig.blobs.findById(oldId).isEmpty());
        assertTrue(rig.manifests.findById(live.get(2).getId()).isEmpty(), "the tombstone that named the old blob is gone");
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    @Test
    void theSwapRefusesWhenAManifestChangedOrWasNotCopied() throws Exception {
        fill(50);
        String oldId = blobOf("o0");
        BlobFileEntity target = rig.blobService.createSmall(pool, true);
        GcRepository.CompactionSwap swap = rig.gcRepo.swapSmallBlob(oldId, target.getId(), Map.of());
        assertFalse(swap.swapped());
        assertTrue(swap.reason().contains("not part of the copy"), swap.reason());
        assertTrue(rig.blobs.findById(oldId).isPresent(), "nothing changed");
        rig.blobService.delete(target.getId());
    }

    @Test
    void unregisteredSmallFilesAreRemovedAndRegisteredOnesKept() throws Exception {
        fill(30);
        Path dir = Path.of(pool.getBasePath());
        Path leftover = dir.resolve("small_deadbeef.sob");
        Files.write(leftover, new byte[100]);
        Path young = dir.resolve("small_cafebabe.sob");
        Files.write(young, new byte[100]);
        Files.setLastModifiedTime(leftover, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        int removed = rig.compactor.deleteUnregisteredFiles(0);
        assertEquals(1, removed);
        assertFalse(Files.exists(leftover));
        assertTrue(Files.exists(young), "a file that may be a blob being created is left alone");
        for (BlobFileEntity b : smallBlobs()) assertTrue(Files.exists(Path.of(b.getFilePath())));
    }

    @Test
    void aCrashAfterTheSwapLeavesAnOldFileThatARestartRemoves() throws Exception {
        Map<String, byte[]> model = fill(50);
        String oldId = deleteMostOfTheFirstBlob(model, 3);
        BlobFileEntity old = rig.blobs.findById(oldId).orElseThrow();
        gc0();
        assertTrue(rig.gc.compact(pool, false).stream().anyMatch(CompactionReport::compacted));
        rig.reopen();                                                  // the process died before the old file was deleted
        pool = rig.bucket("bkt");
        Files.setLastModifiedTime(Path.of(old.getFilePath()), FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        assertTrue(Files.exists(Path.of(old.getFilePath())));
        assertEquals(1, rig.compactor.deleteUnregisteredFiles(0));
        assertFalse(Files.exists(Path.of(old.getFilePath())));
        for (var e : model.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
    }

    RunReportHolder gc0() {
        return new RunReportHolder(rig.gc.run(Options.defaults().withGrace(0)));
    }

    /** Small helper so that {@code gc0().filesDeleted} reads naturally. */
    record RunReportHolder(org.example.sectoriadb.service.gc.GcReports.RunReport r) {
        long filesDeleted() { return r.filesDeleted; }
    }
}
