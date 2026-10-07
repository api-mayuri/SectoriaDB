package org.example.sectoriadb;

import org.example.sectoriadb.format.SmallBlobLayout;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Group fsync of small-object appends: one force covers many records, the tail is published after durability. */
class SmallObjectGroupFsyncTest {

    @TempDir
    Path dir;

    private static final long BIG = 1L << 30;

    private SmallObjectBlob open(RecordingMetrics m, boolean fsync) throws Exception {
        Path p = dir.resolve("small_g.sob");
        if (!java.nio.file.Files.exists(p)) SmallObjectBlob.create(p);
        return SmallObjectBlob.open("g", p, fsync, BIG, 16L << 20, m);
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private record Stored(SmallObjectBlob.Location loc, long seed, int size) {
    }

    @Test
    void concurrentAppendsShareForcesAndEveryRecordIsReadableAfterReopen() throws Exception {
        RecordingMetrics m = new RecordingMetrics();
        int threads = 32, perThread = 50;
        List<Stored> stored = Collections.synchronizedList(new ArrayList<>());
        try (SmallObjectBlob b = open(m, true)) {
            // a slow disk: every force takes a while, so appenders pile up behind the leader
            b.setBeforeForceHook(target -> {
                try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                fs.add(pool.submit(() -> {
                    Random r = new Random(tid);
                    for (int i = 0; i < perThread; i++) {
                        int size = 1 + r.nextInt(3000);
                        long seed = tid * 1000L + i;
                        SmallObjectBlob.Location l = b.append(bytes(size, seed), (int) seed);
                        assertNotNull(l);
                        // durable and published when append returns: readable at once
                        assertArrayEquals(bytes(size, seed), b.read(l.offset(), l.length(), l.crc32c()));
                        stored.add(new Stored(l, seed, size));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            int total = threads * perThread;
            assertEquals(total, stored.size());
            assertEquals(total, m.smallForcedRecords.get(), "every record was covered by exactly one force");
            assertTrue(m.smallForces.get() < total / 4,
                    "forces " + m.smallForces.get() + " for " + total + " appends: they must be grouped");
            assertEquals(total, b.stats().liveRecords());

            // records do not overlap and tile the log without holes
            List<Stored> sorted = new ArrayList<>(stored);
            sorted.sort((x, y) -> Long.compare(x.loc().offset(), y.loc().offset()));
            long expected = SmallBlobLayout.DATA_START;
            for (Stored s : sorted) {
                assertEquals(expected, s.loc().offset());
                expected += SmallBlobLayout.recordSpan(s.size());
            }
            assertEquals(expected, b.tail());
        }
        try (SmallObjectBlob b = open(m, true)) {
            assertEquals(threads * perThread, b.stats().liveRecords());
            for (Stored s : stored) {
                assertArrayEquals(bytes(s.size(), s.seed()), b.read(s.loc().offset(), s.loc().length(), s.loc().crc32c()));
            }
        }
    }

    @Test
    void theTailIsPublishedOnlyAfterTheForce() throws Exception {
        RecordingMetrics m = new RecordingMetrics();
        try (SmallObjectBlob b = open(m, true)) {
            long first = b.tail();
            CountDownLatch inForce = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            b.setBeforeForceHook(target -> {
                inForce.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            byte[] data = bytes(100, 1);
            ExecutorService ex = Executors.newSingleThreadExecutor();
            Future<SmallObjectBlob.Location> f = ex.submit(() -> b.append(data, 1));
            assertTrue(inForce.await(10, TimeUnit.SECONDS));
            // the record is written but not forced: not published, so a reader cannot see it
            assertEquals(first, b.tail());
            assertThrows(SmallObjectCorruptedException.class,
                    () -> b.read(first, data.length, org.example.sectoriadb.format.SmallBlobLayout.crc(data, 0, data.length)));
            assertFalse(f.isDone());
            release.countDown();
            SmallObjectBlob.Location l = f.get(10, TimeUnit.SECONDS);
            assertEquals(first, l.offset());
            assertEquals(first + SmallBlobLayout.recordSpan(100), b.tail());
            assertArrayEquals(data, b.read(l.offset(), l.length(), l.crc32c()));
            ex.shutdown();
        }
    }

    @Test
    void aFailedForceFailsEveryWaiterAndTurnsTheBlobReadOnlyWithoutLosingEarlierRecords() throws Exception {
        RecordingMetrics m = new RecordingMetrics();
        SmallObjectBlob.Location kept;
        try (SmallObjectBlob b = open(m, true)) {
            kept = b.append(bytes(500, 1), 1);
            AtomicInteger calls = new AtomicInteger();
            b.setBeforeForceHook(target -> {
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (calls.incrementAndGet() == 1) throw new IOException("injected: disk is gone");
            });
            int n = 8;
            ExecutorService ex = Executors.newFixedThreadPool(n);
            List<Future<SmallObjectBlob.Location>> fs = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < n; i++) {
                final int k = i;
                fs.add(ex.submit(() -> {
                    go.await();
                    return b.append(bytes(200, 100 + k), k);
                }));
            }
            go.countDown();
            int failed = 0, nulls = 0;
            for (Future<SmallObjectBlob.Location> f : fs) {
                try {
                    if (f.get(20, TimeUnit.SECONDS) == null) nulls++;
                } catch (ExecutionException e) {
                    assertInstanceOf(IOException.class, e.getCause());
                    failed++;
                }
            }
            ex.shutdown();
            assertTrue(failed >= 1, "at least the appenders covered by the failed force see the error");
            assertEquals(n, failed + nulls, "no append silently succeeded: the rest found the blob read-only (null)");
            assertFalse(b.isWritable());
            assertNull(b.append(bytes(10, 5), 5), "read-only now: the caller picks another blob");
            assertArrayEquals(bytes(500, 1), b.read(kept.offset(), kept.length(), kept.crc32c()));
        }
        try (SmallObjectBlob b = open(m, true)) {
            assertEquals(1, b.stats().liveRecords(), "the dropped records are not in the log after a restart");
            assertTrue(b.isWritable());
            assertArrayEquals(bytes(500, 1), b.read(kept.offset(), kept.length(), kept.crc32c()));
        }
    }

    @Test
    void crashAtAnyPointKeepsExactlyTheCompleteRecordsBeforeIt() throws Exception {
        RecordingMetrics m = new RecordingMetrics();
        List<Stored> stored = Collections.synchronizedList(new ArrayList<>());
        Path p = dir.resolve("small_g.sob");
        try (SmallObjectBlob b = open(m, true)) {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final int tid = t;
                fs.add(pool.submit(() -> {
                    Random r = new Random(tid + 500);
                    for (int i = 0; i < 25; i++) {
                        int size = 1 + r.nextInt(700);
                        long seed = tid * 100L + i;
                        stored.add(new Stored(b.append(bytes(size, seed), 0), seed, size));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();
        }
        byte[] full = java.nio.file.Files.readAllBytes(p);
        List<Stored> sorted = new ArrayList<>(stored);
        sorted.sort((x, y) -> Long.compare(x.loc().offset(), y.loc().offset()));
        assertEquals(400, sorted.size());

        Random cuts = new Random(77);
        for (int round = 0; round < 12; round++) {
            // a "crash" leaves a prefix of the file; the checkpoint slots are wiped like after a dirty shutdown
            long cut = SmallBlobLayout.DATA_START + (long) (cuts.nextDouble() * (full.length - SmallBlobLayout.DATA_START));
            Path crashed = dir.resolve("crash_" + round + ".sob");
            java.nio.file.Files.write(crashed, java.util.Arrays.copyOf(full, (int) cut));
            try (RandomAccessFile f = new RandomAccessFile(crashed.toFile(), "rw")) {
                f.seek(SmallBlobLayout.CHECKPOINT_SLOT_A);
                f.write(new byte[2 * SmallBlobLayout.CHECKPOINT_SLOT_SIZE]);
            }
            long expectedTail = SmallBlobLayout.DATA_START;
            int expectedRecords = 0;
            for (Stored s : sorted) {
                long end = s.loc().offset() + SmallBlobLayout.recordSpan(s.size());
                if (end <= cut) {
                    expectedTail = end;
                    expectedRecords++;
                }
            }
            try (SmallObjectBlob b = SmallObjectBlob.open("c" + round, crashed, true, BIG, 16L << 20, m)) {
                assertEquals(expectedTail, b.tail(), "recovered tail at cut " + cut);
                assertEquals(expectedRecords, b.stats().liveRecords());
                assertTrue(b.isWritable());
                for (Stored s : sorted) {
                    if (s.loc().offset() + SmallBlobLayout.recordSpan(s.size()) <= cut) {
                        assertArrayEquals(bytes(s.size(), s.seed()),
                                b.read(s.loc().offset(), s.loc().length(), s.loc().crc32c()));
                    }
                }
                // appends continue exactly where the log ended
                SmallObjectBlob.Location l = b.append(bytes(10, 9), 9);
                assertEquals(expectedTail, l.offset());
            }
        }
    }

    @Test
    void withoutFsyncTheTailIsPublishedImmediately() throws Exception {
        RecordingMetrics m = new RecordingMetrics();
        try (SmallObjectBlob b = open(m, false)) {
            SmallObjectBlob.Location l = b.append(bytes(64, 3), 3);
            assertEquals(l.offset() + SmallBlobLayout.recordSpan(64), b.tail());
            assertEquals(0, m.smallForces.get());
        }
    }
}
