package org.example;

import org.example.entity.ManifestEntity;
import org.example.repository.ManifestRepository;
import org.example.service.FileStorageService;
import org.example.service.PoolService;
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
import java.net.http.HttpResponse;
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
        ManifestEntity m = manifestRepo.findByBucketNameAndObjectKeyAndDeletedFalse("obj-bkt", "pub.txt").orElseThrow();
        m.setAcl("public-read");
        manifestRepo.save(m);

        assertEquals(200, status("GET", "/obj-bkt/pub.txt"));
        assertEquals(200, status("HEAD", "/obj-bkt/pub.txt"));
        assertEquals(403, status("GET", "/obj-bkt/other.txt"));
        assertEquals(403, status("GET", "/obj-bkt?list-type=2"), "listing stays private");
    }

    @Test
    void publicBucketOpensReadAndListButNeverWritesOrAdminCalls() throws Exception {
        putObject("pub-bkt", "a.txt");
        poolService.setAcl("pub-bkt", "public-read");

        assertEquals(200, status("GET", "/pub-bkt/a.txt"));
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
}
