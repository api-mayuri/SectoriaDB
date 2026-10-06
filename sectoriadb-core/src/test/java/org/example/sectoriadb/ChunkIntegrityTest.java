package org.example.sectoriadb;

import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.format.InvalidBlobHeaderException;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.ChunkLocation;
import org.example.sectoriadb.model.CuckooTable;
import org.example.sectoriadb.service.impl.ChunkCorruptedException;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.InsertResult;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.tools.BytesHasher;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 03: header, per-slot CRC32C, quarantine, collision re-keying, fill factor. */
class ChunkIntegrityTest {

    private static final int CHUNK = 64;

    @TempDir
    Path tmp;

    private final BytesHasher xxh = new XxHash64BytesHasher();

    private CuckooHashTable newTable(String name, int buckets, BytesHasher hasher) throws IOException {
        Path p = tmp.resolve(name);
        BlobLayout.createFile(p, buckets, CHUNK);
        return open(p, buckets, hasher);
    }

    private CuckooHashTable open(Path p, int buckets, BytesHasher hasher) throws IOException {
        return new CuckooHashTable(new BlobFile("t", p, Files.size(p)),
                new FileChannelStorageIOEngine(false), hasher, buckets, CHUNK, 32);
    }

    private static byte[] bytes(long seed, int len) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] read(CuckooHashTable t, long key, int len) throws IOException {
        ByteBuffer b = t.readChunkByKey(key, len).orElseThrow(() -> new AssertionError("missing " + key));
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    private static long dataOffset(int buckets, ChunkLocation loc) {
        long perTable = (long) buckets * CuckooHashTable.SLOTS_PER_BUCKET;
        long inTable = (long) loc.bucketIndex() * CuckooHashTable.SLOTS_PER_BUCKET + loc.slotIndex();
        long base = BlobLayout.tableAOffset(buckets) + (loc.table() == CuckooTable.A ? 0 : perTable * CHUNK);
        return base + inTable * CHUNK;
    }

    private static int flatIndex(int buckets, ChunkLocation loc) {
        int perTable = buckets * CuckooHashTable.SLOTS_PER_BUCKET;
        return (loc.table() == CuckooTable.A ? 0 : perTable)
                + loc.bucketIndex() * CuckooHashTable.SLOTS_PER_BUCKET + loc.slotIndex();
    }

    private static void patch(Path p, long offset, byte[] data) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(offset);
            f.write(data);
        }
    }

    private static void flipByte(Path p, long offset) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(offset);
            int b = f.read();
            f.seek(offset);
            f.write(b ^ 0x5A);
        }
    }

    // ── fill factor ──────────────────────────────────────────────────────────

    private double fillUntilFull(int buckets) throws IOException {
        CuckooHashTable t = newTable("fill-" + buckets + ".raw", buckets, xxh);
        Random rng = new Random(buckets);
        int slots = 2 * buckets * CuckooHashTable.SLOTS_PER_BUCKET;
        try {
            for (int i = 0; i < slots * 2; i++) {
                long key = rng.nextLong();
                t.insert(key, ByteBuffer.wrap(bytes(key, 16)));
            }
            fail("table never reported full");
        } catch (TableFullException expected) {
            // measured below
        }
        double fill = t.getFillStats().fillPercent();
        System.out.printf("fill factor: %d buckets -> %.2f%%%n", buckets, fill);
        return fill;
    }

    /** Measured: ~98% for every geometry below; asserted bound leaves room for seed variance. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1000, 1024, 1280, 8192})
    void fillFactor_reachesAtLeast95Percent_beforeTableFull(int buckets) throws IOException {
        assertTrue(fillUntilFull(buckets) >= 95.0);
    }

    // ── read-time verification ───────────────────────────────────────────────

    @Test
    void flippedByteInStoredChunk_readThrowsChunkCorrupted_andScrubFindsIt() throws IOException {
        int buckets = 16;
        Path p = tmp.resolve("corrupt.raw");
        BlobLayout.createFile(p, buckets, CHUNK);
        CuckooHashTable t = open(p, buckets, xxh);
        byte[] a = bytes(1, CHUNK), b = bytes(2, CHUNK);
        long ka = xxh.hash64(a), kb = xxh.hash64(b);
        ChunkLocation locA = t.insert(ka, ByteBuffer.wrap(a)).location();
        t.insert(kb, ByteBuffer.wrap(b));

        flipByte(p, dataOffset(buckets, locA) + 10);

        ChunkCorruptedException e = assertThrows(ChunkCorruptedException.class, () -> t.readChunkByKey(ka, CHUNK));
        assertEquals(ka, e.getKey());
        assertEquals("t", e.getBlobId());
        assertArrayEquals(b, read(t, kb, CHUNK), "other chunks stay readable");

        CuckooHashTable.ScrubReport r = t.scrub();
        assertEquals(2, r.activeSlots());
        assertEquals(1, r.ok());
        assertEquals(1, r.corrupt());
        assertEquals(0, r.quarantined());

        assertThrows(ChunkCorruptedException.class, () -> t.forEachActiveChunk((k, d) -> { }));
    }

    @Test
    void identicalReinsertHealsCorruptedChunk() throws IOException {
        int buckets = 16;
        Path p = tmp.resolve("heal.raw");
        BlobLayout.createFile(p, buckets, CHUNK);
        CuckooHashTable t = open(p, buckets, xxh);
        byte[] a = bytes(1, CHUNK);
        long ka = xxh.hash64(a);
        ChunkLocation loc = t.insert(ka, ByteBuffer.wrap(a)).location();
        flipByte(p, dataOffset(buckets, loc));

        InsertResult r = t.insert(ka, ByteBuffer.wrap(a));
        assertTrue(r.deduplicated());
        assertArrayEquals(a, read(t, ka, CHUNK));
    }

    @Test
    void shortChunk_roundTrips_andOverlongRequestIsRejected() throws IOException {
        CuckooHashTable t = newTable("short.raw", 16, xxh);
        byte[] small = bytes(5, 10);
        long k = xxh.hash64(small);
        t.insert(k, ByteBuffer.wrap(small));
        assertArrayEquals(small, read(t, k, 10));
        assertArrayEquals(java.util.Arrays.copyOf(small, 4), read(t, k, 4));
        assertThrows(ChunkCorruptedException.class, () -> t.readChunkByKey(k, 11));
        assertThrows(IllegalArgumentException.class, () -> t.insert(1L, ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class, () -> t.insert(1L, ByteBuffer.allocate(CHUNK + 1)));
    }

    // ── torn / damaged meta entries ──────────────────────────────────────────

    @Test
    void tornMetaEntry_isQuarantined_notReturned_andNeverOverwritten() throws IOException {
        int buckets = 8; // 64 slots: inserts below will use all kinds of slots
        Path p = tmp.resolve("torn.raw");
        BlobLayout.createFile(p, buckets, CHUNK);
        CuckooHashTable t = open(p, buckets, xxh);

        Map<Long, byte[]> stored = new LinkedHashMap<>();
        Map<Long, ChunkLocation> locs = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            byte[] d = bytes(100 + i, CHUNK);
            long k = xxh.hash64(d);
            locs.put(k, t.insert(k, ByteBuffer.wrap(d)).location());
            stored.put(k, d);
        }
        long victim = stored.keySet().iterator().next();
        ChunkLocation vloc = locs.get(victim);
        int victimSlot = flatIndex(buckets, vloc);
        long metaOffset = BlobLayout.HEADER_SIZE + (long) victimSlot * BlobLayout.META_ENTRY_BYTES;
        long dataOff = dataOffset(buckets, vloc);

        // a power cut that persisted only the first half of the new entry: second half is garbage
        patch(p, metaOffset + 16, new byte[16]);
        byte[] dataBefore = new byte[CHUNK];
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            f.seek(dataOff);
            f.readFully(dataBefore);
        }

        CuckooHashTable r = open(p, buckets, xxh);
        r.loadMetadataFromDisk();
        assertTrue(r.lookup(victim).isEmpty(), "quarantined slot must not be served");
        assertTrue(r.readChunkByKey(victim, CHUNK).isEmpty());
        CuckooHashTable.FillStats st = r.getFillStats();
        assertEquals(1, st.quarantinedSlots());
        assertEquals(stored.size() - 1, st.activeSlots());
        assertEquals(1, r.scrub().quarantined());

        // keep inserting: the quarantined slot must never be reused
        Random rng = new Random(9);
        for (int i = 0; i < 40; i++) {
            long k = rng.nextLong();
            try {
                r.insert(k, ByteBuffer.wrap(bytes(k, CHUNK)));
            } catch (TableFullException ignored) {
                break;
            }
        }
        byte[] dataAfter = new byte[CHUNK];
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            f.seek(dataOff);
            f.readFully(dataAfter);
        }
        assertArrayEquals(dataBefore, dataAfter, "data of a quarantined slot must not be overwritten");
        assertEquals(1, r.getFillStats().quarantinedSlots());
        for (Map.Entry<Long, byte[]> e : stored.entrySet()) {
            if (e.getKey() != victim) {
                assertArrayEquals(e.getValue(), read(r, e.getKey(), CHUNK));
            }
        }
        // a second reload re-detects the same damage (it was never "healed" silently)
        CuckooHashTable r2 = open(p, buckets, xxh);
        r2.loadMetadataFromDisk();
        assertEquals(1, r2.getFillStats().quarantinedSlots());
    }

    @Test
    void metaEntries_neverStraddleSectorBoundaries() {
        assertEquals(0, BlobLayout.HEADER_SIZE % 512);
        assertEquals(0, 512 % BlobLayout.META_ENTRY_BYTES);
        assertEquals(0, BlobLayout.tableAOffset(1000) % 4096);
    }

    // ── header ───────────────────────────────────────────────────────────────

    @Test
    void headerMismatch_failsLoudly() throws IOException {
        Path p = tmp.resolve("hdr.raw");
        BlobLayout.createFile(p, 16, CHUNK);
        // wrong geometry
        CuckooHashTable wrong = new CuckooHashTable(new BlobFile("t", p, Files.size(p)),
                new FileChannelStorageIOEngine(false), xxh, 16, CHUNK * 2, 32);
        assertThrows(InvalidBlobHeaderException.class, wrong::loadMetadataFromDisk);

        // flipped header byte -> CRC failure
        flipByte(p, 13);
        assertThrows(InvalidBlobHeaderException.class, () -> open(p, 16, xxh).loadMetadataFromDisk());
    }

    @Test
    void unknownFormatVersionAndLegacyBlobs_areRejected() throws IOException {
        Path p = tmp.resolve("ver.raw");
        BlobLayout.createFile(p, 16, CHUNK);
        ByteBuffer h = BlobLayout.encodeHeader(16, CHUNK, 0);
        h.putInt(8, 99);
        CRC32C crc = new CRC32C();
        crc.update(h.array(), 0, BlobLayout.HEADER_SIZE - 4);
        h.putInt(BlobLayout.HEADER_SIZE - 4, (int) crc.getValue());
        patch(p, 0, h.array());
        InvalidBlobHeaderException e = assertThrows(InvalidBlobHeaderException.class,
                () -> open(p, 16, xxh).loadMetadataFromDisk());
        assertTrue(e.getMessage().contains("version 99"), e.getMessage());

        Path legacy = tmp.resolve("legacy.raw"); // no header: zeros
        Files.write(legacy, new byte[(int) BlobLayout.requiredSize(16, CHUNK)]);
        assertThrows(InvalidBlobHeaderException.class, () -> open(legacy, 16, xxh).loadMetadataFromDisk());
    }

    // ── collisions ───────────────────────────────────────────────────────────

    /** Hasher whose seed-0 key (the primary key) is constant: every chunk collides; salted seeds are real hashes. */
    private static class CollidingHasher implements BytesHasher {
        private final XxHash64BytesHasher real = new XxHash64BytesHasher();
        private final boolean saltedAlsoCollide;
        CollidingHasher(boolean saltedAlsoCollide) { this.saltedAlsoCollide = saltedAlsoCollide; }
        @Override public long hash64(ByteBuffer buffer, long seed) {
            return (seed == 0 || saltedAlsoCollide) ? 42L : real.hash64(buffer, seed);
        }
    }

    @Test
    void realHashCollision_isRekeyed_notSilentlyDeduplicated() throws IOException {
        CollidingHasher hasher = new CollidingHasher(false);
        Path p = tmp.resolve("coll.raw");
        BlobLayout.createFile(p, 16, CHUNK);
        CuckooHashTable t = open(p, 16, hasher);
        byte[] a = bytes(1, CHUNK), b = bytes(2, CHUNK), c = bytes(3, 20);

        InsertResult ra = t.insert(hasher.hash64(a), ByteBuffer.wrap(a));
        InsertResult rb = t.insert(hasher.hash64(b), ByteBuffer.wrap(b));
        InsertResult rc = t.insert(hasher.hash64(c), ByteBuffer.wrap(c));
        assertEquals(42L, ra.key());
        assertNotEquals(42L, rb.key(), "different bytes under the same key must get a new key");
        assertNotEquals(42L, rc.key());
        assertNotEquals(rb.key(), rc.key());
        assertFalse(rb.deduplicated());

        // true duplicates still dedup, including the re-keyed one
        assertTrue(t.insert(42L, ByteBuffer.wrap(a)).deduplicated());
        InsertResult rb2 = t.insert(42L, ByteBuffer.wrap(b));
        assertTrue(rb2.deduplicated());
        assertEquals(rb.key(), rb2.key());

        assertArrayEquals(a, read(t, 42L, CHUNK));
        assertArrayEquals(b, read(t, rb.key(), CHUNK));
        assertArrayEquals(c, read(t, rc.key(), 20));
        assertEquals(3, t.getFillStats().activeSlots());

        CuckooHashTable reloaded = open(p, 16, hasher);
        reloaded.loadMetadataFromDisk();
        assertArrayEquals(b, read(reloaded, rb.key(), CHUNK));
    }

    @Test
    void collisionOnEveryAttempt_failsInsteadOfCorrupting() throws IOException {
        CollidingHasher hasher = new CollidingHasher(true);
        CuckooHashTable t = newTable("coll8.raw", 16, hasher);
        byte[] a = bytes(1, CHUNK), b = bytes(2, CHUNK);
        t.insert(42L, ByteBuffer.wrap(a));
        assertThrows(IOException.class, () -> t.insert(42L, ByteBuffer.wrap(b)));
        assertArrayEquals(a, read(t, 42L, CHUNK));
        assertEquals(1, t.getFillStats().activeSlots());
    }

    @Test
    void insertPreservingKey_refusesToRekey() throws IOException {
        CuckooHashTable t = newTable("keep.raw", 16, xxh);
        t.insertPreservingKey(7L, ByteBuffer.wrap(bytes(1, CHUNK)));
        assertTrue(t.insertPreservingKey(7L, ByteBuffer.wrap(bytes(1, CHUNK))).deduplicated());
        assertThrows(IOException.class, () -> t.insertPreservingKey(7L, ByteBuffer.wrap(bytes(2, CHUNK))));
    }
}
