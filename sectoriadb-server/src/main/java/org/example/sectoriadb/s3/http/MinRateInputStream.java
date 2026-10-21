package org.example.sectoriadb.s3.http;

import org.example.sectoriadb.s3.error.S3Exception;
import org.springframework.http.HttpStatus;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongSupplier;

/**
 * Slow-body guard: after a grace period the rate over the last {@code window} (a sliding window, see {@link SlidingRate})
 * must stay above a minimum, otherwise a worker thread would be held by a client that trickles bytes. The check runs when
 * a read returns; a client that sends nothing at all is cut by the Tomcat connection timeout.
 */
public final class MinRateInputStream extends FilterInputStream {

    /** Window used when none is given. */
    public static final long DEFAULT_WINDOW_NANOS = 30_000_000_000L;

    private final SlidingRate rate;

    public MinRateInputStream(InputStream in, long graceNanos, long minBytesPerSec) {
        this(in, graceNanos, minBytesPerSec, DEFAULT_WINDOW_NANOS, System::nanoTime);
    }

    public MinRateInputStream(InputStream in, long graceNanos, long minBytesPerSec, LongSupplier nanoClock) {
        this(in, graceNanos, minBytesPerSec, DEFAULT_WINDOW_NANOS, nanoClock);
    }

    public MinRateInputStream(InputStream in, long graceNanos, long minBytesPerSec, long windowNanos, LongSupplier nanoClock) {
        super(in);
        this.rate = new SlidingRate(graceNanos, minBytesPerSec, windowNanos, nanoClock);
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
        if (rate.record(n)) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "RequestTimeout",
                    "Your socket connection to the server was not read from or written to within the timeout period.");
        }
    }
}
