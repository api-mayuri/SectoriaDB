package org.example.sectoriadb.s3;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongSupplier;

/**
 * Slow-body guard: after a grace period the average rate since the stream was opened must stay above a minimum,
 * otherwise a worker thread would be held by a client that trickles bytes. The check runs when a read returns; a client
 * that sends nothing at all is cut by the Tomcat connection timeout.
 */
public final class MinRateInputStream extends FilterInputStream {

    private final long graceNanos;
    private final long minBytesPerSec;
    private final LongSupplier nanoClock;
    private final long start;
    private long total;

    public MinRateInputStream(InputStream in, long graceNanos, long minBytesPerSec) {
        this(in, graceNanos, minBytesPerSec, System::nanoTime);
    }

    public MinRateInputStream(InputStream in, long graceNanos, long minBytesPerSec, LongSupplier nanoClock) {
        super(in);
        this.graceNanos = graceNanos;
        this.minBytesPerSec = minBytesPerSec;
        this.nanoClock = nanoClock;
        this.start = nanoClock.getAsLong();
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        account(b >= 0 ? 1 : 0);
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        account(Math.max(n, 0));
        return n;
    }

    private void account(int n) {
        total += n;
        if (minBytesPerSec <= 0) return;
        long elapsed = nanoClock.getAsLong() - start;
        if (elapsed > graceNanos && total * 1e9 / elapsed < minBytesPerSec) {
            throw new S3Exception(org.springframework.http.HttpStatus.BAD_REQUEST, "RequestTimeout",
                    "Your socket connection to the server was not read from or written to within the timeout period.");
        }
    }
}
