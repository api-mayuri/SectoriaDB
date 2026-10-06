package org.example.sectoriadb.s3.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.s3.S3Support;
import org.example.sectoriadb.service.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
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
     * Applies bucket ACL from request (canned ACL, headers, or XML body).
     * If no ACL specified in request, does nothing.
     */
    public void applyBucketAcl(HttpServletRequest request, byte[] body, PoolEntity pool) {
        try {
            String acl = null;

            // Check x-amz-acl header (canned ACL)
            String cannedAcl = request.getHeader("x-amz-acl");
            if (cannedAcl != null && !cannedAcl.isBlank()) {
                acl = normalizeCannedAcl(cannedAcl);
            } else {
                // Check x-amz-grant-read/write headers
                String grantRead = request.getHeader("x-amz-grant-read");
                String grantWrite = request.getHeader("x-amz-grant-write");
                if (grantRead != null || grantWrite != null) {
                    if (isGrantForAllUsers(grantRead) && isGrantForAllUsers(grantWrite)) {
                        acl = "public-read-write";
                    } else if (isGrantForAllUsers(grantRead)) {
                        acl = "public-read";
                    }
                }
            }

            // Check XML body (AccessControlPolicy)
            if (acl == null && body != null && body.length > 0) {
                acl = parseAclFromXml(new String(body));
            }

            // Apply ACL if determined
            if (acl != null) {
                poolService.setAcl(pool.getName(), acl);
            }
        } catch (Exception e) {
            log.warn("Failed to apply bucket ACL: {}", e.getMessage());
        }
    }

    /**
     * Applies object ACL from request.
     * If no ACL specified in request and this is not a PutObjectAcl request, leaves as-is.
     */
    public void applyObjectAcl(HttpServletRequest request, byte[] body, ManifestEntity manifest) {
        try {
            String acl = null;

            // Check x-amz-acl header
            String cannedAcl = request.getHeader("x-amz-acl");
            if (cannedAcl != null && !cannedAcl.isBlank()) {
                acl = normalizeCannedAcl(cannedAcl);
            } else {
                // Check grant headers
                String grantRead = request.getHeader("x-amz-grant-read");
                String grantWrite = request.getHeader("x-amz-grant-write");
                if (grantRead != null || grantWrite != null) {
                    if (isGrantForAllUsers(grantRead) && isGrantForAllUsers(grantWrite)) {
                        acl = "public-read-write";
                    } else if (isGrantForAllUsers(grantRead)) {
                        acl = "public-read";
                    }
                }
            }

            // Check XML body
            if (acl == null && body != null && body.length > 0) {
                acl = parseAclFromXml(new String(body));
            }

            // Apply ACL if determined
            if (acl != null) {
                manifest.setAcl(acl);
            }
        } catch (Exception e) {
            log.warn("Failed to apply object ACL: {}", e.getMessage());
        }
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

    private String normalizeCannedAcl(String acl) {
        if (acl == null) return "private";
        String normalized = acl.toLowerCase();
        // Map authenticated-read to private (conservative approach)
        if ("authenticated-read".equals(normalized)) {
            return "private";
        }
        if ("public-read".equals(normalized) || "public-read-write".equals(normalized) || "private".equals(normalized)) {
            return normalized;
        }
        return "private";
    }

    private boolean isGrantForAllUsers(String grantHeader) {
        if (grantHeader == null) return false;
        return grantHeader.contains("uri=\"http://acs.amazonaws.com/groups/global/AllUsers\"") ||
               grantHeader.contains("uri='http://acs.amazonaws.com/groups/global/AllUsers'");
    }

    private String parseAclFromXml(String xmlBody) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xmlBody.getBytes()));

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
                                if (uri != null && uri.contains("global/AllUsers")) {
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
        } catch (Exception e) {
            log.debug("Failed to parse ACL from XML: {}", e.getMessage());
        }
        return null;
    }

    /** Sub-resources that must never be reachable anonymously (matched by parameter name). */
    private static final Set<String> PRIVATE_SUBRESOURCES = Set.of(
            "acl", "policy", "uploads", "uploadId", "partNumber", "tagging", "versioning",
            "cors", "lifecycle", "encryption", "publicAccessBlock", "delete", "location");

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
        return manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse(bucket, key)
                .map(m -> "public-read".equals(m.getAcl()) || "public-read-write".equals(m.getAcl()))
                .orElse(false);
    }

    private BucketPolicyEvaluator.Decision policyDecision(PoolEntity pool, String action, String resource) {
        return policyEvaluator.evaluate(pool.getPolicy(), action, resource);
    }
}
