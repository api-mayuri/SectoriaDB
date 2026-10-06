package org.example.sectoriadb.metastore;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Forward range cursor over a tree, implemented with a root-to-leaf stack (copy-on-write pages have no
 * sibling pointers). Usage: {@code while (c.next()) { c.key(); c.value(); }}.
 *
 * <p>Inside a write transaction a cursor is invalidated by any modification of the store made after it
 * was opened ({@link java.util.ConcurrentModificationException}).
 */
public final class Cursor implements AutoCloseable {
    private static final class Frame {
        final Node node;
        int idx;

        Frame(Node node, int idx) {
            this.node = node;
            this.idx = idx;
        }
    }

    private final ReadTxn tx;
    private final byte[] to;
    private final long stamp;
    private final ArrayList<Frame> stack = new ArrayList<>();
    private boolean done;
    private byte[] curKey;
    private Node.LeafVal curVal;

    Cursor(ReadTxn tx, long root, byte[] from, byte[] to) {
        this.tx = tx;
        this.to = to;
        this.stamp = tx.modCount();
        if (root == 0) {
            done = true;
            return;
        }
        long id = root;
        while (true) {
            Node n = tx.node(id);
            if (n.leaf) {
                stack.add(new Frame(n, from == null ? 0 : Node.lowerBound(n.keys, from)));
                break;
            }
            int ci = from == null ? 0 : Node.upperBound(n.keys, from);
            stack.add(new Frame(n, ci));
            id = n.children.get(ci);
        }
    }

    /** Advances to the next entry; false when the range is exhausted. */
    public boolean next() {
        tx.ensureOpen();
        if (tx.modCount() != stamp) throw new java.util.ConcurrentModificationException();
        curKey = null;
        curVal = null;
        while (!done) {
            Frame leaf = stack.get(stack.size() - 1);
            if (leaf.idx < leaf.node.keys.size()) {
                byte[] k = leaf.node.keys.get(leaf.idx);
                if (to != null && Arrays.compareUnsigned(k, to) >= 0) {
                    done = true;
                    return false;
                }
                curKey = k;
                curVal = leaf.node.vals.get(leaf.idx);
                leaf.idx++;
                return true;
            }
            advanceLeaf();
        }
        return false;
    }

    private void advanceLeaf() {
        stack.remove(stack.size() - 1);
        while (!stack.isEmpty()) {
            Frame f = stack.get(stack.size() - 1);
            if (f.idx + 1 < f.node.children.size()) {
                f.idx++;
                long id = f.node.children.get(f.idx);
                while (true) {
                    Node n = tx.node(id);
                    stack.add(new Frame(n, 0));
                    if (n.leaf) return;
                    id = n.children.get(0);
                }
            }
            stack.remove(stack.size() - 1);
        }
        done = true;
    }

    public byte[] key() {
        if (curKey == null) throw new IllegalStateException("no current entry");
        return curKey.clone();
    }

    public byte[] value() {
        if (curVal == null) throw new IllegalStateException("no current entry");
        return tx.readValue(curVal);
    }

    @Override
    public void close() {
        done = true;
        stack.clear();
    }
}
