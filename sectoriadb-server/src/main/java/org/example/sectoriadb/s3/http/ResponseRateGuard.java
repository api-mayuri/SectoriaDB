package org.example.sectoriadb.s3.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.time.Duration;

/** Wraps a response body in the slow-reader guard configured by {@code sectoriadb.s3.limits.min-response-*}. */
@Component
public class ResponseRateGuard {

    private final long graceNanos;
    private final long minBytesPerSecond;
    private final long windowNanos;

    public ResponseRateGuard(
            @Value("${sectoriadb.s3.limits.min-response-rate-grace:30s}") Duration grace,
            @Value("${sectoriadb.s3.limits.min-response-bytes-per-second:1024}") long minBytesPerSecond,
            @Value("${sectoriadb.s3.limits.min-rate-window:30s}") Duration window) {
        this.graceNanos = grace.toNanos();
        this.minBytesPerSecond = minBytesPerSecond;
        this.windowNanos = window.toNanos();
    }

    public OutputStream wrap(OutputStream out) {
        return minBytesPerSecond <= 0 ? out : new MinRateOutputStream(out, graceNanos, minBytesPerSecond, windowNanos);
    }
}
