package org.example.sectoriadb.observability;

import java.util.HashMap;
import java.util.Map;

/**
 * The single place that maps an HTTP request (method, path, query parameters, one header) to an {@link S3Operation}.
 * It mirrors the routing of the S3 controllers (path-style addressing: {@code /}, {@code /bucket}, {@code /bucket/key}):
 * the most specific query sub-resource wins, exactly as Spring picks the most specific {@code params} condition.
 * Metrics, MDC and the slow-request log all use this class, so they always agree.
 *
 * <p>Only the <i>names</i> of query parameters (and the value of {@code list-type}) are inspected, never request
 * bodies, bucket names or keys.
 */
public final class OperationClassifier {

    private OperationClassifier() {
    }

    /**
     * @param method      HTTP method
     * @param requestUri  raw request path (e.g. {@code /bucket/some%20key}); the key is not decoded or used
     * @param queryString raw query string or null
     * @param copySource  true if the request has an {@code x-amz-copy-source} header
     */
    public static S3Operation classify(String method, String requestUri, String queryString, boolean copySource) {
        if (method == null || requestUri == null) return S3Operation.Other;
        String m = method.toUpperCase(java.util.Locale.ROOT);
        Map<String, String> q = parseQuery(queryString);

        int depth = depth(requestUri);
        if (depth == 0) {
            return m.equals("GET") ? S3Operation.ListBuckets : S3Operation.Other;
        }
        return depth == 1 ? bucketOperation(m, q) : objectOperation(m, q, copySource);
    }

    /** 0 = root, 1 = bucket (with or without trailing slash), 2 = object. */
    private static int depth(String uri) {
        int i = 0;
        int n = uri.length();
        while (i < n && uri.charAt(i) == '/') i++;
        if (i == n) return 0;
        int slash = uri.indexOf('/', i);
        if (slash < 0 || slash == n - 1) return 1;
        return 2;
    }

    private static S3Operation bucketOperation(String m, Map<String, String> q) {
        switch (m) {
            case "PUT":
                if (q.containsKey("acl")) return S3Operation.PutBucketAcl;
                if (q.containsKey("policy")) return S3Operation.PutBucketPolicy;
                if (hasSubresource(q)) return S3Operation.Other;   // e.g. PUT ?tagging: not implemented
                return S3Operation.CreateBucket;
            case "GET":
                if (q.containsKey("acl")) return S3Operation.GetBucketAcl;
                if (q.containsKey("policy")) return S3Operation.GetBucketPolicy;
                if (q.containsKey("location")) return S3Operation.GetBucketLocation;
                if (q.containsKey("versioning")) return S3Operation.GetBucketVersioning;
                if (q.containsKey("tagging")) return S3Operation.GetBucketTagging;
                if (q.containsKey("cors")) return S3Operation.GetBucketCors;
                if (q.containsKey("lifecycle")) return S3Operation.GetBucketLifecycle;
                if (q.containsKey("encryption")) return S3Operation.GetBucketEncryption;
                if (q.containsKey("publicAccessBlock")) return S3Operation.GetPublicAccessBlock;
                if (q.containsKey("uploads")) return S3Operation.ListMultipartUploads;
                return "2".equals(q.get("list-type")) ? S3Operation.ListObjectsV2 : S3Operation.ListObjects;
            case "HEAD":
                return S3Operation.HeadBucket;
            case "DELETE":
                if (q.containsKey("policy")) return S3Operation.DeleteBucketPolicy;
                if (hasSubresource(q)) return S3Operation.Other;
                return S3Operation.DeleteBucket;
            case "POST":
                return q.containsKey("delete") ? S3Operation.DeleteObjects : S3Operation.Other;
            default:
                return S3Operation.Other;
        }
    }

    private static S3Operation objectOperation(String m, Map<String, String> q, boolean copySource) {
        switch (m) {
            case "PUT":
                if (q.containsKey("acl")) return S3Operation.PutObjectAcl;
                if (q.containsKey("tagging")) return S3Operation.PutObjectTagging;
                if (q.containsKey("partNumber") && q.containsKey("uploadId")) {
                    return copySource ? S3Operation.UploadPartCopy : S3Operation.UploadPart;
                }
                return copySource ? S3Operation.CopyObject : S3Operation.PutObject;
            case "GET":
                if (q.containsKey("acl")) return S3Operation.GetObjectAcl;
                if (q.containsKey("tagging")) return S3Operation.GetObjectTagging;
                if (q.containsKey("uploadId")) return S3Operation.ListParts;
                return S3Operation.GetObject;
            case "HEAD":
                return S3Operation.HeadObject;
            case "DELETE":
                if (q.containsKey("tagging")) return S3Operation.DeleteObjectTagging;
                if (q.containsKey("uploadId")) return S3Operation.AbortMultipartUpload;
                return S3Operation.DeleteObject;
            case "POST":
                if (q.containsKey("uploads")) return S3Operation.CreateMultipartUpload;
                if (q.containsKey("uploadId")) return S3Operation.CompleteMultipartUpload;
                return S3Operation.Other;
            default:
                return S3Operation.Other;
        }
    }

    /** True if the query carries a bucket sub-resource that the bucket-level PUT/DELETE routes do not implement. */
    private static boolean hasSubresource(Map<String, String> q) {
        for (String k : q.keySet()) {
            switch (k) {
                case "tagging", "versioning", "cors", "lifecycle", "encryption", "publicAccessBlock", "location",
                     "uploads", "website", "logging", "notification", "replication", "accelerate", "versions" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** Names (and raw values) of the query parameters; a parameter without '=' has the value "". Not decoded. */
    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        int i = 0;
        int n = query.length();
        while (i < n) {
            int amp = query.indexOf('&', i);
            if (amp < 0) amp = n;
            if (amp > i) {
                int eq = query.indexOf('=', i);
                if (eq < 0 || eq > amp) {
                    out.putIfAbsent(query.substring(i, amp), "");
                } else {
                    out.putIfAbsent(query.substring(i, eq), query.substring(eq + 1, amp));
                }
            }
            i = amp + 1;
        }
        return out;
    }
}
