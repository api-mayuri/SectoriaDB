package org.example.sectoriadb;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.Crc64Nvme;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumAlgorithmTest {

    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);

    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }

    @Test
    void knownVectors() {
        assertEquals("e3069283", hex(ChecksumAlgorithm.CRC32C.compute(CHECK)));
        assertEquals("cbf43926", hex(ChecksumAlgorithm.CRC32.compute(CHECK)));
        assertEquals("ae8b14860a799888", hex(ChecksumAlgorithm.CRC64NVME.compute(CHECK)));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(ChecksumAlgorithm.SHA1.compute(ABC)));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hex(ChecksumAlgorithm.SHA256.compute(ABC)));
    }

    @Test
    void emptyInputVectors() {
        byte[] none = new byte[0];
        assertEquals("00000000", hex(ChecksumAlgorithm.CRC32C.compute(none)));
        assertEquals("0000000000000000", hex(ChecksumAlgorithm.CRC64NVME.compute(none)));
        assertEquals("AAAAAA==", ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.CRC32C.compute(none)));
    }

    @Test
    void digestsAreBase64OfBigEndianBytes() {
        assertEquals("4waSgw==", ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.CRC32C.compute(CHECK)));
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
                ChecksumAlgorithm.SHA256.encode(ChecksumAlgorithm.SHA256.compute(ABC)));
        assertArrayEquals(ChecksumAlgorithm.CRC32C.compute(CHECK), ChecksumAlgorithm.CRC32C.decode("4waSgw=="));
    }

    @Test
    void decodeRejectsGarbageAndWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> ChecksumAlgorithm.CRC32C.decode("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> ChecksumAlgorithm.CRC32C.decode("AAAAAAAAAAA="));   // 8 bytes
        assertThrows(IllegalArgumentException.class, () -> ChecksumAlgorithm.SHA256.decode("4waSgw=="));
    }

    @Test
    void incrementalUpdatesEqualOneShot() {
        byte[] data = new byte[100_000];
        new java.util.Random(1).nextBytes(data);
        for (ChecksumAlgorithm a : ChecksumAlgorithm.values()) {
            var c = a.newCalculator();
            c.update(data, 0, 7);
            c.update(data, 7, 50_000);
            c.update(data, 50_007, data.length - 50_007);
            assertArrayEquals(a.compute(data), c.digest(), a.name());
        }
    }

    @Test
    void crc64MatchesTableFreeBitwiseReference() {
        byte[] data = new byte[1000];
        new java.util.Random(2).nextBytes(data);
        long crc = -1L;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int k = 0; k < 8; k++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x9A6C9329AC4BC9B5L : crc >>> 1;
        }
        Crc64Nvme c = new Crc64Nvme();
        c.update(data, 0, data.length);
        assertEquals(~crc, c.getValue());
        c.reset();
        c.update(CHECK, 0, CHECK.length);
        assertEquals(0xAE8B14860A799888L, c.getValue());
    }

    @Test
    void nameAndHeaderLookup() {
        assertEquals(ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.fromAwsName("crc32c").orElseThrow());
        assertEquals(ChecksumAlgorithm.CRC64NVME, ChecksumAlgorithm.fromHeader("X-Amz-Checksum-CRC64NVME").orElseThrow());
        assertTrue(ChecksumAlgorithm.fromAwsName("MD5").isEmpty());
        assertTrue(ChecksumAlgorithm.fromHeader("x-amz-checksum-type").isEmpty());
        assertFalse(ChecksumAlgorithm.SHA1.supportsFullObject());
        assertFalse(ChecksumAlgorithm.CRC64NVME.supportsComposite());
    }

    @Test
    void compositeIsAlgorithmOverConcatenatedPartDigestsWithCountSuffix() {
        byte[] p1 = "part one".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "part two!".getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = List.of(ChecksumAlgorithm.CRC32.compute(p1), ChecksumAlgorithm.CRC32.compute(p2));
        // independent expectation: java.util.zip.CRC32 over the 8 concatenated bytes
        CRC32 ref = new CRC32();
        CRC32 a = new CRC32(); a.update(p1);
        CRC32 b = new CRC32(); b.update(p2);
        ref.update(ChecksumAlgorithm.crc32Bytes((int) a.getValue()));
        ref.update(ChecksumAlgorithm.crc32Bytes((int) b.getValue()));
        String expected = ChecksumAlgorithm.CRC32.encode(ChecksumAlgorithm.crc32Bytes((int) ref.getValue())) + "-2";
        assertEquals(expected, ChecksumAlgorithm.CRC32.composite(parts));
    }

    @Test
    void multiDigestComputesEverythingInOnePass() throws Exception {
        MultiDigest d = new MultiDigest(true, EnumSet.allOf(ChecksumAlgorithm.class));
        try (var in = new MultiDigestInputStream(new ByteArrayInputStream(CHECK), d)) {
            in.read();                       // single-byte path
            in.skip(2);                      // skipped bytes are still digested
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        d.finish();
        assertEquals(9, d.count());
        assertEquals("e3069283", hex(d.digest(ChecksumAlgorithm.CRC32C)));
        assertEquals("cbf43926", hex(d.digest(ChecksumAlgorithm.CRC32)));
        assertEquals("ae8b14860a799888", hex(d.digest(ChecksumAlgorithm.CRC64NVME)));
        assertEquals("25f9e794323b453885f5181f1b624d0b", hex(d.md5()));
        assertThrows(IllegalStateException.class, () -> d.update(1));
    }
}
