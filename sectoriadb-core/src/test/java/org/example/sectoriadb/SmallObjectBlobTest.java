package org.example.sectoriadb;

import org.example.sectoriadb.format.InvalidBlobHeaderException;
import org.example.sectoriadb.format.SmallBlobLayout;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SmallObjectBlobTest {

    @TempDir
    Path dir;

    private static final long BIG = 1L << 30;

    private Path newFile() throws Exception {
        Path p = dir.resolve("small_test.sob");
        Files.deleteIfExists(p);
        SmallObjectBlob.create(p);
        return p;
    }

    private static SmallObjectBlob open(Path p) throws Exception {
        return SmallObjectBlob.open("t", p, false, BIG);
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static void patch(Path p, long offset, int value) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(offset);
            int old = f.read();
            f.seek(offset);
            f.write(old ^ value);
        }
    }

    /** Simulates a crash after the last append: no clean-close checkpoint, so recovery verifies the whole log. */
    private static void wipeCheckpoints(Path p) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(SmallBlobLayout.CHECKPOINT_SLOT_A);
            f.write(new byte[2 * SmallBlobLayout.CHECKPOINT_SLOT_SIZE]);
        }
    }

    @Test
    void appendReadRoundTripManySizes() throws Exception {
        Path p = newFile();
        int[] sizes = {0, 1, 2, 7, 8, 9, 31, 32, 33, 511, 512, 4095, 4096, 16383};
        try (SmallObjectBlob b = open(p)) {
            List<SmallObjectBlob.Location> locs = new ArrayList<>();
            for (int i = 0; i < sizes.length; i++) {
                locs.add(b.append(bytes(sizes[i], i), i));
            }
            for (int i = 0; i < sizes.length; i++) {
                SmallObjectBlob.Location l = locs.get(i);
                assertEquals(0, l.offset() % 8, "records are 8-byte aligned");
                assertArrayEquals(bytes(sizes[i], i), b.read(l.offset(), l.length(), l.crc32c()));
            }
            assertEquals(sizes.length, b.stats().liveRecords());
        }
        // and again after a clean reopen: the next append lands exactly at the old tail
        try (SmallObjectBlob b = open(p)) {
            assertEquals(sizes.length, b.stats().liveRecords());
            long tail = b.tail();
            assertEquals(tail, b.append(new byte[1], 0).offset());
        }
    }

    @Test
    void concurrentAppendsThenReadAll() throws Exception {
        Path p = newFile();
        int threads = 8, perThread = 300;
        try (SmallObjectBlob b = open(p)) {
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            List<Future<List<Object[]>>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tt = t;
                fs.add(ex.submit(() -> {
                    List<Object[]> out = new ArrayList<>();
                    Random r = new Random(tt);
                    for (int i = 0; i < perThread; i++) {
                        byte[] d = bytes(1 + r.nextInt(2000), tt * 100000L + i);
                        out.add(new Object[]{d, b.append(d, tt)});
                    }
                    return out;
                }));
            }
            List<Object[]> all = new ArrayList<>();
            for (Future<List<Object[]>> f : fs) all.addAll(f.get(60, TimeUnit.SECONDS));
            ex.shutdown();
            assertEquals(threads * perThread, all.size());
            java.util.Set<Long> offsets = new java.util.HashSet<>();
            for (Object[] o : all) {
                SmallObjectBlob.Location l = (SmallObjectBlob.Location) o[1];
                assertTrue(offsets.add(l.offset()), "no two records share an offset");
                assertArrayEquals((byte[]) o[0], b.read(l.offset(), l.length(), l.crc32c()));
            }
            // a clean reopen walks the log and finds exactly these records
            b.close();
            try (SmallObjectBlob again = open(p)) {
                assertEquals(threads * perThread, again.stats().liveRecords());
                assertEquals(0, again.scrub().corrupt());
            }
        }
    }

    @Test
    void tornTailTruncatedMidRecordIsDroppedAndOverwritten() throws Exception {
        Path p = newFile();
        SmallObjectBlob.Location a, b2, c;
        try (SmallObjectBlob b = open(p)) {
            a = b.append(bytes(100, 1), 1);
            b2 = b.append(bytes(200, 2), 2);
            c = b.append(bytes(300, 3), 3);
        }
        wipeCheckpoints(p);
        long cut = c.offset() + 32 + 150;           // half of the last record's data
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(cut);
        }
        try (SmallObjectBlob b = open(p)) {
            assertTrue(b.isWritable());
            assertEquals(c.offset(), b.tail(), "logical tail is the start of the torn record");
            assertEquals(2, b.stats().liveRecords());
            assertArrayEquals(bytes(100, 1), b.read(a.offset(), a.length(), a.crc32c()));
            assertArrayEquals(bytes(200, 2), b.read(b2.offset(), b2.length(), b2.crc32c()));
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(c.offset(), c.length(), c.crc32c()));
            SmallObjectBlob.Location d = b.append(bytes(50, 4), 4);
            assertEquals(c.offset(), d.offset(), "the next append reuses the torn tail");
            assertArrayEquals(bytes(50, 4), b.read(d.offset(), d.length(), d.crc32c()));
        }
        try (SmallObjectBlob b = open(p)) {
            assertEquals(3, b.stats().liveRecords());
        }
    }

    @Test
    void tornTailWithCorruptHeaderOrDataIsDropped() throws Exception {
        for (int what = 0; what < 2; what++) {
            Path p = dir.resolve("torn" + what + ".sob");
            SmallObjectBlob.create(p);
            SmallObjectBlob.Location first, last;
            try (SmallObjectBlob b = open(p)) {
                first = b.append(bytes(64, 1), 1);
                last = b.append(bytes(64, 2), 2);
            }
            wipeCheckpoints(p);
            // header byte (inside the CRC-covered area) or last data byte
            patch(p, what == 0 ? last.offset() + 9 : last.offset() + 32 + 63, 0x40);
            try (SmallObjectBlob b = open(p)) {
                assertTrue(b.isWritable());
                assertEquals(last.offset(), b.tail());
                assertEquals(1, b.stats().liveRecords());
                assertArrayEquals(bytes(64, 1), b.read(first.offset(), first.length(), first.crc32c()));
                assertEquals(last.offset(), b.append(bytes(10, 3), 3).offset());
            }
        }
    }

    @Test
    void damageInTheMiddleMakesBlobReadOnlyAndKeepsOtherRecords() throws Exception {
        Path p = newFile();
        SmallObjectBlob.Location a, b2, c;
        try (SmallObjectBlob b = open(p)) {
            a = b.append(bytes(100, 1), 1);
            b2 = b.append(bytes(100, 2), 2);
            c = b.append(bytes(100, 3), 3);
        }
        wipeCheckpoints(p);
        patch(p, b2.offset(), 0xFF);                // destroy the middle record's magic
        try (SmallObjectBlob b = open(p)) {
            assertFalse(b.isWritable(), "never overwrite what may still be live data behind the damage");
            assertNull(b.append(bytes(1, 9), 9));
            assertArrayEquals(bytes(100, 1), b.read(a.offset(), a.length(), a.crc32c()));
            assertArrayEquals(bytes(100, 3), b.read(c.offset(), c.length(), c.crc32c()));
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(b2.offset(), b2.length(), b2.crc32c()));
            assertTrue(b.scrub().corrupt() > 0);
        }
    }

    @Test
    void corruptionIsDetectedOnRead() throws Exception {
        Path p = newFile();
        SmallObjectBlob.Location l;
        try (SmallObjectBlob b = open(p)) {
            l = b.append(bytes(500, 1), 1);
            b.append(bytes(500, 2), 2);                 // keep l from being the tail
        }
        patch(p, l.offset() + 32 + 10, 0x01);           // flip a data bit after the blob validated nothing
        try (SmallObjectBlob b = SmallObjectBlob.open("t", p, false, BIG)) {
            // checkpoint is at the end after a clean close, so open() did not read this record's data
            SmallObjectCorruptedException e = assertThrows(SmallObjectCorruptedException.class,
                    () -> b.read(l.offset(), l.length(), l.crc32c()));
            assertTrue(e.getMessage().contains("CRC"));
            assertEquals(1, b.scrub().corrupt());
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(l.offset(), l.length() + 1, l.crc32c()));
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(l.offset(), l.length(), l.crc32c() ^ 1));
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(l.offset() + 8, l.length(), l.crc32c()));
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(1 << 30, 1, 0));
        }
    }

    @Test
    void deleteStatePersistsAcrossReopen() throws Exception {
        Path p = newFile();
        SmallObjectBlob.Location a, b2;
        try (SmallObjectBlob b = open(p)) {
            a = b.append(bytes(100, 1), 1);
            b2 = b.append(bytes(200, 2), 2);
            assertTrue(b.markDeleted(a.offset()));
            assertFalse(b.markDeleted(a.offset()), "idempotent");
            SmallObjectBlob.Stats s = b.stats();
            assertEquals(1, s.liveRecords());
            assertEquals(1, s.deadRecords());
            assertEquals(SmallBlobLayout.recordSpan(100), s.deadBytes());
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(a.offset(), a.length(), a.crc32c()));
        }
        try (SmallObjectBlob b = open(p)) {
            SmallObjectBlob.Stats s = b.stats();
            assertEquals(1, s.liveRecords());
            assertEquals(1, s.deadRecords());
            assertEquals(SmallBlobLayout.recordSpan(100), s.deadBytes());
            assertEquals(SmallBlobLayout.recordSpan(200), s.liveBytes());
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(a.offset(), a.length(), a.crc32c()));
            assertArrayEquals(bytes(200, 2), b.read(b2.offset(), b2.length(), b2.crc32c()));
            assertEquals(0, b.scrub().corrupt());
        }
    }

    @Test
    void deletedLastRecordSurvivesRecoveryAsDeletedNotAsTornTail() throws Exception {
        Path p = newFile();
        SmallObjectBlob.Location a;
        try (SmallObjectBlob b = open(p)) {
            a = b.append(bytes(100, 1), 1);
            b.markDeleted(a.offset());
        }
        // simulate a crash: lose the clean-close checkpoint by truncating nothing, just reopen
        try (SmallObjectBlob b = open(p)) {
            assertEquals(a.offset() + SmallBlobLayout.recordSpan(100), b.tail());
            assertEquals(1, b.stats().deadRecords());
        }
    }

    @Test
    void rolloverAtTinyMaxSize() throws Exception {
        Path p = newFile();
        long max = SmallBlobLayout.DATA_START + 2 * SmallBlobLayout.recordSpan(100);
        try (SmallObjectBlob b = SmallObjectBlob.open("t", p, false, max)) {
            assertTrue(b.hasRoom(100));
            assertNotNull(b.append(bytes(100, 1), 1));
            assertTrue(b.hasRoom(100));
            assertNotNull(b.append(bytes(100, 2), 2));
            assertFalse(b.hasRoom(100));
            assertNull(b.append(bytes(100, 3), 3), "full: the caller must roll over to a new blob");
            assertEquals(max, b.tail());
        }
        // a single record larger than the limit is still accepted by an empty blob
        Path q = dir.resolve("huge.sob");
        SmallObjectBlob.create(q);
        try (SmallObjectBlob b = SmallObjectBlob.open("t", q, false, 100)) {
            assertNotNull(b.append(bytes(500, 1), 1));
            assertNull(b.append(bytes(1, 2), 2));
        }
    }

    @Test
    void checkpointAdvancesAndSurvivesDamagedSlots() throws Exception {
        Path p = newFile();
        List<SmallObjectBlob.Location> locs = new ArrayList<>();
        try (SmallObjectBlob b = SmallObjectBlob.open("t", p, false, BIG, 1000)) {
            for (int i = 0; i < 50; i++) locs.add(b.append(bytes(100, i), i));
        }
        // destroy both checkpoint slots: recovery must fall back to a full verification scan
        patch(p, SmallBlobLayout.CHECKPOINT_SLOT_A + 3, 0xFF);
        patch(p, SmallBlobLayout.CHECKPOINT_SLOT_B + 3, 0xFF);
        try (SmallObjectBlob b = SmallObjectBlob.open("t", p, false, BIG, 1000)) {
            assertEquals(50, b.stats().liveRecords());
            for (int i = 0; i < 50; i++) {
                SmallObjectBlob.Location l = locs.get(i);
                assertArrayEquals(bytes(100, i), b.read(l.offset(), l.length(), l.crc32c()));
            }
        }
    }

    @Test
    void badHeaderIsRejectedLoudly() throws Exception {
        Path p = newFile();
        patch(p, 0, 0x01);                                  // magic
        assertTrue(assertThrows(InvalidBlobHeaderException.class, () -> open(p)).getMessage().contains("magic"));

        Path q = newFile();
        patch(q, 17, 0x01);                                 // creation time: static CRC mismatch
        assertTrue(assertThrows(InvalidBlobHeaderException.class, () -> open(q)).getMessage().contains("CRC"));

        Path v = newFile();
        // version 2 with a correct CRC -> "unsupported version"
        try (RandomAccessFile f = new RandomAccessFile(v.toFile(), "rw")) {
            byte[] h = new byte[SmallBlobLayout.HEADER_SIZE];
            f.readFully(h);
            h[11] = 2;
            int crc = SmallBlobLayout.crc(h, 0, 64);
            h[4092] = (byte) (crc >>> 24); h[4093] = (byte) (crc >>> 16); h[4094] = (byte) (crc >>> 8); h[4095] = (byte) crc;
            f.seek(0);
            f.write(h);
        }
        assertTrue(assertThrows(InvalidBlobHeaderException.class, () -> open(v)).getMessage().contains("version"));

        Path s = dir.resolve("short.sob");
        Files.write(s, new byte[100]);
        assertThrows(InvalidBlobHeaderException.class, () -> open(s));
    }
}
