package org.example.sectoriadb.s3.http;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.s3.error.S3Exception;

/** Parsing and validation of the parts of an S3 request that every handler needs: the key, query parameters, preconditions. */
public final class S3Request {

    /** Request attribute set by SigV4Filter when the request carries a verified identity (or auth is off / open setup). */
    public static final String AUTHENTICATED_ATTRIBUTE = "sectoriadb.s3.authenticated";

    private S3Request() {}

    /**
     * Bucket and object sub-resources that S3 has but SectoriaDB does not implement. A GET carrying one of them must
     * not fall through to ListObjects / GetObject (the client would get a listing or the object's bytes as the answer
     * to, say, {@code ?object-lock} or {@code ?retention}).
     */
    private static final java.util.Set<String> UNSUPPORTED_BUCKET_SUBRESOURCES = java.util.Set.of(
            "object-lock", "ownershipControls", "website", "logging", "notification", "policyStatus", "accelerate",
            "analytics", "inventory", "metrics", "requestPayment", "replication", "intelligent-tiering", "metadataConfiguration");
    private static final java.util.Set<String> UNSUPPORTED_OBJECT_SUBRESOURCES = java.util.Set.of(
            "attributes", "retention", "legal-hold", "torrent", "select", "restore");

    public static void rejectUnsupportedBucketSubresource(jakarta.servlet.http.HttpServletRequest request) {
        rejectIfPresent(request, UNSUPPORTED_BUCKET_SUBRESOURCES);
    }

    public static void rejectUnsupportedObjectSubresource(jakarta.servlet.http.HttpServletRequest request) {
        rejectIfPresent(request, UNSUPPORTED_OBJECT_SUBRESOURCES);
    }

    /**
     * Writes and deletes must not carry query parameters that nobody handles. A request such as
     * {@code DELETE /bucket?tagging} or {@code PUT /bucket/key?retention} that no sub-resource handler claims would
     * otherwise be executed as the plain DeleteBucket / DeleteObject / PutObject and destroy data. Only the names in
     * {@code allowed} (for example {@code x-id}, which the AWS SDKs for JavaScript and Go add to every request) pass, and so do the SigV4 query
     * parameters of presigned URLs ({@code X-Amz-*}).
     */
    public static void requireOnlyQueryParams(jakarta.servlet.http.HttpServletRequest request, String... allowed) {
        String q = request.getQueryString();
        if (q == null || q.isBlank()) return;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name;
            try {
                name = UrlCodec.decodeQueryComponent(eq < 0 ? pair : pair.substring(0, eq));
            } catch (IllegalArgumentException e) {
                throw S3Exception.invalidArgument("The query string is malformed");
            }
            // presigned URLs carry the SigV4 parameters (X-Amz-Algorithm, X-Amz-Credential, X-Amz-Signature, ...) in the query
            boolean ok = name.regionMatches(true, 0, "X-Amz-", 0, 6);
            for (String a : allowed) if (a.equals(name)) { ok = true; break; }
            if (!ok) {
                throw new S3Exception(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                        "A header or query parameter you provided implies functionality that is not implemented");
            }
        }
    }

    private static void rejectIfPresent(jakarta.servlet.http.HttpServletRequest request, java.util.Set<String> names) {
        String q = request.getQueryString();
        if (q == null || q.isEmpty()) return;
        for (String name : request.getParameterMap().keySet()) {
            if (names.contains(name)) {
                throw new S3Exception(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                        "A header or query parameter you provided implies functionality that is not implemented");
            }
        }
    }

    /**
     * Conditional write headers of PutObject / CompleteMultipartUpload as a precondition checked inside the commit
     * transaction: {@code If-None-Match: *} (create only) and {@code If-Match: <etag>} (replace that version only).
     * Other forms (lists of ETags, If-None-Match with an ETag) are not supported by S3 for writes either: 501.
     */
    public static org.example.sectoriadb.catalog.ManifestRepository.CommitPrecondition writePrecondition(
            HttpServletRequest request) {
        String none = request.getHeader("If-None-Match");
        String match = request.getHeader("If-Match");
        if (none == null && match == null) {
            return org.example.sectoriadb.catalog.ManifestRepository.CommitPrecondition.NONE;
        }
        if (none != null && !"*".equals(none.trim())) {
            throw new S3Exception(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                    "If-None-Match on a write supports only the value '*'");
        }
        if (match != null && (match.contains(",") || match.isBlank())) {
            throw new S3Exception(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                    "If-Match on a write supports a single ETag");
        }
        return new org.example.sectoriadb.catalog.ManifestRepository.CommitPrecondition(none != null, match);
    }

    /** CopyObject's {@code x-amz-copy-source-if-*} conditions are not evaluated; refusing beats silently ignoring them. */
    public static void rejectCopySourceConditions(HttpServletRequest request) {
        for (String h : new String[]{"x-amz-copy-source-if-match", "x-amz-copy-source-if-none-match",
                "x-amz-copy-source-if-modified-since", "x-amz-copy-source-if-unmodified-since"}) {
            if (request.getHeader(h) != null) {
                throw new S3Exception(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "NotImplemented",
                        "A header you provided implies functionality that is not implemented: " + h);
            }
        }
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
        return UrlCodec.percentDecode(uri.substring(prefix.length()));
    }
}
