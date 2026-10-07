package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In-place recovery of the write side after a failed commit (doc 10, part B): reads keep working on the last committed
 * snapshot during the failure, writes fail fast, and once the fault is gone writes succeed without a restart; the
 * free-page state is rebuilt from the persisted freelist, so {@code verify()} is exact.
 */
class MetaStoreRecoveryTest {
    @TempDir
    Path dir;
    MetaStore store;

    static final String ENOSPC = "No space left on device";
    /** fsync on: the force faults only fire when the store forces. Backoff 0: every write may try to recover. */
    static final MetaStoreOptions OPTS = MetaStoreOptions.defaults().pageSize(512).fsync(true).recoveryBackoff(0);

    MetaStore open(MetaStoreOptions o) {
        store = MetaStore.open(dir.resolve("r.db"), o);
        return store;
    }

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    static byte[] k(String s) {
        return s.getBytes();
    }

    static String get(MetaStore s, String key) {
        return s.read(tx -> tx.tree("t").get(k(key)).map(String::new).orElse(null));
    }

    @Test
    void readsKeepWorkingWhileWritesFailAndWritesResumeWithoutARestart() {
        MetaStore s = open(OPTS.recoveryBackoff(60_000));
        for (int i = 0; i < 200; i++) {
            final int n = i;
            s.writeVoid(tx -> tx.tree("t").put(k("k" + n), k("v" + n)));
        }
        s.injectFaultsForTesting(Integer.MAX_VALUE, 0, ENOSPC);
        MetaStoreUnavailableException first = assertThrows(MetaStoreUnavailableException.class,
                () -> s.writeVoid(tx -> tx.tree("t").put(k("lost"), k("x"))));   // the commit's own failure
        assertTrue(hasMessageInChain(first, ENOSPC));
        assertTrue(s.isReadOnly());
        // the failure is visible, writes fail fast (the backoff of one minute has not passed) ...
        MetaStoreUnavailableException e = assertThrows(MetaStoreUnavailableException.class,
                () -> s.writeVoid(tx -> tx.tree("t").put(k("lost2"), k("x"))));
        assertTrue(hasMessageInChain(e, ENOSPC), "the cause chain tells a full disk: " + e);
        assertThrows(MetaStoreUnavailableException.class, () -> s.writeGroupedVoid(tx -> tx.tree("t").put(k("lost3"), k("x"))));
        // ... while reads serve the committed snapshot, even new read transactions
        for (int i = 0; i < 200; i++) assertEquals("v" + i, get(s, "k" + i));
        assertEquals(200L, (long) s.read(tx -> tx.tree("t").size()));
        assertEquals(null, get(s, "lost"));
    }

    @Test
    void afterTheFaultClearsWritesSucceedAndVerifyIsExact() {
        MetaStore s = open(OPTS);
        for (int i = 0; i < 300; i++) {
            final int n = i;
            s.writeVoid(tx -> tx.tree("t").put(k("k" + n), k("v" + n)));
        }
        for (int i = 0; i < 300; i += 2) {   // builds a free list with pages freed by many transactions
            final int n = i;
            s.writeVoid(tx -> tx.tree("t").delete(k("k" + n)));
        }
        long freeBefore = s.stats().freePages();
        s.injectFaultsForTesting(3, 0, "injected");
        assertThrows(RuntimeException.class, () -> s.writeVoid(tx -> {
            for (int i = 0; i < 50; i++) tx.tree("t").put(k("big" + i), new byte[100]);
        }));
        assertTrue(s.isReadOnly());
        s.injectFaultsForTesting(0, 0, "");
        s.writeVoid(tx -> tx.tree("t").put(k("after"), k("ok")));   // recovers by itself
        assertFalse(s.isReadOnly());
        assertEquals("ok", get(s, "after"));
        assertEquals(null, get(s, "big0"), "the failed transaction left nothing behind");
        for (int i = 1; i < 300; i += 2) assertEquals("v" + i, get(s, "k" + i));
        s.verify();
        assertTrue(s.stats().freePages() >= freeBefore - 5, "no pages were lost by the failure: " + freeBefore + " -> " + s.stats().freePages());
        // more churn after the recovery: the rebuilt free list is used and stays exact
        for (int round = 0; round < 50; round++) {
            final int n = round;
            s.writeVoid(tx -> {
                tx.tree("t").put(k("c" + n), new byte[100 + n]);
                tx.tree("t").delete(k("c" + (n - 3)));
            });
        }
        s.verify();
        String path = dir.resolve("r.db").toString();
        s.close();
        store = null;
        try (MetaStore r = MetaStore.open(Path.of(path), OPTS)) {
            r.verify();
            assertEquals("ok", get(r, "after"));
        }
    }

    @Test
    void aReaderOfAnOlderSnapshotIsNotDisturbedByTheFailureAndTheRecovery() {
        MetaStore s = open(OPTS);
        for (int i = 0; i < 100; i++) {
            final int n = i;
            s.writeVoid(tx -> tx.tree("t").put(k("k" + n), k("old" + n)));
        }
        try (ReadTxn reader = s.beginRead()) {
            for (int i = 0; i < 100; i++) {
                final int n = i;
                s.writeVoid(tx -> tx.tree("t").put(k("k" + n), k("new" + n)));   // pages freed while the reader lives
            }
            s.injectFaultsForTesting(2, 0, "injected");
            assertThrows(RuntimeException.class, () -> s.writeVoid(tx -> {
                for (int i = 0; i < 100; i++) tx.tree("t").put(k("k" + i), k("failed" + i));
            }));
            s.injectFaultsForTesting(0, 0, "");
            for (int round = 0; round < 40; round++) {   // plenty of writes after the recovery: they must not reuse the reader's pages
                final int n = round;
                s.writeVoid(tx -> {
                    for (int i = 0; i < 100; i++) tx.tree("t").put(k("k" + i), k("round" + n + "-" + i));
                });
            }
            for (int i = 0; i < 100; i++) assertEquals("old" + i, new String(reader.tree("t").get(k("k" + i)).orElseThrow()));
        }
        s.writeVoid(tx -> tx.tree("t").put(k("z"), k("z")));
        s.verify();
    }

    @Test
    void theGroupCommitterRecoversByItself() throws Exception {
        MetaStore s = open(OPTS);
        s.writeGroupedVoid(tx -> tx.tree("t").put(k("a"), k("1")));
        s.injectFaultsForTesting(1, 0, "injected");
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> s.submit(tx -> { tx.tree("t").put(k("b"), k("2")); return null; }).get(10, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
        s.injectFaultsForTesting(0, 0, "");
        for (int i = 0; i < 50; i++) {
            final int n = i;
            s.writeGroupedVoid(tx -> tx.tree("t").put(k("g" + n), k("v")));
        }
        assertEquals("1", get(s, "a"));
        assertEquals(null, get(s, "b"));
        assertEquals("v", get(s, "g49"));
        s.verify();
        assertTrue(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("metastore-committer")).count() <= 1);
    }

    @Test
    void whileTheDiskStillFailsRecoveryFailsToo() throws Exception {
        MetaStore s = open(OPTS);
        s.writeVoid(tx -> tx.tree("t").put(k("a"), k("1")));
        // the meta page of the failed commit reaches the file, its fsync fails: the recovery has to erase that page,
        // which needs a working disk
        s.commitHook = stage -> {
            if (stage.equals("after-data")) s.injectFaultsForTesting(0, Integer.MAX_VALUE, ENOSPC);
        };
        assertThrows(RuntimeException.class, () -> s.writeVoid(tx -> tx.tree("t").put(k("b"), k("2"))));
        s.commitHook = null;
        for (int i = 0; i < 3; i++) {
            MetaStoreUnavailableException e = assertThrows(MetaStoreUnavailableException.class,
                    () -> s.writeVoid(tx -> tx.tree("t").put(k("c"), k("3"))));
            assertTrue(hasMessageInChain(e, ENOSPC));
            assertThrows(MetaStoreUnavailableException.class, () -> s.writeGroupedVoid(tx -> tx.tree("t").put(k("c"), k("3"))));
        }
        assertEquals("1", get(s, "a"), "reads are fine");
        s.injectFaultsForTesting(0, 0, "");
        s.writeVoid(tx -> tx.tree("t").put(k("d"), k("4")));
        assertEquals(null, get(s, "b"), "a transaction whose callers were told it failed never appears");
        s.verify();
        s.close();
        store = null;
        try (MetaStore r = MetaStore.open(dir.resolve("r.db"), OPTS)) {
            assertEquals(null, get(r, "b"));
            assertEquals("4", get(r, "d"));
            r.verify();
        }
    }

    @Test
    void theMetaPageOfAFailedCommitDoesNotComeBackAfterACrashFollowingTheRecovery() {
        MetaStore s = open(OPTS);
        s.writeVoid(tx -> tx.tree("t").put(k("a"), k("1")));
        s.commitHook = stage -> {
            if (stage.equals("after-data")) s.injectFaultsForTesting(0, 1, "injected");   // the meta fsync fails
        };
        assertThrows(RuntimeException.class, () -> s.writeVoid(tx -> tx.tree("t").put(k("ghost"), k("x"))));
        s.commitHook = null;
        s.injectFaultsForTesting(0, 0, "");
        // recovery erases the stray meta page; the next transaction reuses the pages the failed one wrote, then "crashes"
        // between its data and its meta page: the store must come back at the last good state, not at the ghost
        s.commitHook = stage -> {
            if (stage.equals("after-data")) throw new IllegalStateException("simulated crash");
        };
        assertThrows(RuntimeException.class, () -> s.writeVoid(tx -> {
            for (int i = 0; i < 20; i++) tx.tree("t").put(k("n" + i), new byte[300]);
        }));
        s.commitHook = null;
        s.close();
        store = null;
        try (MetaStore r = MetaStore.open(dir.resolve("r.db"), OPTS)) {
            assertEquals("1", get(r, "a"));
            assertEquals(null, get(r, "ghost"));
            r.verify();
        }
    }

    /** Writers that keep going while the disk fails and heals at random: every acknowledged write is there, no failed one is. */
    @Test
    void randomFaultsUnderConcurrentWritersAndReaders() throws Exception {
        MetaStore s = open(OPTS.groupCommit(32, 256, 0, 256));
        int writers = 6;
        ExecutorService ex = Executors.newFixedThreadPool(writers + 2);
        Map<String, String> acknowledged = new ConcurrentHashMap<>();
        Map<String, String> refused = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger readFailures = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int id = w;
            fs.add(ex.submit(() -> {
                Random r = new Random(id);
                for (int i = 0; i < 400; i++) {
                    String key = "w" + id + "-" + i;
                    String val = "v" + r.nextInt(1000);
                    try {
                        if (i % 2 == 0) s.writeGroupedVoid(tx -> tx.tree("t").put(k(key), k(val)));
                        else s.writeVoid(tx -> tx.tree("t").put(k(key), k(val)));
                        acknowledged.put(key, val);
                    } catch (RuntimeException e) {
                        refused.put(key, val);
                    }
                }
            }));
        }
        fs.add(ex.submit(() -> {   // readers never fail, whatever the disk does
            while (!stop.get()) {
                try {
                    s.read(tx -> tx.tree("t").size());
                } catch (RuntimeException e) {
                    readFailures.incrementAndGet();
                }
            }
        }));
        fs.add(ex.submit(() -> {   // the "disk": breaks and heals
            Random r = new Random(7);
            while (!stop.get()) {
                s.injectFaultsForTesting(r.nextInt(4) == 0 ? Integer.MAX_VALUE : 1 + r.nextInt(3), r.nextInt(5) == 0 ? 1 : 0, "injected");
                try {
                    Thread.sleep(1 + r.nextInt(4));
                } catch (InterruptedException e) {
                    return;
                }
                s.injectFaultsForTesting(0, 0, "");
                try {
                    Thread.sleep(1 + r.nextInt(6));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }));
        for (int i = 0; i < writers; i++) fs.get(i).get(120, TimeUnit.SECONDS);
        stop.set(true);
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        ex.shutdown();
        s.injectFaultsForTesting(0, 0, "");
        s.writeVoid(tx -> tx.tree("t").put(k("final"), k("ok")));   // recovers if the last failure was the last word
        assertEquals(0, readFailures.get(), "reads never failed");
        assertTrue(!acknowledged.isEmpty() && !refused.isEmpty(), "both outcomes happened: " + acknowledged.size() + "/" + refused.size());
        TreeMap<String, String> inStore = new TreeMap<>();
        s.read(tx -> {
            try (Cursor c = tx.tree("t").scan()) {
                while (c.next()) inStore.put(new String(c.key()), new String(c.value()));
            }
            return null;
        });
        for (var e : acknowledged.entrySet()) assertEquals(e.getValue(), inStore.get(e.getKey()), e.getKey());
        for (String key : refused.keySet()) assertFalse(inStore.containsKey(key), "a refused write must not appear: " + key);
        assertEquals(acknowledged.size() + 1, inStore.size());
        s.verify();
        s.close();
        store = null;
        try (MetaStore r = MetaStore.open(dir.resolve("r.db"), OPTS)) {
            r.verify();
            assertEquals(acknowledged.size() + 1L, (long) r.read(tx -> tx.tree("t").size()));
        }
    }

    private static boolean hasMessageInChain(Throwable t, String text) {
        for (int i = 0; t != null && i < 10; t = t.getCause(), i++) {
            if (t.getMessage() != null && t.getMessage().contains(text)) return true;
        }
        return false;
    }
}
