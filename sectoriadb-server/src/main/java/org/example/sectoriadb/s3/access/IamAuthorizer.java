package org.example.sectoriadb.s3.access;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.observability.OperationClassifier;
import org.example.sectoriadb.observability.S3Operation;
import org.example.sectoriadb.s3.access.BucketPolicyEvaluator.Decision;
import org.example.sectoriadb.s3.http.CopySource;
import org.example.sectoriadb.s3.http.UrlCodec;
import org.example.sectoriadb.security.CredentialEntity;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Authorization of SIGNED requests: the IAM identity policy of the access key together with the bucket policy,
 * evaluated like AWS does in a single account:
 * <ol>
 *   <li>an explicit Deny in either policy refuses the request;</li>
 *   <li>otherwise an Allow in either policy permits it;</li>
 *   <li>otherwise a key without an identity policy (a legacy key) is permitted, a key with one is refused.</li>
 * </ol>
 * The one mapping from an HTTP request to S3 actions and resource ARNs lives here ({@link #requirements}); it builds
 * on {@link OperationClassifier}, which the metrics use, so both always agree about what an operation is.
 * Anonymous requests do not come through here (see {@link AccessControlService#isAnonymousAllowed}).
 */
@Service
public class IamAuthorizer {

    /** One permission the request needs: {@code bucket} is null for the service-level ListAllMyBuckets. */
    public record Requirement(String action, String bucket, String key) {
        public String resource() {
            if (bucket == null) return "arn:aws:s3:::*";
            return "arn:aws:s3:::" + bucket + (key == null ? "" : "/" + key);
        }
    }

    /** The first requirement a request fails. */
    public record Denial(String accessKeyId, Requirement requirement) {
        public String message() {
            return "User: " + BucketPolicyEvaluator.principalArn(accessKeyId) + " is not authorized to perform: "
                    + requirement.action() + " on resource: " + requirement.resource();
        }
    }

    /** Sub-resource families of which a request may carry only one: a mix is routed by Spring differently than classified. */
    private static final List<Set<String>> FAMILIES = List.of(
            Set.of("acl"), Set.of("tagging"), Set.of("policy"), Set.of("delete"), Set.of("uploads"),
            Set.of("uploadId", "partNumber"), Set.of("location"), Set.of("versioning"), Set.of("cors"),
            Set.of("lifecycle"), Set.of("encryption"), Set.of("publicAccessBlock"), Set.of("versions"));

    private final CredentialService credentials;
    private final Function<String, String> bucketPolicies;
    private final BucketPolicyEvaluator evaluator;

    @Autowired
    public IamAuthorizer(CredentialService credentials, PoolService poolService, ObjectMapper objectMapper) {
        this(credentials, bucket -> poolService.findByName(bucket).map(PoolEntity::getPolicy).orElse(null), objectMapper);
    }

    /** @param bucketPolicies bucket name to its policy JSON, null when the bucket has none or does not exist */
    IamAuthorizer(CredentialService credentials, Function<String, String> bucketPolicies, ObjectMapper objectMapper) {
        this.credentials = credentials;
        this.bucketPolicies = bucketPolicies;
        this.evaluator = new BucketPolicyEvaluator(objectMapper);
    }

    /** Checks everything the request needs; empty when it is permitted. */
    public Optional<Denial> authorize(HttpServletRequest request, String accessKeyId) {
        Optional<CredentialEntity> cred = credentials.findByAccessKeyId(accessKeyId);
        if (cred.isEmpty() || !cred.get().isEnabled()) {
            return Optional.of(new Denial(accessKeyId, new Requirement("s3:*", null, null)));
        }
        JsonNode identity = cred.get().getPolicy();
        List<Requirement> needs = requirements(request);
        if (needs == null) {   // unmapped operation or ambiguous request: only a key allowed everything may try
            var everything = new BucketPolicyEvaluator.Request("s3:*", "arn:aws:s3:::*", accessKeyId, request.isSecure(), Map.of());
            return identity != null && evaluator.evaluateIdentity(identity, everything) == Decision.ALLOW ? Optional.empty()
                    : Optional.of(new Denial(accessKeyId, new Requirement("s3:*", null, null)));
        }
        for (Requirement r : needs) {
            if (!allowed(identity, accessKeyId, request, r)) return Optional.of(new Denial(accessKeyId, r));
        }
        return Optional.empty();
    }

    /** One permission, e.g. s3:DeleteObject of one key of a multi-object delete. */
    public boolean isAllowed(HttpServletRequest request, String accessKeyId, String action, String bucket, String key) {
        Optional<CredentialEntity> cred = credentials.findByAccessKeyId(accessKeyId);
        return cred.isPresent() && cred.get().isEnabled()
                && allowed(cred.get().getPolicy(), accessKeyId, request, new Requirement(action, bucket, key));
    }

    private boolean allowed(JsonNode identity, String accessKeyId, HttpServletRequest request, Requirement r) {
        Map<String, String> context = "s3:ListBucket".equals(r.action()) || "s3:ListBucketVersions".equals(r.action())
                ? listContext(request) : Map.of();
        var question = new BucketPolicyEvaluator.Request(r.action(), r.resource(), accessKeyId, request.isSecure(), context);
        Decision byIdentity = identity == null ? Decision.NONE : evaluator.evaluateIdentity(identity, question);
        Decision byBucket = r.bucket() == null ? Decision.NONE : evaluator.evaluate(bucketPolicies.apply(r.bucket()), question);
        if (byIdentity == Decision.DENY || byBucket == Decision.DENY) return false;
        // deny by default: a key without a policy, or with one that says nothing about the request, is refused
        return byIdentity == Decision.ALLOW || byBucket == Decision.ALLOW;
    }

    /** s3:prefix is "" when the request names none; s3:delimiter exists only when it is sent. */
    static Map<String, String> listContext(HttpServletRequest request) {
        String prefix = request.getParameter("prefix");
        String delimiter = request.getParameter("delimiter");
        return delimiter == null ? Map.of("s3:prefix", prefix == null ? "" : prefix)
                : Map.of("s3:prefix", prefix == null ? "" : prefix, "s3:delimiter", delimiter);
    }

    /**
     * The permissions of a request; empty for DeleteObjects (the controller checks each key), null when the operation
     * is not mapped (Other) or the query mixes sub-resources.
     */
    List<Requirement> requirements(HttpServletRequest request) {
        String query = request.getQueryString();
        String copyHeader = request.getHeader("x-amz-copy-source");
        S3Operation op = OperationClassifier.classify(request.getMethod(), request.getRequestURI(), query, copyHeader != null);
        if (op == S3Operation.Other || ambiguous(request.getRequestURI(), query)) return null;
        if (op == S3Operation.ListBuckets) return List.of(new Requirement("s3:ListAllMyBuckets", null, null));
        if (op == S3Operation.DeleteObjects) return List.of();

        String path = request.getRequestURI().replaceFirst("^/+", "");
        int slash = path.indexOf('/');
        String bucket = UrlCodec.percentDecode(slash < 0 ? path : path.substring(0, slash));
        String rest = slash < 0 ? "" : path.substring(slash + 1);
        String key = rest.isEmpty() ? null : UrlCodec.percentDecode(rest);

        List<Requirement> out = new ArrayList<>();
        switch (op) {
            case CreateBucket -> out.add(bucketLevel("s3:CreateBucket", bucket));
            case DeleteBucket -> out.add(bucketLevel("s3:DeleteBucket", bucket));
            case HeadBucket -> out.add(bucketLevel("s3:ListBucket", bucket));
            case ListObjects, ListObjectsV2 -> out.add(bucketLevel(
                    query != null && OperationClassifier.hasParam(query, "versions") ? "s3:ListBucketVersions" : "s3:ListBucket", bucket));
            case GetBucketLocation -> out.add(bucketLevel("s3:GetBucketLocation", bucket));
            case GetBucketVersioning -> out.add(bucketLevel("s3:GetBucketVersioning", bucket));
            case GetBucketTagging -> out.add(bucketLevel("s3:GetBucketTagging", bucket));
            case GetBucketCors -> out.add(bucketLevel("s3:GetBucketCORS", bucket));
            case GetBucketLifecycle -> out.add(bucketLevel("s3:GetLifecycleConfiguration", bucket));
            case GetBucketEncryption -> out.add(bucketLevel("s3:GetEncryptionConfiguration", bucket));
            case GetPublicAccessBlock -> out.add(bucketLevel("s3:GetBucketPublicAccessBlock", bucket));
            case GetBucketAcl -> out.add(bucketLevel("s3:GetBucketAcl", bucket));
            case PutBucketAcl -> out.add(bucketLevel("s3:PutBucketAcl", bucket));
            case GetBucketPolicy -> out.add(bucketLevel("s3:GetBucketPolicy", bucket));
            case PutBucketPolicy -> out.add(bucketLevel("s3:PutBucketPolicy", bucket));
            case DeleteBucketPolicy -> out.add(bucketLevel("s3:DeleteBucketPolicy", bucket));
            case ListMultipartUploads -> out.add(bucketLevel("s3:ListBucketMultipartUploads", bucket));
            case PutObject, CreateMultipartUpload, UploadPart, CompleteMultipartUpload ->
                    out.add(new Requirement("s3:PutObject", bucket, key));
            case GetObject, HeadObject -> out.add(new Requirement("s3:GetObject", bucket, key));
            case DeleteObject -> out.add(new Requirement("s3:DeleteObject", bucket, key));
            case GetObjectAcl -> out.add(new Requirement("s3:GetObjectAcl", bucket, key));
            case PutObjectAcl -> out.add(new Requirement("s3:PutObjectAcl", bucket, key));
            case GetObjectTagging -> out.add(new Requirement("s3:GetObjectTagging", bucket, key));
            case PutObjectTagging -> out.add(new Requirement("s3:PutObjectTagging", bucket, key));
            case DeleteObjectTagging -> out.add(new Requirement("s3:DeleteObjectTagging", bucket, key));
            case AbortMultipartUpload -> out.add(new Requirement("s3:AbortMultipartUpload", bucket, key));
            case ListParts -> out.add(new Requirement("s3:ListMultipartUploadParts", bucket, key));
            case CopyObject, UploadPartCopy -> {
                out.add(new Requirement("s3:PutObject", bucket, key));
                try {
                    CopySource source = CopySource.parse(copyHeader);
                    out.add(new Requirement("s3:GetObject", source.bucket(), source.key()));
                } catch (RuntimeException malformed) {
                    return null;   // the controller answers 400; a restricted key must not get past this check
                }
            }
            default -> {
                return null;
            }
        }
        if (carriesAclHeaders(request)) {
            // like AWS: publishing through x-amz-acl / x-amz-grant-* is an ACL change and needs the ACL permission
            switch (op) {
                case PutObject, CopyObject, CreateMultipartUpload -> out.add(new Requirement("s3:PutObjectAcl", bucket, key));
                case CreateBucket -> out.add(bucketLevel("s3:PutBucketAcl", bucket));
                default -> { }
            }
        }
        return out;
    }

    /** A non-private canned ACL or any grant header: {@code x-amz-acl: private} changes nothing and needs no extra right. */
    static boolean carriesAclHeaders(HttpServletRequest request) {
        String canned = request.getHeader("x-amz-acl");
        if (canned != null && !canned.isBlank() && !"private".equalsIgnoreCase(canned.trim())) return true;
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            if (names.nextElement().regionMatches(true, 0, "x-amz-grant-", 0, 12)) return true;
        }
        return false;
    }

    private static Requirement bucketLevel(String action, String bucket) {
        return new Requirement(action, bucket, null);
    }

    /**
     * True when the classification (which looks at raw names) could differ from how Spring routes the request: encoded
     * parameter names, a path with an empty first segment, or a mix of sub-resource families.
     */
    private static boolean ambiguous(String uri, String query) {
        if (uri.startsWith("//")) return true;
        if (query == null || query.isEmpty()) return false;
        if (query.indexOf('%') >= 0 || query.indexOf('+') >= 0 || query.indexOf(';') >= 0) {
            // a percent sign in a VALUE is common (prefix=a%2Fb); only names matter
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                if (name.indexOf('%') >= 0 || name.indexOf('+') >= 0) return true;
            }
        }
        int families = 0;
        for (Set<String> family : FAMILIES) {
            for (String name : family) {
                if (OperationClassifier.hasParam(query, name)) {
                    families++;
                    break;
                }
            }
        }
        return families > 1;
    }
}
