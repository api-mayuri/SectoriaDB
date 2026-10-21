package org.example.sectoriadb.s3.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/** Passes at most {@code max} bytes; the first byte beyond it raises the exception the supplier creates. */
public final class LimitedInputStream extends FilterInputStream {

    private final long max;
    private final Supplier<? extends RuntimeException> tooLarge;
    private long count;

    public LimitedInputStream(InputStream in, long max, Supplier<? extends RuntimeException> tooLarge) {
        super(in);
        this.max = max;
        this.tooLarge = tooLarge;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0 && ++count > max) throw tooLarge.get();
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) {
            count += n;
            if (count > max) throw tooLarge.get();
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        byte[] sink = new byte[(int) Math.min(8192, Math.max(1, n))];
        long skipped = 0;
        while (skipped < n) {
            int r = read(sink, 0, (int) Math.min(sink.length, n - skipped));
            if (r < 0) break;
            skipped += r;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() { return false; }
}
