package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.ManifestEntity;

import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.StorageKind;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Value of the {@code manifests} tree.
 *
 * <pre>
 *   u8   formatVersion (2; 1 has no segment section)
 *   u32  jsonLength
 *   ...  JSON of every scalar / map field (the resolved blob references and the chunk keys are not part of it)
 *   u32  chunkCount
 *   u64  chunkKey[chunkCount]      big-endian, in file order
 *   u32  segmentCount              version 2: the parts of a MULTIPART object, in object order
 *   per segment: i32 partNumber, i64 length, u8 kind, i32 chunkSize, i32 lastChunkSize, i32 chunkCount,
 *                i64 smallOffset, i32 smallLength, i32 smallCrc32c, u16 blobIdLength, utf8 blobId
 * </pre>
 *
 * The chunk keys are the only part that grows with the object (8 bytes per chunk instead of 17 for the old
 * comma-separated hex string), so they are a binary array; everything else stays easily extensible JSON.
 * Integrity is provided by the page CRC32C of the metastore and atomic commits.
 */
public final class ManifestCodec {

    static final int VERSION = 2;

    private static final int SEGMENT_FIXED = 4 + 8 + 1 + 4 + 4 + 4 + 8 + 4 + 4 + 2;

    private ManifestCodec() {
    }

    public static byte[] encode(ManifestEntity m) {
        byte[] json = JsonValues.write(m);
        long[] keys = m.chunkKeyArray();
        List<PartSegment> segments = m.getSegments();
        byte[][] blobIds = new byte[segments.size()][];
        int segBytes = 4;
        for (int i = 0; i < blobIds.length; i++) {
            String id = segments.get(i).smallBlobId();
            blobIds[i] = id == null ? new byte[0] : id.getBytes(StandardCharsets.UTF_8);
            segBytes += SEGMENT_FIXED + blobIds[i].length;
        }
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + json.length + 4 + 8 * keys.length + segBytes);
        b.put((byte) VERSION).putInt(json.length).put(json).putInt(keys.length);
        for (long k : keys) b.putLong(k);
        b.putInt(segments.size());
        for (int i = 0; i < blobIds.length; i++) {
            PartSegment s = segments.get(i);
            b.putInt(s.partNumber()).putLong(s.length()).put((byte) s.kind().ordinal())
                    .putInt(s.chunkSize()).putInt(s.lastChunkSize()).putInt(s.chunkCount())
                    .putLong(s.smallOffset()).putInt(s.smallLength()).putInt(s.smallCrc32c())
                    .putShort((short) blobIds[i].length).put(blobIds[i]);
        }
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
            if (version != 1 && version != VERSION) throw new IllegalStateException("unsupported manifest record version " + version);
            int jsonLen = b.getInt();
            if (jsonLen < 2 || jsonLen > b.remaining() - 4) throw new IllegalStateException("bad JSON length " + jsonLen);
            ManifestEntity m = JsonValues.read(value, b.position(), jsonLen, ManifestEntity.class);
            b.position(b.position() + jsonLen);
            int count = b.getInt();
            if (count < 0 || (long) count * 8 > b.remaining()) {
                throw new IllegalStateException("bad chunk key count " + count);
            }
            if (withChunkKeys) {
                long[] keys = new long[count];
                for (int i = 0; i < count; i++) keys[i] = b.getLong();
                m.setChunkKeyArray(keys);
            } else {
                b.position(b.position() + count * 8);
            }
            if (version == 1) {
                if (b.hasRemaining()) throw new IllegalStateException("bad chunk key count " + count);
                return m;
            }
            int segCount = b.getInt();
            if (segCount < 0 || (long) segCount * SEGMENT_FIXED > b.remaining()) {
                throw new IllegalStateException("bad segment count " + segCount);
            }
            if (withChunkKeys && segCount > 0) {
                StorageKind[] kinds = StorageKind.values();
                List<PartSegment> segments = new ArrayList<>(segCount);
                for (int i = 0; i < segCount; i++) {
                    int partNumber = b.getInt();
                    long length = b.getLong();
                    StorageKind kind = kinds[b.get()];
                    int chunkSize = b.getInt();
                    int lastChunkSize = b.getInt();
                    int chunkCount = b.getInt();
                    long smallOffset = b.getLong();
                    int smallLength = b.getInt();
                    int smallCrc = b.getInt();
                    byte[] id = new byte[b.getShort() & 0xFFFF];
                    b.get(id);
                    segments.add(new PartSegment(partNumber, length, kind, chunkSize, lastChunkSize, chunkCount,
                            id.length == 0 ? null : new String(id, StandardCharsets.UTF_8), smallOffset, smallLength, smallCrc));
                }
                m.setSegments(segments);
            }
            return m;
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalStateException("truncated manifest record", e);
        }
    }
}
