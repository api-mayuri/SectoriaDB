package org.example.sectoriadb;

import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.ChunkLocation;
import org.example.sectoriadb.service.StorageIOEngine;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.tools.MurmurBytesHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for atomic insert (C1), locking (C2), hash functions (C3) and IO ordering (C5). */
class CuckooInsertSafetyTest {

    private static final int CHUNK = 64;

    @TempDir
    Path tmp;

    private final MurmurBytesHasher hasher = new MurmurBytesHasher();

    private Path newBlob(String name, int buckets) throws IOException {
        Path p = tmp.resolve(name);
        long size = CuckooHashTable.computeRequiredBlobSize(buckets, CHUNK);
        try (FileChannel fc = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            fc.position(size - 1);
            fc.write(ByteBuffer.wrap(new byte[]{0}));
        }
        return p;
    }

    private CuckooHashTable open(Path p, int buckets, int maxEvictions, StorageIOEngine engine) throws IOException {
        BlobFile bf = new BlobFile("t", p, Files.size(p));
        return new CuckooHashTable(bf, engine, hasher, buckets, CHUNK, maxEvictions);
    }

    /** Deterministic payload derived from the key. */
    private static byte[] payload(long key) {
        byte[] b = new byte[CHUNK];
        new Random(key).nextBytes(b);
        return b;
    }

    private static void assertStored(CuckooHashTable t, Map<Long, byte[]> expected) throws IOException {
        for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
            ByteBuffer got = t.readChunkByKey(e.getKey(), CHUNK)
                    .orElseThrow(() -> new AssertionError("lost chunk " + Long.toHexString(e.getKey())));
            byte[] bytes = new byte[CHUNK];
            got.get(bytes);
            assertArrayEquals(e.getValue(), bytes, "corrupted chunk " + Long.toHexString(e.getKey()));
        }
    }

    // ── C1 ───────────────────────────────────────────────────────────────────

    @Test
    void tableFull_leavesExistingChunksIntactAndTableUnchanged() throws IOException {
        int buckets = 4; // 32 slots
        Path p = newBlob("full.raw", buckets);
        CuckooHashTable t = open(p, buckets, 6, new FileChannelStorageIOEngine(false));

        Map<Long, byte[]> stored = new LinkedHashMap<>();
        Random rng = new Random(7);
        int failures = 0;
        for (int i = 0; i < 400 && failures < 20; i++) {
            long key = rng.nextLong();
            byte[] data = payload(key);
            byte[] before = Files.readAllBytes(p);
            int activeBefore = t.getFillStats().activeSlots();
            try {
                t.insert(key, ByteBuffer.wrap(data));
                stored.put(key, data);
            } catch (TableFullException e) {
                failures++;
                assertTrue(t.lookup(key).isEmpty());
                assertEquals(activeBefore, t.getFillStats().activeSlots());
                assertArrayEquals(before, Files.readAllBytes(p), "a failed insert must not touch the file");
            }
            assertStored(t, stored);
        }
        assertTrue(failures > 0, "test must reach the full state");
        assertTrue(stored.size() >= 20, "table should fill well beyond one bucket pair");

        CuckooHashTable reloaded = open(p, buckets, 6, new FileChannelStorageIOEngine(false));
        reloaded.loadMetadataFromDisk();
        assertStored(reloaded, stored);
        assertEquals(stored.size(), reloaded.getFillStats().activeSlots());
    }

    @Test
    void eviction_movesKeepEveryChunkReadable() throws IOException {
        // Non-power-of-two bucket count: hash B is degenerate for power-of-two sizes (see docs).
        int buckets = 20; // 160 slots, this hash pair saturates around 100
        Path p = newBlob("evict.raw", buckets);
        CuckooHashTable t = open(p, buckets, 32, new FileChannelStorageIOEngine(false));
        Map<Long, byte[]> stored = new LinkedHashMap<>();
        Random rng = new Random(11);
        // fill until the table refuses: forces many eviction chains
        while (stored.size() < 150) {
            long key = rng.nextLong();
            try {
                t.insert(key, ByteBuffer.wrap(payload(key)));
                stored.put(key, payload(key));
            } catch (TableFullException ignored) {
                break;
            }
        }
        assertTrue(stored.size() > 80, "stored only " + stored.size());
        assertStored(t, stored);
    }

    /** Engine that fails with "crash" before the Nth write and counts writes. */
    private static class CrashEngine implements StorageIOEngine {
        final StorageIOEngine delegate = new FileChannelStorageIOEngine(false);
        int writes;
        int crashAtWrite = Integer.MAX_VALUE;

        @Override public ByteBuffer readChunk(BlobFile f, long o, int l) throws IOException {
            return delegate.readChunk(f, o, l);
        }
        @Override public void writeChunk(BlobFile f, long o, ByteBuffer c) throws IOException {
            if (++writes >= crashAtWrite) {
                throw new IOException("simulated crash");
            }
            delegate.writeChunk(f, o, c);
        }
        @Override public void close() throws IOException { delegate.close(); }
    }

    @Test
    void crashAtAnyPointDuringMove_neverLosesStoredChunks() throws IOException {
        int buckets = 10; // 80 slots
        Path live = newBlob("crash-live.raw", buckets);
        CuckooHashTable t = open(live, buckets, 16, new FileChannelStorageIOEngine(false));
        Map<Long, byte[]> stored = new LinkedHashMap<>();
        Random rng = new Random(3);
        int movingInserts = 0;

        for (int i = 0; i < 120 && movingInserts < 6; i++) {
            long key = rng.nextLong();
            Path snapshot = tmp.resolve("snap-" + i + ".raw");
            Files.copy(live, snapshot, StandardCopyOption.REPLACE_EXISTING);

            // Count the writes of the real insert
            CrashEngine probeEngine = new CrashEngine();
            CuckooHashTable probe = open(snapshot, buckets, 16, probeEngine);
            probe.loadMetadataFromDisk();
            probeEngine.writes = 0;
            Path probeCopy = tmp.resolve("probe-" + i + ".raw");
            Files.copy(snapshot, probeCopy, StandardCopyOption.REPLACE_EXISTING);
            try {
                probe.insert(key, ByteBuffer.wrap(payload(key)));
            } catch (TableFullException e) {
                continue;
            }
            int totalWrites = probeEngine.writes;

            if (totalWrites > 2) {
                movingInserts++;
                for (int crashAt = 1; crashAt <= totalWrites; crashAt++) {
                    Path crashed = tmp.resolve("crashed-" + i + "-" + crashAt + ".raw");
                    Files.copy(live, crashed, StandardCopyOption.REPLACE_EXISTING);
                    CrashEngine ce = new CrashEngine();
                    CuckooHashTable victim = open(crashed, buckets, 16, ce);
                    victim.loadMetadataFromDisk();
                    ce.writes = 0;
                    ce.crashAtWrite = crashAt;
                    assertThrows(IOException.class, () -> victim.insert(key, ByteBuffer.wrap(payload(key))));

                    CuckooHashTable recovered = open(crashed, buckets, 16, new FileChannelStorageIOEngine(false));
                    recovered.loadMetadataFromDisk();
                    assertStored(recovered, stored);
                    int active = recovered.getFillStats().activeSlots();
                    assertEquals(stored.size(), active, "recovery must drop duplicates and release RESERVED slots");
                }
            }

            try {
                t.insert(key, ByteBuffer.wrap(payload(key)));
                stored.put(key, payload(key));
            } catch (TableFullException ignored) {
            }
        }
        assertTrue(movingInserts >= 3, "scenario must exercise multi-step moves, got " + movingInserts);
    }

    // ── C2 ───────────────────────────────────────────────────────────────────

    @Test
    void concurrentInserts_allChunksReadable() throws Exception {
        int buckets = 100;
        Path p = newBlob("conc.raw", buckets);
        CuckooHashTable t = open(p, buckets, 32, new FileChannelStorageIOEngine(false));

        int threads = 8, perThread = 40;
        Map<Long, byte[]> expected = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int th = 0; th < threads; th++) {
            final int id = th;
            futures.add(pool.submit(() -> {
                Random rng = new Random(1000 + id);
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long key = rng.nextLong();
                    t.insert(key, ByteBuffer.wrap(payload(key)));
                    expected.put(key, payload(key));
                    // concurrent readers must always see consistent data
                    assertTrue(t.readChunkByKey(key, CHUNK).isPresent());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(threads * perThread, expected.size());
        assertStored(t, expected);
        assertEquals(expected.size(), t.getFillStats().activeSlots());

        CuckooHashTable reloaded = open(p, buckets, 32, new FileChannelStorageIOEngine(false));
        reloaded.loadMetadataFromDisk();
        assertStored(reloaded, expected);
    }

    @Test
    void lockForWrite_isReentrantAndReleased() throws Exception {
        Path p = newBlob("lock.raw", 4);
        CuckooHashTable t = open(p, 4, 8, new FileChannelStorageIOEngine(false));
        try (CuckooHashTable.WriteGuard g = t.lockForWrite()) {
            t.insert(1L, ByteBuffer.wrap(payload(1L)));
            assertTrue(t.lookup(1L).isPresent());
        }
        Future<Boolean> other = Executors.newSingleThreadExecutor().submit(() -> t.lookup(1L).isPresent());
        assertTrue(other.get(5, TimeUnit.SECONDS));
    }

    // ── C3 ───────────────────────────────────────────────────────────────────

    @Test
    void extremeKeys_includingLongMinValue_workWithNonPowerOfTwoBuckets() throws IOException {
        int buckets = 13;
        Path p = newBlob("extreme.raw", buckets);
        CuckooHashTable t = open(p, buckets, 16, new FileChannelStorageIOEngine(false));
        long[] keys = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, Long.MIN_VALUE + 1, -13L};
        Map<Long, byte[]> stored = new LinkedHashMap<>();
        for (long k : keys) {
            ChunkLocation loc = t.insert(k, ByteBuffer.wrap(payload(k)));
            assertTrue(loc.bucketIndex() >= 0 && loc.bucketIndex() < buckets);
            stored.put(k, payload(k));
        }
        assertStored(t, stored);
    }

    // ── C5 ───────────────────────────────────────────────────────────────────

    @Test
    void directInsert_ordersDataForceMetaForce() throws IOException {
        int buckets = 4;
        Path p = newBlob("order.raw", buckets);
        List<String> ops = new ArrayList<>();
        long metaSize = (long) 2 * buckets * CuckooHashTable.SLOTS_PER_BUCKET * 9;
        FileChannelStorageIOEngine real = new FileChannelStorageIOEngine(false);
        StorageIOEngine recording = new StorageIOEngine() {
            @Override public ByteBuffer readChunk(BlobFile f, long o, int l) throws IOException {
                return real.readChunk(f, o, l);
            }
            @Override public void writeChunk(BlobFile f, long o, ByteBuffer c) throws IOException {
                ops.add(o < metaSize ? "META" : "DATA");
                real.writeChunk(f, o, c);
            }
            @Override public void force(BlobFile f) { ops.add("FORCE"); }
            @Override public void close() throws IOException { real.close(); }
        };
        CuckooHashTable t = open(p, buckets, 8, recording);
        t.insert(42L, ByteBuffer.wrap(payload(42L)));
        assertEquals(List.of("DATA", "FORCE", "META", "FORCE"), ops);
    }

    @Test
    void closedTable_rejectsFurtherIo() throws IOException {
        Path p = newBlob("closed.raw", 4);
        CuckooHashTable t = open(p, 4, 8, new FileChannelStorageIOEngine(true));
        t.insert(5L, ByteBuffer.wrap(payload(5L)));
        t.close();
        assertThrows(IOException.class, () -> t.insert(6L, ByteBuffer.wrap(payload(6L))));
    }
}
