package org.example.sectoriadb.s3.access;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.security.JsonCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The combination of the identity policy of a key and the bucket policy, and the operation to action/resource mapping. */
class IamAuthorizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private CredentialService credentials;
    private final Map<String, String> bucketPolicies = new HashMap<>();
    private IamAuthorizer authorizer;
    private String admin;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setMetaDir(dir.toString());
        credentials = new CredentialService(new JsonCredentialRepository(MAPPER, props));
        authorizer = new IamAuthorizer(credentials, bucketPolicies::get, MAPPER);
        admin = credentials.create("admin").getAccessKeyId();
        credentials.setPolicy(admin, CredentialService.fullAccessPolicy());
    }

    private String keyWith(String identityStatements) {
        String id = credentials.create("restricted").getAccessKeyId();
        try {
            credentials.setPolicy(id, MAPPER.readTree("{\"Version\":\"2012-10-17\",\"Statement\":[" + identityStatements + "]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private static String stmt(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String bucketStmt(String effect, String principal, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Principal\":" + principal + ",\"Action\":\"" + action
                + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String bucketPolicy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static MockHttpServletRequest req(String method, String uri, String query) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        if (query != null) {
            r.setQueryString(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                r.addParameter(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        return r;
    }

    private boolean allowed(String key, String method, String uri, String query) {
        return authorizer.authorize(req(method, uri, query), key).isEmpty();
    }

    // ── evaluation order ─────────────────────────────────────────────────────

    @Test
    void aKeyWithoutAnIdentityPolicyIsRefusedEverything() {
        String bare = credentials.create("bare").getAccessKeyId();
        assertFalse(allowed(bare, "GET", "/b/k", null));
        assertFalse(allowed(bare, "GET", "/", null));
        assertFalse(allowed(bare, "POST", "/b/k", "restore"));
        bucketPolicies.put("b", bucketPolicy(bucketStmt("Allow", "{\"AWS\":\"" + bare + "\"}", "s3:GetObject", "arn:aws:s3:::b/*")));
        assertTrue(allowed(bare, "GET", "/b/k", null), "a bucket policy can still grant a key without an identity policy");
    }

    @Test
    void theFullAccessPolicyAllowsEverythingIncludingUnmappedOperations() {
        assertTrue(allowed(admin, "GET", "/b/k", null));
        assertTrue(allowed(admin, "DELETE", "/b/k", null));
        assertTrue(allowed(admin, "GET", "/", null));
        assertTrue(allowed(admin, "PUT", "/b", null));
        assertTrue(allowed(admin, "POST", "/b/k", "restore"), "even unmapped operations (the controller answers 501)");
    }

    @Test
    void aKeyWithAnIdentityPolicyIsRefusedWhatItDoesNotAllow() {
        String key = keyWith(stmt("Allow", "s3:GetObject", "arn:aws:s3:::b/*"));
        assertTrue(allowed(key, "GET", "/b/k", null));
        assertTrue(allowed(key, "HEAD", "/b/k", null));
        assertFalse(allowed(key, "GET", "/other/k", null), "another bucket");
        assertFalse(allowed(key, "PUT", "/b/k", null), "another action");
        assertFalse(allowed(key, "GET", "/b", "list-type=2"), "ListBucket is a different action on the bucket ARN");
        assertFalse(allowed(key, "GET", "/", null));
        assertFalse(allowed(key, "POST", "/b/k", "restore"), "unmapped operations are refused");
    }

    @Test
    void allowInTheBucketPolicyAloneIsEnoughEvenForARestrictedKey() {
        String key = keyWith(stmt("Allow", "s3:GetObject", "arn:aws:s3:::mine/*"));
        assertFalse(allowed(key, "GET", "/shared/k", null));
        bucketPolicies.put("shared", bucketPolicy(bucketStmt("Allow", "{\"AWS\":\"" + BucketPolicyEvaluator.principalArn(key) + "\"}",
                "s3:GetObject", "arn:aws:s3:::shared/*")));
        assertTrue(allowed(key, "GET", "/shared/k", null));
        assertFalse(allowed(key, "PUT", "/shared/k", null));
    }

    @Test
    void aBucketPolicyForEveryoneAppliesToSignedKeysToo() {
        String key = keyWith(stmt("Allow", "s3:GetObject", "arn:aws:s3:::mine/*"));
        bucketPolicies.put("open", bucketPolicy(bucketStmt("Allow", "\"*\"", "s3:GetObject", "arn:aws:s3:::open/*")));
        assertTrue(allowed(key, "GET", "/open/k", null));
        assertTrue(allowed(admin, "GET", "/open/k", null));
    }

    @Test
    void explicitDenyInTheIdentityPolicyBeatsAnAllowEverywhere() {
        String key = keyWith(stmt("Allow", "s3:*", "arn:aws:s3:::*") + "," + stmt("Deny", "s3:DeleteObject", "arn:aws:s3:::b/*"));
        bucketPolicies.put("b", bucketPolicy(bucketStmt("Allow", "\"*\"", "s3:*", "arn:aws:s3:::b/*")));
        assertFalse(allowed(key, "DELETE", "/b/k", null));
        assertTrue(allowed(key, "GET", "/b/k", null));
    }

    @Test
    void explicitDenyInTheBucketPolicyBeatsTheIdentityAllowAndAlsoBindsLegacyKeys() {
        String key = keyWith(stmt("Allow", "s3:*", "arn:aws:s3:::*"));
        bucketPolicies.put("b", bucketPolicy(bucketStmt("Deny", "{\"AWS\":\"" + key + "\"}", "s3:PutObject", "arn:aws:s3:::b/*"),
                bucketStmt("Deny", "\"*\"", "s3:DeleteObject", "arn:aws:s3:::b/*")));
        assertFalse(allowed(key, "PUT", "/b/k", null), "Deny naming the key by its plain id");
        assertFalse(allowed(key, "DELETE", "/b/k", null));
        assertTrue(allowed(key, "GET", "/b/k", null));
        assertFalse(allowed(admin, "DELETE", "/b/k", null), "a Deny for * applies to a admin key");
        assertTrue(allowed(admin, "PUT", "/b/k", null), "the Deny that names another key does not");
    }

    @Test
    void aDisabledOrDeletedKeyIsRefused() {
        credentials.setEnabled(admin, false);
        assertFalse(allowed(admin, "GET", "/b/k", null));
        assertFalse(allowed("NOSUCHKEY", "GET", "/b/k", null));
    }

    @Test
    void aMalformedIdentityPolicyGrantsNothing() throws Exception {
        String key = credentials.create("broken").getAccessKeyId();
        var entity = credentials.findByAccessKeyId(key).orElseThrow();
        entity.setPolicy(MAPPER.readTree("{\"Statement\":\"nonsense\"}"));
        // bypass the validation on purpose: a hand-edited credentials.json
        new JsonCredentialRepository(MAPPER, props()).save(entity);
        assertFalse(allowed(key, "GET", "/b/k", null));
    }

    private StorageProperties props() {
        StorageProperties p = new StorageProperties();
        p.setMetaDir(dir.toString());
        return p;
    }

    @Test
    void secureTransportConditionOfAnIdentityPolicy() {
        String key = keyWith("{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"Resource\":\"*\","
                + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"false\"}}}," + stmt("Allow", "s3:*", "*"));
        MockHttpServletRequest plain = req("GET", "/b/k", null);
        MockHttpServletRequest tls = req("GET", "/b/k", null);
        tls.setSecure(true);
        assertTrue(authorizer.authorize(plain, key).isPresent());
        assertTrue(authorizer.authorize(tls, key).isEmpty());
    }

    @Test
    void listBucketPrefixCondition() {
        String key = keyWith("{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::b\","
                + "\"Condition\":{\"StringLike\":{\"s3:prefix\":\"home/alice/*\"}}}");
        assertTrue(allowed(key, "GET", "/b", "list-type=2&prefix=home/alice/x"));
        assertFalse(allowed(key, "GET", "/b", "list-type=2&prefix=home/bob/"));
        assertFalse(allowed(key, "GET", "/b", "list-type=2"));
        assertFalse(allowed(key, "HEAD", "/b", null));
    }

    // ── operation to action and resource ─────────────────────────────────────

    private static List<String> needs(IamAuthorizer a, String method, String uri, String query, String copySource) {
        MockHttpServletRequest r = req(method, uri, query);
        if (copySource != null) r.addHeader("x-amz-copy-source", copySource);
        List<IamAuthorizer.Requirement> list = a.requirements(r);
        return list == null ? null : list.stream().map(x -> x.action() + " " + x.resource()).toList();
    }

    @Test
    void everyOperationNeedsTheDocumentedActionOnTheDocumentedResource() {
        Object[][] table = {
                {"GET", "/", null, null, "s3:ListAllMyBuckets arn:aws:s3:::*"},
                {"PUT", "/b", null, null, "s3:CreateBucket arn:aws:s3:::b"},
                {"DELETE", "/b", null, null, "s3:DeleteBucket arn:aws:s3:::b"},
                {"HEAD", "/b", null, null, "s3:ListBucket arn:aws:s3:::b"},
                {"GET", "/b", "list-type=2", null, "s3:ListBucket arn:aws:s3:::b"},
                {"GET", "/b/", null, null, "s3:ListBucket arn:aws:s3:::b"},
                {"GET", "/b", "versions", null, "s3:ListBucketVersions arn:aws:s3:::b"},
                {"GET", "/b", "location", null, "s3:GetBucketLocation arn:aws:s3:::b"},
                {"GET", "/b", "acl", null, "s3:GetBucketAcl arn:aws:s3:::b"},
                {"PUT", "/b", "acl", null, "s3:PutBucketAcl arn:aws:s3:::b"},
                {"GET", "/b", "policy", null, "s3:GetBucketPolicy arn:aws:s3:::b"},
                {"PUT", "/b", "policy", null, "s3:PutBucketPolicy arn:aws:s3:::b"},
                {"DELETE", "/b", "policy", null, "s3:DeleteBucketPolicy arn:aws:s3:::b"},
                {"GET", "/b", "uploads", null, "s3:ListBucketMultipartUploads arn:aws:s3:::b"},
                {"GET", "/b", "versioning", null, "s3:GetBucketVersioning arn:aws:s3:::b"},
                {"GET", "/b", "tagging", null, "s3:GetBucketTagging arn:aws:s3:::b"},
                {"GET", "/b", "cors", null, "s3:GetBucketCORS arn:aws:s3:::b"},
                {"GET", "/b", "lifecycle", null, "s3:GetLifecycleConfiguration arn:aws:s3:::b"},
                {"GET", "/b", "encryption", null, "s3:GetEncryptionConfiguration arn:aws:s3:::b"},
                {"GET", "/b", "publicAccessBlock", null, "s3:GetBucketPublicAccessBlock arn:aws:s3:::b"},
                {"GET", "/b/dir/k%20x", null, null, "s3:GetObject arn:aws:s3:::b/dir/k x"},
                {"HEAD", "/b/k", null, null, "s3:GetObject arn:aws:s3:::b/k"},
                {"PUT", "/b/k", null, null, "s3:PutObject arn:aws:s3:::b/k"},
                {"DELETE", "/b/k", null, null, "s3:DeleteObject arn:aws:s3:::b/k"},
                {"GET", "/b/k", "acl", null, "s3:GetObjectAcl arn:aws:s3:::b/k"},
                {"PUT", "/b/k", "acl", null, "s3:PutObjectAcl arn:aws:s3:::b/k"},
                {"GET", "/b/k", "tagging", null, "s3:GetObjectTagging arn:aws:s3:::b/k"},
                {"PUT", "/b/k", "tagging", null, "s3:PutObjectTagging arn:aws:s3:::b/k"},
                {"DELETE", "/b/k", "tagging", null, "s3:DeleteObjectTagging arn:aws:s3:::b/k"},
                {"POST", "/b/k", "uploads", null, "s3:PutObject arn:aws:s3:::b/k"},
                {"PUT", "/b/k", "partNumber=1&uploadId=u", null, "s3:PutObject arn:aws:s3:::b/k"},
                {"POST", "/b/k", "uploadId=u", null, "s3:PutObject arn:aws:s3:::b/k"},
                {"DELETE", "/b/k", "uploadId=u", null, "s3:AbortMultipartUpload arn:aws:s3:::b/k"},
                {"GET", "/b/k", "uploadId=u", null, "s3:ListMultipartUploadParts arn:aws:s3:::b/k"},
                {"PUT", "/dst/k", null, "/src/a%20b", "s3:PutObject arn:aws:s3:::dst/k|s3:GetObject arn:aws:s3:::src/a b"},
                {"PUT", "/dst/k", "partNumber=1&uploadId=u", "src/k2", "s3:PutObject arn:aws:s3:::dst/k|s3:GetObject arn:aws:s3:::src/k2"},
                {"POST", "/b", "delete", null, ""},
        };
        for (Object[] row : table) {
            List<String> got = needs(authorizer, (String) row[0], (String) row[1], (String) row[2], (String) row[3]);
            String expected = (String) row[4];
            List<String> want = expected.isEmpty() ? List.of() : List.of(expected.split("\\|"));
            assertEquals(want, got, row[0] + " " + row[1] + (row[2] == null ? "" : "?" + row[2]));
        }
    }

    @Test
    void unmappedAndAmbiguousRequestsAreRefusedForRestrictedKeysOnly() {
        String key = keyWith(stmt("Allow", "s3:*", "arn:aws:s3:::b") + "," + stmt("Allow", "s3:*", "arn:aws:s3:::b/*"));
        for (String[] r : new String[][]{
                {"PUT", "/b/k", "acl&partNumber=1&uploadId=u"},   // acl vs upload part: Spring would pick the upload
                {"PUT", "/b/k", "%61cl"},                            // encoded sub-resource name
                {"POST", "/b/k", "restore"},
                {"PATCH", "/b/k", null},
                {"GET", "//b/k", null}}) {
            assertNull(needs(authorizer, r[0], r[1], r[2], null), String.join(" ", r[0], r[1], String.valueOf(r[2])));
            assertFalse(allowed(key, r[0], r[1], r[2]));
            assertTrue(allowed(admin, r[0], r[1], r[2]));
        }
        // a percent sign in a VALUE is fine
        assertTrue(allowed(key, "GET", "/b", "list-type=2&prefix=a%2Fb"));
    }

    @Test
    void aCopyWithAnUnreadableSourceHeaderIsRefusedForARestrictedKey() {
        String key = keyWith(stmt("Allow", "s3:*", "arn:aws:s3:::b/*"));
        MockHttpServletRequest r = req("PUT", "/b/k", null);
        r.addHeader("x-amz-copy-source", "no-slash");
        assertTrue(authorizer.authorize(r, key).isPresent());
        assertTrue(authorizer.authorize(r, admin).isEmpty(), "the controller will answer 400 for a admin key");
    }

    @Test
    void theDenialNamesThePrincipalActionAndResource() {
        String key = keyWith(stmt("Allow", "s3:GetObject", "arn:aws:s3:::b/*"));
        Optional<IamAuthorizer.Denial> d = authorizer.authorize(req("PUT", "/b/k", null), key);
        assertEquals("User: arn:aws:iam::sectoriadb:user/" + key + " is not authorized to perform: s3:PutObject on resource: arn:aws:s3:::b/k",
                d.orElseThrow().message());
    }

    @Test
    void perKeyDeleteDecision() {
        String key = keyWith(stmt("Allow", "s3:DeleteObject", "arn:aws:s3:::b/tmp/*"));
        var r = req("POST", "/b", "delete");
        assertTrue(authorizer.isAllowed(r, key, "s3:DeleteObject", "b", "tmp/x"));
        assertFalse(authorizer.isAllowed(r, key, "s3:DeleteObject", "b", "keep/x"));
        assertTrue(authorizer.isAllowed(r, admin, "s3:DeleteObject", "b", "keep/x"));
    }
}
