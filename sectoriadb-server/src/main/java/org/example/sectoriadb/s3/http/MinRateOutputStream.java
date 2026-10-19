package org.example.sectoriadb.s3.http;

import org.example.sectoriadb.s3.error.ResponseAbortedException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongSupplier;

/**
 * Slow-reader guard for response bodies: Tomcat's write timeout restarts every time the peer accepts a few bytes, so a
 * client that reads one TCP window per minute would keep a worker thread for the whole transfer. When the rate at which
 * the peer takes the body over the last window drops below the minimum (after the grace period) the response is
 * aborted, which closes the connection.
 */
public final class MinRateOutputStream extends FilterOutputStream {

    private final SlidingRate rate;

    public MinRateOutputStream(OutputStream out, long graceNanos, long minBytesPerSec, long windowNanos) {
        this(out, graceNanos, minBytesPerSec, windowNanos, System::nanoTime);
    }

    public MinRateOutputStream(OutputStream out, long graceNanos, long minBytesPerSec, long windowNanos, LongSupplier clock) {
        super(out);
        this.rate = new SlidingRate(graceNanos, minBytesPerSec, windowNanos, clock);
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        account(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        account(len);
    }

    private void account(int n) {
        if (rate.record(n)) {
            throw new ResponseAbortedException("Aborting response: the client reads too slowly", null);
        }
    }
}
