package org.example.sectoriadb;

import org.example.sectoriadb.format.BlobLayout;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.metrics.StorageMetrics.CrcKind;
import org.example.sectoriadb.metrics.StorageMetrics.Target;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.model.ChunkLocation;
import org.example.sectoriadb.model.CuckooTable;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.example.sectoriadb.service.impl.TableFullException;
import org.example.sectoriadb.service.impl.ChunkCorruptedException;
import org.example.sectoriadb.tools.XxHash64BytesHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The engine reports its events through the StorageMetrics SPI (recording stub, no Micrometer in core). */
class EngineMetricsTest {

    private static final int CHUNK = 64;

    @TempDir
    Path tmp;

    private final RecordingMetrics m = new RecordingMetrics();

    private CuckooHashTable table(String name, int buckets, int maxEvictions, boolean fsync) throws IOException {
        Path p = tmp.resolve(name);
        BlobLayout.createFile(p, buckets, CHUNK);
        return new CuckooHashTable(new BlobFile("t", p, Files.size(p)),
                new FileChannelStorageIOEngine(fsync, m), new XxHash64BytesHasher(), buckets, CHUNK, maxEvictions, m);
    }

    private static byte[] bytes(long seed, int len) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void insertReportsFsyncWriteAndEvictionPath() throws IOException {
        CuckooHashTable t = table("a.raw", 8, 16, true);
        t.insert(1L, ByteBuffer.wrap(bytes(1, CHUNK)));
        assertEquals(0, m.get(m.fsyncs, Target.CUCKOO), "a new chunk is forced by the barrier, not by the insert");
        t.barrier();
        assertEquals(1, m.get(m.fsyncs, Target.CUCKOO), "one fsync covers data and meta");
        assertTrue(m.get(m.writes, Target.CUCKOO) >= 2);
        assertEquals(CHUNK + 32, m.get(m.writeBytes, Target.CUCKOO), "slot meta entry (32 B) + chunk data");
        assertEquals(1, m.evictionPaths.size());
        assertEquals(0, m.evictionPaths.get(0), "an empty table needs no evictions");
        assertEquals(1, m.cuckooLockWaits.get());
    }

    @Test
    void noFsyncEventsWhenFsyncIsOff() throws IOException {
        CuckooHashTable t = table("b.raw", 8, 16, false);
        t.insert(1L, ByteBuffer.wrap(bytes(1, CHUNK)));
        assertEquals(0, m.get(m.fsyncs, Target.CUCKOO));
    }

    @Test
    void fullTableReportsLongerPathsAndTableFull() throws IOException {
        CuckooHashTable t = table("c.raw", 4, 6, false);
        Random rng = new Random(7);
        boolean full = false;
        for (int i = 0; i < 400 && !full; i++) {
            long key = rng.nextLong();
            try {
                t.insert(key, ByteBuffer.wrap(bytes(key, CHUNK)));
            } catch (TableFullException e) {
                full = true;
            }
        }
        assertTrue(full);
        assertEquals(1, m.tableFull.get());
        assertTrue(m.evictionPaths.stream().anyMatch(p -> p > 0), "a nearly full table must evict");
        assertTrue(m.evictionPaths.stream().allMatch(p -> p >= 0 && p <= 6));
    }

    @Test
    void identicalChunkIsADedupHit() throws IOException {
        CuckooHashTable t = table("d.raw", 8, 16, false);
        byte[] data = bytes(3, CHUNK);
        t.insert(42L, ByteBuffer.wrap(data));
        assertEquals(0, m.dedupHits.get());
        t.insert(42L, ByteBuffer.wrap(data));
        assertEquals(1, m.dedupHits.get());
        assertEquals(1, m.evictionPaths.size(), "the dedup hit does not search a path");
    }

    @Test
    void differentChunkUnderTheSameKeyIsRekeyed() throws IOException {
        CuckooHashTable t = table("e.raw", 8, 16, false);
        t.insert(42L, ByteBuffer.wrap(bytes(3, CHUNK)));
        t.insert(42L, ByteBuffer.wrap(bytes(4, CHUNK)));
        assertEquals(1, m.rekeys.get());
    }

    @Test
    void corruptedChunkCountsAChunkCrcFailure() throws IOException {
        CuckooHashTable t = table("f.raw", 8, 16, false);
        long key = 9L;
        var res = t.insert(key, ByteBuffer.wrap(bytes(9, CHUNK)));
        ChunkLocation loc = res.location();
        long perTable = 8L * CuckooHashTable.SLOTS_PER_BUCKET;
        long inTable = (long) loc.bucketIndex() * CuckooHashTable.SLOTS_PER_BUCKET + loc.slotIndex();
        long base = BlobLayout.tableAOffset(8) + (loc.table() == CuckooTable.A ? 0 : perTable * CHUNK);
        try (RandomAccessFile f = new RandomAccessFile(tmp.resolve("f.raw").toFile(), "rw")) {
            f.seek(base + inTable * CHUNK);
            int b = f.read();
            f.seek(base + inTable * CHUNK);
            f.write(b ^ 0x5A);
        }
        assertThrows(ChunkCorruptedException.class, () -> t.readChunkByKey(key, CHUNK));
        assertEquals(1, m.get(m.crcFailures, CrcKind.CHUNK));
        assertEquals(0, m.get(m.crcFailures, CrcKind.SMALL_RECORD));
    }

    @Test
    void fillStatsStayInStepWithInsertsAndReload() throws IOException {
        CuckooHashTable t = table("g.raw", 8, 16, false);
        for (int i = 1; i <= 10; i++) t.insert(i * 7919L, ByteBuffer.wrap(bytes(i, CHUNK)));
        assertEquals(10, t.getFillStats().activeSlots());
        assertEquals(2 * 8 * 4, t.getFillStats().totalSlots());
        CuckooHashTable again = new CuckooHashTable(new BlobFile("t", tmp.resolve("g.raw"), Files.size(tmp.resolve("g.raw"))),
                new FileChannelStorageIOEngine(false), new XxHash64BytesHasher(), 8, CHUNK, 16);
        again.loadMetadataFromDisk();
        assertEquals(10, again.getFillStats().activeSlots());
    }

    @Test
    void smallBlobReportsAppendLockWaitIoFsyncAndCrcFailure() throws IOException {
        Path p = tmp.resolve("s.sob");
        SmallObjectBlob.create(p);
        try (SmallObjectBlob b = SmallObjectBlob.open("s", p, true, 1 << 20, 1 << 20, m)) {
            byte[] data = bytes(5, 100);
            SmallObjectBlob.Location loc = b.append(data, 1);
            assertEquals(1, m.smallLockWaits.get());
            assertTrue(m.get(m.writes, Target.SMALL) >= 1);
            assertTrue(m.get(m.fsyncs, Target.SMALL) >= 1);
            assertArrayEquals(data, b.read(loc.offset(), loc.length(), loc.crc32c()));
            assertTrue(m.get(m.reads, Target.SMALL) >= 1);
            assertThrows(SmallObjectCorruptedException.class, () -> b.read(loc.offset(), loc.length(), loc.crc32c() ^ 1));
            assertEquals(1, m.get(m.crcFailures, CrcKind.SMALL_RECORD));
        }
    }

    @Test
    void metastoreReportsWriterWaitCommitAndFsync() {
        MetaStore store = MetaStore.open(tmp.resolve("meta.db"), MetaStoreOptions.defaults().fsync(true).metrics(m));
        try {
            store.writeVoid(tx -> tx.tree("t").put(new byte[]{1}, new byte[]{2}));
            assertEquals(1, m.metaWriterLockWaits.get());
            assertEquals(1, m.metaCommits.get());
            assertTrue(m.get(m.fsyncs, Target.METASTORE) >= 2, "data barrier + meta barrier");
            assertTrue(m.get(m.writes, Target.METASTORE) >= 1);
            store.read(tx -> tx.tree("t").get(new byte[]{1}));
            assertTrue(m.get(m.reads, Target.METASTORE) >= 1);
        } finally {
            store.close();
        }
    }

    @Test
    void groupCommitReportsBatchQueueWaitAndRollbacks() {
        MetaStore store = MetaStore.open(tmp.resolve("group.db"), MetaStoreOptions.defaults().fsync(false).metrics(m));
        try {
            store.writeGrouped(tx -> {
                tx.tree("t").put(new byte[]{1}, new byte[]{2});
                return null;
            });
            assertThrows(IllegalStateException.class, () -> store.writeGrouped(tx -> {
                throw new IllegalStateException("x");
            }));
            assertEquals(1, m.metaCommits.get(), "a batch of failures commits nothing");
            assertEquals(2, m.groupBatches.get());
            assertEquals(2, m.groupBodies.get());
            assertEquals(2, m.groupQueueWaits.get());
            assertEquals(1, m.groupRollbacks.get());
        } finally {
            store.close();
        }
    }
}
