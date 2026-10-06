package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.ChunkLocation;

/**
 * Outcome of {@link CuckooHashTable#insert}.
 *
 * @param key          the key the chunk is stored under; differs from the requested key when the requested key
 *                     collided with a different chunk and the chunk was re-keyed (record this one in the manifest)
 * @param location     where the chunk lives (valid only while the table lock is held)
 * @param deduplicated true if an identical chunk was already stored and nothing was written
 */
public record InsertResult(long key, ChunkLocation location, boolean deduplicated) {
}
