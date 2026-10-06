package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Input-validation hardening (auth disabled so requests need no signature): uploadId and XXE. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3SecurityHardeningTest {

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir",            () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir",            () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.default-chunk-size",  () -> "4096");
        r.add("sectoriadb.s3.auth.enabled",     () -> "false");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private void mkBucket(String name) throws Exception {
        assertEquals(200, send("PUT", "/" + name, null).statusCode());
    }

    @Test
    void uploadIdThatIsNotACanonicalUuidIsRejectedBeforeTouchingTheFilesystem() throws Exception {
        mkBucket("hardbkt");
        for (String bad : new String[]{"..", "..%2F..%2Fetc", "1-1-1-1-1", "../x", "%2e%2e",
                "00000000-0000-0000-0000-00000000000G", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}) {
            var put = send("PUT", "/hardbkt/o?partNumber=1&uploadId=" + bad, "data");
            assertEquals(404, put.statusCode(), bad + " -> " + put.body());
            assertTrue(put.body().contains("NoSuchUpload"), bad);
            assertEquals(404, send("DELETE", "/hardbkt/o?uploadId=" + bad, null).statusCode(), bad);
            assertEquals(404, send("GET", "/hardbkt/o?uploadId=" + bad, null).statusCode(), bad);
            assertEquals(404, send("POST", "/hardbkt/o?uploadId=" + bad, "<CompleteMultipartUpload/>").statusCode(), bad);
        }
    }

    @Test
    void validMultipartFlowStillWorks() throws Exception {
        mkBucket("mpbkt");
        var init = send("POST", "/mpbkt/obj?uploads", null);
        assertEquals(200, init.statusCode());
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(init.body());
        assertTrue(m.find());
        String id = m.group(1);
        assertEquals(200, send("PUT", "/mpbkt/obj?partNumber=1&uploadId=" + id, "hello").statusCode());
        assertEquals(204, send("DELETE", "/mpbkt/obj?uploadId=" + id, null).statusCode());
    }

    @Test
    void xmlWithDoctypeIsRejectedAndExternalEntitiesAreNotResolved() throws Exception {
        mkBucket("xxebkt");
        Path secret = Files.createTempFile(tempRoot, "xxe-secret", ".txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<Delete><Object><Key>&x;</Key></Object></Delete>";

        var del = send("POST", "/xxebkt?delete", xxe);
        assertTrue(del.statusCode() >= 400, "status " + del.statusCode());
        assertFalse(del.body().contains("TOP-SECRET-CONTENT"));

        var init = send("POST", "/xxebkt/obj?uploads", null);
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(init.body());
        assertTrue(m.find());
        String complete = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>&x;</ETag></Part></CompleteMultipartUpload>";
        var done = send("POST", "/xxebkt/obj?uploadId=" + m.group(1), complete);
        assertTrue(done.statusCode() >= 400, "status " + done.statusCode());
        assertFalse(done.body().contains("TOP-SECRET-CONTENT"));

        // ACL body with a DOCTYPE is ignored (does not change the ACL / does not leak)
        var acl = send("PUT", "/xxebkt?acl", "<!DOCTYPE d [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><AccessControlPolicy/>");
        assertFalse(acl.body().contains("TOP-SECRET-CONTENT"));
    }
}
