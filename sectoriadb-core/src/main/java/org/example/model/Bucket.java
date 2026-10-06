package org.example.model;

import java.util.Arrays;

/**
 * In-memory representation of a single bucket (4 consecutive chunk slots inside one cuckoo table).
 */
public class Bucket {

    public static final int SLOTS_COUNT = 4;

    private final CuckooTable table;
    private final int bucketIndex;
    private final long[] chunkIds;
    private final SlotStateMap[] states;

    public Bucket(CuckooTable table, int bucketIndex) {
        this.table = table;
        this.bucketIndex = bucketIndex;
        this.chunkIds = new long[SLOTS_COUNT];
        this.states = new SlotStateMap[SLOTS_COUNT];
        Arrays.fill(this.states, SlotStateMap.FREE);
    }

    public CuckooTable getTable() {
        return table;
    }

    public int getBucketIndex() {
        return bucketIndex;
    }

    public long getChunkId(int slot) {
        return chunkIds[slot];
    }

    public SlotStateMap getState(int slot) {
        return states[slot];
    }

    public void setSlot(int slot, long chunkId, SlotStateMap state) {
        chunkIds[slot] = chunkId;
        states[slot] = state;
    }

    public boolean isFull() {
        for (SlotStateMap s : states) {
            if (s == SlotStateMap.FREE || s == SlotStateMap.DELETED) return false;
        }
        return true;
    }

    /** Returns index of first free/deleted slot, or -1 if bucket is full. */
    public int firstFreeSlot() {
        for (int i = 0; i < SLOTS_COUNT; i++) {
            if (states[i] == SlotStateMap.FREE || states[i] == SlotStateMap.DELETED) return i;
        }
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bucket[").append(table).append("][").append(bucketIndex).append("] { ");
        for (int i = 0; i < SLOTS_COUNT; i++) {
            sb.append("slot").append(i).append("=").append(states[i]);
            if (states[i] == SlotStateMap.ACTIVE) {
                sb.append("(").append(Long.toHexString(chunkIds[i])).append(")");
            }
            if (i < SLOTS_COUNT - 1) sb.append(", ");
        }
        sb.append(" }");
        return sb.toString();
    }
}
