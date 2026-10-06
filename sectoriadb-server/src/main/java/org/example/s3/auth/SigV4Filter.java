package org.example.s3.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.model.CredentialEntity;
import org.example.s3.access.AccessControlService;
import org.example.service.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Validates AWS Signature Version 4 on every S3 API request.
 *
 * Supports:
 *  - Authorization header auth  (standard SDK requests)
 *  - Query-parameter auth       (presigned URLs)
 *
 * When sectoriadb.s3.auth.enabled=false the filter passes all requests through
 * (useful for development and testing without credentials).
 */
@Component
public class SigV4Filter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SigV4Filter.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter RFC_1123_DATE_TIME =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final long SKEW_TOLERANCE_SECONDS = 900; // 15 minutes

    private final CredentialService credentialService;
    private final AccessControlService accessControlService;
    private final boolean authEnabled;
    private final String region;

    public SigV4Filter(
            CredentialService credentialService,
            AccessControlService accessControlService,
            @Value("${sectoriadb.s3.auth.enabled:true}") boolean authEnabled,
            @Value("${sectoriadb.s3.region:us-east-1}") String region) {
        this.credentialService = credentialService;
        this.accessControlService = accessControlService;
        this.authEnabled = authEnabled;
        this.region = region;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!authEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // If no credentials are configured, let all requests through
        if (credentialService.listAll().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader   = request.getHeader("Authorization");
        String xAmzDate     = request.getHeader("x-amz-date");
        String queryString  = request.getQueryString();

        // ── Presigned URL (query-parameter auth) ─────────────────────────────
        if (queryString != null && queryString.contains("X-Amz-Signature")) {
            if (!verifyPresigned(request, response)) return;
            chain.doFilter(request, response);
            return;
        }

        // ── Authorization header auth or Anonymous ───────────────────────────
        if (authHeader == null || !authHeader.startsWith(SigV4Utils.ALGORITHM)) {
            // Check if this is an anonymous request that should be allowed
            if (accessControlService.isAnonymousAllowed(request)) {
                chain.doFilter(request, response);
                return;
            }
            // Anonymous access denied
            sendError(response, 403, "AccessDenied",
                    "Access Denied");
            return;
        }

        try {
            verifyHeaderAuth(request, authHeader, xAmzDate);
        } catch (AuthException e) {
            log.warn("SigV4 auth failed: {}", e.getMessage());
            sendError(response, e.httpStatus, e.errorCode, e.getMessage());
            return;
        }

        chain.doFilter(request, response);
    }

    // ── Header-based auth ─────────────────────────────────────────────────────

    private void verifyHeaderAuth(HttpServletRequest request, String authHeader, String xAmzDate)
            throws AuthException {

        // Parse Authorization header:
        // AWS4-HMAC-SHA256 Credential=AKID/YYYYMMDD/region/s3/aws4_request,
        //                  SignedHeaders=..., Signature=...
        Map<String, String> authParts = parseAuthHeader(authHeader);
        String credential     = authParts.getOrDefault("Credential", "");
        String signedHeadersStr = authParts.getOrDefault("SignedHeaders", "");
        String signature      = authParts.getOrDefault("Signature", "");

        String[] credParts = credential.split("/", 5);
        if (credParts.length < 5) throw new AuthException(400, "InvalidArgument",
                "Malformed credential in Authorization header");

        String accessKeyId    = credParts[0];
        String dateStr        = credParts[1];
        String regionFromCred = credParts[2];
        String service        = credParts[3];  // should be "s3"

        // Find secret key
        CredentialEntity cred = credentialService.findByAccessKeyId(accessKeyId)
                .filter(CredentialEntity::isEnabled)
                .orElseThrow(() -> new AuthException(403, "InvalidAccessKeyId",
                        "The access key ID does not exist: " + accessKeyId));

        // Validate timestamp
        String timestamp = resolveTimestamp(request, xAmzDate);
        checkTimestamp(timestamp);

        // Collect signed headers
        List<String> headerNames = Arrays.asList(signedHeadersStr.split(";"));
        Map<String, String> signedHeaders = new TreeMap<>();
        for (String h : headerNames) {
            String val = request.getHeader(h);
            if (val == null && h.equals("host")) {
                val = request.getServerName() + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "");
            }
            signedHeaders.put(h.toLowerCase(), val != null ? val : "");
        }

        // Payload hash
        String payloadHash = resolvePayloadHash(request);

        // Build canonical request
        String canonicalRequest = SigV4Utils.buildCanonicalRequest(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                signedHeaders,
                payloadHash);

        String canonicalRequestHash = SigV4Utils.sha256Hex(
                canonicalRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String credentialScope = SigV4Utils.credentialScope(dateStr, regionFromCred, service);
        String stringToSign = SigV4Utils.buildStringToSign(timestamp, credentialScope, canonicalRequestHash);

        byte[] signingKey = SigV4Utils.deriveSigningKey(cred.getSecretKey(), dateStr, regionFromCred, service);
        String expectedSignature = SigV4Utils.computeSignature(signingKey, stringToSign);

        if (!constantTimeEquals(signature, expectedSignature)) {
            log.debug("Signature mismatch for {}: expected={} got={}",
                    accessKeyId, expectedSignature, signature);
            throw new AuthException(403, "SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided.");
        }
    }

    // ── Presigned URL auth ────────────────────────────────────────────────────

    private boolean verifyPresigned(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            verifyPresignedInternal(request);
            return true;
        } catch (AuthException e) {
            log.warn("Presigned URL auth failed: {}", e.getMessage());
            sendError(response, e.httpStatus, e.errorCode, e.getMessage());
            return false;
        }
    }

    private void verifyPresignedInternal(HttpServletRequest request) throws AuthException {
        Map<String, String> params = parseQueryParams(request.getQueryString());

        String algorithm  = params.getOrDefault("X-Amz-Algorithm", "");
        String credential = params.getOrDefault("X-Amz-Credential", "");
        String xAmzDate   = params.getOrDefault("X-Amz-Date", "");
        String expires    = params.getOrDefault("X-Amz-Expires", "");
        String signedHdrs = params.getOrDefault("X-Amz-SignedHeaders", "");
        String signature  = params.getOrDefault("X-Amz-Signature", "");

        if (!SigV4Utils.ALGORITHM.equals(algorithm)) {
            throw new AuthException(400, "InvalidArgument", "Unsupported algorithm: " + algorithm);
        }

        String[] credParts = credential.split("/", 5);
        if (credParts.length < 5) {
            throw new AuthException(400, "InvalidArgument", "Malformed X-Amz-Credential");
        }

        String accessKeyId    = credParts[0];
        String dateStr        = credParts[1];
        String regionFromCred = credParts[2];
        String service        = credParts[3];

        CredentialEntity cred = credentialService.findByAccessKeyId(accessKeyId)
                .filter(CredentialEntity::isEnabled)
                .orElseThrow(() -> new AuthException(403, "InvalidAccessKeyId",
                        "The access key ID does not exist: " + accessKeyId));

        // Check expiry
        checkPresignedExpiry(xAmzDate, expires);

        // For presigned URLs, the body hash is UNSIGNED-PAYLOAD
        String payloadHash = SigV4Utils.UNSIGNED_PAYLOAD;

        // Signed headers for presigned: usually just "host"
        Map<String, String> signedHeaders = new TreeMap<>();
        for (String h : signedHdrs.split(";")) {
            String val = request.getHeader(h);
            if (val == null && h.equals("host")) {
                val = request.getServerName() + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "");
            }
            signedHeaders.put(h.toLowerCase(), val != null ? val : "");
        }

        String canonicalRequest = SigV4Utils.buildCanonicalRequest(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                signedHeaders,
                payloadHash);

        String canonicalRequestHash = SigV4Utils.sha256Hex(
                canonicalRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String credentialScope = SigV4Utils.credentialScope(dateStr, regionFromCred, service);
        String stringToSign = SigV4Utils.buildStringToSign(xAmzDate, credentialScope, canonicalRequestHash);

        byte[] signingKey = SigV4Utils.deriveSigningKey(cred.getSecretKey(), dateStr, regionFromCred, service);
        String expectedSig = SigV4Utils.computeSignature(signingKey, stringToSign);

        if (!constantTimeEquals(signature, expectedSig)) {
            throw new AuthException(403, "SignatureDoesNotMatch",
                    "Presigned URL signature does not match.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> parseAuthHeader(String authHeader) throws AuthException {
        // Strip "AWS4-HMAC-SHA256 " prefix
        String rest = authHeader.substring(SigV4Utils.ALGORITHM.length()).trim();
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rest.split(",")) {
            part = part.trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                result.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        if (!result.containsKey("Credential") || !result.containsKey("Signature")) {
            throw new AuthException(400, "InvalidArgument", "Malformed Authorization header");
        }
        return result;
    }

    private Map<String, String> parseQueryParams(String queryString) {
        Map<String, String> map = new LinkedHashMap<>();
        if (queryString == null) return map;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                String val = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    private String resolveTimestamp(HttpServletRequest request, String xAmzDate) throws AuthException {
        if (xAmzDate != null && !xAmzDate.isBlank()) return xAmzDate;

        String date = request.getHeader("Date");
        if (date != null && !date.isBlank()) {
            // Convert RFC 1123 date to x-amz-date format (ISO8601 compact)
            try {
                Instant instant = Instant.from(RFC_1123_DATE_TIME.parse(date));
                return DATE_FMT.format(instant);
            } catch (java.time.format.DateTimeParseException e) {
                log.debug("Failed to parse Date header: {}", date);
                throw new AuthException(400, "MissingSecurityHeader",
                        "Request must include x-amz-date or Date header");
            }
        }

        throw new AuthException(400, "MissingSecurityHeader",
                "Request must include x-amz-date or Date header");
    }

    private void checkTimestamp(String timestamp) throws AuthException {
        try {
            Instant requestTime = Instant.from(DATE_FMT.parse(timestamp));
            long diffSeconds = Math.abs(Instant.now().getEpochSecond() - requestTime.getEpochSecond());
            if (diffSeconds > SKEW_TOLERANCE_SECONDS) {
                throw new AuthException(403, "RequestTimeTooSkewed",
                        "The difference between the request time and the current time is too large.");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new AuthException(400, "InvalidArgument", "Cannot parse timestamp: " + timestamp);
        }
    }

    private void checkPresignedExpiry(String xAmzDate, String expiresStr) throws AuthException {
        try {
            Instant requestTime = Instant.from(DATE_FMT.parse(xAmzDate));
            long expiresSeconds = Long.parseLong(expiresStr);

            // Check X-Amz-Expires is within valid range (1..604800 seconds = 1 second to 7 days)
            if (expiresSeconds < 1 || expiresSeconds > 604800) {
                throw new AuthException(400, "InvalidArgument",
                        "X-Amz-Expires must be between 1 and 604800 seconds");
            }

            // Check if request time is not too far in the future (> 15 minutes)
            long nowEpoch = Instant.now().getEpochSecond();
            long requestEpoch = requestTime.getEpochSecond();
            if (requestEpoch > nowEpoch + 900) {  // 15 minutes = 900 seconds
                throw new AuthException(403, "RequestTimeTooSkewed",
                        "The request time is too far in the future");
            }

            // Check if token has expired
            Instant expiry = requestTime.plusSeconds(expiresSeconds);
            if (Instant.now().isAfter(expiry)) {
                throw new AuthException(403, "ExpiredToken", "The provided token has expired.");
            }
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new AuthException(400, "InvalidArgument", "Cannot parse presigned URL parameters");
        }
    }

    private String resolvePayloadHash(HttpServletRequest request) {
        String hash = request.getHeader("x-amz-content-sha256");
        if (hash == null || hash.isBlank()) {
            return SigV4Utils.sha256HexEmpty();
        }
        // UNSIGNED-PAYLOAD or STREAMING-... pass through as-is
        return hash;
    }

    private static boolean constantTimeEquals(String a, String b) {
        try {
            return MessageDigest.isEqual(a.getBytes(), b.getBytes());
        } catch (Exception e) {
            return false;
        }
    }

    private void sendError(HttpServletResponse response, int status,
                           String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/xml;charset=UTF-8");
        String requestId = UUID.randomUUID().toString();
        try (PrintWriter w = response.getWriter()) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<Error><Code>" + code + "</Code>");
            w.write("<Message>" + escapeXml(message) + "</Message>");
            w.write("<RequestId>" + requestId + "</RequestId>");
            w.write("</Error>");
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    static class AuthException extends Exception {
        final int httpStatus;
        final String errorCode;

        AuthException(int httpStatus, String errorCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode  = errorCode;
        }
    }
}
