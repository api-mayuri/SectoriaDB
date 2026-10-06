package org.example.sectoriadb.metastore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single read-write transaction. Everything written is buffered (dirty tree nodes in memory, large
 * values directly into not-yet-referenced pages) and becomes visible atomically on {@link #commit()}.
 * Closing without commit discards all changes. A write transaction can also read (it sees its own writes).
 */
public final class WriteTxn extends ReadTxn {
    final long newTxId;
    long hwm;
    final Map<Long, Node> dirty = new HashMap<>();
    final Set<Long> owned = new HashSet<>();            // pages allocated by this txn
    final List<Long> reusable = new ArrayList<>();      // owned pages freed again within this txn
    final List<Long> pendingFreed = new ArrayList<>();  // committed pages freed by this txn
    final LongList fromAvail = new LongList();          // pages taken from the store's free list
    final Set<String> dropped = new HashSet<>();
    long modCount;
    boolean broken;
    private boolean finished;

    WriteTxn(MetaStore store, MetaStore.Snapshot snap) {
        super(store, snap, false);
        this.newTxId = snap.txId() + 1;
        this.hwm = snap.pageCount();
    }

    @Override
    BTree missing(String name) {
        BTree t = new BTree(this, name, 0, 0);
        t.dirty = true;
        dropped.remove(name);
        return t;
    }

    @Override
    public BTree tree(String name) {
        ensureOpen();
        BTree t = handles.get(name);
        if (t != null) return t;
        if (dropped.contains(name)) {   // dropped earlier in this txn: do not resurrect the committed one
            t = missing(name);
            handles.put(name, t);
            return t;
        }
        return super.tree(name);
    }

    @Override
    public boolean hasTree(String name) {
        return treeNames().contains(name);
    }

    @Override
    public List<String> treeNames() {
        ensureOpen();
        TreeSet<String> out = new TreeSet<>((a, b) -> java.util.Arrays.compareUnsigned(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        out.addAll(catalogNames());
        out.removeAll(dropped);
        out.addAll(handles.keySet());
        return new ArrayList<>(out);
    }

    /** Drops a tree and frees its pages. @return false if there was no such tree. */
    public boolean dropTree(String name) {
        ensureOpen();
        if (broken) throw new IllegalStateException("transaction failed earlier and must be aborted");
        if (!treeNames().contains(name)) return false;
        BTree t = tree(name);
        touch();
        t.freeAll(this);
        t.invalid = true;
        handles.remove(name);
        dropped.add(name);
        return true;
    }

    public void commit() {
        ensureOpen();
        if (broken) throw new IllegalStateException("transaction failed earlier and must be aborted");
        try {
            if (isModified()) store.commit(this);
        } finally {
            finish(true);
        }
    }

    boolean isModified() {
        if (!dropped.isEmpty()) return true;
        for (BTree t : handles.values()) if (t.dirty) return true;
        return false;
    }

    @Override
    public void close() {
        if (!finished) finish(false);
    }

    private void finish(boolean committed) {
        if (finished) return;
        finished = true;
        closed = true;
        if (!committed) store.abort(this);
        store.releaseWriter();
    }

    @Override
    long modCount() {
        return modCount;
    }

    void touch() {
        modCount++;
    }

    // ---------------------------------------------------------------- page management

    @Override
    Node node(long id) {
        Node n = dirty.get(id);
        return n != null ? n : super.node(id);
    }

    long allocId() {
        long id;
        if (!reusable.isEmpty()) {
            id = reusable.remove(reusable.size() - 1);
        } else if (!store.available.isEmpty()) {
            id = store.available.pop();
            fromAvail.push(id);
        } else {
            id = hwm++;
        }
        owned.add(id);
        return id;
    }

    void free(long id) {
        dirty.remove(id);
        if (owned.remove(id)) reusable.add(id);
        else pendingFreed.add(id);
    }

    Node newNode(boolean leaf) {
        Node n = new Node(allocId(), leaf);
        dirty.put(n.id, n);
        return n;
    }

    /** Returns a node owned by this txn: the same object if already dirty, otherwise a copy on a fresh page. */
    Node mutable(Node n) {
        if (dirty.get(n.id) == n) return n;
        Node c = n.copyWithId(allocId());
        dirty.put(c.id, c);
        free(n.id);
        return c;
    }

    /** Writes the value into a fresh overflow chain right away; the pages are unreachable until commit. */
    long writeOverflow(byte[] v) {
        int per = pager.pageSize - 16;
        int n = (v.length + per - 1) / per;
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) ids[i] = allocId();
        for (int i = 0; i < n; i++) {
            byte[] p = new byte[pager.pageSize];
            ByteBuffer b = ByteBuffer.wrap(p);
            int off = i * per;
            int len = Math.min(per, v.length - off);
            p[4] = Pager.T_OVERFLOW;
            b.putShort(6, (short) len);
            b.putLong(8, i + 1 < n ? ids[i + 1] : 0);
            System.arraycopy(v, off, p, 16, len);
            pager.write(ids[i], p);
        }
        return ids[0];
    }

    void freeOverflow(long head) {
        long id = head;
        while (id != 0) {
            byte[] p = pager.read(id);
            if (p[4] != Pager.T_OVERFLOW) throw new CorruptedPageException(id, "not an overflow page");
            long next = ByteBuffer.wrap(p).getLong(8);
            free(id);
            id = next;
        }
    }
}
