package org.example.sectoriadb.metastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for edge cases found in the metastore review. */
@Timeout(60)
class MetaStoreHardeningTest {
    @TempDir
    Path dir;

    static byte[] k(String s) {
        return s.getBytes();
    }

    static MetaStoreOptions opts(int ps) {
        return MetaStoreOptions.defaults().pageSize(ps).fsync(false);
    }

    // ---------------------------------------------------------------- A1

    @Test
    void emptyAndTinyValuesWithMaximumKeysDoNotCrash() {
        for (int ps : new int[]{512, 1024, 4096, 65536}) {
            Path f = dir.resolve("a1-" + ps + ".db");
            int max = BTree.maxKeySize(ps);
            try (MetaStore s = MetaStore.open(f, opts(ps))) {
                for (int len : new int[]{1, max / 8, max / 2, max - 1, max}) {
                    for (int vlen : new int[]{0, 1, 5, 100}) {
                        byte[] key = new byte[len];
                        Arrays.fill(key, (byte) (vlen + 1));
                        key[0] = (byte) (len & 0x7f);
                        byte[] val = new byte[vlen];
                        Arrays.fill(val, (byte) 7);
                        s.writeVoid(tx -> tx.tree("t").put(key, val));
                        assertArrayEquals(val, s.read(tx -> tx.tree("t").get(key)).orElseThrow());
                    }
                }
                s.verify();
            }
        }
    }

    @Test
    void writeOverflowRejectsAnEmptyValue() {
        try (MetaStore s = MetaStore.open(dir.resolve("a1b.db"), opts(512)); WriteTxn tx = s.beginWrite()) {
            assertThrows(IllegalArgumentException.class, () -> tx.writeOverflow(new byte[0]));
        }
    }

    // ---------------------------------------------------------------- A2

    @Test
    void aBodyThatLeavesTheInterruptFlagSetDoesNotKillGroupedWrites() throws Exception {
        try (MetaStore s = MetaStore.open(dir.resolve("a2.db"), opts(512))) {
            s.writeGroupedVoid(tx -> {
                tx.tree("t").put(k("a"), k("1"));
                Thread.currentThread().interrupt();     // on the committer thread
            });
            s.writeGroupedVoid(tx -> tx.tree("t").put(k("b"), k("2")));
            s.writeGroupedVoid(tx -> tx.tree("t").put(k("c"), k("3")));
            assertEquals(3L, (long) s.read(tx -> tx.tree("t").size()));
            s.verify();
        }
    }

    // ---------------------------------------------------------------- A3

    @Test
    void aFailingMetricsSinkDoesNotFailOrPoisonDurableCommits() throws Exception {
        MetaStoreListener bad = (MetaStoreListener) Proxy.newProxyInstance(MetaStoreListener.class.getClassLoader(),
                new Class<?>[]{MetaStoreListener.class}, (p, m, a) -> {
                    if (m.getName().equals("toString")) return "bad";
                    throw new IllegalStateException("metrics down: " + m.getName());
                });
        try (MetaStore s = MetaStore.open(dir.resolve("a3.db"), opts(512).listener(bad))) {
            s.writeVoid(tx -> tx.tree("t").put(k("direct"), k("1")));
            s.writeGroupedVoid(tx -> tx.tree("t").put(k("grouped"), k("2")));
            assertFalse(s.isReadOnly());
            assertEquals(2L, (long) s.read(tx -> tx.tree("t").size()));
            s.verify();
        }
        try (MetaStore s = MetaStore.open(dir.resolve("a3.db"), opts(512))) {
            assertEquals(2L, (long) s.read(tx -> tx.tree("t").size()));
        }
    }

    // ---------------------------------------------------------------- A4

    @Test
    void aHalfCreatedFileIsReinitialisedOnOpen() throws Exception {
        for (int cut : new int[]{1, 100, 512, 700}) {
            Path f = dir.resolve("a4-" + cut + ".db");
            try (MetaStore s = MetaStore.open(f, opts(512))) {
                // created
            }
            try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
                raf.setLength(cut);                        // only the first page (or less) made it
            }
            if (cut < 512) {
                // a torn first page is not a valid meta page: only an all-zero file counts as "never initialised"
                try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
                    raf.seek(0);
                    raf.write(new byte[cut]);
                }
            }
            try (MetaStore s = MetaStore.open(f, opts(512))) {
                s.writeVoid(tx -> tx.tree("t").put(k("x"), k("y")));
                s.verify();
            }
            try (MetaStore s = MetaStore.open(f, opts(512))) {
                assertArrayEquals(k("y"), s.read(tx -> tx.tree("t").get(k("x"))).orElseThrow());
            }
        }
    }

    @Test
    void aNonStoreFileIsStillRejected() throws Exception {
        Path f = dir.resolve("junk.db");
        Files.write(f, "this is not a metadata store".getBytes());
        assertThrows(CorruptedPageException.class, () -> MetaStore.open(f, opts(512)));
        assertEquals("this is not a metadata store", Files.readString(f));
    }

    // ---------------------------------------------------------------- A5

    @Test
    void cursorKeyAndValueFailAfterAModification() {
        try (MetaStore s = MetaStore.open(dir.resolve("a5.db"), opts(512))) {
            s.writeVoid(tx -> {
                BTree t = tx.tree("t");
                t.put(k("a"), new byte[5000]);
                t.put(k("b"), new byte[5000]);
            });
            s.writeVoid(tx -> {
                BTree t = tx.tree("t");
                try (Cursor c = t.scan()) {
                    assertTrue(c.next());
                    assertEquals(5000, c.value().length);
                    t.put(k("a"), k("small"));             // frees the overflow chain the cursor points at
                    assertThrows(ConcurrentModificationException.class, c::value);
                    assertThrows(ConcurrentModificationException.class, c::key);
                    assertThrows(ConcurrentModificationException.class, c::next);
                }
            });
        }
    }

    // ---------------------------------------------------------------- A7

    @Test
    void beginWriteAndVerifyFromAGroupedBodyFailFastInsteadOfDeadlocking() throws Exception {
        try (MetaStore s = MetaStore.open(dir.resolve("a7.db"), opts(512))) {
            AtomicReference<Throwable> begin = new AtomicReference<>(), verify = new AtomicReference<>(), tryBegin = new AtomicReference<>();
            s.writeGroupedVoid(tx -> {
                try (WriteTxn w = s.beginWrite()) {
                    // must not get here
                } catch (IllegalStateException e) {
                    begin.set(e);
                }
                try {
                    s.verify();
                } catch (IllegalStateException e) {
                    verify.set(e);
                }
                try {
                    s.tryBeginWrite(1, TimeUnit.MILLISECONDS);
                } catch (IllegalStateException | InterruptedException e) {
                    tryBegin.set(e);
                }
                tx.tree("t").put(k("k"), k("v"));
            });
            assertTrue(begin.get() instanceof IllegalStateException);
            assertTrue(verify.get() instanceof IllegalStateException);
            assertTrue(tryBegin.get() instanceof IllegalStateException);
            assertEquals(1L, (long) s.read(tx -> tx.tree("t").size()));
        }
    }

    // ---------------------------------------------------------------- A8

    @Test
    void beginReadAfterCloseFailsAndCloseIsIdempotent() {
        MetaStore s = MetaStore.open(dir.resolve("a8.db"), opts(512));
        s.close();
        s.close();
        assertThrows(IllegalStateException.class, s::beginRead);
        assertThrows(IllegalStateException.class, s::beginWrite);
    }

    @Test
    void concurrentClosesAllReturnOnlyAfterTheStoreIsClosed() throws Exception {
        for (int round = 0; round < 20; round++) {
            MetaStore s = MetaStore.open(dir.resolve("a8-" + round + ".db"), opts(512));
            s.writeVoid(tx -> tx.tree("t").put(k("a"), k("b")));
            int n = 4;
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int i = 0; i < n; i++) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                        s.close();
                        // after ANY close() returned the store must be closed for everybody
                        try {
                            s.beginRead().close();
                            failure.set(new AssertionError("beginRead succeeded after close() returned"));
                        } catch (IllegalStateException expected) {
                            // fine
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                t.start();
                ts.add(t);
            }
            go.countDown();
            for (Thread t : ts) t.join();
            assertEquals(null, failure.get());
        }
    }

    @Test
    void readersRacingWithCloseEndCleanly() throws Exception {
        for (int round = 0; round < 20; round++) {
            MetaStore s = MetaStore.open(dir.resolve("a8r-" + round + ".db"), opts(512));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread r = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    try (ReadTxn tx = s.beginRead()) {
                        tx.treeNames();
                    } catch (IllegalStateException closed) {
                        return;
                    } catch (RuntimeException other) {
                        // a reader that registered before close may see the channel closed under it
                        if (!(other instanceof UncheckedIOException)) failure.set(other);
                        return;
                    }
                }
            });
            r.start();
            s.close();
            r.join();
            assertEquals(null, failure.get());
        }
    }

    // ---------------------------------------------------------------- A9

    @Test
    void corruptPagePointersGiveCorruptedPageException() throws Exception {
        Path f = dir.resolve("a9.db");
        int ps = 512;
        try (MetaStore s = MetaStore.open(f, opts(ps))) {
            s.writeVoid(tx -> {
                for (int i = 0; i < 200; i++) tx.tree("t").put(k(String.format("key%05d", i)), new byte[20]);
                tx.tree("t").put(k("big"), new byte[3000]);
            });
        }
        // rewrite the pointer of the first non-root branch/overflow page structure: patch every page's child pointers
        // is intrusive, so corrupt the catalog root instead through direct node construction
        try (MetaStore s = MetaStore.open(f, opts(ps))) {
            ReadTxn tx = s.beginRead();
            assertThrows(CorruptedPageException.class, () -> tx.node(-5));
            assertThrows(CorruptedPageException.class, () -> tx.node(1));
            assertThrows(CorruptedPageException.class, () -> tx.node(tx.snap.pageCount()));
            assertThrows(CorruptedPageException.class, () -> tx.node(Long.MAX_VALUE));
            Node.LeafVal bad = new Node.LeafVal(null, 3, -1);
            assertThrows(CorruptedPageException.class, () -> tx.readValue(bad));
            Node.LeafVal huge = new Node.LeafVal(null, 3, Integer.MAX_VALUE);
            assertThrows(CorruptedPageException.class, () -> tx.readValue(huge));
            Node.LeafVal outside = new Node.LeafVal(null, tx.snap.pageCount() + 10, 10);
            assertThrows(CorruptedPageException.class, () -> tx.readValue(outside));
            Node.LeafVal negative = new Node.LeafVal(null, -3, 10);
            assertThrows(CorruptedPageException.class, () -> tx.readValue(negative));
            tx.close();
            try (WriteTxn w = s.beginWrite()) {
                assertThrows(CorruptedPageException.class, () -> w.freeOverflow(w.hwm + 5));
            }
        }
    }

    // ---------------------------------------------------------------- A11

    @Test
    void readingAMissingTreeInAWriteTxnDoesNotCreateOrDirtyIt() {
        try (MetaStore s = MetaStore.open(dir.resolve("a11.db"), opts(512))) {
            long before = s.stats().lastTxId();
            try (WriteTxn tx = s.beginWrite()) {
                assertTrue(tx.tree("ghost").get(k("x")).isEmpty());
                assertFalse(tx.tree("ghost").containsKey(k("x")));
                assertFalse(tx.tree("ghost").delete(k("x")));
                assertFalse(tx.hasTree("ghost"));
                assertFalse(tx.treeNames().contains("ghost"));
                assertFalse(tx.isModified());
                tx.commit();
            }
            assertEquals(before, s.stats().lastTxId(), "nothing changed, nothing committed");
            s.read(tx -> {
                assertFalse(tx.hasTree("ghost"));
                return null;
            });
            // a write creates it
            s.writeVoid(tx -> {
                tx.tree("real").put(k("a"), k("b"));
                assertTrue(tx.hasTree("real"));
            });
            assertEquals(Boolean.TRUE, s.read(tx -> tx.hasTree("real")));
        }
    }

    @Test
    void aTreeDroppedAndRecreatedInOneTxnIsReplacedNotKept() {
        try (MetaStore s = MetaStore.open(dir.resolve("a11b.db"), opts(512))) {
            s.writeVoid(tx -> {
                tx.tree("t").put(k("old"), k("1"));
                tx.tree("u").put(k("old"), k("1"));
            });
            s.writeVoid(tx -> {
                assertTrue(tx.dropTree("t"));
                assertTrue(tx.tree("t").get(k("old")).isEmpty());     // just reading the recreated tree
                assertTrue(tx.dropTree("u"));
                tx.tree("u").put(k("new"), k("2"));
            });
            s.verify();
            s.read(tx -> {
                assertFalse(tx.hasTree("t"), "dropped and only read: gone");
                assertTrue(tx.tree("u").get(k("old")).isEmpty());
                assertArrayEquals(k("2"), tx.tree("u").get(k("new")).orElseThrow());
                return null;
            });
        }
    }
}
