package org.example.sectoriadb.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.sectoriadb.s3.auth.AuthFailure;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * RED metrics of the S3 API (see the catalogue in docs/architecture/08-observability-bench.md). All labels are
 * closed sets: operation ({@link S3Operation}), status class, S3 error code (validated), auth failure reason.
 */
@Component
public class S3Metrics {

    private static final String[] STATUS_CLASSES = {"2xx", "3xx", "4xx", "5xx", "other"};

    private final MeterRegistry registry;
    private final AtomicReferenceArray<Timer> requestTimers =
            new AtomicReferenceArray<>(S3Operation.values().length * STATUS_CLASSES.length);
    private final Timer[] ttfbTimer = new Timer[1];
    private final Counter[] requestBytes = new Counter[S3Operation.values().length];
    private final Counter[] responseBytes = new Counter[S3Operation.values().length];
    private final Counter[] aborted = new Counter[S3Operation.values().length];
    private final Counter[] slow = new Counter[S3Operation.values().length];
    private final AtomicInteger[] inflight = new AtomicInteger[S3Operation.values().length];
    private final Map<String, Counter> errorCodes = new ConcurrentHashMap<>();
    private final Map<AuthFailure, Counter> authFailures = new EnumMap<>(AuthFailure.class);

    public S3Metrics(MeterRegistry registry) {
        this.registry = registry;
        for (S3Operation op : S3Operation.values()) {
            int i = op.ordinal();
            String o = op.label();
            requestBytes[i] = Counter.builder("sectoriadb.s3.request.bytes")
                    .description("Request bytes received from clients (raw wire bytes of the body, aws-chunked framing included)")
                    .baseUnit("bytes").tag("operation", o).register(registry);
            responseBytes[i] = Counter.builder("sectoriadb.s3.response.bytes")
                    .description("Response body bytes written to clients")
                    .baseUnit("bytes").tag("operation", o).register(registry);
            aborted[i] = Counter.builder("sectoriadb.s3.requests.aborted")
                    .description("Requests whose processing ended with an exception that escaped the S3 error handling "
                            + "(e.g. a GET aborted after the headers were sent because stored data failed verification)")
                    .tag("operation", o).register(registry);
            slow[i] = Counter.builder("sectoriadb.s3.requests.slow")
                    .description("Requests slower than sectoriadb.observability.slow-request-ms")
                    .tag("operation", o).register(registry);
            AtomicInteger g = new AtomicInteger();
            inflight[i] = g;
            Gauge.builder("sectoriadb.s3.requests.inflight", g, AtomicInteger::get)
                    .description("Requests currently being processed")
                    .tag("operation", o).strongReference(true).register(registry);
        }
        for (AuthFailure f : AuthFailure.values()) {
            authFailures.put(f, Counter.builder("sectoriadb.s3.auth.failures")
                    .description("Requests rejected by SigV4 authentication, by reason")
                    .tag("reason", f.label()).register(registry));
        }
        ttfbTimer[0] = Timer.builder("sectoriadb.s3.get.ttfb")
                .description("GetObject: time from request start to the first body byte written")
                .serviceLevelObjectives(Buckets.REQUEST).register(registry);
    }

    public void started(S3Operation op) {
        inflight[op.ordinal()].incrementAndGet();
    }

    public void finished(S3Operation op, int status, long nanos, long bytesIn, long bytesOut, String errorCode,
                         AuthFailure authFailure, boolean abortedByException) {
        int i = op.ordinal();
        inflight[i].decrementAndGet();
        requestTimer(op, statusClass(status)).record(nanos, TimeUnit.NANOSECONDS);
        if (bytesIn > 0) requestBytes[i].increment(bytesIn);
        if (bytesOut > 0) responseBytes[i].increment(bytesOut);
        if (abortedByException) aborted[i].increment();
        if (status >= 400 || abortedByException) {
            String code = errorCode != null ? ObservabilityAttributes.safeCode(errorCode)
                    : abortedByException ? "Aborted" : "Http" + status;
            errorCodes.computeIfAbsent(op.label() + '/' + code, k -> Counter.builder("sectoriadb.s3.errors")
                    .description("S3 error responses by operation and S3 error code")
                    .tag("operation", op.label()).tag("code", code).register(registry)).increment();
        }
        if (authFailure != null) authFailures.get(authFailure).increment();
    }

    public void recordTtfb(long nanos) {
        ttfbTimer[0].record(nanos, TimeUnit.NANOSECONDS);
    }

    public void slowRequest(S3Operation op) {
        slow[op.ordinal()].increment();
    }

    private Timer requestTimer(S3Operation op, String statusClass) {
        int sc = 0;
        while (sc < STATUS_CLASSES.length - 1 && !STATUS_CLASSES[sc].equals(statusClass)) sc++;
        int idx = op.ordinal() * STATUS_CLASSES.length + sc;
        Timer t = requestTimers.get(idx);
        if (t == null) {
            t = Timer.builder("sectoriadb.s3.requests")
                    .description("S3 API request latency, from the first byte of the request until the response body is written")
                    .serviceLevelObjectives(Buckets.REQUEST)
                    .tag("operation", op.label()).tag("status_class", STATUS_CLASSES[sc]).register(registry);
            requestTimers.set(idx, t);
        }
        return t;
    }

    static String statusClass(int status) {
        if (status >= 200 && status < 300) return "2xx";
        if (status >= 300 && status < 400) return "3xx";
        if (status >= 400 && status < 500) return "4xx";
        if (status >= 500 && status < 600) return "5xx";
        return "other";
    }
}
