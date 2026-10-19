package org.example.sectoriadb.s3.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.observability.AuthFailure;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.observability.RequestIds;
import org.example.sectoriadb.s3.access.AccessControlService;
import org.example.sectoriadb.s3.access.IamAuthorizer;
import org.example.sectoriadb.s3.http.S3Request;
import org.example.sectoriadb.s3.http.UrlCodec;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.security.CredentialEntity;
import org.example.sectoriadb.security.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

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
    private static final DateTimeFormatter RFC_1123_DATE_TIME =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final long SKEW_TOLERANCE_SECONDS = 900; // 15 minutes
    private static final String STREAMING_SIGNED = SigV4Utils.STREAMING_PAYLOAD;
    private static final String STREAMING_SIGNED_TRAILER = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
    private static final String STREAMING_UNSIGNED_TRAILER = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final CredentialService credentialService;
    private final AccessControlService accessControlService;
    private final IamAuthorizer iamAuthorizer;
    private final boolean authEnabled;
    private final String region;
    private final boolean allowOpenSetup;
    private final boolean allowUnsignedPayload;
    private final boolean enforceRegion;
    private final boolean requireSignedHeaders;
    private final int maxInflightWrites;
    private final int maxInflightReads;
    private final Semaphore anonymousSlots;
    private final ConcurrentHashMap<String, Semaphore> writeSlots =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> readSlots =
            new ConcurrentHashMap<>();

    public SigV4Filter(
            CredentialService credentialService,
            AccessControlService accessControlService,
            IamAuthorizer iamAuthorizer,
            @Value("${sectoriadb.s3.auth.enabled:true}") boolean authEnabled,
            @Value("${sectoriadb.s3.region:us-east-1}") String region,
            @Value("${sectoriadb.s3.auth.allow-open-setup:false}") boolean allowOpenSetup,
            @Value("${sectoriadb.s3.auth.allow-unsigned-payload:true}") boolean allowUnsignedPayload,
            @Value("${sectoriadb.s3.auth.enforce-region:true}") boolean enforceRegion,
            @Value("${sectoriadb.s3.auth.require-signed-headers:true}") boolean requireSignedHeaders,
            @Value("${sectoriadb.s3.limits.max-inflight-writes-per-key:32}") int maxInflightWrites,
            @Value("${sectoriadb.s3.limits.max-inflight-reads-per-key:64}") int maxInflightReads,
            @Value("${sectoriadb.s3.limits.max-inflight-anonymous:16}") int maxInflightAnonymous) {
        this.credentialService = credentialService;
        this.accessControlService = accessControlService;
        this.iamAuthorizer = iamAuthorizer;
        this.authEnabled = authEnabled;
        this.region = region;
        this.allowOpenSetup = allowOpenSetup;
        this.allowUnsignedPayload = allowUnsignedPayload;
        this.enforceRegion = enforceRegion;
        this.requireSignedHeaders = requireSignedHeaders;
        this.maxInflightWrites = maxInflightWrites;
        this.maxInflightReads = maxInflightReads;
        this.anonymousSlots = maxInflightAnonymous > 0 ? new Semaphore(maxInflightAnonymous) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!authEnabled) {
            request.setAttribute(S3Request.AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
            chain.doFilter(request, response);
            return;
        }

        // No credentials configured: deny by default (S4). Open setup mode is an explicit opt-in.
        if (credentialService.listAll().isEmpty()) {
            if (allowOpenSetup) {
                request.setAttribute(S3Request.AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
                chain.doFilter(request, response);
                return;
            }
            request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, AuthFailure.OPEN_SETUP_DENIED);
            sendError(request, response, 403, "AccessDenied",
                    "No S3 access keys are configured, so all requests are denied. "
                    + "Create a key with the shell command 'mk-key', or set SECTORIADB_S3_ACCESS_KEY and "
                    + "SECTORIADB_S3_SECRET_KEY (sectoriadb.s3.access-key / sectoriadb.s3.secret-key). "
                    + "To temporarily allow unauthenticated access set sectoriadb.s3.auth.allow-open-setup=true.");
            return;
        }

        String authHeader   = request.getHeader("Authorization");
        String xAmzDate     = request.getHeader("x-amz-date");

        // ── Presigned URL (query-parameter auth): detected by the parameter NAME, not by a substring ──
        boolean presigned;
        try {
            presigned = hasQueryParam(request.getQueryString(), "X-Amz-Signature");
        } catch (IllegalArgumentException e) {
            request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, AuthFailure.MALFORMED_HEADER);
            sendError(request, response, 400, "InvalidArgument", "The query string is malformed.");
            return;
        }
        if (presigned) {
            String key = verifyPresigned(request, response);
            if (key == null) return;
            request.setAttribute(ACCESS_KEY_ATTRIBUTE, key);
            if (!authorized(request, response, key)) return;
            request.setAttribute(S3Request.AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
            proceed(request, response, chain, key);
            return;
        }

        // ── Authorization header auth or Anonymous ───────────────────────────
        if (authHeader == null || !authHeader.startsWith(SigV4Utils.ALGORITHM)) {
            // Check if this is an anonymous request that should be allowed
            if (accessControlService.isAnonymousAllowed(request)) {
                proceed(request, response, chain, null);
                return;
            }
            // Anonymous access denied
            request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, AuthFailure.ANONYMOUS_DENIED);
            sendError(request, response, 403, "AccessDenied",
                    "Access Denied");
            return;
        }

        HttpServletRequest verified;
        try {
            verified = verifyHeaderAuth(request, authHeader, xAmzDate);
        } catch (AuthException e) {
            log.warn("SigV4 auth failed: {}", sanitize(e.getMessage()));
            request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, e.reason);
            sendError(request, response, e.httpStatus, e.errorCode, e.getMessage());
            return;
        }

        String accessKey = (String) request.getAttribute(ACCESS_KEY_ATTRIBUTE);
        if (!authorized(verified, response, accessKey)) return;
        request.setAttribute(S3Request.AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        proceed(verified, response, chain, accessKey);
    }

    /** IAM identity policy + bucket policy of a verified key; writes the 403 AccessDenied itself when the request is refused. */
    private boolean authorized(HttpServletRequest request, HttpServletResponse response, String accessKey)
            throws IOException {
        var denial = iamAuthorizer.authorize(request, accessKey);
        if (denial.isEmpty()) return true;
        request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, AuthFailure.POLICY_DENIED);
        sendError(request, response, 403, "AccessDenied", sanitize(denial.get().message()));
        return false;
    }

    private static final String ACCESS_KEY_ATTRIBUTE = S3Request.ACCESS_KEY_ATTRIBUTE;

    /**
     * Runs the request under the in-flight limit: one valid key cannot occupy every worker thread, and all anonymous
     * requests (public objects, public-read-write buckets) share one small budget of their own ({@code accessKey == null}).
     */
    private void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String accessKey)
            throws IOException, ServletException {
        if (accessKey == null) {
            runLimited(request, response, chain, anonymousSlots,
                    "Too many concurrent anonymous requests. Please reduce your request rate.");
            return;
        }
        String method = request.getMethod();
        boolean write = "PUT".equals(method) || "POST".equals(method);
        int limit = write ? maxInflightWrites : maxInflightReads;
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        var slots = (write ? writeSlots : readSlots)
                .computeIfAbsent(accessKey, k -> new Semaphore(limit));
        runLimited(request, response, chain, slots,
                "Too many concurrent requests for this access key. Please reduce your request rate.");
    }

    private void runLimited(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                            Semaphore slots, String tooManyMessage) throws IOException, ServletException {
        if (slots == null) {   // limit switched off
            chain.doFilter(request, response);
            return;
        }
        if (!slots.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            sendError(request, response, 503, "SlowDown", tooManyMessage);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    /** True when the raw query string has a parameter with exactly this (decoded) name. */
    static boolean hasQueryParam(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) return false;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (UrlCodec.decodeQueryComponent(eq < 0 ? pair : pair.substring(0, eq)).equals(name)) return true;
        }
        return false;
    }

    // ── Header-based auth ─────────────────────────────────────────────────────

    /**
     * Verifies the request signature and returns the request to continue with: when the signed payload hash
     * is a concrete SHA-256 the body is wrapped so it is verified while streaming (S3); for aws-chunked
     * uploads the chunk-signing context is attached as a request attribute.
     */
    private HttpServletRequest verifyHeaderAuth(HttpServletRequest request, String authHeader, String xAmzDate)
            throws AuthException {

        // Parse Authorization header:
        // AWS4-HMAC-SHA256 Credential=AKID/YYYYMMDD/region/s3/aws4_request,
        //                  SignedHeaders=..., Signature=...
        Map<String, String> authParts = parseAuthHeader(authHeader);
        String credential     = authParts.getOrDefault("Credential", "");
        String signedHeadersStr = authParts.getOrDefault("SignedHeaders", "");
        String signature      = authParts.getOrDefault("Signature", "");

        String[] credParts = credential.split("/", 5);
        if (credParts.length < 5) throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument",
                "Malformed credential in Authorization header");

        String accessKeyId    = credParts[0];
        String dateStr        = credParts[1];
        String regionFromCred = credParts[2];
        String service        = credParts[3];  // must be "s3"
        if (!"s3".equals(service) || !"aws4_request".equals(credParts[4])) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "AuthorizationHeaderMalformed",
                    "The authorization header is malformed; the credential scope must end with s3/aws4_request.");
        }
        requireRegion(regionFromCred, "AuthorizationHeaderMalformed");

        // Find secret key
        CredentialEntity cred = requireEnabledKey(accessKeyId);

        // Validate timestamp
        String timestamp = resolveTimestamp(request, xAmzDate);
        checkTimestamp(timestamp);
        if (timestamp.length() != 16 || !timestamp.substring(0, 8).equals(dateStr)) {
            throw new AuthException(AuthFailure.SIGNATURE_MISMATCH, 403, "SignatureDoesNotMatch",
                    "The credential date does not match the request date.");
        }

        // Collect signed headers
        Map<String, String> signedHeaders = collectSignedHeaders(request, signedHeadersStr, true);

        // Payload hash
        String payloadHash = resolvePayloadHash(request);
        if (SigV4Utils.UNSIGNED_PAYLOAD.equals(payloadHash) || STREAMING_UNSIGNED_TRAILER.equals(payloadHash)) {
            if (!allowUnsignedPayload) {
                throw new AuthException(AuthFailure.UNSIGNED_PAYLOAD_DENIED, 400, "InvalidRequest",
                        "Unsigned payloads are not allowed by this server "
                        + "(sectoriadb.s3.auth.allow-unsigned-payload=false); send a signed payload hash.");
            }
        } else if (payloadHash.startsWith("STREAMING-")
                && !STREAMING_SIGNED.equals(payloadHash) && !STREAMING_SIGNED_TRAILER.equals(payloadHash)) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 501, "NotImplemented", "Unsupported streaming payload type: " + sanitize(payloadHash));
        } else if (!payloadHash.startsWith("STREAMING-") && !SigV4Utils.UNSIGNED_PAYLOAD.equals(payloadHash)
                && !HEX_SHA256.matcher(payloadHash).matches()) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "x-amz-content-sha256 must be UNSIGNED-PAYLOAD, "
                    + "STREAMING-*, or a hex SHA-256 digest");
        }

        // Build canonical request
        String canonicalRequestHash = canonicalRequestHash(request, signedHeaders, payloadHash);

        String credentialScope = SigV4Utils.credentialScope(dateStr, regionFromCred, service);
        String stringToSign = SigV4Utils.buildStringToSign(timestamp, credentialScope, canonicalRequestHash);

        byte[] signingKey = SigV4Utils.deriveSigningKey(cred.getSecretKey(), dateStr, regionFromCred, service);
        String expectedSignature = SigV4Utils.computeSignature(signingKey, stringToSign);

        if (!constantTimeEquals(signature, expectedSignature)) {
            // Never log the expected signature, the signing key or the secret: not even at debug level.
            log.debug("Signature mismatch for access key {}", sanitize(accessKeyId));
            throw new AuthException(AuthFailure.SIGNATURE_MISMATCH, 403, "SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided.");
        }
        request.setAttribute(ACCESS_KEY_ATTRIBUTE, accessKeyId);

        // The request headers are authentic; now bind the body to what was signed.
        if (STREAMING_SIGNED.equals(payloadHash) || STREAMING_SIGNED_TRAILER.equals(payloadHash)) {
            request.setAttribute(ChunkSigningContext.REQUEST_ATTRIBUTE, new ChunkSigningContext(
                    signingKey, timestamp, credentialScope, expectedSignature,
                    STREAMING_SIGNED_TRAILER.equals(payloadHash)));
            return request;
        }
        if (HEX_SHA256.matcher(payloadHash).matches()) {
            return new VerifiedBodyRequest(request, payloadHash);
        }
        return request;
    }

    // ── Presigned URL auth ────────────────────────────────────────────────────

    /** Returns the access key id of the verified request, or null after an error response was written. */
    private String verifyPresigned(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            return verifyPresignedInternal(request);
        } catch (AuthException e) {
            log.warn("Presigned URL auth failed: {}", sanitize(e.getMessage()));
            request.setAttribute(ObservabilityAttributes.AUTH_FAILURE, e.reason);
            sendError(request, response, e.httpStatus, e.errorCode, e.getMessage());
            return null;
        }
    }

    private String verifyPresignedInternal(HttpServletRequest request) throws AuthException {
        Map<String, String> params = parseQueryParams(request.getQueryString());

        String algorithm  = params.getOrDefault("X-Amz-Algorithm", "");
        String credential = params.getOrDefault("X-Amz-Credential", "");
        String xAmzDate   = params.getOrDefault("X-Amz-Date", "");
        String expires    = params.getOrDefault("X-Amz-Expires", "");
        String signedHdrs = params.getOrDefault("X-Amz-SignedHeaders", "");
        String signature  = params.getOrDefault("X-Amz-Signature", "");

        if (!SigV4Utils.ALGORITHM.equals(algorithm)) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "Unsupported algorithm: " + sanitize(algorithm));
        }

        String[] credParts = credential.split("/", 5);
        if (credParts.length < 5) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "Malformed X-Amz-Credential");
        }

        String accessKeyId    = credParts[0];
        String dateStr        = credParts[1];
        String regionFromCred = credParts[2];
        String service        = credParts[3];
        if (!"s3".equals(service) || !"aws4_request".equals(credParts[4])) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "AuthorizationQueryParametersError",
                    "The credential scope must end with s3/aws4_request.");
        }
        requireRegion(regionFromCred, "AuthorizationQueryParametersError");

        CredentialEntity cred = requireEnabledKey(accessKeyId);

        // Check expiry
        checkPresignedExpiry(xAmzDate, expires);
        if (xAmzDate.length() != 16 || !xAmzDate.substring(0, 8).equals(dateStr)) {
            throw new AuthException(AuthFailure.SIGNATURE_MISMATCH, 403, "SignatureDoesNotMatch",
                    "The credential date does not match X-Amz-Date.");
        }

        // For presigned URLs, the body hash is UNSIGNED-PAYLOAD
        String payloadHash = SigV4Utils.UNSIGNED_PAYLOAD;

        // Signed headers for presigned: usually just "host"
        Map<String, String> signedHeaders = collectSignedHeaders(request, signedHdrs, false);

        String canonicalRequestHash = canonicalRequestHash(request, signedHeaders, payloadHash);

        String credentialScope = SigV4Utils.credentialScope(dateStr, regionFromCred, service);
        String stringToSign = SigV4Utils.buildStringToSign(xAmzDate, credentialScope, canonicalRequestHash);

        byte[] signingKey = SigV4Utils.deriveSigningKey(cred.getSecretKey(), dateStr, regionFromCred, service);
        String expectedSig = SigV4Utils.computeSignature(signingKey, stringToSign);

        if (!constantTimeEquals(signature, expectedSig)) {
            throw new AuthException(AuthFailure.SIGNATURE_MISMATCH, 403, "SignatureDoesNotMatch",
                    "Presigned URL signature does not match.");
        }
        return accessKeyId;
    }

    // ── Shared verification steps ─────────────────────────────────────────────

    private void requireRegion(String regionFromCred, String errorCode) throws AuthException {
        if (enforceRegion && !region.equals(regionFromCred)) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, errorCode,
                    "The authorization credential is for region '" + sanitize(regionFromCred)
                    + "' but this server is configured for '" + region + "' (sectoriadb.s3.region).");
        }
    }

    /**
     * Collects the values of the signed headers. Repeated header lines are joined with "," as the spec says. The
     * signature must cover {@code host}, and (unless disabled) every {@code x-amz-*} header of the request:
     * otherwise an on-path party could change the ACL, copy source, metadata, checksum headers or swap the body hash
     * (x-amz-content-sha256) without breaking the signature.
     */
    private Map<String, String> collectSignedHeaders(HttpServletRequest request, String signedHeadersStr,
                                                     boolean headerAuth) throws AuthException {
        if (signedHeadersStr == null || signedHeadersStr.isBlank()) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "AuthorizationHeaderMalformed",
                    "The SignedHeaders list is empty.");
        }
        Map<String, String> signedHeaders = new TreeMap<>();
        for (String h : signedHeadersStr.split(";")) {
            String name = h.toLowerCase(Locale.ROOT);
            String val = joinedHeader(request, h);
            if (val == null && name.equals("host")) {
                val = request.getServerName() + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "");
            }
            signedHeaders.put(name, val != null ? val : "");
        }
        if (requireSignedHeaders) {
            List<String> missing = new ArrayList<>();
            if (!signedHeaders.containsKey("host")) missing.add("host");
            for (Enumeration<String> names = request.getHeaderNames(); names != null && names.hasMoreElements(); ) {
                String n = names.nextElement().toLowerCase(Locale.ROOT);
                if (n.startsWith("x-amz-") && !signedHeaders.containsKey(n)) missing.add(n);
            }
            if (!missing.isEmpty()) {
                throw new AuthException(AuthFailure.MALFORMED_HEADER, 403, "AccessDenied",
                        "There were headers present in the request which were not signed: " + sanitize(String.join(", ", missing)));
            }
        }
        return signedHeaders;
    }

    private static String joinedHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        List<String> all = new ArrayList<>();
        while (values.hasMoreElements()) all.add(values.nextElement().trim());
        return String.join(",", all);
    }

    /**
     * SHA-256 of the canonical request as the CLIENT computed it. The container decodes header bytes as ISO-8859-1, so
     * a UTF-8 value such as x-amz-meta-name: "e-acute" arrives as two Latin-1 chars; every other part of the canonical
     * request is ASCII, so hashing the Latin-1 bytes gives back exactly the bytes the client signed (re-encoding as
     * UTF-8 would double-encode them and fail the signature).
     */
    private String canonicalRequestHash(HttpServletRequest request, Map<String, String> signedHeaders,
                                        String payloadHash) throws AuthException {
        try {
            String canonicalRequest = SigV4Utils.buildCanonicalRequest(
                    request.getMethod(), request.getRequestURI(), request.getQueryString(), signedHeaders, payloadHash);
            return SigV4Utils.sha256Hex(canonicalRequest.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IllegalArgumentException e) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "The query string is malformed.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CredentialEntity requireEnabledKey(String accessKeyId) throws AuthException {
        CredentialEntity cred = credentialService.findByAccessKeyId(accessKeyId).orElseThrow(() ->
                new AuthException(AuthFailure.UNKNOWN_KEY, 403, "InvalidAccessKeyId",
                        "The access key ID does not exist: " + sanitize(accessKeyId)));
        if (!cred.isEnabled()) {
            throw new AuthException(AuthFailure.DISABLED_KEY, 403, "InvalidAccessKeyId",
                    "The access key ID does not exist: " + sanitize(accessKeyId));
        }
        return cred;
    }

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
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "Malformed Authorization header");
        }
        return result;
    }

    private Map<String, String> parseQueryParams(String queryString) throws AuthException {
        Map<String, String> map = new LinkedHashMap<>();
        if (queryString == null) return map;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key;
                String val;
                try {
                    key = UrlCodec.decodeQueryComponent(pair.substring(0, eq));
                    val = UrlCodec.decodeQueryComponent(pair.substring(eq + 1));
                } catch (IllegalArgumentException e) {
                    throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "The query string is malformed.");
                }
                if (map.put(key, val) != null && key.startsWith("X-Amz-")) {
                    throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "AuthorizationQueryParametersError",
                            "Query parameter " + sanitize(key) + " must appear only once.");
                }
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
            } catch (DateTimeParseException e) {
                log.debug("Failed to parse Date header: {}", date);
                throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "MissingSecurityHeader",
                        "Request must include x-amz-date or Date header");
            }
        }

        throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "MissingSecurityHeader",
                "Request must include x-amz-date or Date header");
    }

    private void checkTimestamp(String timestamp) throws AuthException {
        try {
            Instant requestTime = Instant.from(DATE_FMT.parse(timestamp));
            long diffSeconds = Math.abs(Instant.now().getEpochSecond() - requestTime.getEpochSecond());
            if (diffSeconds > SKEW_TOLERANCE_SECONDS) {
                throw new AuthException(AuthFailure.CLOCK_SKEW, 403, "RequestTimeTooSkewed",
                        "The difference between the request time and the current time is too large.");
            }
        } catch (DateTimeParseException e) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "Cannot parse timestamp: " + sanitize(timestamp));
        }
    }

    private void checkPresignedExpiry(String xAmzDate, String expiresStr) throws AuthException {
        try {
            Instant requestTime = Instant.from(DATE_FMT.parse(xAmzDate));
            long expiresSeconds = Long.parseLong(expiresStr);

            // Check X-Amz-Expires is within valid range (1..604800 seconds = 1 second to 7 days)
            if (expiresSeconds < 1 || expiresSeconds > 604800) {
                throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument",
                        "X-Amz-Expires must be between 1 and 604800 seconds");
            }

            // Check if request time is not too far in the future (> 15 minutes)
            long nowEpoch = Instant.now().getEpochSecond();
            long requestEpoch = requestTime.getEpochSecond();
            if (requestEpoch > nowEpoch + 900) {  // 15 minutes = 900 seconds
                throw new AuthException(AuthFailure.CLOCK_SKEW, 403, "RequestTimeTooSkewed",
                        "The request time is too far in the future");
            }

            // Check if token has expired
            Instant expiry = requestTime.plusSeconds(expiresSeconds);
            if (Instant.now().isAfter(expiry)) {
                throw new AuthException(AuthFailure.EXPIRED_PRESIGN, 403, "ExpiredToken", "The provided token has expired.");
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new AuthException(AuthFailure.MALFORMED_HEADER, 400, "InvalidArgument", "Cannot parse presigned URL parameters");
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
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** Strips control characters from client-supplied text before it is logged (log injection). */
    private static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", "?");
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    /** Request whose body stream verifies the signed x-amz-content-sha256 at end of stream. */
    private static final class VerifiedBodyRequest extends HttpServletRequestWrapper {
        private final String expectedHash;
        private ServletInputStream stream;

        VerifiedBodyRequest(HttpServletRequest request, String expectedHash) {
            super(request);
            this.expectedHash = expectedHash;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream raw = super.getInputStream();
                Sha256VerifyingInputStream verifying = new Sha256VerifyingInputStream(raw, expectedHash);
                stream = new ServletInputStream() {
                    @Override public int read() throws IOException { return verifying.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        return verifying.read(b, off, len);
                    }
                    @Override public long skip(long n) throws IOException { return verifying.skip(n); }
                    @Override public boolean isFinished() { return raw.isFinished(); }
                    @Override public boolean isReady() { return raw.isReady(); }
                    @Override public void setReadListener(ReadListener l) { raw.setReadListener(l); }
                    @Override public void close() throws IOException { verifying.close(); }
                };
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc != null ? Charset.forName(enc)
                    : StandardCharsets.ISO_8859_1;
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    private void sendError(HttpServletRequest request, HttpServletResponse response, int status,
                           String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/xml;charset=UTF-8");
        String requestId = RequestIds.current();
        ObservabilityAttributes.noteErrorCode(request, code);
        try (PrintWriter w = response.getWriter()) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<Error><Code>" + code + "</Code>");
            w.write("<Message>" + XmlSupport.escape(message) + "</Message>");
            w.write("<RequestId>" + requestId + "</RequestId>");
            w.write("</Error>");
        }
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    static class AuthException extends Exception {
        final AuthFailure reason;
        final int httpStatus;
        final String errorCode;

        AuthException(AuthFailure reason, int httpStatus, String errorCode, String message) {
            super(message);
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.errorCode  = errorCode;
        }
    }
}
