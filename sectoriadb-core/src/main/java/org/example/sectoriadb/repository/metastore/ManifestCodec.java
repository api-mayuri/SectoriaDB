package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.ManifestEntity;

import java.nio.ByteBuffer;

/**
 * Value of the {@code manifests} tree.
 *
 * <pre>
 *   u8   formatVersion (1)
 *   u32  jsonLength
 *   ...  JSON of every scalar / map field (the resolved blob references and the chunk keys are not part of it)
 *   u32  chunkCount
 *   u64  chunkKey[chunkCount]      big-endian, in file order
 * </pre>
 *
 * The chunk keys are the only part that grows with the object (8 bytes per chunk instead of 17 for the old
 * comma-separated hex string), so they are a binary array; everything else stays easily extensible JSON.
 * Integrity is provided by the page CRC32C of the metastore and atomic commits.
 */
public final class ManifestCodec {

    static final int VERSION = 1;

    private ManifestCodec() {
    }

    public static byte[] encode(ManifestEntity m) {
        byte[] json = JsonValues.write(m);
        long[] keys = m.chunkKeyArray();
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + json.length + 4 + 8 * keys.length);
        b.put((byte) VERSION).putInt(json.length).put(json).putInt(keys.length);
        for (long k : keys) b.putLong(k);
        return b.array();
    }

    public static ManifestEntity decode(byte[] value) {
        return decode(value, true);
    }

    /** {@code withChunkKeys=false} skips the key array (listings only need the scalar attributes). */
    public static ManifestEntity decode(byte[] value, boolean withChunkKeys) {
        try {
            ByteBuffer b = ByteBuffer.wrap(value);
            int version = b.get();
            if (version != VERSION) throw new IllegalStateException("unsupported manifest record version " + version);
            int jsonLen = b.getInt();
            if (jsonLen < 2 || jsonLen > b.remaining() - 4) throw new IllegalStateException("bad JSON length " + jsonLen);
            ManifestEntity m = JsonValues.read(value, b.position(), jsonLen, ManifestEntity.class);
            b.position(b.position() + jsonLen);
            int count = b.getInt();
            if (count < 0 || (long) count * 8 != b.remaining()) {
                throw new IllegalStateException("bad chunk key count " + count);
            }
            if (withChunkKeys) {
                long[] keys = new long[count];
                for (int i = 0; i < count; i++) keys[i] = b.getLong();
                m.setChunkKeyArray(keys);
            }
            return m;
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalStateException("truncated manifest record", e);
        }
    }
}
