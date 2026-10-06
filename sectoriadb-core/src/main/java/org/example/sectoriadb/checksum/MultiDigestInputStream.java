package org.example.sectoriadb.checksum;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Feeds everything read through a {@link MultiDigest}. */
public final class MultiDigestInputStream extends FilterInputStream {

    private final MultiDigest digest;

    public MultiDigestInputStream(InputStream in, MultiDigest digest) {
        super(in);
        this.digest = digest;
    }

    public MultiDigest digest() { return digest; }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) digest.update(b);
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) digest.update(buf, off, n);
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        // skipped bytes are part of the content and must be digested too
        if (n <= 0) return 0;
        byte[] tmp = new byte[(int) Math.min(8192, n)];
        long total = 0;
        while (total < n) {
            int r = read(tmp, 0, (int) Math.min(tmp.length, n - total));
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    @Override
    public boolean markSupported() { return false; }

    @Override
    public synchronized void mark(int readlimit) { }

    @Override
    public synchronized void reset() throws IOException { throw new IOException("mark/reset not supported"); }
}
