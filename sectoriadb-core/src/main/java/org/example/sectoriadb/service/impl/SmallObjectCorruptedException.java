package org.example.sectoriadb.service.impl;

import java.io.IOException;

/** A small-object record failed header, length or CRC32C verification. */
public class SmallObjectCorruptedException extends IOException {

    private final String blobId;
    private final long offset;

    public SmallObjectCorruptedException(String blobId, long offset, String detail) {
        super("Small object corrupted: blob=" + blobId + " offset=" + offset + ": " + detail);
        this.blobId = blobId;
        this.offset = offset;
    }

    public String getBlobId() { return blobId; }
    public long getOffset() { return offset; }
}
