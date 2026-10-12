package org.example.sectoriadb.s3.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.s3.S3Exception;
import org.example.sectoriadb.s3.S3Support;
import org.example.sectoriadb.storage.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import org.example.sectoriadb.s3.XmlSupport;
import java.io.ByteArrayInputStream;
import java.util.Set;

/**
 * ACL and access control service for S3 buckets and objects.
 * Determines whether a request is allowed (based on ACL, bucket policy, and authentication).
 */
@Service
public class AccessControlService {

    private static final Logger log = LoggerFactory.getLogger(AccessControlService.class);

    private final PoolService poolService;
    private final ObjectMapper objectMapper;
    private final ManifestRepository manifestRepo;
    private final BucketPolicyEvaluator policyEvaluator;

    public AccessControlService(PoolService poolService, ObjectMapper objectMapper,
                                ManifestRepository manifestRepo) {
        this.poolService = poolService;
        this.objectMapper = objectMapper;
        this.manifestRepo = manifestRepo;
        this.policyEvaluator = new BucketPolicyEvaluator(objectMapper);
    }

    /**
     * Applies the bucket ACL given by the request (canned ACL header, grant headers or XML body); nothing is changed
     * when the request names no ACL. A malformed ACL document or an unknown canned ACL is an error, never a silent no-op.
     */
    public void applyBucketAcl(HttpServletRequest request, byte[] body, PoolEntity pool) {
        String acl = resolveAcl(request, body);
        if (acl != null) {
            poolService.setAcl(pool.getName(), acl);
        }
    }

    /** As {@link #applyBucketAcl} for an object manifest (the caller persists it). */
    public void applyObjectAcl(HttpServletRequest request, byte[] body, ManifestEntity manifest) {
        String acl = resolveAcl(request, body);
        if (acl != null) {
            manifest.setAcl(acl);
        }
    }

    /**
     * The ACL the request asks for ("private", "public-read" or "public-read-write"), or null if it names none.
     *
     * @throws S3Exception 400 InvalidArgument for an unknown canned ACL, 400 MalformedACLError for a bad document
     */
    public String resolveAcl(HttpServletRequest request, byte[] body) {
        String cannedAcl = request.getHeader("x-amz-acl");
        if (cannedAcl != null && !cannedAcl.isBlank()) {
            return normalizeCannedAcl(cannedAcl);
        }
        String grantRead = request.getHeader("x-amz-grant-read");
        String grantWrite = request.getHeader("x-amz-grant-write");
        if (grantRead != null || grantWrite != null) {
            if (isGrantForAllUsers(grantRead) && isGrantForAllUsers(grantWrite)) return "public-read-write";
            if (isGrantForAllUsers(grantRead)) return "public-read";
            return null;
        }
        if (body != null && body.length > 0) {
            return parseAclFromXml(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * Returns the ACL as an XML AccessControlPolicy.
     */
    public String aclXml(String cannedAcl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
        sb.append("  <Owner>\n");
        sb.append("    <ID>sectoriadb</ID>\n");
        sb.append("    <DisplayName>sectoriadb</DisplayName>\n");
        sb.append("  </Owner>\n");
        sb.append("  <AccessControlList>\n");

        // Owner always has FULL_CONTROL
        sb.append("    <Grant>\n");
        sb.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">\n");
        sb.append("        <ID>sectoriadb</ID>\n");
        sb.append("        <DisplayName>sectoriadb</DisplayName>\n");
        sb.append("      </Grantee>\n");
        sb.append("      <Permission>FULL_CONTROL</Permission>\n");
        sb.append("    </Grant>\n");

        // Add public grants based on canned ACL
        if ("public-read".equals(cannedAcl)) {
            sb.append("    <Grant>\n");
            sb.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">\n");
            sb.append("        <URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>\n");
            sb.append("      </Grantee>\n");
            sb.append("      <Permission>READ</Permission>\n");
            sb.append("    </Grant>\n");
        } else if ("public-read-write".equals(cannedAcl)) {
            sb.append("    <Grant>\n");
            sb.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">\n");
            sb.append("        <URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>\n");
            sb.append("      </Grantee>\n");
            sb.append("      <Permission>READ</Permission>\n");
            sb.append("    </Grant>\n");
            sb.append("    <Grant>\n");
            sb.append("      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\">\n");
            sb.append("        <URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>\n");
            sb.append("      </Grantee>\n");
            sb.append("      <Permission>WRITE</Permission>\n");
            sb.append("    </Grant>\n");
        }

        sb.append("  </AccessControlList>\n");
        sb.append("</AccessControlPolicy>");
        return sb.toString();
    }

    /**
     * Determines if an anonymous request should be allowed.
     * Checks:
     * - Bucket and object ACLs (public-read, public-read-write)
     * - Bucket policy
     */
    public boolean isAnonymousAllowed(HttpServletRequest request) {
        try {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();

            // Parse URI to extract bucket and optional key
            String bucket = extractBucketFromUri(uri);
            if (bucket == null) {
                return false;
            }

            // If this is a private-query parameter (acl, policy, etc.) deny anonymous access
            if (isPrivateQuery(queryString)) {
                return false;
            }

            // An anonymous request may never name another object as its source (CopyObject reads the source without
            // any permission check, so an open bucket would become an exfiltration channel for every private
            // object) and may never set a non-private ACL / grants (AWS needs s3:PutObjectAcl for that, which the
            // public-read-write bucket ACL does not give to anonymous callers).
            if (carriesPrivilegedHeaders(request)) {
                return false;
            }

            PoolEntity poolEntity;
            try {
                poolEntity = poolService.getByName(bucket);
            } catch (IllegalArgumentException e) {
                return false;  // Bucket doesn't exist
            }

            String bucketArn = "arn:aws:s3:::" + bucket;

            // For GET/HEAD
            if ("GET".equals(method) || "HEAD".equals(method)) {
                // Bucket-level request (ListObjects / HeadBucket) -> s3:ListBucket
                if (isBucketListRequest(uri, bucket)) {
                    if (policyDecision(poolEntity, "s3:ListBucket", bucketArn) == BucketPolicyEvaluator.Decision.DENY) {
                        return false;
                    }
                    return isPublicBucket(poolEntity)
                            || policyDecision(poolEntity, "s3:ListBucket", bucketArn) == BucketPolicyEvaluator.Decision.ALLOW;
                }

                String key = extractKeyFromUri(uri, bucket);
                if (key == null || key.isEmpty()) {
                    return false;
                }
                String resource = bucketArn + "/" + key;
                var decision = policyDecision(poolEntity, "s3:GetObject", resource);
                if (decision == BucketPolicyEvaluator.Decision.DENY) return false;   // explicit Deny beats ACLs
                // Bucket ACL does NOT make objects readable (S1): only the object ACL or a policy Allow does.
                return isObjectPublicReadable(bucket, key) || decision == BucketPolicyEvaluator.Decision.ALLOW;
            }

            // For PUT/DELETE: public-read-write bucket ACL (WRITE grant) or a policy Allow, unless explicitly denied
            if ("PUT".equals(method) || "DELETE".equals(method)) {
                String key = extractKeyFromUri(uri, bucket);
                if (key == null || key.isEmpty()) {
                    return false;
                }
                String action = "PUT".equals(method) ? "s3:PutObject" : "s3:DeleteObject";
                var decision = policyDecision(poolEntity, action, bucketArn + "/" + key);
                if (decision == BucketPolicyEvaluator.Decision.DENY) return false;
                return "public-read-write".equals(poolEntity.getAcl()) || decision == BucketPolicyEvaluator.Decision.ALLOW;
            }

            return false;
        } catch (Exception e) {
            log.debug("Error checking anonymous access: {}", e.getMessage());
            return false;
        }
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    /**
     * Canned ACLs. SectoriaDB has one owner, so the owner-oriented ones (bucket-owner-*, aws-exec-read, log-delivery-write)
     * and authenticated-read (conservatively) are stored as "private"; an unknown name is rejected.
     */
    private String normalizeCannedAcl(String acl) {
        String normalized = acl.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "private", "public-read", "public-read-write":
                return normalized;
            case "authenticated-read", "aws-exec-read", "bucket-owner-read", "bucket-owner-full-control", "log-delivery-write":
                return "private";
            default:
                throw S3Exception.invalidArgument("Unknown canned ACL: " + sanitizeForMessage(acl));
        }
    }

    private static String sanitizeForMessage(String s) {
        String t = s.replaceAll("\\p{Cntrl}", "?");
        return t.length() > 64 ? t.substring(0, 64) + "..." : t;
    }

    private boolean isGrantForAllUsers(String grantHeader) {
        if (grantHeader == null) return false;
        return grantHeader.contains("uri=\"http://acs.amazonaws.com/groups/global/AllUsers\"") ||
               grantHeader.contains("uri='http://acs.amazonaws.com/groups/global/AllUsers'");
    }

    private String parseAclFromXml(String xmlBody) {
        try {
            DocumentBuilder db = XmlSupport.newSecureDocumentBuilder(false);
            Document doc = db.parse(new ByteArrayInputStream(xmlBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            NodeList grants = doc.getElementsByTagName("Grant");
            boolean hasPublicRead = false;
            boolean hasPublicWrite = false;

            for (int i = 0; i < grants.getLength(); i++) {
                org.w3c.dom.Node grantNode = grants.item(i);
                boolean isAllUsers = false;
                String permission = null;

                // Check if grant is for AllUsers
                NodeList children = grantNode.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    org.w3c.dom.Node child = children.item(j);
                    if ("Grantee".equals(child.getNodeName())) {
                        NodeList granteeChildren = child.getChildNodes();
                        for (int k = 0; k < granteeChildren.getLength(); k++) {
                            org.w3c.dom.Node gc = granteeChildren.item(k);
                            if ("URI".equals(gc.getNodeName())) {
                                String uri = gc.getTextContent();
                                if (uri != null && "http://acs.amazonaws.com/groups/global/AllUsers".equals(uri.trim())) {
                                    isAllUsers = true;
                                }
                            }
                        }
                    } else if ("Permission".equals(child.getNodeName())) {
                        permission = child.getTextContent();
                    }
                }

                if (isAllUsers) {
                    if ("READ".equals(permission)) {
                        hasPublicRead = true;
                    } else if ("WRITE".equals(permission)) {
                        hasPublicWrite = true;
                    }
                }
            }

            if (hasPublicRead && hasPublicWrite) {
                return "public-read-write";
            } else if (hasPublicRead) {
                return "public-read";
            }
            return "private";   // a valid document without public grants removes public access
        } catch (Exception e) {
            log.debug("Failed to parse ACL from XML: {}", e.getMessage());
            throw new S3Exception(org.springframework.http.HttpStatus.BAD_REQUEST, "MalformedACLError",
                    "The XML you provided was not well-formed or did not validate against our published schema");
        }
    }

    /** Sub-resources that must never be reachable anonymously (matched by parameter name). */
    private static final Set<String> PRIVATE_SUBRESOURCES = Set.of(
            "acl", "policy", "uploads", "uploadId", "partNumber", "tagging", "versioning",
            "cors", "lifecycle", "encryption", "publicAccessBlock", "delete", "location");

    private static boolean carriesPrivilegedHeaders(HttpServletRequest request) {
        if (request.getHeader("x-amz-copy-source") != null) return true;
        String acl = request.getHeader("x-amz-acl");
        if (acl != null && !acl.isBlank() && !"private".equalsIgnoreCase(acl.trim())) return true;
        for (java.util.Enumeration<String> names = request.getHeaderNames(); names != null && names.hasMoreElements(); ) {
            if (names.nextElement().regionMatches(true, 0, "x-amz-grant-", 0, "x-amz-grant-".length())) return true;
        }
        return false;
    }

    private boolean isPrivateQuery(String queryString) {
        if (queryString == null || queryString.isEmpty()) return false;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String name = S3Support.percentDecode(eq < 0 ? pair : pair.substring(0, eq));
            if (PRIVATE_SUBRESOURCES.contains(name)) return true;
        }
        return false;
    }

    private String extractBucketFromUri(String uri) {
        // Path-style: /bucket/key or /bucket
        if (!uri.startsWith("/")) return null;
        String[] parts = uri.substring(1).split("/", 2);
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
    }

    private boolean isBucketListRequest(String uri, String bucket) {
        String bucketPrefix = "/" + bucket;
        return uri.equals(bucketPrefix) || uri.equals(bucketPrefix + "/");
    }

    private String extractKeyFromUri(String uri, String bucket) {
        String prefix = "/" + bucket + "/";
        if (uri.startsWith(prefix)) {
            return S3Support.percentDecode(uri.substring(prefix.length()));
        }
        return null;
    }

    private boolean isPublicBucket(PoolEntity pool) {
        String acl = pool.getAcl();
        return "public-read".equals(acl) || "public-read-write".equals(acl);
    }

    /**
     * Object readability comes ONLY from the object's own ACL (AWS semantics). A public-read bucket ACL
     * grants listing, not reading of objects; a bucket policy is checked separately by the caller.
     */
    private boolean isObjectPublicReadable(String bucket, String key) {
        return manifestRepo.findCurrent(bucket, key)
                .map(m -> "public-read".equals(m.getAcl()) || "public-read-write".equals(m.getAcl()))
                .orElse(false);
    }

    private BucketPolicyEvaluator.Decision policyDecision(PoolEntity pool, String action, String resource) {
        return policyEvaluator.evaluate(pool.getPolicy(), action, resource);
    }
}
