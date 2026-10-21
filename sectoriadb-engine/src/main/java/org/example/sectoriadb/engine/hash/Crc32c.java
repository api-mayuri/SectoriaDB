package org.example.sectoriadb.engine.hash;

import java.util.zip.CRC32C;

/** CRC32C of a byte range as the signed {@code int} the on-disk formats store. */
public final class Crc32c {
    private Crc32c() {
    }

    public static int of(byte[] data, int off, int len) {
        CRC32C c = new CRC32C();
        c.update(data, off, len);
        return (int) c.getValue();
    }
}
