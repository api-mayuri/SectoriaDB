package org.example.sectoriadb.service.impl;

import java.io.IOException;

/** A chunk the manifest refers to is not in the blob. */
public class ChunkNotFoundException extends IOException {

    public ChunkNotFoundException(long key) {
        super("Chunk not found: key=0x" + Long.toHexString(key));
    }
}
