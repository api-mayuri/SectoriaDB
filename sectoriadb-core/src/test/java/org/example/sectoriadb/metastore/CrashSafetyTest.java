package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashSafetyTest {
    @TempDir
    Path dir;

    static final MetaStoreOptions OPTS = MetaStoreOptions.defaults().pageSize(1024).fsync(false);
    static final byte[] K = "k".getBytes();

    static void put(MetaStore s, String v) {
        s.writeVoid(tx -> tx.tree("t").put(K, v.getBytes()));
    }

    static String get(MetaStore s) {
        return s.read(tx -> tx.tree("t").get(K).map(String::new).orElse(null));
    }

    private void scribble(Path f, long pos, int len, int fill) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(pos);
            byte[] b = new byte[len];
            java.util.Arrays.fill(b, (byte) fill);
            raf.write(b);
        }
    }

    @Test
    void zeroedNewestMetaFallsBackToPreviousCommit() throws IOException {
        Path f = dir.resolve("a.db");
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            put(s, "v1");
            put(s, "v2");
            put(s, "v3");   // tx 3 -> meta slot 1
            assertEquals(3, s.stats().lastTxId());
        }
        scribble(f, 1024, 1024, 0);
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            assertEquals("v2", get(s));
            assertEquals(2, s.stats().lastTxId());
            s.verify();
            put(s, "v4");   // continues normally and overwrites the damaged slot
            assertEquals("v4", get(s));
        }
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            assertEquals("v4", get(s));
            s.verify();
        }
    }

    @Test
    void bitFlippedMetaIsIgnoredAndPageSizeIsRecoveredFromTheOtherSlot() throws IOException {
        Path f = dir.resolve("b.db");
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().pageSize(2048).fsync(false))) {
            put(s, "v1");
            put(s, "v2");   // tx 2 -> slot 0
        }
        scribble(f, 20, 1, 0x5A);  // corrupt slot 0 (txId field) -> CRC mismatch
        try (MetaStore s = MetaStore.open(f, OPTS)) {  // different requested page size: file header wins
            assertEquals(2048, s.stats().pageSize());
            assertEquals("v1", get(s));
            s.verify();
        }
    }

    @Test
    void bothMetasDestroyedIsReported() throws IOException {
        Path f = dir.resolve("c.db");
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            put(s, "v1");
        }
        scribble(f, 0, 64, 0);
        scribble(f, 1024, 64, 0);
        assertThrows(CorruptedPageException.class, () -> MetaStore.open(f, OPTS));
    }

    @Test
    void truncatedTailMakesNewestCommitInvalid() throws IOException {
        Path f = dir.resolve("d.db");
        long sizeBefore;
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            put(s, "v1");
            sizeBefore = s.stats().fileSize();
            s.writeVoid(tx -> {
                tx.tree("t").put(K, "v2".getBytes());
                tx.tree("t").put("big".getBytes(), new byte[50_000]);
            });
            assertTrue(s.stats().fileSize() > sizeBefore + 10_000);
        }
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(sizeBefore + 300);   // torn: tail pages of the last commit are missing
        }
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            assertEquals("v1", get(s));
            assertEquals(0, (int) s.read(tx -> tx.tree("t").get("big".getBytes()).map(b -> 1).orElse(0)));
            s.verify();
        }
    }

    @Test
    void crashAfterDataBeforeMetaKeepsPreviousState() {
        Path f = dir.resolve("e.db");
        MetaStore s = MetaStore.open(f, OPTS);
        put(s, "v1");
        s.commitHook = stage -> {
            if (stage.equals("after-data")) throw new IllegalStateException("simulated crash");
        };
        assertThrows(IllegalStateException.class, () -> put(s, "v2"));
        assertThrows(MetaStoreUnavailableException.class, s::beginWrite);   // writes are refused until the store recovers
        assertEquals("v1", get(s));                                         // reads keep serving the committed snapshot
        s.close();
        try (MetaStore r = MetaStore.open(f, OPTS)) {
            assertEquals("v1", get(r));
            r.verify();
        }
    }

    @Test
    void crashAfterMetaIsDurable() {
        Path f = dir.resolve("f.db");
        MetaStore s = MetaStore.open(f, OPTS);
        put(s, "v1");
        s.commitHook = stage -> {
            if (stage.equals("after-meta")) throw new IllegalStateException("simulated crash");
        };
        assertThrows(IllegalStateException.class, () -> put(s, "v2"));
        s.close();
        try (MetaStore r = MetaStore.open(f, OPTS)) {
            assertEquals("v2", get(r));
            r.verify();
        }
    }

    @Test
    void repeatedRandomCrashesNeverLoseCommittedData() {
        Path f = dir.resolve("g.db");
        Random rnd = new Random(42);
        TreeMap<Integer, byte[]> model = new TreeMap<>();
        int crashes = 0;
        for (int round = 0; round < 80; round++) {
            try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().pageSize(512).fsync(false))) {
                // state after the previous crash must equal the model
                s.verify();
                s.read(tx -> {
                    BTree t = tx.tree("t");
                    assertEquals(model.size(), t.size());
                    for (Map.Entry<Integer, byte[]> e : model.entrySet()) {
                        assertArrayEquals(e.getValue(), t.get(Integer.toString(e.getKey()).getBytes()).orElseThrow());
                    }
                    return null;
                });
                for (int c = 0; c < 6; c++) {
                    boolean crash = rnd.nextInt(5) == 0;
                    TreeMap<Integer, byte[]> work = new TreeMap<>(model);
                    if (crash) {
                        String stage = rnd.nextBoolean() ? "after-data" : "before-commit";
                        s.commitHook = st -> {
                            if (st.equals(stage)) throw new IllegalStateException("crash");
                        };
                    }
                    try {
                        s.writeVoid(tx -> {
                            BTree t = tx.tree("t");
                            for (int i = 0; i < 40; i++) {
                                int k = rnd.nextInt(300);
                                if (rnd.nextInt(3) == 0) {
                                    t.delete(Integer.toString(k).getBytes());
                                    work.remove(k);
                                } else {
                                    byte[] v = new byte[rnd.nextInt(400)];
                                    rnd.nextBytes(v);
                                    t.put(Integer.toString(k).getBytes(), v);
                                    work.put(k, v);
                                }
                            }
                        });
                        model.clear();
                        model.putAll(work);
                    } catch (IllegalStateException e) {
                        crashes++;
                        break;   // process "died"; reopen
                    }
                }
            }
        }
        assertTrue(crashes > 5, "test must actually crash: " + crashes);
    }

    /** Rewrites many keys per commit so that freed pages are reused aggressively. */
    private static void churn(MetaStore s, int round) {
        s.writeVoid(tx -> {
            BTree t = tx.tree("churn");
            for (int i = 0; i < 200; i++) {
                t.put(("key-" + i).getBytes(), ("r" + round + "-" + i + "-" + "x".repeat(40)).getBytes());
            }
        });
    }

    private static void assertChurnRound(MetaStore s, int round) {
        s.read(tx -> {
            BTree t = tx.tree("churn");
            for (int i = 0; i < 200; i++) {
                assertEquals("r" + round + "-" + i + "-" + "x".repeat(40),
                        new String(t.get(("key-" + i).getBytes()).orElseThrow()));
            }
            return null;
        });
    }

    @Test
    void fallbackSnapshotSurvivesPageReuseByTheNextCommit() throws IOException {
        Path f = dir.resolve("reuse.db");
        long lastTx;
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            for (int r = 1; r <= 30; r++) churn(s, r);
            lastTx = s.stats().lastTxId();
        }
        // reopen and commit once more: pages freed by the newest txn must still not be reused after a restart
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            churn(s, 31);
            lastTx = s.stats().lastTxId();
        }
        // destroy the newest meta page; the store must fall back to round 30 with every page intact
        scribble(f, (lastTx % 2) * 1024L, 1024, 0);
        try (MetaStore s = MetaStore.open(f, OPTS)) {
            assertEquals(lastTx - 1, s.stats().lastTxId());
            assertChurnRound(s, 30);
            s.verify();
        }
    }
}
