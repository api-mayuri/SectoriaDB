package org.example.sectoriadb.tools;

import java.nio.ByteBuffer;

/**
 * 64-bit content hash with a seed. Seed 0 is the default and produces the primary chunk key; when two
 * different chunks collide on the primary key, the table re-keys the chunk with seed = attempt number.
 */
public interface BytesHasher {

    long hash64(ByteBuffer buffer, long seed);

    default long hash64(ByteBuffer buffer) {
        return hash64(buffer, 0L);
    }

    default long hash64(byte[] data, long seed) {
        return hash64(ByteBuffer.wrap(data), seed);
    }

    default long hash64(byte[] data) {
        return hash64(data, 0L);
    }
}
