package org.example.sectoriadb.service;

import org.example.sectoriadb.engine.error.StorageException;

/** A chunk the manifest refers to is not in the blob. */
public class ChunkNotFoundException extends StorageException {

    public ChunkNotFoundException(long key) {
        super("Chunk not found: key=0x" + Long.toHexString(key));
    }
}
