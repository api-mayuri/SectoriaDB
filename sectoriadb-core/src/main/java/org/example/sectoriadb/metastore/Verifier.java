package org.example.sectoriadb.metastore;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;

/** Structural check behind {@link MetaStore#verify()}. Runs with the writer lock held. */
final class Verifier {
    private final MetaStore store;
    private final MetaStore.Snapshot snap;
    private final ReadTxn tx;
    private final int ps;
    private final BitSet seen = new BitSet();
    private long treePages, overflowPages, entries;
    private int leafDepth;

    Verifier(MetaStore store, MetaStore.Snapshot snap) {
        this.store = store;
        this.snap = snap;
        this.tx = new ReadTxn(store, snap, false);
        this.ps = store.pager.pageSize;
    }

    MetaStore.VerifyReport run() {
        seen.set(0);
        seen.set(1);
        int trees = 0;
        long catEntries = walkTree(snap.catalogRoot(), -1, true);
        BTree cat = tx.catalog();
        if (catEntries != countCatalog(cat)) throw new CorruptedPageException(-1, "catalog entry count mismatch");
        entries = 0;
        try (Cursor c = cat.scan()) {
            while (c.next()) {
                ByteBuffer v = ByteBuffer.wrap(c.value());
                long root = v.getLong(0);
                long cnt = v.getLong(8);
                long actual = walkTree(root, cnt, false);
                entries += actual;
                trees++;
            }
        }
        long freelistPages = 0;
        for (long p : store.chainPages) {
            mark(p);
            freelistPages++;
        }
        long free = 0;
        for (int i = 0; i < store.available.size(); i++) {
            mark(store.available.get(i));
            free++;
        }
        for (Map.Entry<Long, long[]> e : store.pending.entrySet()) {
            for (long p : e.getValue()) {
                mark(p);
                free++;
            }
        }
        int missing = seen.nextClearBit(0);
        if (missing < snap.pageCount()) throw new CorruptedPageException(missing, "page is leaked (unreachable and not free)");
        return new MetaStore.VerifyReport(trees, entries, treePages, overflowPages, freelistPages, free);
    }

    private long countCatalog(BTree cat) {
        long n = 0;
        try (Cursor c = cat.scan()) {
            while (c.next()) n++;
        }
        return n;
    }

    private void mark(long id) {
        if (id < 2 || id >= snap.pageCount()) throw new CorruptedPageException(id, "page id out of range");
        if (seen.get((int) id)) throw new CorruptedPageException(id, "page referenced more than once");
        seen.set((int) id);
    }

    private long walkTree(long root, long expected, boolean catalog) {
        if (root == 0) {
            if (expected > 0) throw new CorruptedPageException(-1, "empty tree with non-zero count");
            return 0;
        }
        leafDepth = -1;
        long n = walk(root, null, null, 0, true, catalog);
        if (expected >= 0 && n != expected) {
            throw new CorruptedPageException(root, "entry count " + n + " != recorded " + expected);
        }
        return n;
    }

    private long walk(long id, byte[] lo, byte[] hi, int depth, boolean isRoot, boolean catalog) {
        mark(id);
        treePages++;
        Node n = tx.node(id);
        if (n.size() > ps) throw new CorruptedPageException(id, "node larger than a page");
        int max = BTree.maxKeySize(ps);
        for (int i = 0; i < n.keys.size(); i++) {
            byte[] k = n.keys.get(i);
            if (k.length > max) throw new CorruptedPageException(id, "key too long");
            if (i > 0 && Arrays.compareUnsigned(n.keys.get(i - 1), k) >= 0) {
                throw new CorruptedPageException(id, "keys not strictly increasing");
            }
            if (lo != null && Arrays.compareUnsigned(k, lo) < 0) throw new CorruptedPageException(id, "key below subtree range");
            if (hi != null && Arrays.compareUnsigned(k, hi) >= 0) throw new CorruptedPageException(id, "key above subtree range");
        }
        if (n.leaf) {
            if (leafDepth < 0) leafDepth = depth;
            else if (leafDepth != depth) throw new CorruptedPageException(id, "leaves at different depths");
            if (n.keys.isEmpty()) throw new CorruptedPageException(id, "empty leaf");
            for (Node.LeafVal v : n.vals) if (v.inline() == null) walkChain(v.ovHead(), v.ovLen());
            return n.keys.size();
        }
        if (n.children.size() != n.keys.size() + 1) throw new CorruptedPageException(id, "branch child count mismatch");
        if (isRoot && n.keys.isEmpty()) throw new CorruptedPageException(id, "root branch with a single child");
        long sum = 0;
        for (int i = 0; i < n.children.size(); i++) {
            byte[] clo = i == 0 ? lo : n.keys.get(i - 1);
            byte[] chi = i == n.keys.size() ? hi : n.keys.get(i);
            sum += walk(n.children.get(i), clo, chi, depth + 1, false, catalog);
        }
        return sum;
    }

    private void walkChain(long head, long len) {
        long remaining = len;
        long id = head;
        while (remaining > 0) {
            if (id == 0) throw new CorruptedPageException(-1, "overflow chain too short");
            mark(id);
            overflowPages++;
            byte[] p = store.pager.read(id);
            if (p[4] != Pager.T_OVERFLOW) throw new CorruptedPageException(id, "not an overflow page");
            ByteBuffer b = ByteBuffer.wrap(p);
            int used = b.getShort(6) & 0xFFFF;
            if (used == 0 || used > ps - 16 || used > remaining) throw new CorruptedPageException(id, "bad overflow length");
            remaining -= used;
            id = b.getLong(8);
        }
        if (id != 0) throw new CorruptedPageException(id, "overflow chain too long");
    }
}
