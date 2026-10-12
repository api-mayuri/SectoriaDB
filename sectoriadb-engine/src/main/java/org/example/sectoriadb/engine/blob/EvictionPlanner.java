package org.example.sectoriadb.engine.blob;

import org.example.sectoriadb.engine.format.BlobLayout;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Searches the eviction path of a cuckoo insert on the in-memory slot metadata of a {@link CuckooHashTable} (no IO).
 * Not thread-safe: the table calls it with its write lock held, which also makes the one scratch set safe to share.
 */
final class EvictionPlanner {

    private static final int SLOTS_PER_BUCKET = BlobLayout.SLOTS_PER_BUCKET;
    private static final byte FREE = SlotStateMap.FREE.getValue();
    private static final byte ACTIVE = SlotStateMap.ACTIVE.getValue();
    private static final byte DELETED = SlotStateMap.DELETED.getValue();

    /** Upper bound of buckets visited by one eviction search. */
    private static final int MAX_SEARCH_NODES = 4096;

    private final byte[] stateMeta;
    private final long[] chunkIdMeta;
    private final int numBuckets;
    private final int slotsPerTable;
    private final int maxEvictions;
    private BitSet visitedScratch;

    EvictionPlanner(byte[] stateMeta, long[] chunkIdMeta, int numBuckets, int maxEvictions) {
        this.stateMeta = stateMeta;
        this.chunkIdMeta = chunkIdMeta;
        this.numBuckets = numBuckets;
        this.slotsPerTable = numBuckets * SLOTS_PER_BUCKET;
        this.maxEvictions = maxEvictions;
    }

    private int flatIndex(int tableId, int bucket, int slot) {
        return tableId * slotsPerTable + bucket * SLOTS_PER_BUCKET + slot;
    }

    /** FREE and DELETED slots can take a new chunk. QUARANTINED, RESERVED and ACTIVE slots cannot. */
    private boolean isFreeSlot(int idx) {
        return stateMeta[idx] == FREE || stateMeta[idx] == DELETED;
    }

    private int bucketA(long key) {
        return BlobLayout.bucketA(key, numBuckets);
    }

    private int bucketB(long key) {
        return BlobLayout.bucketB(key, numBuckets);
    }

    /**
     * BFS over buckets using in-memory metadata only. Roots are the two candidate buckets of the new key
     * (table A and table B). An edge goes from a bucket to the alternate bucket of one of its ACTIVE chunks.
     * Returns the flat slot indices p[0..n]: the new chunk goes to p[0], the chunk in p[i-1] moves to p[i],
     * and p[n] is a free slot (n = number of moves, 0 if a root bucket has room). Null if no path of at
     * most maxEvictions moves exists.
     */
    int[] findPath(long newKey) {
        int bA = bucketA(newKey);
        int bB = bucketB(newKey);
        // a root bucket with room needs no search state at all (the common case)
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(0, bA, s);
            if (isFreeSlot(idx)) return new int[]{idx};
        }
        for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
            int idx = flatIndex(1, bB, s);
            if (isFreeSlot(idx)) return new int[]{idx};
        }
        // node: {tableId, bucket, parentNodeIndex, slotInParentOfMovedChunk, depth}
        List<int[]> nodes = new ArrayList<>();
        BitSet visited = visitedScratch();   // the caller holds the write lock: one scratch set per table is safe
        try {
            nodes.add(new int[]{0, bA, -1, -1, 0});
            visited.set(bA);
            nodes.add(new int[]{1, bB, -1, -1, 0});
            visited.set(numBuckets + bB);

            for (int head = 0; head < nodes.size(); head++) {
                int[] node = nodes.get(head);
                int tableId = node[0];
                int bucket = node[1];

                for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                    int idx = flatIndex(tableId, bucket, s);
                    if (isFreeSlot(idx)) {
                        return buildPath(nodes, head, s);
                    }
                }

                if (node[4] >= maxEvictions) {
                    continue; // cannot move anything out of this bucket within the budget
                }
                int other = 1 - tableId;
                for (int s = 0; s < SLOTS_PER_BUCKET; s++) {
                    int idx = flatIndex(tableId, bucket, s);
                    if (stateMeta[idx] != ACTIVE) {
                        continue;
                    }
                    long key = chunkIdMeta[idx];
                    int otherBucket = (other == 0) ? bucketA(key) : bucketB(key);
                    int visitId = other * numBuckets + otherBucket;
                    if (!visited.get(visitId)) {
                        visited.set(visitId);
                        nodes.add(new int[]{other, otherBucket, head, s, node[4] + 1});
                    }
                }
                // bounded work under the write lock: a nearly full table reports TableFull instead of exploring every bucket
                if (nodes.size() >= MAX_SEARCH_NODES) {
                    return null;
                }
            }
            return null;
        } finally {
            for (int[] n : nodes) visited.clear(n[0] * numBuckets + n[1]);   // leave the scratch set empty for the next insert
        }
    }

    private BitSet visitedScratch() {
        if (visitedScratch == null) visitedScratch = new BitSet(2 * numBuckets);
        return visitedScratch;
    }

    private int[] buildPath(List<int[]> nodes, int endNode, int freeSlot) {
        int[] end = nodes.get(endNode);
        int depth = end[4];
        int[] path = new int[depth + 1];
        path[depth] = flatIndex(end[0], end[1], freeSlot);
        int cur = endNode;
        for (int i = depth - 1; i >= 0; i--) {
            int[] child = nodes.get(cur);
            int[] parent = nodes.get(child[2]);
            path[i] = flatIndex(parent[0], parent[1], child[3]);
            cur = child[2];
        }
        return path;
    }
}
