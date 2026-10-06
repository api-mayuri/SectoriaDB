package org.example.sectoriadb.metastore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decoded B+tree page. Leaf: keys + values; branch: n keys and n+1 children, child i holds keys in
 * [keys[i-1], keys[i]).
 */
final class Node {
    /** Leaf value: inline bytes, or a reference to an overflow chain when {@code inline == null}. */
    record LeafVal(byte[] inline, long ovHead, long ovLen) {
        static LeafVal inline(byte[] b) {
            return new LeafVal(b, 0, 0);
        }

        int entrySize() {
            return inline != null ? 2 + inline.length : 16;
        }
    }

    long id;
    final boolean leaf;
    ArrayList<byte[]> keys = new ArrayList<>();
    ArrayList<LeafVal> vals;       // leaf only
    ArrayList<Long> children;      // branch only

    Node(long id, boolean leaf) {
        this.id = id;
        this.leaf = leaf;
        if (leaf) vals = new ArrayList<>();
        else children = new ArrayList<>();
    }

    int entrySize(int i) {
        return leaf ? 2 + keys.get(i).length + 1 + vals.get(i).entrySize() : 2 + keys.get(i).length + 8;
    }

    int size() {
        int s = leaf ? Pager.HEADER : Pager.HEADER + 8;
        for (int i = 0; i < keys.size(); i++) s += entrySize(i);
        return s;
    }

    byte[] encode(int pageSize) {
        byte[] p = new byte[pageSize];
        ByteBuffer b = ByteBuffer.wrap(p);
        p[4] = leaf ? Pager.T_LEAF : Pager.T_BRANCH;
        b.putShort(6, (short) keys.size());
        b.position(Pager.HEADER);
        if (!leaf) b.putLong(children.get(0));
        for (int i = 0; i < keys.size(); i++) {
            byte[] k = keys.get(i);
            b.putShort((short) k.length).put(k);
            if (leaf) {
                LeafVal v = vals.get(i);
                if (v.inline() != null) {
                    b.put((byte) 0).putShort((short) v.inline().length).put(v.inline());
                } else {
                    b.put((byte) 1).putLong(v.ovHead()).putLong(v.ovLen());
                }
            } else {
                b.putLong(children.get(i + 1));
            }
        }
        return p;
    }

    static Node decode(long id, byte[] p) {
        try {
            ByteBuffer b = ByteBuffer.wrap(p);
            byte type = p[4];
            if (type != Pager.T_LEAF && type != Pager.T_BRANCH) {
                throw new CorruptedPageException(id, "unexpected page type " + type);
            }
            int count = b.getShort(6) & 0xFFFF;
            Node n = new Node(id, type == Pager.T_LEAF);
            b.position(Pager.HEADER);
            if (!n.leaf) n.children.add(b.getLong());
            for (int i = 0; i < count; i++) {
                byte[] k = new byte[b.getShort() & 0xFFFF];
                b.get(k);
                n.keys.add(k);
                if (n.leaf) {
                    byte kind = b.get();
                    if (kind == 0) {
                        byte[] v = new byte[b.getShort() & 0xFFFF];
                        b.get(v);
                        n.vals.add(LeafVal.inline(v));
                    } else if (kind == 1) {
                        n.vals.add(new LeafVal(null, b.getLong(), b.getLong()));
                    } else {
                        throw new CorruptedPageException(id, "bad value kind " + kind);
                    }
                } else {
                    n.children.add(b.getLong());
                }
            }
            return n;
        } catch (CorruptedPageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptedPageException(id, "malformed node: " + e);
        }
    }

    Node copyWithId(long newId) {
        Node n = new Node(newId, leaf);
        n.keys = new ArrayList<>(keys);
        if (leaf) n.vals = new ArrayList<>(vals);
        else n.children = new ArrayList<>(children);
        return n;
    }

    static int lowerBound(List<byte[]> keys, byte[] k) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(keys.get(mid), k) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    static int upperBound(List<byte[]> keys, byte[] k) {
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(keys.get(mid), k) <= 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
