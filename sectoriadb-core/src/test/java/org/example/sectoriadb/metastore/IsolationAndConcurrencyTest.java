package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolationAndConcurrencyTest {
    @TempDir
    Path dir;
    MetaStore store;

    @BeforeEach
    void open() {
        store = MetaStore.open(dir.resolve("iso.db"), MetaStoreOptions.defaults().pageSize(512).fsync(false));
    }

    @AfterEach
    void close() {
        store.close();
    }

    static byte[] k(int i) {
        return Keys.builder().longUnsigned(i).build();
    }

    static byte[] v(String s) {
        return s.getBytes();
    }

    static String str(java.util.Optional<byte[]> o) {
        return o.map(String::new).orElse(null);
    }

    @Test
    void readerKeepsItsSnapshotAndUncommittedWritesAreInvisible() {
        store.writeVoid(tx -> tx.tree("t").put(k(1), v("old")));
        try (ReadTxn r1 = store.beginRead()) {
            WriteTxn w = store.beginWrite();
            w.tree("t").put(k(1), v("new"));
            w.tree("t").put(k(2), v("added"));
            w.tree("other").put(k(1), v("x"));
            assertEquals("new", str(w.tree("t").get(k(1))));         // writer sees its own writes
            try (ReadTxn mid = store.beginRead()) {                   // uncommitted data is not visible
                assertEquals("old", str(mid.tree("t").get(k(1))));
                assertNull(str(mid.tree("t").get(k(2))));
                assertFalse(mid.hasTree("other"));
            }
            w.commit();
            assertEquals("old", str(r1.tree("t").get(k(1))));        // r1 predates the commit
            assertNull(str(r1.tree("t").get(k(2))));
            assertEquals(1, r1.tree("t").size());
            try (ReadTxn r2 = store.beginRead()) {
                assertEquals("new", str(r2.tree("t").get(k(1))));
                assertEquals("added", str(r2.tree("t").get(k(2))));
                assertTrue(r2.hasTree("other"));
            }
        }
        // an aborted transaction leaves no trace
        try (WriteTxn w = store.beginWrite()) {
            w.tree("t").put(k(1), v("aborted"));
            w.dropTree("other");
        }
        store.read(tx -> {
            assertEquals("new", str(tx.tree("t").get(k(1))));
            assertTrue(tx.hasTree("other"));
            return null;
        });
    }

    @Test
    void oldSnapshotSurvivesManyCommitsThatWouldReuseItsPages() {
        int n = 150;
        store.writeVoid(tx -> {
            BTree t = tx.tree("t");
            for (int i = 0; i < n; i++) t.put(k(i), v("gen0-" + i));
        });
        try (ReadTxn old = store.beginRead()) {
            long pagesAtStart = store.stats().pageCount();
            for (int gen = 1; gen <= 60; gen++) {
                int g = gen;
                store.writeVoid(tx -> {
                    BTree t = tx.tree("t");
                    for (int i = 0; i < n; i++) t.put(k(i), v("gen" + g + "-" + i + "-" + "x".repeat(g % 7)));
                });
                if (gen % 15 == 0) {
                    BTree t = old.tree("t");
                    for (int i = 0; i < n; i++) assertEquals("gen0-" + i, str(t.get(k(i))));
                    try (Cursor c = t.scan()) {
                        int i = 0;
                        while (c.next()) assertEquals("gen0-" + i++, new String(c.value()));
                        assertEquals(n, i);
                    }
                }
            }
            assertEquals(1, store.stats().liveReaders());
            // the pinned snapshot forced the file to grow ...
            assertTrue(store.stats().pageCount() > pagesAtStart);
        }
        // ... but once it is gone, pages are recycled again and the file stops growing
        for (int gen = 0; gen < 5; gen++) overwriteAll(n, gen);
        long settled = store.stats().pageCount();
        for (int gen = 0; gen < 50; gen++) overwriteAll(n, gen);
        assertTrue(store.stats().pageCount() <= settled + 4, "file keeps growing: " + settled + " -> " + store.stats().pageCount());
        assertEquals(0, store.stats().liveReaders());
        store.verify();
    }

    private void overwriteAll(int n, int gen) {
        store.writeVoid(tx -> {
            BTree t = tx.tree("t");
            for (int i = 0; i < n; i++) t.put(k(i), v("again" + gen + "-" + i));
        });
    }

    @Test
    void concurrentWritersAreSerialized() throws Exception {
        store.writeVoid(tx -> tx.tree("c").put(v("n"), ByteBuffer.allocate(8).putLong(0).array()));
        int threads = 8, perThread = 150;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    try (WriteTxn tx = store.beginWrite()) {
                        BTree c = tx.tree("c");
                        long cur = ByteBuffer.wrap(c.get(v("n")).orElseThrow()).getLong();
                        c.put(v("n"), ByteBuffer.allocate(8).putLong(cur + 1).array());
                        tx.commit();
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        long fin = store.read(tx -> ByteBuffer.wrap(tx.tree("c").get(v("n")).orElseThrow()).getLong());
        assertEquals((long) threads * perThread, fin);
        assertEquals(1 + threads * perThread, store.stats().lastTxId() - 0);
        store.verify();
    }

    @Test
    void readersSeeConsistentSnapshotsWhileWritersRun() throws Exception {
        // invariant maintained by every commit: a == b, and the common value never decreases
        store.writeVoid(tx -> {
            tx.tree("p").put(v("a"), v("0"));
            tx.tree("q").put(v("b"), v("0"));
            for (int i = 0; i < 100; i++) tx.tree("p").put(k(i), v("0"));
        });
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Future<Long>> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(ex.submit(() -> {
                long last = -1, reads = 0;
                while (!stop.get()) {
                    try (ReadTxn tx = store.beginRead()) {
                        long a = Long.parseLong(str(tx.tree("p").get(v("a"))));
                        Thread.yield();
                        long b = Long.parseLong(str(tx.tree("q").get(v("b"))));
                        assertEquals(a, b, "torn snapshot");
                        assertTrue(a >= last, "went back in time");
                        try (Cursor c = tx.tree("p").scanPrefix(new byte[]{0})) {
                            while (c.next()) assertEquals(String.valueOf(a), new String(c.value()));
                        }
                        last = a;
                        reads++;
                    }
                }
                return reads;
            }));
        }
        Future<?> writer = ex.submit(() -> {
            for (int i = 1; i <= 300; i++) {
                String val = String.valueOf(i);
                try (WriteTxn tx = store.beginWrite()) {
                    tx.tree("p").put(v("a"), v(val));
                    for (int j = 0; j < 100; j++) tx.tree("p").put(k(j), v(val));
                    tx.tree("q").put(v("b"), v(val));
                    if (i % 10 == 0) continue;   // abort every tenth
                    tx.commit();
                }
            }
        });
        writer.get(60, TimeUnit.SECONDS);
        stop.set(true);
        long total = 0;
        for (Future<Long> f : readers) total += f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        assertTrue(total > 0);
        assertEquals(0, store.stats().liveReaders());
        store.verify();
    }

    @Test
    void tryBeginWriteTimesOutWhileAnotherWriterIsActive() throws Exception {
        try (WriteTxn w = store.beginWrite()) {
            assertTrue(store.tryBeginWrite(50, TimeUnit.MILLISECONDS).isEmpty());
        }
        var again = store.tryBeginWrite(50, TimeUnit.MILLISECONDS);
        assertTrue(again.isPresent());
        again.get().close();
    }

    @Test
    void misuseIsRejected() {
        try (ReadTxn r = store.beginRead()) {
            BTree t = r.tree("none");
            assertThrows(IllegalStateException.class, () -> t.put(k(1), v("x")));
            assertThrows(IllegalStateException.class, () -> t.delete(k(1)));
            assertFalse(r.hasTree("none"));   // a read txn never creates trees
        }
        WriteTxn w = store.beginWrite();
        BTree t = w.tree("t");
        t.put(k(1), v("a"));
        Cursor c = t.scan();
        t.put(k(2), v("b"));
        assertThrows(java.util.ConcurrentModificationException.class, c::next);
        w.commit();
        assertThrows(IllegalStateException.class, () -> t.get(k(1)));
        assertThrows(IllegalStateException.class, w::commit);
        assertThrows(IllegalArgumentException.class, () -> store.writeVoid(tx -> tx.tree("t").put(new byte[BTree.maxKeySize(512) + 1], v("x"))));
        assertEquals(List.of("t"), store.read(ReadTxn::treeNames));
    }

    @Test
    void droppedTreesAreFreedAndCanBeRecreated() {
        store.writeVoid(tx -> {
            tx.tree("a").put(k(1), v("1"));
            tx.tree("b").put(k(1), new String(new byte[3000]).getBytes());
        });
        store.writeVoid(tx -> {
            assertTrue(tx.dropTree("a"));
            assertFalse(tx.dropTree("a"));
            assertFalse(tx.hasTree("a"));
            assertEquals(0, tx.tree("a").size());   // recreated empty in the same txn
            tx.tree("a").put(k(2), v("2"));
        });
        store.read(tx -> {
            assertEquals(List.of("a", "b"), tx.treeNames());
            assertNull(str(tx.tree("a").get(k(1))));
            assertEquals("2", str(tx.tree("a").get(k(2))));
            return null;
        });
        store.writeVoid(tx -> tx.dropTree("b"));
        store.writeVoid(tx -> tx.tree("z").put(k(1), v("x")));
        store.verify();
    }
}
