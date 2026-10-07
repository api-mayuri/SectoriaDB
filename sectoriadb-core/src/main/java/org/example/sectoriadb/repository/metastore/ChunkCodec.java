package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.ChunkEntry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Value of the {@code chunks} tree (big-endian):
 *
 * <pre>
 *   u16  blobIdLength
 *   ...  blobId, UTF-8
 *   u32  dataLength
 *   u32  crc32c
 *   u64  refcount
 * </pre>
 *
 * About 56 bytes with a UUID blob id. The blob id is stored as text (not as a small ordinal) because blobs are
 * created, replaced by resize and deleted at any time; the cost, ~0.3 % of the chunk size, is the price of not having a
 * second registry to keep in step.
 */
public final class ChunkCodec {

    private ChunkCodec() {
    }

    public static byte[] encode(ChunkEntry e) {
        byte[] blob = e.blobId().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + blob.length + 4 + 4 + 8)
                .putShort((short) blob.length).put(blob)
                .putInt(e.dataLength()).putInt(e.crc32c()).putLong(e.refcount())
                .array();
    }

    public static ChunkEntry decode(byte[] v) {
        try {
            ByteBuffer b = ByteBuffer.wrap(v);
            int n = b.getShort() & 0xFFFF;
            byte[] blob = new byte[n];
            b.get(blob);
            return new ChunkEntry(new String(blob, StandardCharsets.UTF_8), b.getInt(), b.getInt(), b.getLong());
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalStateException("truncated chunk index record", e);
        }
    }
}
