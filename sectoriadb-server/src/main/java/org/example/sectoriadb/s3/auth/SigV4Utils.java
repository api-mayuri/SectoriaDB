package org.example.sectoriadb.s3.auth;

import org.example.sectoriadb.s3.S3Support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;

/**
 * AWS Signature Version 4 computation utilities.
 *
 * Reference: https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 */
public final class SigV4Utils {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String STREAMING_PAYLOAD = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";

    private SigV4Utils() {}

    // ── Canonical Request ─────────────────────────────────────────────────────

    /**
     * Builds the canonical request string.
     *
     * Format:
     *   HTTPMethod\n
     *   CanonicalURI\n
     *   CanonicalQueryString\n
     *   CanonicalHeaders\n
     *   SignedHeaders\n
     *   HexEncode(Hash(RequestPayload))
     */
    public static String buildCanonicalRequest(
            String httpMethod,
            String uri,
            String queryString,
            Map<String, String> signedHeadersMap,
            String payloadHash) {

        String canonicalUri     = canonicalizeUri(uri);
        String canonicalQuery   = canonicalizeQuery(queryString);
        String canonicalHeaders = canonicalizeHeaders(signedHeadersMap);
        String signedHeaders    = String.join(";", new TreeSet<>(signedHeadersMap.keySet()));

        return httpMethod + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
    }

    /**
     * Canonicalize URI: percent-encode path segments (not the slash separators).
     * S3 does NOT normalize the path (preserve double-slashes, dots, etc.).
     */
    public static String canonicalizeUri(String uri) {
        if (uri == null || uri.isEmpty()) return "/";
        String[] segments = uri.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            // Decode first to avoid double-encoding an already percent-encoded URI
            // Use percent-decode that leaves '+' untouched (unlike URLDecoder)
            String decoded = S3Support.percentDecode(segments[i]);
            sb.append(uriEncode(decoded, false));
        }
        return sb.toString();
    }

    /**
     * Canonicalize query string: sort by key (then value), URL-encode each, join with &.
     */
    public static String canonicalizeQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) return "";
        Map<String, List<String>> params = new TreeMap<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key   = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            key   = uriEncode(URLDecoder.decode(key,   StandardCharsets.UTF_8), true);
            value = uriEncode(URLDecoder.decode(value, StandardCharsets.UTF_8), true);
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        // Exclude X-Amz-Signature from the canonical query when computing presigned URLs
        params.remove("X-Amz-Signature");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            Collections.sort(e.getValue());
            for (String v : e.getValue()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(e.getKey()).append('=').append(v);
            }
        }
        return sb.toString();
    }

    /**
     * Canonicalize headers: sort by lowercase name, trim and collapse whitespace in values.
     */
    public static String canonicalizeHeaders(Map<String, String> headersMap) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(headersMap).forEach((k, v) -> {
            String trimmed = v.trim();
            // Collapse consecutive spaces into single space
            String collapsed = trimmed.replaceAll("\\s+", " ");
            sb.append(k.toLowerCase()).append(':').append(collapsed).append('\n');
        });
        return sb.toString();
    }

    // ── String To Sign ────────────────────────────────────────────────────────

    public static String buildStringToSign(
            String timestamp,       // ISO8601 compact: 20260928T120000Z
            String credentialScope, // YYYYMMDD/region/service/aws4_request
            String canonicalRequestHash) {

        return ALGORITHM + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + canonicalRequestHash;
    }

    // ── Signing Key ───────────────────────────────────────────────────────────

    public static byte[] deriveSigningKey(String secretKey, String date,
                                          String region, String service) {
        byte[] kDate    = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion  = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    // ── Signature ─────────────────────────────────────────────────────────────

    public static String computeSignature(byte[] signingKey, String stringToSign) {
        return hexEncode(hmacSha256(signingKey, stringToSign));
    }

    // ── Payload hash ──────────────────────────────────────────────────────────

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hexEncode(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256HexEmpty() {
        return sha256Hex(new byte[0]);
    }

    // ── Credential scope helpers ──────────────────────────────────────────────

    public static String credentialScope(String date, String region, String service) {
        return date + "/" + region + "/" + service + "/aws4_request";
    }

    // ── Low-level helpers ─────────────────────────────────────────────────────

    public static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public static String hexEncode(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * URI-encode a string component.
     *
     * @param encodeSlash when false, slashes are left unencoded (for URI path)
     */
    public static String uriEncode(String value, boolean encodeSlash) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        // By code point, not by char: a character outside the BMP (emoji, rare CJK) is a surrogate PAIR in Java, and
        // encoding each half separately yields "?" bytes, i.e. a wrong canonical URI and SignatureDoesNotMatch.
        value.codePoints().forEach(cp -> {
            if (cp < 0x80 && (isUnreserved((char) cp) || (!encodeSlash && cp == '/'))) {
                result.append((char) cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    result.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
                }
            }
        });
        return result.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }
}
