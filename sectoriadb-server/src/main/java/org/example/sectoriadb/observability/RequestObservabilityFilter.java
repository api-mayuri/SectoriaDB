package org.example.sectoriadb.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.example.sectoriadb.s3.auth.AuthFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The outermost S3 filter (ordered before {@code SigV4Filter}): it gives every request an id, classifies it, fills the
 * MDC, counts in-flight requests, and when the response has been written records latency, status class, S3 error code,
 * auth failure reason, body bytes and slow requests.
 *
 * <p>GET responses are streamed synchronously on the request thread (see {@code S3ObjectController#getObject}), so
 * {@code chain.doFilter} returns only after the last byte was handed to the container; the measured time therefore
 * covers the whole transfer. Only {@code /actuator} is not seen here: it lives on a separate management port with its
 * own servlet context, to which this filter is not registered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "x-amz-request-id";
    public static final String EXTENDED_ID_HEADER = "x-amz-id-2";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_OPERATION = "operation";

    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
    private static final Logger slowLog = LoggerFactory.getLogger("org.example.sectoriadb.observability.SlowRequests");

    private final S3Metrics metrics;
    private final ObservabilityProperties props;

    public RequestObservabilityFilter(S3Metrics metrics, ObservabilityProperties props) {
        this.metrics = metrics;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        String requestId = RequestIds.newRequestId();
        S3Operation op = OperationClassifier.classify(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), request.getHeader("x-amz-copy-source") != null);

        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(EXTENDED_ID_HEADER, RequestIds.newExtendedId());
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_OPERATION, op.label());

        CountingRequest in = new CountingRequest(request);
        CountingResponse out = new CountingResponse(response, op == S3Operation.GetObject, t0);
        metrics.started(op);
        boolean escaped = false;
        try {
            chain.doFilter(in, out);
            if (!request.isAsyncStarted()) {
                out.flushQuietly();   // the last buffered bytes belong to this request's latency
            }
        } catch (IOException | ServletException | RuntimeException | Error e) {
            escaped = true;
            throw e;
        } finally {
            long nanos = System.nanoTime() - t0;
            int status = out.getStatus();
            if (escaped && !response.isCommitted()) status = 500;
            try {
                finish(request, op, requestId, status, nanos, in.bytes(), out.bytes(), out.ttfbNanos(), escaped);
            } catch (RuntimeException e) {
                log.debug("Could not record request metrics: {}", e.toString());
            } finally {
                MDC.remove(MDC_REQUEST_ID);
                MDC.remove(MDC_OPERATION);
            }
        }
    }

    private void finish(HttpServletRequest request, S3Operation op, String requestId, int status, long nanos,
                        long bytesIn, long bytesOut, long ttfbNanos, boolean escaped) {
        Object code = request.getAttribute(ObservabilityAttributes.ERROR_CODE);
        Object reason = request.getAttribute(ObservabilityAttributes.AUTH_FAILURE);
        metrics.finished(op, status, nanos, bytesIn, bytesOut, code instanceof String s ? s : null,
                reason instanceof AuthFailure f ? f : null, escaped);
        if (ttfbNanos >= 0 && (status == 200 || status == 206)) {
            metrics.recordTtfb(ttfbNanos);
        }
        long durationMs = nanos / 1_000_000;
        long slowMs = props.getSlowRequestMs();
        if (slowMs > 0 && durationMs >= slowMs) {
            metrics.slowRequest(op);
            // No bucket, key or credentials here: requestId and operation are also in the MDC
            slowLog.warn("Slow request: requestId={} operation={} status={} durationMs={} requestBytes={} responseBytes={}",
                    requestId, op.label(), status, durationMs, bytesIn, bytesOut);
        }
        if (log.isDebugEnabled()) {
            // The object key is only ever logged at DEBUG
            log.debug("Request done: requestId={} {} {} operation={} status={} durationMs={} requestBytes={} responseBytes={} errorCode={}",
                    requestId, request.getMethod(), request.getRequestURI(), op.label(), status, durationMs,
                    bytesIn, bytesOut, code);
        }
    }

    // ── request wrapper: counts the raw body bytes the container hands to the application ───────────────────────

    private static final class CountingRequest extends HttpServletRequestWrapper {
        private CountingInput stream;

        CountingRequest(HttpServletRequest request) {
            super(request);
        }

        long bytes() {
            return stream == null ? 0 : stream.count;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CountingInput(super.getInputStream());
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc != null ? Charset.forName(enc) : StandardCharsets.ISO_8859_1;
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    private static final class CountingInput extends ServletInputStream {
        private final ServletInputStream in;
        long count;

        CountingInput(ServletInputStream in) {
            this.in = in;
        }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long s = in.skip(n);
            if (s > 0) count += s;
            return s;
        }

        @Override public int available() throws IOException { return in.available(); }
        @Override public boolean isFinished() { return in.isFinished(); }
        @Override public boolean isReady() { return in.isReady(); }
        @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
        @Override public void close() throws IOException { in.close(); }
    }

    // ── response wrapper: counts body bytes, notes the first write, pins the request id headers ─────────────────

    private static final class CountingResponse extends HttpServletResponseWrapper {
        private final boolean wantTtfb;
        private final long t0;
        private CountingOutput stream;
        private PrintWriter writer;
        private long firstWriteNanos = -1;

        CountingResponse(HttpServletResponse response, boolean wantTtfb, long t0) {
            super(response);
            this.wantTtfb = wantTtfb;
            this.t0 = t0;
        }

        long bytes() {
            return stream == null ? 0 : stream.count;
        }

        long ttfbNanos() {
            return firstWriteNanos;
        }

        void flushQuietly() {
            try {
                if (writer != null) writer.flush();
                if (stream != null) flushBuffer();   // only after a body was written: never interferes with sendError()
            } catch (IOException | RuntimeException ignored) {
                // the client may be gone; the request is over either way
            }
        }

        private void firstByte() {
            if (wantTtfb && firstWriteNanos < 0) firstWriteNanos = System.nanoTime() - t0;
        }

        // The request id is fixed by this filter: later setHeader/addHeader calls of controllers are ignored.
        @Override public void setHeader(String name, String value) {
            if (!pinned(name)) super.setHeader(name, value);
        }

        @Override public void addHeader(String name, String value) {
            if (!pinned(name)) super.addHeader(name, value);
        }

        private static boolean pinned(String name) {
            return REQUEST_ID_HEADER.equalsIgnoreCase(name) || EXTENDED_ID_HEADER.equalsIgnoreCase(name);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) stream = new CountingOutput(super.getOutputStream(), this);
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String enc = getCharacterEncoding();
                Charset cs = enc != null ? Charset.forName(enc) : StandardCharsets.ISO_8859_1;
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), cs));
            }
            return writer;
        }
    }

    private static final class CountingOutput extends ServletOutputStream {
        private final ServletOutputStream out;
        private final CountingResponse owner;
        long count;

        CountingOutput(ServletOutputStream out, CountingResponse owner) {
            this.out = out;
            this.owner = owner;
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            if (count++ == 0) owner.firstByte();
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            if (len > 0) {
                if (count == 0) owner.firstByte();
                count += len;
            }
        }

        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
        @Override public boolean isReady() { return out.isReady(); }
        @Override public void setWriteListener(WriteListener l) { out.setWriteListener(l); }
    }
}
