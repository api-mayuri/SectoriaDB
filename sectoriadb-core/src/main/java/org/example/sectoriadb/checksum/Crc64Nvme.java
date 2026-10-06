package org.example.sectoriadb.checksum;

import java.util.zip.Checksum;

/**
 * CRC-64/NVME (reflected, polynomial 0xAD93D23594C93659, init and xor-out all ones), the CRC64 that S3 calls
 * {@code CRC64NVME}. Check value of "123456789" is 0xAE8B14860A799888.
 */
public final class Crc64Nvme implements Checksum {

    /** The polynomial in reflected form. */
    private static final long POLY_REFLECTED = 0x9A6C9329AC4BC9B5L;
    private static final long[] TABLE = new long[256];

    static {
        for (int i = 0; i < 256; i++) {
            long c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ POLY_REFLECTED : c >>> 1;
            }
            TABLE[i] = c;
        }
    }

    private long crc = -1L;

    @Override
    public void update(int b) {
        crc = TABLE[(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
    }

    @Override
    public void update(byte[] b, int off, int len) {
        long c = crc;
        for (int i = off, end = off + len; i < end; i++) {
            c = TABLE[(int) ((c ^ b[i]) & 0xFF)] ^ (c >>> 8);
        }
        crc = c;
    }

    @Override
    public long getValue() {
        return ~crc;
    }

    @Override
    public void reset() {
        crc = -1L;
    }
}
