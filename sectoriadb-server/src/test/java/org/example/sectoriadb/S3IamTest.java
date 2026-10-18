package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.security.CredentialEntity;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.security.JsonCredentialRepository;
import org.example.sectoriadb.storage.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM identity policies over HTTP: an admin key without a policy (full access) and tenant keys with policies, against
 * the real filter chain and controllers. Anonymous rules are covered by S3PublicAccessTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3IamTest {

    static final String AK = "TESTACCESSKEY0000001";
    static final String SK = "test-secret-key-for-unit-tests";

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.multipart.min-part-size", () -> "0");
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "true");
        r.add("sectoriadb.s3.access-key",       () -> AK);
        r.add("sectoriadb.s3.secret-key",       () -> SK);
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired CredentialService credentials;
    @Autowired PoolService poolService;

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String EMPTY = SigV4TestClient.sha256(new byte[0]);

    private SigV4TestClient admin() { return new SigV4TestClient(port, AK, SK); }

    private SigV4TestClient tenant(String policyJson) throws Exception {
        CredentialEntity key = credentials.create("tenant");
        if (policyJson != null) credentials.setPolicy(key.getAccessKeyId(), MAPPER.readTree(policyJson));
        return new SigV4TestClient(port, key.getAccessKeyId(), key.getSecretKey());
    }

    private static HttpResponse<String> send(SigV4TestClient c, String method, String path, String query, String body) throws Exception {
        return send(c, method, path, query, body, Map.of());
    }

    private static HttpResponse<String> send(SigV4TestClient c, String method, String path, String query, String body,
                                             Map<String, String> headers) throws Exception {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return c.request(method, path, query, bytes == null ? EMPTY : SigV4TestClient.sha256(bytes), bytes, headers);
    }

    private static String stmt(String effect, String actions, String resources) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":" + actions + ",\"Resource\":" + resources + "}";
    }

    private static String policy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    /** A tenant limited to bucket {@code b}: list, read, write, delete and multipart. */
    private static String onlyBucket(String b) {
        return policy(stmt("Allow", "[\"s3:ListBucket\",\"s3:GetBucketLocation\",\"s3:ListBucketMultipartUploads\"]", "\"arn:aws:s3:::" + b + "\""),
                stmt("Allow", "[\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\",\"s3:AbortMultipartUpload\",\"s3:ListMultipartUploadParts\"]",
                        "\"arn:aws:s3:::" + b + "/*\""));
    }

    private void bucketWithObject(String bucket, String key, String content) throws Exception {
        assertEquals(200, send(admin(), "PUT", "/" + bucket, null, null).statusCode());
        assertEquals(200, send(admin(), "PUT", "/" + bucket + "/" + key, null, content).statusCode());
    }

    private static void assertDenied(HttpResponse<String> r, String what) {
        assertEquals(403, r.statusCode(), what + " -> " + r.body());
        // a HEAD response has no body
        assertTrue(r.body().isEmpty() || r.body().contains("<Code>AccessDenied</Code>"), what + " -> " + r.body());
    }

    private static String uploadId(HttpResponse<String> init) {
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(init.body());
        assertTrue(m.find(), init.body());
        return m.group(1);
    }

    // ── tenant isolation ─────────────────────────────────────────────────────

    @Test
    void aKeyLimitedToBucketBCannotTouchBucketA() throws Exception {
        bucketWithObject("iso-a", "secret.txt", "classified");
        assertEquals(200, send(admin(), "PUT", "/iso-b", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("iso-b"));

        assertDenied(send(alt, "GET", "/iso-a/secret.txt", null, null), "read A");
        assertDenied(send(alt, "HEAD", "/iso-a/secret.txt", null, null), "head A");
        assertDenied(send(alt, "PUT", "/iso-a/new.txt", null, "x"), "write A");
        assertDenied(send(alt, "DELETE", "/iso-a/secret.txt", null, null), "delete A");
        assertDenied(send(alt, "GET", "/iso-a", "list-type=2", null), "list A");
        assertDenied(send(alt, "HEAD", "/iso-a", null, null), "head bucket A");
        assertDenied(send(alt, "DELETE", "/iso-a", null, null), "delete bucket A");
        assertDenied(send(alt, "GET", "/iso-a", "acl", null), "bucket ACL of A");
        assertDenied(send(alt, "PUT", "/iso-a", "acl", null, Map.of("x-amz-acl", "public-read")), "make A public");
        assertDenied(send(alt, "GET", "/iso-a", "policy", null), "policy of A");
        assertDenied(send(alt, "PUT", "/iso-a/secret.txt", "acl", null, Map.of("x-amz-acl", "public-read")), "make an object public");
        assertDenied(send(alt, "GET", "/iso-a/secret.txt", "acl", null), "object ACL");
        assertDenied(send(alt, "GET", "/iso-a", "uploads", null), "list uploads of A");

        assertEquals("classified", send(admin(), "GET", "/iso-a/secret.txt", null, null).body(), "A is untouched");
        assertEquals(200, send(admin(), "GET", "/iso-a", "list-type=2", null).statusCode());

        // the allowed operations on B
        assertEquals(200, send(alt, "PUT", "/iso-b/mine.txt", null, "hello").statusCode());
        assertEquals("hello", send(alt, "GET", "/iso-b/mine.txt", null, null).body());
        assertEquals(200, send(alt, "HEAD", "/iso-b/mine.txt", null, null).statusCode());
        var list = send(alt, "GET", "/iso-b", "list-type=2", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("mine.txt"));
        assertEquals(200, send(alt, "HEAD", "/iso-b", null, null).statusCode());
        assertEquals(204, send(alt, "DELETE", "/iso-b/mine.txt", null, null).statusCode());
        // not granted on B either: administration of the bucket itself
        assertDenied(send(alt, "DELETE", "/iso-b", null, null), "delete bucket B");
        assertDenied(send(alt, "PUT", "/iso-b", "acl", null, Map.of("x-amz-acl", "public-read")), "ACL of B");
    }

    @Test
    void deniedRequestsAreCountedAsPolicyDeniedAndNameTheAction() throws Exception {
        bucketWithObject("msg-a", "k", "v");
        var denied = send(tenant(onlyBucket("msg-other")), "GET", "/msg-a/k", null, null);
        assertDenied(denied, "read");
        assertTrue(denied.body().contains("is not authorized to perform: s3:GetObject on resource: arn:aws:s3:::msg-a/k"), denied.body());
    }

    @Test
    void listAllMyBucketsNeedsItsOwnPermissionAndThenShowsEveryBucket() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/lab-a", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("lab-b"));
        assertDenied(send(alt, "GET", "/", null, null), "ListBuckets without s3:ListAllMyBuckets");

        SigV4TestClient lister = tenant(policy(stmt("Allow", "\"s3:ListAllMyBuckets\"", "\"*\"")));
        var r = send(lister, "GET", "/", null, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("<Name>lab-a</Name>"), "like AWS: every name, the bucket contents stay protected");
        assertDenied(send(lister, "GET", "/lab-a", "list-type=2", null), "listing the contents");
    }

    @Test
    void createBucketIsAnActionOfItsOwn() throws Exception {
        SigV4TestClient alt = tenant(onlyBucket("cb-b"));
        assertDenied(send(alt, "PUT", "/cb-other", null, null), "create another bucket");
        assertFalse(poolService.existsByName("cb-other"));
        SigV4TestClient creator = tenant(policy(stmt("Allow", "\"s3:CreateBucket\"", "\"arn:aws:s3:::cb-new-*\"")));
        assertEquals(200, send(creator, "PUT", "/cb-new-1", null, null).statusCode());
        assertDenied(send(creator, "PUT", "/cb-bad", null, null), "outside the name pattern");
    }

    // ── copy ─────────────────────────────────────────────────────────────────

    @Test
    void copyNeedsGetObjectOnTheSourceAndPutObjectOnTheDestination() throws Exception {
        bucketWithObject("cp-a", "o.txt", "payload");
        assertEquals(200, send(admin(), "PUT", "/cp-b", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("cp-b"));

        assertDenied(send(alt, "PUT", "/cp-b/copy.txt", null, null, Map.of("x-amz-copy-source", "/cp-a/o.txt")), "copy from A to B");
        assertEquals(404, send(admin(), "GET", "/cp-b/copy.txt", null, null).statusCode(), "nothing was copied");

        // reading the source is not enough without PutObject on the destination
        SigV4TestClient reader = tenant(policy(stmt("Allow", "\"s3:GetObject\"", "\"arn:aws:s3:::cp-a/*\"")));
        assertDenied(send(reader, "PUT", "/cp-b/copy.txt", null, null, Map.of("x-amz-copy-source", "/cp-a/o.txt")), "no PutObject");

        // both
        SigV4TestClient both = tenant(policy(stmt("Allow", "\"s3:GetObject\"", "\"arn:aws:s3:::cp-a/*\""),
                stmt("Allow", "\"s3:PutObject\"", "\"arn:aws:s3:::cp-b/*\"")));
        assertEquals(200, send(both, "PUT", "/cp-b/copy.txt", null, null, Map.of("x-amz-copy-source", "/cp-a/o.txt")).statusCode());
        assertEquals("payload", send(admin(), "GET", "/cp-b/copy.txt", null, null).body());
        assertDenied(send(both, "PUT", "/cp-a/back.txt", null, null, Map.of("x-amz-copy-source", "/cp-b/copy.txt")), "copy from B into A");
    }

    // ── multipart ────────────────────────────────────────────────────────────

    @Test
    void multipartOnAForbiddenBucketIsDeniedAtCreateAndAtUploadPart() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/mp-a", null, null).statusCode());
        assertEquals(200, send(admin(), "PUT", "/mp-b", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("mp-b"));

        assertDenied(send(alt, "POST", "/mp-a/big", "uploads", null), "create in A");
        String foreign = uploadId(send(admin(), "POST", "/mp-a/big", "uploads", null));
        assertDenied(send(alt, "PUT", "/mp-a/big", "partNumber=1&uploadId=" + foreign, "part"), "upload part into A");
        assertDenied(send(alt, "POST", "/mp-a/big", "uploadId=" + foreign, "<CompleteMultipartUpload/>"), "complete in A");
        assertDenied(send(alt, "DELETE", "/mp-a/big", "uploadId=" + foreign, null), "abort in A");
        assertDenied(send(alt, "GET", "/mp-a/big", "uploadId=" + foreign, null), "list parts in A");
        assertDenied(send(alt, "GET", "/mp-a", "uploads", null), "list uploads of A");

        // the whole life cycle on B
        String id = uploadId(send(alt, "POST", "/mp-b/big", "uploads", null));
        var part = send(alt, "PUT", "/mp-b/big", "partNumber=1&uploadId=" + id, "part-one");
        assertEquals(200, part.statusCode(), part.body());
        assertEquals(200, send(alt, "GET", "/mp-b/big", "uploadId=" + id, null).statusCode());
        assertEquals(200, send(alt, "GET", "/mp-b", "uploads", null).statusCode());
        String etag = part.headers().firstValue("ETag").orElseThrow();
        var done = send(alt, "POST", "/mp-b/big", "uploadId=" + id,
                "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag + "</ETag></Part></CompleteMultipartUpload>");
        assertEquals(200, done.statusCode(), done.body());
        assertEquals("part-one", send(alt, "GET", "/mp-b/big", null, null).body());
        String second = uploadId(send(alt, "POST", "/mp-b/other", "uploads", null));
        assertEquals(204, send(alt, "DELETE", "/mp-b/other", "uploadId=" + second, null).statusCode());
    }

    @Test
    void uploadPartCopyNeedsGetObjectOnItsSource() throws Exception {
        bucketWithObject("pc-a", "src", "source-bytes");
        assertEquals(200, send(admin(), "PUT", "/pc-b", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("pc-b"));
        String id = uploadId(send(alt, "POST", "/pc-b/dst", "uploads", null));
        assertDenied(send(alt, "PUT", "/pc-b/dst", "partNumber=1&uploadId=" + id, null, Map.of("x-amz-copy-source", "/pc-a/src")),
                "UploadPartCopy from A");
    }

    // ── DeleteObjects ────────────────────────────────────────────────────────

    @Test
    void multiObjectDeleteReportsEachRefusedKeyLikeAws() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/del-b", null, null).statusCode());
        for (String k : List.of("tmp/1", "tmp/2", "keep/3")) {
            assertEquals(200, send(admin(), "PUT", "/del-b/" + k, null, "x").statusCode());
        }
        SigV4TestClient alt = tenant(policy(stmt("Allow", "\"s3:DeleteObject\"", "\"arn:aws:s3:::del-b/tmp/*\"")));
        var r = send(alt, "POST", "/del-b", "delete", "<Delete><Object><Key>tmp/1</Key></Object><Object><Key>keep/3</Key></Object>"
                + "<Object><Key>tmp/2</Key></Object></Delete>");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("<Deleted><Key>tmp/1</Key></Deleted>"), r.body());
        assertTrue(r.body().contains("<Deleted><Key>tmp/2</Key></Deleted>"), r.body());
        assertTrue(r.body().contains("<Error><Key>keep/3</Key><Code>AccessDenied</Code>"), r.body());
        assertFalse(r.body().contains("<Deleted><Key>keep/3</Key>"), r.body());
        assertEquals(404, send(admin(), "GET", "/del-b/tmp/1", null, null).statusCode());
        assertEquals(200, send(admin(), "GET", "/del-b/keep/3", null, null).statusCode(), "the refused key survives");

        // quiet mode still lists the errors
        var quiet = send(alt, "POST", "/del-b", "delete", "<Delete><Quiet>true</Quiet><Object><Key>keep/3</Key></Object></Delete>");
        assertTrue(quiet.body().contains("AccessDenied"), quiet.body());
        // a key with no delete permission at all still gets a 200 with errors, as in AWS
        SigV4TestClient nothing = tenant(onlyBucket("somewhere-else"));
        var none = send(nothing, "POST", "/del-b", "delete", "<Delete><Object><Key>keep/3</Key></Object></Delete>");
        assertEquals(200, none.statusCode());
        assertTrue(none.body().contains("<Code>AccessDenied</Code>"));
        assertEquals(200, send(admin(), "GET", "/del-b/keep/3", null, null).statusCode());
        // a legacy key deletes everything
        var legacy = send(admin(), "POST", "/del-b", "delete", "<Delete><Object><Key>keep/3</Key></Object></Delete>");
        assertTrue(legacy.body().contains("<Deleted><Key>keep/3</Key></Deleted>"));
    }

    // ── interplay with bucket policies ───────────────────────────────────────

    @Test
    void bucketPolicyCanGrantAKeyAndCanDenyAKeyThatItsIdentityPolicyAllows() throws Exception {
        bucketWithObject("bp-a", "doc", "shared");
        SigV4TestClient alt = tenant(onlyBucket("bp-own"));
        assertDenied(send(alt, "GET", "/bp-a/doc", null, null), "before the bucket policy");

        String grant = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::sectoriadb:user/"
                + alt.accessKey + "\"},\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::bp-a/*\"}]}";
        assertEquals(204, send(admin(), "PUT", "/bp-a", "policy", grant).statusCode());
        assertEquals("shared", send(alt, "GET", "/bp-a/doc", null, null).body());
        assertDenied(send(alt, "PUT", "/bp-a/doc", null, "overwrite"), "the grant is for reading only");

        // Deny in the bucket policy beats the identity policy's Allow, also for the plain access key id as principal
        SigV4TestClient wide = tenant(policy(stmt("Allow", "\"s3:*\"", "[\"arn:aws:s3:::*\"]")));
        String deny = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\""
                + wide.accessKey + "\"},\"Action\":\"s3:DeleteObject\",\"Resource\":\"arn:aws:s3:::bp-a/*\"}]}";
        assertEquals(204, send(admin(), "PUT", "/bp-a", "policy", deny).statusCode());
        assertDenied(send(wide, "DELETE", "/bp-a/doc", null, null), "explicit Deny");
        assertEquals(200, send(wide, "GET", "/bp-a/doc", null, null).statusCode());
        assertEquals(204, send(admin(), "DELETE", "/bp-a", "policy", null).statusCode());
        assertEquals(204, send(wide, "DELETE", "/bp-a/doc", null, null).statusCode());
    }

    @Test
    void aRestrictedKeyCannotGrantItselfMoreThroughThePolicyOrAclCalls() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/esc-a", null, null).statusCode());
        SigV4TestClient alt = tenant(onlyBucket("esc-b"));
        String openAll = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
        assertDenied(send(alt, "PUT", "/esc-a", "policy", openAll), "PutBucketPolicy");
        assertDenied(send(alt, "PUT", "/esc-b", "policy", openAll), "PutBucketPolicy on its own bucket");
    }

    // ── presigned URLs ───────────────────────────────────────────────────────

    @Test
    void presignedUrlsFollowThePolicyOfTheSigningKey() throws Exception {
        bucketWithObject("ps-a", "k", "presigned");
        assertEquals(200, send(admin(), "PUT", "/ps-b", null, null).statusCode());
        assertEquals(200, send(admin(), "PUT", "/ps-b/k", null, "mine").statusCode());
        SigV4TestClient alt = tenant(onlyBucket("ps-b"));
        assertEquals(403, alt.get(alt.presign("/ps-a/k", Instant.now(), 300)).statusCode());
        assertEquals("mine", alt.get(alt.presign("/ps-b/k", Instant.now(), 300)).body());
        assertEquals("presigned", admin().get(admin().presign("/ps-a/k", Instant.now(), 300)).body());
    }

    // ── changes without restart ──────────────────────────────────────────────

    @Test
    void aPolicyChangeMadeByAnotherProcessApplyToTheNextRequest() throws Exception {
        bucketWithObject("live-a", "k", "v");
        SigV4TestClient key = tenant(null);   // no policy: legacy full access
        assertEquals(200, send(key, "GET", "/live-a/k", null, null).statusCode());

        // the admin CLI is a separate process with its own repository over the same credentials.json
        StorageProperties props = new StorageProperties();
        props.setMetaDir(tempRoot.resolve("meta").toString());
        CredentialService cli = new CredentialService(new JsonCredentialRepository(MAPPER, props));
        cli.setPolicy(key.accessKey, MAPPER.readTree(onlyBucket("live-other")));
        assertDenied(send(key, "GET", "/live-a/k", null, null), "after key-policy --file");

        cli.setPolicy(key.accessKey, MAPPER.readTree(onlyBucket("live-a")));
        assertEquals(200, send(key, "GET", "/live-a/k", null, null).statusCode());

        cli.setPolicy(key.accessKey, null);
        assertEquals(200, send(key, "PUT", "/live-other", null, null).statusCode(), "removed: full access again");
        assertTrue(Files.readString(tempRoot.resolve("meta").resolve("credentials.json"), StandardCharsets.UTF_8).contains(key.accessKey));
    }

    // ── the example policies of deploy/iam, verbatim ─────────────────────────

    private static String example(String name) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("deploy/iam/" + name))) p = p.getParent();
        return Files.readString(p.resolve("deploy/iam/" + name), StandardCharsets.UTF_8);
    }

    private void exampleBucket() throws Exception {
        if (!poolService.existsByName("example-bucket")) {
            assertEquals(200, send(admin(), "PUT", "/example-bucket", null, null).statusCode());
            assertEquals(200, send(admin(), "PUT", "/example-bucket/seed.txt", null, "seed").statusCode());
            assertEquals(200, send(admin(), "PUT", "/not-example", null, null).statusCode());
            assertEquals(200, send(admin(), "PUT", "/not-example/x", null, null, Map.of()).statusCode());
        }
    }

    @Test
    void readOnlyExample() throws Exception {
        exampleBucket();
        var ro = tenant(example("read-only.json"));
        assertEquals("seed", send(ro, "GET", "/example-bucket/seed.txt", null, null).body());
        assertEquals(200, send(ro, "GET", "/example-bucket", "list-type=2", null).statusCode());
        assertEquals(200, send(ro, "GET", "/example-bucket", "location", null).statusCode());
        assertDenied(send(ro, "PUT", "/example-bucket/new", null, "x"), "write");
        assertDenied(send(ro, "DELETE", "/example-bucket/seed.txt", null, null), "delete");
        assertDenied(send(ro, "POST", "/example-bucket/new", "uploads", null), "multipart");
        assertDenied(send(ro, "GET", "/not-example/x", null, null), "other bucket");
        assertDenied(send(ro, "GET", "/", null, null), "ListBuckets");
    }

    @Test
    void readWriteExample() throws Exception {
        exampleBucket();
        var rw = tenant(example("read-write.json"));
        assertEquals(200, send(rw, "PUT", "/example-bucket/rw.txt", null, "rw").statusCode());
        assertEquals("rw", send(rw, "GET", "/example-bucket/rw.txt", null, null).body());
        assertEquals(200, send(rw, "GET", "/example-bucket", "list-type=2", null).statusCode());
        String id = uploadId(send(rw, "POST", "/example-bucket/mp", "uploads", null));
        var part = send(rw, "PUT", "/example-bucket/mp", "partNumber=1&uploadId=" + id, "p");
        assertEquals(200, part.statusCode());
        assertEquals(200, send(rw, "GET", "/example-bucket/mp", "uploadId=" + id, null).statusCode());
        assertEquals(204, send(rw, "DELETE", "/example-bucket/mp", "uploadId=" + id, null).statusCode());
        assertEquals(204, send(rw, "DELETE", "/example-bucket/rw.txt", null, null).statusCode());
        assertDenied(send(rw, "PUT", "/not-example/x", null, "no"), "other bucket");
        assertDenied(send(rw, "DELETE", "/example-bucket", null, null), "delete the bucket");
        assertDenied(send(rw, "PUT", "/example-bucket", "policy", "{}"), "policy");
    }

    @Test
    void backupWriterExample() throws Exception {
        exampleBucket();
        var bk = tenant(example("backup-writer.json"));
        assertEquals(200, send(bk, "PUT", "/example-bucket/backup-1.tar", null, "tar").statusCode());
        String id = uploadId(send(bk, "POST", "/example-bucket/big.tar", "uploads", null));
        var part = send(bk, "PUT", "/example-bucket/big.tar", "partNumber=1&uploadId=" + id, "part");
        assertEquals(200, part.statusCode(), part.body());
        String etag = part.headers().firstValue("ETag").orElseThrow();
        assertEquals(200, send(bk, "POST", "/example-bucket/big.tar", "uploadId=" + id,
                "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag + "</ETag></Part></CompleteMultipartUpload>").statusCode());
        String aborted = uploadId(send(bk, "POST", "/example-bucket/aborted.tar", "uploads", null));
        assertEquals(204, send(bk, "DELETE", "/example-bucket/aborted.tar", "uploadId=" + aborted, null).statusCode());

        assertDenied(send(bk, "DELETE", "/example-bucket/backup-1.tar", null, null), "delete");
        assertDenied(send(bk, "GET", "/example-bucket/backup-1.tar", null, null), "read back");
        assertDenied(send(bk, "GET", "/example-bucket", "list-type=2", null), "list");
        assertDenied(send(bk, "PUT", "/not-example/x", null, "no"), "other bucket");
        var many = send(bk, "POST", "/example-bucket", "delete", "<Delete><Object><Key>backup-1.tar</Key></Object></Delete>");
        assertTrue(many.body().contains("AccessDenied"), many.body());
        assertEquals("tar", send(admin(), "GET", "/example-bucket/backup-1.tar", null, null).body());
    }

    @Test
    void aKeyWithoutAPolicyStillHasFullAccess() throws Exception {
        SigV4TestClient legacy = tenant(null);
        assertEquals(200, send(legacy, "PUT", "/legacy-b", null, null).statusCode());
        assertEquals(200, send(legacy, "PUT", "/legacy-b/k", null, "v").statusCode());
        assertEquals(200, send(legacy, "GET", "/", null, null).statusCode());
        assertEquals(204, send(legacy, "DELETE", "/legacy-b/k", null, null).statusCode());
    }
}
