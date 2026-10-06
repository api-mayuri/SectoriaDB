package org.example.sectoriadb.service.impl;

import java.io.IOException;

/** A stored chunk failed its length / CRC32C verification. */
public class ChunkCorruptedException extends IOException {

    private final String blobId;
    private final int slot;
    private final long key;

    public ChunkCorruptedException(String blobId, int slot, long key, String detail) {
        super("Chunk corrupted: blob=" + blobId + " slot=" + slot + " key=0x" + Long.toHexString(key) + ": " + detail);
        this.blobId = blobId;
        this.slot = slot;
        this.key = key;
    }

    public String getBlobId() { return blobId; }
    /** Flat slot index (table A slots first, then table B). */
    public int getSlot() { return slot; }
    public long getKey() { return key; }
}
