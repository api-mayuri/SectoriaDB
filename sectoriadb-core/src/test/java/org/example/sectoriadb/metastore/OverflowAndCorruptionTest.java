package org.example.sectoriadb.metastore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverflowAndCorruptionTest {
    @TempDir
    Path dir;

    static byte[] bytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    static byte[] k(String s) {
        return s.getBytes();
    }

    @Test
    void largeValuesRoundTripReplaceAndDelete() {
        Random r = new Random(7);
        for (int ps : new int[]{512, 4096}) {
            Path f = dir.resolve("o" + ps + ".db");
            int[] sizes = {0, 1, 100, 500, 1000, ps / 4, ps / 4 + 1, ps - 16, ps - 15, ps * 3, 100_000, 3_000_000};
            byte[][] vals = new byte[sizes.length][];
            try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().pageSize(ps).fsync(false))) {
                s.writeVoid(tx -> {
                    for (int i = 0; i < sizes.length; i++) {
                        vals[i] = bytes(r, sizes[i]);
                        tx.tree("t").put(k("key" + i), vals[i]);
                    }
                });
                s.verify();
                s.read(tx -> {
                    for (int i = 0; i < sizes.length; i++) assertArrayEquals(vals[i], tx.tree("t").get(k("key" + i)).orElseThrow());
                    return null;
                });
            }
            try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().fsync(false))) {
                // replace big with small, small with big, delete big
                s.writeVoid(tx -> {
                    BTree t = tx.tree("t");
                    t.put(k("key11"), k("tiny"));
                    t.put(k("key1"), bytes(r, 50_000));
                    assertTrue(t.delete(k("key10")));
                    assertTrue(t.delete(k("key9")));
                });
                s.verify();
                s.read(tx -> {
                    assertArrayEquals(k("tiny"), tx.tree("t").get(k("key11")).orElseThrow());
                    assertEquals(50_000, tx.tree("t").get(k("key1")).orElseThrow().length);
                    assertTrue(tx.tree("t").get(k("key10")).isEmpty());
                    assertArrayEquals(vals[8], tx.tree("t").get(k("key8")).orElseThrow());
                    return null;
                });
                s.writeVoid(tx -> assertTrue(tx.dropTree("t")));
                s.writeVoid(tx -> tx.tree("u").put(k("x"), k("y")));  // lets the pending pages mature
                s.verify();
            }
        }
    }

    @Test
    void sizeLimits() {
        try (MetaStore s = MetaStore.open(dir.resolve("l.db"), MetaStoreOptions.defaults().pageSize(65536).fsync(false))) {
            s.writeVoid(tx -> {
                BTree t = tx.tree("t");
                t.put(new byte[2048], k("ok"));
                assertThrows(IllegalArgumentException.class, () -> t.put(new byte[2049], k("no")));
                assertThrows(IllegalArgumentException.class, () -> t.put(k("k"), new byte[BTree.MAX_VALUE_SIZE + 1]));
                t.put(k("max"), new byte[BTree.MAX_VALUE_SIZE]);
            });
            assertEquals(BTree.MAX_VALUE_SIZE, (int) s.read(tx -> tx.tree("t").get(k("max")).orElseThrow().length));
            s.verify();
        }
    }

    @Test
    void fileStaysBoundedUnderOverwriteChurn() {
        for (int ps : new int[]{512, 4096}) {
            Random r = new Random(ps);
            try (MetaStore s = MetaStore.open(dir.resolve("c" + ps + ".db"), MetaStoreOptions.defaults().pageSize(ps).fsync(false))) {
                long afterWarmup = 0;
                for (int round = 0; round < 300; round++) {
                    s.writeVoid(tx -> {
                        BTree t = tx.tree("t");
                        for (int i = 0; i < 100; i++) {
                            // same 100 keys, alternating small and multi-page values
                            t.put(k("key" + i), bytes(r, r.nextBoolean() ? 20 : 3000 + r.nextInt(3000)));
                        }
                    });
                    if (round == 30) afterWarmup = s.stats().pageCount();
                }
                long end = s.stats().pageCount();
                assertTrue(end <= afterWarmup * 1.6 + 20, ps + ": pages grew from " + afterWarmup + " to " + end);
                s.verify();
                // deleting everything returns (almost) the whole file to the free list
                s.writeVoid(tx -> tx.dropTree("t"));
                s.writeVoid(tx -> tx.tree("x").put(k("a"), k("b")));
                s.writeVoid(tx -> tx.tree("x").put(k("a"), k("c")));
                var st = s.stats();
                // the rest is the freelist chain itself (16 bytes per free page) plus a few live pages
                assertTrue(st.freePages() >= st.pageCount() * 9 / 10, st.toString());
            }
        }
    }

    // ------------------------------------------------------------ corruption

    private static void flip(Path f, long pos) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(pos);
            int b = raf.read();
            raf.seek(pos);
            raf.write(b ^ 0x01);
        }
    }

    @Test
    void corruptedTreePageIsDetected() throws IOException {
        Path f = dir.resolve("x.db");
        long leafRoot;
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().pageSize(1024).fsync(false))) {
            s.writeVoid(tx -> tx.tree("t").put(k("a"), k("1")));
            leafRoot = s.read(tx -> tx.tree("t").root);
        }
        Path copy = dir.resolve("x2.db");
        Files.copy(f, copy);
        flip(f, leafRoot * 1024 + 100);   // inside the used area of the leaf
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults())) {
            CorruptedPageException e = assertThrows(CorruptedPageException.class,
                    () -> s.read(tx -> tx.tree("t").get(k("a"))));
            assertEquals(leafRoot, e.pageId());
            assertThrows(CorruptedPageException.class, s::verify);
        }
        // a flipped bit in padding is covered by the checksum too
        flip(copy, leafRoot * 1024 + 1000);
        try (MetaStore s = MetaStore.open(copy, MetaStoreOptions.defaults())) {
            assertThrows(CorruptedPageException.class, () -> s.read(tx -> tx.tree("t").get(k("a"))));
        }
    }

    @Test
    void corruptedCatalogAndOverflowPagesAreDetected() throws IOException {
        Path f = dir.resolve("y.db");
        long catalogRoot, ovHead;
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults().pageSize(512).fsync(false))) {
            s.writeVoid(tx -> tx.tree("t").put(k("a"), new byte[5000]));
            catalogRoot = s.read(tx -> tx.snap.catalogRoot());
            ovHead = s.read(tx -> {
                Node n = tx.node(tx.tree("t").root);
                return n.vals.get(0).ovHead();
            });
        }
        Path copy = dir.resolve("y2.db");
        Files.copy(f, copy);
        flip(f, ovHead * 512 + 200);
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults())) {
            assertThrows(CorruptedPageException.class, () -> s.read(tx -> tx.tree("t").get(k("a"))));
            assertThrows(CorruptedPageException.class, s::verify);
        }
        flip(copy, catalogRoot * 512 + 20);
        try (MetaStore s = MetaStore.open(copy, MetaStoreOptions.defaults())) {
            assertThrows(CorruptedPageException.class, () -> s.read(tx -> tx.tree("t")));
            assertThrows(CorruptedPageException.class, s::verify);
        }
    }

    @Test
    void fileCannotBeOpenedTwice() {
        Path f = dir.resolve("z.db");
        try (MetaStore s = MetaStore.open(f, MetaStoreOptions.defaults())) {
            assertThrows(RuntimeException.class, () -> MetaStore.open(f, MetaStoreOptions.defaults()));
        }
        MetaStore.open(f, MetaStoreOptions.defaults()).close();
    }
}
