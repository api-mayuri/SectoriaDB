package org.example.sectoriadb.checksum;

import java.io.IOException;

/**
 * The checksum the client declared for an upload does not match what was received. Thrown before anything
 * is committed.
 */
public class ChecksumMismatchException extends IOException {

    private final String label;

    private ChecksumMismatchException(String label, String message) {
        super(message);
        this.label = label;
    }

    public static ChecksumMismatchException forAlgorithm(ChecksumAlgorithm alg) {
        return new ChecksumMismatchException(alg.name(),
                "The " + alg.name() + " you specified did not match the calculated checksum.");
    }

    public static ChecksumMismatchException forContentMd5() {
        return new ChecksumMismatchException("Content-MD5",
                "The Content-MD5 you specified did not match what we received.");
    }

    /** "CRC32C", "SHA256", ... or "Content-MD5". */
    public String getLabel() { return label; }
}
