package org.example.sectoriadb.metastore;

/**
 * The file is a SectoriaDB metadata store, but of a format version this build does not read (written by a newer
 * version, or damaged in exactly the version field). Opening stops instead of guessing: an older build must not
 * interpret, and never rewrites, a newer file.
 */
public class UnsupportedFormatException extends RuntimeException {

    private final int found;
    private final int supported;

    public UnsupportedFormatException(int found, int supported) {
        super("sectoria.db has metastore format version " + found + "; this build reads version " + supported
                + " only. Start the SectoriaDB version that wrote it (a newer one), or restore a backup made with this one.");
        this.found = found;
        this.supported = supported;
    }

    public int found() {
        return found;
    }

    public int supported() {
        return supported;
    }
}
