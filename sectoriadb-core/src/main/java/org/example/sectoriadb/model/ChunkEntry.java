package org.example.sectoriadb.model;

/**
 * Value of the {@code chunks} tree of the metastore: where a chunk of a pool lives and how many manifest positions
 * refer to it. The key is {@code (poolId, chunkKey)}; the bytes are in the cuckoo blob {@code blobId}.
 *
 * @param dataLength number of stored bytes (1..chunkSize)
 * @param crc32c     CRC32C of those bytes
 * @param refcount   number of chunk-list positions of live manifests that hold this key (an object that contains the
 *                   same chunk twice counts twice); 0 means the chunk is garbage waiting in {@code chunk_gc}
 */
public record ChunkEntry(String blobId, int dataLength, int crc32c, long refcount) {
}
