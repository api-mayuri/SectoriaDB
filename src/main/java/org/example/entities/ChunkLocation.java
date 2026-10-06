package org.example.entities;

public record ChunkLocation(
        CuckooTable table,
        int bucketIndex,
        int slotIndex
) {
    @Override
    public String toString() {
        return "ChunkLocation{table=" + table + ", bucket=" + bucketIndex + ", slot=" + slotIndex + "}";
    }
}
