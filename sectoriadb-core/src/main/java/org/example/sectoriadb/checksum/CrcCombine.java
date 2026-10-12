package org.example.sectoriadb.checksum;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Combines the CRCs of two adjacent byte ranges into the CRC of their concatenation without reading any data
 * ({@code crc(A || B) = f(crc(A), crc(B), len(B))}), for the three CRC algorithms of S3: CRC32, CRC32C and CRC64NVME.
 *
 * <h3>Math</h3>
 * A reflected CRC with init and xor-out of all ones is a linear map over GF(2) apart from those constants, and the two
 * constants cancel when two CRCs are combined. Feeding {@code n} zero bytes into the register is the {@code n}-th power of
 * a fixed matrix {@code M} (the register after one zero bit, raised to the 8th power for a byte). Therefore
 * <pre>
 *   crc(A || B) = M^len(B) * crc(A)  XOR  crc(B)
 * </pre>
 * {@code M^n} is evaluated as a product of the precomputed matrices {@code M^(2^k)}, one per set bit of {@code n}, so a
 * combine costs at most 40 matrix-vector products of {@code width} steps, independent of the data length (this is zlib's
 * {@code crc32_combine} with the squarings moved out of the call).
 */
public final class CrcCombine {

    private static final int MAX_LENGTH_BITS = 48;   // 256 TiB; objects are far smaller

    private static final class Operators {
        final int width;
        /** powers[k] is the operator that appends 2^k zero BYTES to a register. */
        final long[][] powers = new long[MAX_LENGTH_BITS][];

        Operators(int width, long reflectedPoly) {
            this.width = width;
            long[] bit = new long[width];                       // one zero bit
            bit[0] = reflectedPoly;
            for (int n = 1; n < width; n++) bit[n] = 1L << (n - 1);
            long[] m = bit;
            for (int i = 0; i < 3; i++) m = square(m, width);   // 2, 4, 8 bits = one byte
            powers[0] = m;
            for (int k = 1; k < MAX_LENGTH_BITS; k++) powers[k] = square(powers[k - 1], width);
        }

        long combine(long crc1, long crc2, long len2) {
            if (len2 < 0) throw new IllegalArgumentException("negative length");
            long v = crc1;
            for (int k = 0; len2 != 0 && k < MAX_LENGTH_BITS; k++, len2 >>>= 1) {
                if ((len2 & 1) != 0) v = times(powers[k], v);
            }
            if (len2 != 0) throw new IllegalArgumentException("length too large");
            return v ^ crc2;
        }
    }

    private static final Operators CRC32 = new Operators(32, 0xEDB88320L);
    private static final Operators CRC32C = new Operators(32, 0x82F63B78L);
    private static final Operators CRC64NVME = new Operators(64, 0x9A6C9329AC4BC9B5L);

    private CrcCombine() {
    }

    private static long times(long[] mat, long vec) {
        long sum = 0;
        for (int i = 0; vec != 0; i++, vec >>>= 1) {
            if ((vec & 1) != 0) sum ^= mat[i];
        }
        return sum;
    }

    private static long[] square(long[] mat, int width) {
        long[] sq = new long[width];
        for (int n = 0; n < width; n++) sq[n] = times(mat, mat[n]);
        return sq;
    }

    /** CRC32 (java.util.zip.CRC32 value) of {@code A || B}. */
    public static int crc32(int crcA, int crcB, long lengthB) {
        return (int) CRC32.combine(crcA & 0xFFFFFFFFL, crcB & 0xFFFFFFFFL, lengthB);
    }

    /** CRC32C (java.util.zip.CRC32C value) of {@code A || B}. */
    public static int crc32c(int crcA, int crcB, long lengthB) {
        return (int) CRC32C.combine(crcA & 0xFFFFFFFFL, crcB & 0xFFFFFFFFL, lengthB);
    }

    /** CRC64NVME of {@code A || B}. */
    public static long crc64nvme(long crcA, long crcB, long lengthB) {
        return CRC64NVME.combine(crcA, crcB, lengthB);
    }

    /** True for the algorithms this class can combine (the CRCs). */
    public static boolean supports(ChecksumAlgorithm algorithm) {
        return algorithm == ChecksumAlgorithm.CRC32 || algorithm == ChecksumAlgorithm.CRC32C
                || algorithm == ChecksumAlgorithm.CRC64NVME;
    }

    /**
     * Combines big-endian digests (the form {@link ChecksumAlgorithm#compute} returns and S3 base64-encodes).
     *
     * @throws IllegalArgumentException for SHA-1 / SHA-256, which cannot be combined
     */
    public static byte[] combine(ChecksumAlgorithm algorithm, byte[] digestA, byte[] digestB, long lengthB) {
        return switch (algorithm) {
            case CRC32 -> ByteBuffer.allocate(4)
                    .putInt(crc32(ByteBuffer.wrap(digestA).getInt(), ByteBuffer.wrap(digestB).getInt(), lengthB)).array();
            case CRC32C -> ByteBuffer.allocate(4)
                    .putInt(crc32c(ByteBuffer.wrap(digestA).getInt(), ByteBuffer.wrap(digestB).getInt(), lengthB)).array();
            case CRC64NVME -> ByteBuffer.allocate(8)
                    .putLong(crc64nvme(ByteBuffer.wrap(digestA).getLong(), ByteBuffer.wrap(digestB).getLong(), lengthB)).array();
            default -> throw new IllegalArgumentException(algorithm + " digests cannot be combined");
        };
    }

    /** The digest of the concatenation of consecutive ranges, from their digests and lengths (left to right). */
    public static byte[] combineAll(ChecksumAlgorithm algorithm, List<byte[]> digests, List<Long> lengths) {
        if (digests.isEmpty()) return algorithm.compute(new byte[0]);
        byte[] acc = digests.get(0);
        for (int i = 1; i < digests.size(); i++) acc = combine(algorithm, acc, digests.get(i), lengths.get(i));
        return acc;
    }
}
