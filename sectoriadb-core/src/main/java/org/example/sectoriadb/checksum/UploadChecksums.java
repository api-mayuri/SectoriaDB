package org.example.sectoriadb.checksum;

import java.security.MessageDigest;

/**
 * What the client asked to be checked (and stored) for one upload: Content-MD5, plus at most one additional
 * checksum algorithm with an optional expected value. The expected value may arrive late (aws-chunked trailer),
 * so it is settable until {@link #verify} is called.
 */
public final class UploadChecksums {

    private byte[] contentMd5;
    private ChecksumAlgorithm algorithm;
    private byte[] expected;

    public static UploadChecksums none() { return new UploadChecksums(); }

    /** Compute and store {@code algorithm} for the object without comparing it to anything. */
    public static UploadChecksums compute(ChecksumAlgorithm algorithm) {
        UploadChecksums c = new UploadChecksums();
        c.algorithm = algorithm;
        return c;
    }

    public UploadChecksums contentMd5(byte[] md5) { this.contentMd5 = md5; return this; }

    public UploadChecksums algorithm(ChecksumAlgorithm algorithm, byte[] expected) {
        this.algorithm = algorithm;
        this.expected = expected;
        return this;
    }

    public UploadChecksums expected(byte[] expected) { this.expected = expected; return this; }

    public ChecksumAlgorithm algorithm() { return algorithm; }

    public byte[] expected() { return expected; }

    public byte[] contentMd5() { return contentMd5; }

    /** Compares the declared values with the computed digests; the digest must include MD5 and {@link #algorithm()}. */
    public void verify(MultiDigest digest) throws ChecksumMismatchException {
        if (contentMd5 != null && !MessageDigest.isEqual(contentMd5, digest.md5())) {
            throw ChecksumMismatchException.forContentMd5();
        }
        if (algorithm != null && expected != null && !MessageDigest.isEqual(expected, digest.digest(algorithm))) {
            throw ChecksumMismatchException.forAlgorithm(algorithm);
        }
    }
}
