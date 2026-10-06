package org.example.sectoriadb.tools;

import java.nio.ByteBuffer;

public interface BytesHasher {
    long hash64(ByteBuffer buffer);
    long hash64(byte[] data);
}
