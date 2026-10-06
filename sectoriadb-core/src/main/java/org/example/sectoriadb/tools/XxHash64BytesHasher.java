package org.example.sectoriadb.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * XXH64 (xxHash, 64-bit variant) as specified at https://github.com/Cyan4973/xxHash.
 * Hashes the remaining bytes of the buffer (position..limit); the buffer's position is not changed.
 */
public class XxHash64BytesHasher implements BytesHasher {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    @Override
    public long hash64(ByteBuffer buffer, long seed) {
        if (buffer == null) {
            return hash64(ByteBuffer.allocate(0), seed);
        }
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int p = b.position();
        int end = b.limit();
        long len = end - p;
        long h;

        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            int limit = end - 32;
            do {
                v1 = round(v1, b.getLong(p));
                v2 = round(v2, b.getLong(p + 8));
                v3 = round(v3, b.getLong(p + 16));
                v4 = round(v4, b.getLong(p + 24));
                p += 32;
            } while (p <= limit);
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;

        while (p + 8 <= end) {
            h ^= round(0, b.getLong(p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h ^= (b.getInt(p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (b.get(p) & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }

        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long mergeRound(long acc, long val) {
        acc ^= round(0, val);
        return acc * P1 + P4;
    }
}
