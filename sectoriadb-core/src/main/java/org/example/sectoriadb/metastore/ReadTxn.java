package org.example.sectoriadb.metastore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot of the store as of the moment the transaction began. Not thread-safe: use one
 * transaction from one thread (open several read transactions for several threads).
 *
 * <p>Must be closed. An open read transaction pins every page reachable from its snapshot: pages freed
 * by later commits cannot be reused while it is open, so a long-lived reader makes the file grow.
 */
public class ReadTxn implements AutoCloseable {
    final MetaStore store;
    final MetaStore.Snapshot snap;
    final Pager pager;
    final boolean registered;
    final Map<String, BTree> handles = new HashMap<>();
    boolean closed;

    ReadTxn(MetaStore store, MetaStore.Snapshot snap, boolean registered) {
        this.store = store;
        this.snap = snap;
        this.pager = store.pager;
        this.registered = registered;
    }

    /** Id of the commit this snapshot corresponds to (0 for a freshly created store). */
    public long txId() {
        return snap.txId();
    }

    /**
     * Returns the named tree. In a read transaction a missing tree yields an empty read-only tree;
     * in a write transaction it is created.
     */
    public BTree tree(String name) {
        ensureOpen();
        BTree t = handles.get(name);
        if (t != null) return t;
        byte[] v = catalog().get(nameKey(name)).orElse(null);
        t = v == null ? missing(name)
                : new BTree(this, name, ByteBuffer.wrap(v).getLong(0), ByteBuffer.wrap(v).getLong(8));
        handles.put(name, t);
        return t;
    }

    BTree missing(String name) {
        return new BTree(this, name, 0, 0);
    }

    public boolean hasTree(String name) {
        ensureOpen();
        return treeNames().contains(name);
    }

    /** Names of all trees, sorted by their UTF-8 bytes. */
    public List<String> treeNames() {
        ensureOpen();
        return catalogNames();
    }

    final List<String> catalogNames() {
        List<String> out = new ArrayList<>();
        try (Cursor c = catalog().scan()) {
            while (c.next()) out.add(new String(c.key(), StandardCharsets.UTF_8));
        }
        return out;
    }

    final BTree catalog() {
        return new BTree(this, null, snap.catalogRoot(), 0);
    }

    byte[] nameKey(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("tree name must not be empty");
        byte[] k = name.getBytes(StandardCharsets.UTF_8);
        if (k.length > BTree.maxKeySize(pager.pageSize)) throw new IllegalArgumentException("tree name too long");
        return k;
    }

    Node node(long id) {
        return Node.decode(id, pager.read(id));
    }

    long modCount() {
        return 0;
    }

    byte[] readValue(Node.LeafVal v) {
        if (v.inline() != null) return v.inline().clone();
        long len = v.ovLen();
        if (len > Integer.MAX_VALUE) throw new CorruptedPageException(v.ovHead(), "value too large");
        byte[] out = new byte[(int) len];
        int off = 0;
        long id = v.ovHead();
        while (off < len) {
            if (id == 0) throw new CorruptedPageException(-1, "overflow chain too short");
            byte[] p = pager.read(id);
            if (p[4] != Pager.T_OVERFLOW) throw new CorruptedPageException(id, "not an overflow page");
            ByteBuffer b = ByteBuffer.wrap(p);
            int used = b.getShort(6) & 0xFFFF;
            if (used == 0 || used > p.length - 16 || off + used > len) {
                throw new CorruptedPageException(id, "bad overflow length");
            }
            System.arraycopy(p, 16, out, off, used);
            off += used;
            id = b.getLong(8);
        }
        if (id != 0) throw new CorruptedPageException(id, "overflow chain too long");
        return out;
    }

    void ensureOpen() {
        if (closed) throw new IllegalStateException("transaction is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (registered) store.unregisterReader(snap.txId());
    }
}
