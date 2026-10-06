package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.s3.auth.AwsChunkedInputStream;
import org.example.sectoriadb.s3.auth.ChunkSigningContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Small static helpers shared by all S3 controllers. */
public final class S3Support {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private S3Support() {}

    /** The id of the request being served (MDC {@code requestId}, set by the observability filter); a fresh id outside a request. */
    public static String requestId() {
        String id = org.slf4j.MDC.get("requestId");
        return id != null ? id : org.example.sectoriadb.observability.RequestIds.newRequestId();
    }

    /** RFC 1123 date for Last-Modified headers. */
    public static String httpDate(Instant t) { return HTTP_DATE.format(t); }

    /** ISO-8601 with millis, as used inside S3 XML bodies. */
    public static String isoDate(Instant t) { return ISO_MILLIS.format(t); }

    public static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Extracts the percent-decoded object key from a path-style request URI
     * ("/bucket/some%20key" → "some key"). '+' is NOT treated as a space.
     */
    public static String extractKey(HttpServletRequest request, String bucket) {
        String uri = request.getRequestURI();
        String prefix = "/" + bucket + "/";
        if (!uri.startsWith(prefix)) {
            throw S3Exception.invalidArgument("Invalid request URI: " + uri);
        }
        return percentDecode(uri.substring(prefix.length()));
    }

    /** Percent-decoding that leaves '+' untouched (unlike URLDecoder). */
    public static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() + 0 && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Returns the request body, transparently stripping aws-chunked framing
     * (Content-Encoding: aws-chunked, or x-amz-content-sha256: STREAMING-*).
     */
    public static InputStream openBody(HttpServletRequest request) throws IOException {
        return openBody(request, null);
    }

    /**
     * As {@link #openBody(HttpServletRequest)}; when the body is aws-chunked, a trailing checksum is handed to
     * {@code declared} (and checked there) at the end of the stream.
     */
    public static InputStream openBody(HttpServletRequest request, S3Checksums.Declared declared) throws IOException {
        InputStream raw = request.getInputStream();
        String encoding = request.getHeader("Content-Encoding");
        String sha = request.getHeader("x-amz-content-sha256");
        boolean chunked = (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (sha != null && sha.startsWith("STREAMING-"));
        if (!chunked) return raw;
        Object ctx = request.getAttribute(ChunkSigningContext.REQUEST_ATTRIBUTE);
        return new AwsChunkedInputStream(raw, ctx instanceof ChunkSigningContext c ? c : null,
                declared != null ? declared::acceptTrailers : null);
    }
}
