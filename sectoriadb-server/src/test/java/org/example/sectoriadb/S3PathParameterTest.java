package org.example.sectoriadb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.security.CredentialEntity;
import org.example.sectoriadb.security.CredentialService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code ;} in the raw path would be seen by the authorizer as part of the bucket name but stripped by Spring's
 * routing (path parameter), which bypassed an identity-policy Deny on bucket-level operations. Against a live Tomcat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3PathParameterTest {

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

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String EMPTY = SigV4TestClient.sha256(new byte[0]);

    private SigV4TestClient admin() { return new SigV4TestClient(port, AK, SK); }

    private HttpResponse<String> send(SigV4TestClient c, String method, String path, String query, String body) throws Exception {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return c.request(method, path, query, bytes == null ? EMPTY : SigV4TestClient.sha256(bytes), bytes, Map.of());
    }

    @Test
    void semicolonInTheBucketSegmentIsRefusedInsteadOfBypassingADeny() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/secret", null, null).statusCode());
        CredentialEntity key = credentials.create("t");
        credentials.setPolicy(key.getAccessKeyId(), MAPPER.readTree("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:*","Resource":["arn:aws:s3:::secret","arn:aws:s3:::secret/*"]}]}"""));
        SigV4TestClient t = new SigV4TestClient(port, key.getAccessKeyId(), key.getSecretKey());

        assertEquals(403, send(t, "GET", "/secret", "list-type=2", null).statusCode(), "plain Deny holds");
        for (String path : new String[] {"/secret;x", "/secret;", "/secret/;x"}) {
            HttpResponse<String> r = send(t, "GET", path, "list-type=2", null);
            assertEquals(400, r.statusCode(), path + " -> " + r.body());
            assertTrue(r.body().contains("<Code>InvalidURI</Code>"), r.body());
        }
        assertEquals(400, send(t, "PUT", "/secret;x", "policy", "{}").statusCode());
        assertEquals(400, send(t, "DELETE", "/secret;x", null, null).statusCode());
        // and the signed admin is refused too: the rule is about the URI, not about who asks
        assertEquals(400, send(admin(), "GET", "/secret;x", "list-type=2", null).statusCode());
    }

    @Test
    void anonymousRequestsWithASemicolonAreRefusedToo() throws Exception {
        HttpResponse<String> r = admin().http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/pub;x?list-type=2")).GET().build(), BodyHandlers.ofString());
        assertEquals(400, r.statusCode(), r.body());
        assertTrue(r.body().contains("<Code>InvalidURI</Code>"), r.body());
    }

    @Test
    void anEncodedSemicolonInAnObjectKeyStillWorks() throws Exception {
        assertEquals(200, send(admin(), "PUT", "/semi", null, null).statusCode());
        assertEquals(200, send(admin(), "PUT", "/semi/a%3Bb.txt", null, "hello").statusCode());
        HttpResponse<String> g = send(admin(), "GET", "/semi/a%3Bb.txt", null, null);
        assertEquals(200, g.statusCode(), g.body());
        assertEquals("hello", g.body());
        HttpResponse<String> list = send(admin(), "GET", "/semi", "list-type=2", null);
        assertTrue(list.body().contains("<Key>a;b.txt</Key>"), list.body());
    }
}
