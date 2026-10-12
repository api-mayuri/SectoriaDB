package org.example.sectoriadb.s3.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.s3.http.MinRateInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Slow-body guard for every request: the body stream is wrapped so that a client that sends fewer than
 * {@code sectoriadb.s3.limits.min-body-bytes-per-second} bytes per second (averaged after
 * {@code ...min-body-rate-grace}) is answered 400 RequestTimeout instead of holding a worker thread for as long as it
 * trickles bytes. Runs before authentication so that signature verification and aws-chunked decoding read through it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestBodyGuardFilter extends OncePerRequestFilter {

    private final long graceNanos;
    private final long minBytesPerSecond;

    public RequestBodyGuardFilter(
            @Value("${sectoriadb.s3.limits.min-body-rate-grace:30s}") Duration grace,
            @Value("${sectoriadb.s3.limits.min-body-bytes-per-second:1024}") long minBytesPerSecond) {
        this.graceNanos = grace.toNanos();
        this.minBytesPerSecond = minBytesPerSecond;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (minBytesPerSecond <= 0 || request.getContentLengthLong() == 0) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new Guarded(request), response);
    }

    private final class Guarded extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Guarded(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream raw = super.getInputStream();
                MinRateInputStream guarded = new MinRateInputStream(raw, graceNanos, minBytesPerSecond);
                stream = new ServletInputStream() {
                    @Override public int read() throws IOException { return guarded.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return guarded.read(b, off, len); }
                    @Override public boolean isFinished() { return raw.isFinished(); }
                    @Override public boolean isReady() { return raw.isReady(); }
                    @Override public void setReadListener(ReadListener l) { raw.setReadListener(l); }
                    @Override public void close() throws IOException { guarded.close(); }
                };
            }
            return stream;
        }
    }
}
