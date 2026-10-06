package org.example.sectoriadb.tools;

import java.nio.ByteBuffer;

public class MurmurBytesHasher implements BytesHasher {
    private static final long DEFAULT_SEED = 0x2EEA8E7;

    @Override
    public long hash64(byte[] data) {
        if (data == null) return 0;
        return hash64(ByteBuffer.wrap(data));
    }

    @Override
     public long hash64(ByteBuffer buffer) {
        if (buffer == null) return 0;

        ByteBuffer dup = buffer.duplicate();

        long h1 = DEFAULT_SEED;
        long h2 = DEFAULT_SEED;

        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5b332116545a5L;

        while (dup.remaining() >= 16) {
            long k1 = dup.getLong();
            long k2 = dup.getLong();

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int remaining = dup.remaining();

        if (remaining > 0) {
            ByteBuffer tailBuf = ByteBuffer.allocate(16);
            byte[] tailBytes = new byte[remaining];
            dup.get(tailBytes);
            tailBuf.put(tailBytes);
            tailBuf.rewind(); // keep limit=16 so both getLong() calls can read 8 bytes each (zero-padded tail)

            k1 = tailBuf.getLong();
            k2 = tailBuf.getLong();

            k1 *= c1; k1 = Long.rotateLeft(k1, 31); k1 *= c2; h1 ^= k1;
            k2 *= c2; k2 = Long.rotateLeft(k2, 33); k2 *= c1; h2 ^= k2;
        }

        h1 ^= dup.limit();
        h2 ^= dup.limit();

        h1 += h2;
        h2 += h1;

        h1 ^= h1 >>> 33;
        h1 *= 0xff51afd7ed558ccdL;
        h1 ^= h1 >>> 33;
        h1 *= 0xc4ceb9fe1a85ec53L;
        h1 ^= h1 >>> 33;

        return h1;
    }
}
