package org.example.sectoriadb.checksum;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Feeds everything written through a {@link MultiDigest}. */
public final class MultiDigestOutputStream extends FilterOutputStream {

    private final MultiDigest digest;

    public MultiDigestOutputStream(OutputStream out, MultiDigest digest) {
        super(out);
        this.digest = digest;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        digest.update(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        digest.update(b, off, len);
    }
}
