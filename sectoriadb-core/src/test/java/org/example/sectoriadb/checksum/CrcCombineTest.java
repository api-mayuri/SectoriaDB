package org.example.sectoriadb.checksum;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The combined CRC of random splits must equal the CRC of the whole, for every CRC algorithm. */
class CrcCombineTest {

    private static final ChecksumAlgorithm[] CRCS =
            {ChecksumAlgorithm.CRC32, ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.CRC64NVME};

    @Test
    void twoPiecesEqualTheWhole() {
        Random rnd = new Random(7);
        for (ChecksumAlgorithm alg : CRCS) {
            for (int round = 0; round < 200; round++) {
                byte[] data = new byte[rnd.nextInt(5000)];
                rnd.nextBytes(data);
                int cut = data.length == 0 ? 0 : rnd.nextInt(data.length + 1);
                byte[] a = Arrays.copyOfRange(data, 0, cut);
                byte[] b = Arrays.copyOfRange(data, cut, data.length);
                assertArrayEquals(alg.compute(data), CrcCombine.combine(alg, alg.compute(a), alg.compute(b), b.length),
                        alg + " cut " + cut + " of " + data.length);
            }
        }
    }

    @Test
    void manyRandomPiecesEqualTheWhole() {
        Random rnd = new Random(11);
        for (ChecksumAlgorithm alg : CRCS) {
            byte[] data = new byte[300_000];
            rnd.nextBytes(data);
            List<byte[]> digests = new ArrayList<>();
            List<Long> lengths = new ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                int len = Math.min(data.length - pos, rnd.nextInt(40_000));   // includes empty pieces
                byte[] piece = Arrays.copyOfRange(data, pos, pos + len);
                digests.add(alg.compute(piece));
                lengths.add((long) len);
                pos += len;
            }
            assertArrayEquals(alg.compute(data), CrcCombine.combineAll(alg, digests, lengths), alg.name());
        }
    }

    @Test
    void largeLengthsWithoutData() {
        // crc(A || zeros(n)) for a long zero run, checked against streaming the zeros
        for (ChecksumAlgorithm alg : CRCS) {
            byte[] a = "head".getBytes();
            long n = 5_000_000L;
            ChecksumCalculator direct = alg.newCalculator();
            direct.update(a, 0, a.length);
            byte[] zeros = new byte[100_000];
            for (long done = 0; done < n; done += zeros.length) direct.update(zeros, 0, zeros.length);
            ChecksumCalculator onlyZeros = alg.newCalculator();
            for (long done = 0; done < n; done += zeros.length) onlyZeros.update(zeros, 0, zeros.length);
            long total = (n + zeros.length - 1) / zeros.length * zeros.length;   // what was actually fed
            assertArrayEquals(direct.digest(), CrcCombine.combine(alg, alg.compute(a), onlyZeros.digest(), total));
        }
    }

    @Test
    void knownVectorSplit() {
        byte[] whole = "123456789".getBytes();
        assertEquals(0xE3069283, CrcCombine.crc32c(
                (int) crc(ChecksumAlgorithm.CRC32C, "1234".getBytes()), (int) crc(ChecksumAlgorithm.CRC32C, "56789".getBytes()), 5));
        assertEquals(0xCBF43926, CrcCombine.crc32(
                (int) crc(ChecksumAlgorithm.CRC32, "12345".getBytes()), (int) crc(ChecksumAlgorithm.CRC32, "6789".getBytes()), 4));
        assertEquals(0xAE8B14860A799888L, CrcCombine.crc64nvme(
                crc(ChecksumAlgorithm.CRC64NVME, "123".getBytes()), crc(ChecksumAlgorithm.CRC64NVME, "456789".getBytes()), 6));
        assertEquals(9, whole.length);
    }

    @Test
    void emptySecondPieceKeepsTheFirst() {
        for (ChecksumAlgorithm alg : CRCS) {
            byte[] d = alg.compute("abc".getBytes());
            assertArrayEquals(d, CrcCombine.combine(alg, d, alg.compute(new byte[0]), 0));
        }
    }

    @Test
    void shaCannotBeCombined() {
        assertThrows(IllegalArgumentException.class,
                () -> CrcCombine.combine(ChecksumAlgorithm.SHA256, new byte[32], new byte[32], 1));
    }

    private static long crc(ChecksumAlgorithm alg, byte[] data) {
        byte[] d = alg.compute(data);
        ByteBuffer b = ByteBuffer.wrap(d);
        return d.length == 4 ? b.getInt() : b.getLong();
    }
}
