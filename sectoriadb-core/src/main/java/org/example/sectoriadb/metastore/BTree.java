package org.example.sectoriadb.metastore;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/**
 * A named copy-on-write B+tree bound to a transaction. Keys and values are byte arrays; keys are ordered
 * by unsigned lexicographic comparison. Handles are only valid until their transaction ends.
 *
 * <p>Rebalancing policy: after a delete, an underfull node (less than 1/4 of a page) is merged with an
 * adjacent sibling if the result fits in one page; otherwise it is left underfull (no borrowing). Empty
 * leaves therefore always disappear (an empty node always fits), a root with a single child is collapsed.
 */
public final class BTree {
    public static final int MAX_VALUE_SIZE = 64 * 1024 * 1024;
    static final int MAX_KEY_CAP = 1024;

    private record Res(long id, byte[] sep, long right) {
    }

    final ReadTxn tx;
    final String name;
    long root;
    long count;
    boolean dirty;
    boolean invalid;

    BTree(ReadTxn tx, String name, long root, long count) {
        this.tx = tx;
        this.name = name;
        this.root = root;
        this.count = count;
    }

    /** Largest accepted key: 1 KiB, less for tiny pages (a node must always hold at least three max-size entries). */
    public static int maxKeySize(int pageSize) {
        return Math.min(MAX_KEY_CAP, (pageSize - Pager.HEADER) / 3 - 24);
    }

    public String name() {
        return name;
    }

    public long size() {
        check();
        return count;
    }

    // ---------------------------------------------------------------- reads

    public Optional<byte[]> get(byte[] key) {
        check();
        Node.LeafVal v = find(key);
        return v == null ? Optional.empty() : Optional.of(tx.readValue(v));
    }

    public boolean containsKey(byte[] key) {
        check();
        return find(key) != null;
    }

    private Node.LeafVal find(byte[] key) {
        long id = root;
        if (id == 0) return null;
        while (true) {
            Node n = tx.node(id);
            if (n.leaf) {
                int i = Node.lowerBound(n.keys, key);
                return i < n.keys.size() && Arrays.equals(n.keys.get(i), key) ? n.vals.get(i) : null;
            }
            id = n.children.get(Node.upperBound(n.keys, key));
        }
    }

    /** All entries. */
    public Cursor scan() {
        return scan(null, null);
    }

    /** Entries with {@code fromInclusive <= key < toExclusive}; a null bound is unbounded. */
    public Cursor scan(byte[] fromInclusive, byte[] toExclusive) {
        check();
        return new Cursor(tx, root, fromInclusive, toExclusive);
    }

    public Cursor scanPrefix(byte[] prefix) {
        return scan(prefix, Keys.prefixEnd(prefix));
    }

    // ---------------------------------------------------------------- writes

    public void put(byte[] key, byte[] value) {
        WriteTxn w = writer();
        checkKey(key);
        if (value.length > MAX_VALUE_SIZE) throw new IllegalArgumentException("value too large: " + value.length);
        w.touch();
        try {
            Node.LeafVal lv = makeVal(w, key, value);
            key = key.clone();
            if (root == 0) {
                Node l = w.newNode(true);
                l.keys.add(key);
                l.vals.add(lv);
                root = l.id;
                count++;
            } else {
                Res r = insert(w, root, key, lv);
                root = r.id();
                if (r.right() != 0) {
                    Node b = w.newNode(false);
                    b.children.add(r.id());
                    b.keys.add(r.sep());
                    b.children.add(r.right());
                    root = b.id;
                }
            }
            dirty = true;
        } catch (RuntimeException | Error e) {
            w.broken = true;
            throw e;
        }
    }

    /** @return true if the key existed */
    public boolean delete(byte[] key) {
        WriteTxn w = writer();
        if (key.length > maxKeySize(w.pager.pageSize) || find(key) == null) return false;
        w.touch();
        try {
            root = delete(w, root, key);
            while (root != 0) {
                Node r = w.node(root);
                if (r.leaf && r.keys.isEmpty()) {
                    w.free(r.id);
                    root = 0;
                } else if (!r.leaf && r.keys.isEmpty()) {
                    w.free(r.id);
                    root = r.children.get(0);
                } else {
                    break;
                }
            }
            count--;
            dirty = true;
            return true;
        } catch (RuntimeException | Error e) {
            w.broken = true;
            throw e;
        }
    }

    private Node.LeafVal makeVal(WriteTxn w, byte[] key, byte[] v) {
        int cap = (w.pager.pageSize - Pager.HEADER) / 4;
        if (5 + key.length + v.length <= cap) return Node.LeafVal.inline(v.clone());
        return new Node.LeafVal(null, w.writeOverflow(v), v.length);
    }

    private void freeVal(WriteTxn w, Node.LeafVal v) {
        if (v.inline() == null) w.freeOverflow(v.ovHead());
    }

    private Res insert(WriteTxn w, long id, byte[] key, Node.LeafVal lv) {
        Node n = w.mutable(w.node(id));
        if (n.leaf) {
            int i = Node.lowerBound(n.keys, key);
            if (i < n.keys.size() && Arrays.equals(n.keys.get(i), key)) {
                freeVal(w, n.vals.get(i));
                n.vals.set(i, lv);
            } else {
                n.keys.add(i, key);
                n.vals.add(i, lv);
                count++;
            }
        } else {
            int ci = Node.upperBound(n.keys, key);
            Res r = insert(w, n.children.get(ci), key, lv);
            n.children.set(ci, r.id());
            if (r.right() != 0) {
                n.keys.add(ci, r.sep());
                n.children.add(ci + 1, r.right());
            }
        }
        return n.size() > w.pager.pageSize ? split(w, n) : new Res(n.id, null, 0);
    }

    /** Splits an overfull node into two, choosing the cut that minimises the larger half. */
    private Res split(WriteTxn w, Node n) {
        int ps = w.pager.pageSize;
        int cnt = n.keys.size();
        int base = n.leaf ? Pager.HEADER : Pager.HEADER + 8;
        int total = n.size();
        int best = -1;
        int bestMax = Integer.MAX_VALUE;
        int acc = base;
        int lo = 1, hi = n.leaf ? cnt - 1 : cnt - 2;
        for (int i = 0; i <= hi; i++) {
            // acc = size of left half holding entries [0, i)
            if (i >= lo) {
                int left = acc;
                int right = n.leaf ? total - acc + base : total - acc - n.entrySize(i) + base;
                int m = Math.max(left, right);
                if (left <= ps && right <= ps && m < bestMax) {
                    bestMax = m;
                    best = i;
                }
            }
            acc += n.entrySize(i);
        }
        if (best < 0) throw new IllegalStateException("cannot split node of " + total + " bytes");
        Node r = w.newNode(n.leaf);
        byte[] sep;
        if (n.leaf) {
            r.keys.addAll(n.keys.subList(best, cnt));
            r.vals.addAll(n.vals.subList(best, cnt));
            n.keys.subList(best, cnt).clear();
            n.vals.subList(best, cnt).clear();
            sep = r.keys.get(0);
        } else {
            sep = n.keys.get(best);
            r.keys.addAll(n.keys.subList(best + 1, cnt));
            r.children.addAll(n.children.subList(best + 1, cnt + 1));
            n.keys.subList(best, cnt).clear();
            n.children.subList(best + 1, cnt + 1).clear();
        }
        return new Res(n.id, sep, r.id);
    }

    private long delete(WriteTxn w, long id, byte[] key) {
        Node n = w.mutable(w.node(id));
        if (n.leaf) {
            int i = Node.lowerBound(n.keys, key);
            freeVal(w, n.vals.get(i));
            n.keys.remove(i);
            n.vals.remove(i);
        } else {
            int ci = Node.upperBound(n.keys, key);
            n.children.set(ci, delete(w, n.children.get(ci), key));
            rebalance(w, n, ci);
        }
        return n.id;
    }

    private void rebalance(WriteTxn w, Node parent, int ci) {
        int ps = w.pager.pageSize;
        Node child = w.node(parent.children.get(ci));
        if (child.size() >= ps / 4 || parent.children.size() < 2) return;
        int li = ci + 1 < parent.children.size() ? ci : ci - 1;
        Node l = w.node(parent.children.get(li));
        Node r = w.node(parent.children.get(li + 1));
        Node m = l.copyWithId(-1);
        if (l.leaf) {
            m.keys.addAll(r.keys);
            m.vals.addAll(r.vals);
        } else {
            m.keys.add(parent.keys.get(li));
            m.keys.addAll(r.keys);
            m.children.addAll(r.children);
        }
        if (m.size() > ps) return;
        Node lw = w.mutable(l);
        lw.keys = m.keys;
        if (l.leaf) lw.vals = m.vals;
        else lw.children = m.children;
        w.free(r.id);
        parent.keys.remove(li);
        parent.children.remove(li + 1);
        parent.children.set(li, lw.id);
    }

    /** Frees every page of this tree (used by dropTree). */
    void freeAll(WriteTxn w) {
        if (root != 0) freeSubtree(w, root);
        root = 0;
        count = 0;
    }

    private void freeSubtree(WriteTxn w, long id) {
        Node n = w.node(id);
        if (n.leaf) {
            for (Node.LeafVal v : n.vals) freeVal(w, v);
        } else {
            for (long c : n.children) freeSubtree(w, c);
        }
        w.free(id);
    }

    // ---------------------------------------------------------------- checks

    private void check() {
        tx.ensureOpen();
        if (invalid) throw new IllegalStateException("tree was dropped");
    }

    private WriteTxn writer() {
        check();
        if (!(tx instanceof WriteTxn w)) throw new IllegalStateException("read-only transaction");
        if (w.broken) throw new IllegalStateException("transaction failed earlier and must be aborted");
        return w;
    }

    private void checkKey(byte[] key) {
        int max = maxKeySize(tx.pager.pageSize);
        if (key.length == 0 && name == null) throw new IllegalArgumentException("empty key");
        if (key.length > max) throw new IllegalArgumentException("key too large: " + key.length + " > " + max);
    }

    static byte[] encodeCatalogValue(long root, long count) {
        return ByteBuffer.allocate(16).putLong(root).putLong(count).array();
    }
}
