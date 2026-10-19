package org.example.sectoriadb.metastore;

import java.util.ArrayList;
import java.util.List;

/** Per-tree page accounting behind {@link MetaStore#treeStats()}: how many pages a tree occupies and how full they are. */
final class TreeStats {
    private TreeStats() {
    }

    static List<MetaStore.TreeStat> collect(ReadTxn tx) {
        List<MetaStore.TreeStat> out = new ArrayList<>();
        int ps = tx.pager.pageSize;
        for (String name : tx.treeNames()) {
            BTree t = tx.tree(name);
            long[] acc = new long[5];   // branch pages, leaf pages, overflow pages, used bytes in leaves, entries
            if (t.root != 0) walk(tx, t.root, acc);
            out.add(new MetaStore.TreeStat(name, t.count, acc[0], acc[1], acc[2],
                    acc[1] == 0 ? 0 : (double) acc[3] / (acc[1] * (double) ps)));
        }
        return out;
    }

    private static void walk(ReadTxn tx, long id, long[] acc) {
        Node n = tx.node(id);
        if (n.leaf) {
            acc[1]++;
            acc[3] += n.size();
            for (Node.LeafVal v : n.vals) {
                if (v.inline() == null) {
                    long len = v.ovLen();
                    long page = tx.pager.pageSize - 16L;
                    acc[2] += (len + page - 1) / page;
                }
            }
        } else {
            acc[0]++;
            for (long c : n.children) walk(tx, c, acc);
        }
    }
}
