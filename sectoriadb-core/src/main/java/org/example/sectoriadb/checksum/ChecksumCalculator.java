package org.example.sectoriadb.checksum;

/** Incremental digest of a byte stream. One instance computes exactly one digest; it is not reusable. */
public interface ChecksumCalculator {

    void update(byte[] b, int off, int len);

    /** Finalizes the calculation and returns the digest in big-endian byte order (as S3 transmits it). */
    byte[] digest();
}
