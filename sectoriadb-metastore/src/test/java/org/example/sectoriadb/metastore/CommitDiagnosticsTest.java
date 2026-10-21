package org.example.sectoriadb.metastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools for finding what makes the file grow: commit size per transaction kind and entries per tree. */
class CommitDiagnosticsTest {

    @TempDir
    Path dir;

    record Commit(String label, long pages) {
    }

    @Test
    void everyCommitReportsItsLabelAndThePagesItWrote() {
        List<Commit> commits = new ArrayList<>();
        MetaStoreListener listener = new MetaStoreListener() {
            @Override
            public void onCommitDetail(String label, long pages, long nanos) {
                synchronized (commits) {
                    commits.add(new Commit(label, pages));
                }
            }
        };
        // a threshold of 1 page also exercises the large-commit log on every commit
        MetaStoreOptions opts = MetaStoreOptions.defaults().pageSize(512).fsync(false).listener(listener).largeCommitPages(1);
        try (MetaStore s = MetaStore.open(dir.resolve("d.db"), opts)) {
            try (WriteTxn tx = s.beginWrite().label("bulk")) {
                BTree t = tx.tree("t");
                for (int i = 0; i < 2000; i++) t.put(Keys.builder().longUnsigned(i).build(), new byte[40]);
                tx.commit();
            }
            try (WriteTxn tx = s.beginWrite()) {
                tx.tree("t").put(Keys.builder().longUnsigned(5).build(), new byte[1]);
                tx.commit();
            }
            s.writeGroupedVoid(tx -> tx.tree("u").put(new byte[]{1}, new byte[]{2}));
            // a large value goes to overflow pages written before the commit; they count for the transaction too
            try (WriteTxn tx = s.beginWrite().label("big-value")) {
                tx.tree("v").put(new byte[]{1}, new byte[64 * 1024]);
                tx.commit();
            }
            // an aborted transaction reports nothing
            try (WriteTxn tx = s.beginWrite().label("aborted")) {
                tx.tree("t").put(new byte[]{9}, new byte[]{9});
            }

            assertEquals(List.of("bulk", "other", "group", "big-value"), commits.stream().map(Commit::label).toList());
            assertTrue(commits.get(0).pages() > 100, "2000 entries of a 512-byte-page tree: many pages, got " + commits.get(0).pages());
            assertTrue(commits.get(1).pages() < 20, "one changed key rewrites one root-to-leaf path, got " + commits.get(1).pages());
            assertTrue(commits.get(3).pages() >= 64 * 1024 / 512, "overflow pages are counted, got " + commits.get(3).pages());

            Map<String, Long> sizes = s.treeSizes();
            assertEquals(2000L, sizes.get("t"));
            assertEquals(1L, sizes.get("u"));
            assertEquals(1L, sizes.get("v"));
        }
    }
}
