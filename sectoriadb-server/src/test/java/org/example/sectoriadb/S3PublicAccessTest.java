package org.example.sectoriadb;

import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.PoolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anonymous access with authentication ENABLED (a credential exists, so unsigned requests are rejected
 * unless the bucket/object is public). No signing is needed: only anonymous requests are sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3PublicAccessTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "true");
        r.add("sectoriadb.s3.access-key",       () -> "TESTACCESSKEY0000001");
        r.add("sectoriadb.s3.secret-key",       () -> "test-secret-key-for-unit-tests");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired PoolService poolService;
    @Autowired FileStorageService fileService;
    @Autowired ManifestRepository manifestRepo;

    private final HttpClient http = HttpClient.newHttpClient();

    private int status(String method, String path) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if ("PUT".equals(method)) b.PUT(BodyPublishers.ofString("x"));
        else if ("DELETE".equals(method)) b.DELETE();
        else b.method(method, BodyPublishers.noBody());
        return http.send(b.build(), BodyHandlers.discarding()).statusCode();
    }

    private void putObject(String bucket, String key) throws Exception {
        var pool = poolService.createBucket(bucket);
        fileService.storeStream(new ByteArrayInputStream("data".getBytes()), pool, key, "text/plain");
    }

    @Test
    void everythingIsPrivateByDefault() throws Exception {
        putObject("priv-bkt", "a.txt");
        assertEquals(403, status("GET", "/priv-bkt/a.txt"));
        assertEquals(403, status("GET", "/priv-bkt?list-type=2"));
        assertEquals(403, status("GET", "/"));
        assertEquals(403, status("PUT", "/priv-bkt/new.txt"));
    }

    @Test
    void publicObjectAclOpensOnlyThatObject() throws Exception {
        putObject("obj-bkt", "pub.txt");
        putObject("obj-bkt", "other.txt");
        ManifestEntity m = manifestRepo.findCurrent("obj-bkt", "pub.txt").orElseThrow();
        m.setAcl("public-read");
        manifestRepo.save(m);

        assertEquals(200, status("GET", "/obj-bkt/pub.txt"));
        assertEquals(200, status("HEAD", "/obj-bkt/pub.txt"));
        assertEquals(403, status("GET", "/obj-bkt/other.txt"));
        assertEquals(403, status("GET", "/obj-bkt?list-type=2"), "listing stays private");
    }

    @Test
    void publicBucketOpensListButNotObjectsNorWritesNorAdminCalls() throws Exception {
        putObject("pub-bkt", "a.txt");
        poolService.setAcl("pub-bkt", "public-read");

        // AWS semantics: bucket public-read grants ListBucket only; objects need their own ACL or a policy
        assertEquals(403, status("GET", "/pub-bkt/a.txt"));
        assertEquals(403, status("HEAD", "/pub-bkt/a.txt"));
        assertEquals(200, status("GET", "/pub-bkt?list-type=2"));
        // prefixes that merely CONTAIN words like acl/uploads must not be treated as admin sub-resources
        assertEquals(200, status("GET", "/pub-bkt?list-type=2&prefix=uploads/"));
        assertEquals(200, status("GET", "/pub-bkt?list-type=2&prefix=place/"));
        assertEquals(403, status("PUT", "/pub-bkt/x.txt"));
        assertEquals(403, status("DELETE", "/pub-bkt/a.txt"));
        assertEquals(403, status("GET", "/pub-bkt?acl"));
        assertEquals(403, status("GET", "/pub-bkt?policy"));
        assertEquals(403, status("POST", "/pub-bkt/a.txt?uploads"));
    }

    @Test
    void bucketPolicyOpensOnlyMatchingPrefix() throws Exception {
        putObject("pol-bkt", "docs/a.txt");
        putObject("pol-bkt", "secret/b.txt");
        poolService.setPolicy("pol-bkt", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::pol-bkt/docs/*\"}]}");

        assertEquals(200, status("GET", "/pol-bkt/docs/a.txt"));
        assertEquals(403, status("GET", "/pol-bkt/secret/b.txt"));
        poolService.deletePolicy("pol-bkt");
        assertEquals(403, status("GET", "/pol-bkt/docs/a.txt"));
    }

    @Test
    void invalidSignatureIsRejectedEvenForPublicBucket() throws Exception {
        putObject("sig-bkt", "a.txt");
        poolService.setAcl("sig-bkt", "public-read");
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sig-bkt/a.txt"))
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=NOPE/20260101/us-east-1/s3/aws4_request,"
                        + "SignedHeaders=host,Signature=" + "0".repeat(64))
                .header("x-amz-date", "20260101T000000Z").GET().build();
        int code = http.send(req, BodyHandlers.discarding()).statusCode();
        assertTrue(code == 403 || code == 400, "a request that claims credentials must be validated, got " + code);
    }

    @Test
    void publicReadWriteBucketStillAllowsWritesButNotReads() throws Exception {
        putObject("rw-bkt", "a.txt");
        poolService.setAcl("rw-bkt", "public-read-write");
        assertEquals(403, status("GET", "/rw-bkt/a.txt"));
        assertEquals(200, status("GET", "/rw-bkt?list-type=2"));
        assertEquals(200, status("PUT", "/rw-bkt/new.txt"));
    }

    private int statusWithHeaders(String method, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        if ("PUT".equals(method)) b.PUT(BodyPublishers.ofString("x"));
        else b.method(method, BodyPublishers.noBody());
        return http.send(b.build(), BodyHandlers.discarding()).statusCode();
    }

    @Test
    void anonymousCopyIntoPublicWritableBucketCannotReadPrivateObjects() throws Exception {
        putObject("loot-src", "secret.txt");
        putObject("loot-dst", "seed.txt");
        poolService.setAcl("loot-dst", "public-read-write");

        // plain anonymous write is allowed by the bucket ACL ...
        assertEquals(200, status("PUT", "/loot-dst/plain.txt"));
        // ... but naming a source (CopyObject) or making the copy public is not
        assertEquals(403, statusWithHeaders("PUT", "/loot-dst/loot.txt", "x-amz-copy-source", "/loot-src/secret.txt"));
        assertEquals(403, statusWithHeaders("PUT", "/loot-dst/loot2.txt", "x-amz-copy-source", "loot-src/secret.txt",
                "x-amz-acl", "public-read"));
        assertTrue(manifestRepo.findCurrent("loot-dst", "loot.txt").isEmpty());
        assertTrue(manifestRepo.findCurrent("loot-dst", "loot2.txt").isEmpty());
        // an anonymous caller cannot make its own upload public or hand out grants either (needs s3:PutObjectAcl)
        assertEquals(403, statusWithHeaders("PUT", "/loot-dst/pub.txt", "x-amz-acl", "public-read"));
        assertEquals(403, statusWithHeaders("PUT", "/loot-dst/pub2.txt", "x-amz-grant-read",
                "uri=\"http://acs.amazonaws.com/groups/global/AllUsers\""));
        assertEquals(200, statusWithHeaders("PUT", "/loot-dst/priv.txt", "x-amz-acl", "private"));
        assertEquals(403, statusWithHeaders("GET", "/loot-dst/plain.txt"), "uploaded object is not readable anonymously");
    }

    @Test
    void publicBucketPlusPublicObjectAclReadsOnlyThatObject() throws Exception {
        putObject("mix-bkt", "pub.txt");
        putObject("mix-bkt", "priv.txt");
        poolService.setAcl("mix-bkt", "public-read");
        ManifestEntity m = manifestRepo.findCurrent("mix-bkt", "pub.txt").orElseThrow();
        m.setAcl("public-read");
        manifestRepo.save(m);
        assertEquals(200, status("GET", "/mix-bkt/pub.txt"));
        assertEquals(403, status("GET", "/mix-bkt/priv.txt"));
    }

    @Test
    void explicitDenyOverridesAllowAndPublicAcl() throws Exception {
        putObject("deny-bkt", "docs/a.txt");
        putObject("deny-bkt", "docs/secret/b.txt");
        poolService.setPolicy("deny-bkt", "{\"Version\":\"2012-10-17\",\"Statement\":[\n"
                + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::deny-bkt/*\"]},\n"
                + "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"s3:Get*\",\"Resource\":\"arn:aws:s3:::deny-bkt/docs/secret/*\"}]}");
        assertEquals(200, status("GET", "/deny-bkt/docs/a.txt"));
        assertEquals(403, status("GET", "/deny-bkt/docs/secret/b.txt"));
        // object ACL public-read does not beat an explicit Deny either
        ManifestEntity m = manifestRepo.findCurrent("deny-bkt", "docs/secret/b.txt").orElseThrow();
        m.setAcl("public-read");
        manifestRepo.save(m);
        assertEquals(403, status("GET", "/deny-bkt/docs/secret/b.txt"));
    }

    @Test
    void allowWithConditionDoesNotGrantAccess() throws Exception {
        putObject("cond-bkt", "a.txt");
        poolService.setPolicy("cond-bkt", "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::cond-bkt/*\","
                + "\"Condition\":{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}}]}");
        assertEquals(403, status("GET", "/cond-bkt/a.txt"));
    }

    @Test
    void policyAllowListBucketOpensListing() throws Exception {
        putObject("lst-bkt", "a.txt");
        poolService.setPolicy("lst-bkt", "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::lst-bkt\"}}");
        assertEquals(200, status("GET", "/lst-bkt?list-type=2"));
        assertEquals(403, status("GET", "/lst-bkt/a.txt"));
    }
}
