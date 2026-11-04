package org.example.sectoriadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bucket versioning over the S3 API: an accidentally deleted or overwritten object comes back by its version id. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3VersioningTest {

    static final String AK = "VERSIONINGKEY00001";
    static final String SK = "versioningsecret0123456789";
    static final String EMPTY = SigV4TestClient.sha256(new byte[0]);

    @TempDir
    static Path tempRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sectoriadb.meta-dir", () -> tempRoot.resolve("meta").toString());
        r.add("sectoriadb.data-dir", () -> tempRoot.resolve("data").toString());
        r.add("sectoriadb.auto-resize.enabled", () -> "false");
        r.add("sectoriadb.default-num-buckets", () -> "256");
        r.add("sectoriadb.s3.credentials", () -> AK + ":" + SK + "|versioning");
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;

    SigV4TestClient c() {
        return new SigV4TestClient(port, AK, SK);
    }

    HttpResponse<String> send(String method, String path, String query, String body) throws Exception {
        byte[] b = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return c().request(method, path, query, b == null ? EMPTY : SigV4TestClient.sha256(b), b);
    }

    static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    @Test
    void aDeletedAndAnOverwrittenObjectComeBackByVersionId() throws Exception {
        assertEquals(200, send("PUT", "/vers-b", null, null).statusCode());
        assertEquals(200, send("PUT", "/vers-b", "versioning",
                "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>").statusCode());
        assertTrue(send("GET", "/vers-b", "versioning", null).body().contains("<Status>Enabled</Status>"));

        var p1 = send("PUT", "/vers-b/doc.txt", null, "first draft");
        String v1 = header(p1, "x-amz-version-id");
        var p2 = send("PUT", "/vers-b/doc.txt", null, "oops, overwritten");
        String v2 = header(p2, "x-amz-version-id");
        assertTrue(v1 != null && v2 != null && !v1.equals(v2), v1 + " " + v2);
        assertEquals("first draft", send("GET", "/vers-b/doc.txt", "versionId=" + v1, null).body());

        var del = send("DELETE", "/vers-b/doc.txt", null, null);
        assertEquals(204, del.statusCode());
        assertEquals("true", header(del, "x-amz-delete-marker"));
        String marker = header(del, "x-amz-version-id");
        var gone = send("GET", "/vers-b/doc.txt", null, null);
        assertEquals(404, gone.statusCode());
        assertEquals("true", header(gone, "x-amz-delete-marker"));
        assertEquals(405, send("GET", "/vers-b/doc.txt", "versionId=" + marker, null).statusCode(), "a marker is not readable");

        // the listing shows both versions and the marker
        String list = send("GET", "/vers-b", "versions", null).body();
        assertTrue(list.contains("<DeleteMarker><Key>doc.txt</Key><VersionId>" + marker + "</VersionId><IsLatest>true</IsLatest>"), list);
        assertEquals(2, count(list, "<Version>"));

        // undo the delete: remove the marker
        assertEquals(204, send("DELETE", "/vers-b/doc.txt", "versionId=" + marker, null).statusCode());
        assertEquals("oops, overwritten", send("GET", "/vers-b/doc.txt", null, null).body());
        // undo the overwrite: remove the newer version for good
        assertEquals(204, send("DELETE", "/vers-b/doc.txt", "versionId=" + v2, null).statusCode());
        var back = send("GET", "/vers-b/doc.txt", null, null);
        assertEquals("first draft", back.body());
        assertEquals(v1, header(back, "x-amz-version-id"));

        // a bucket with versions is not empty; after the last version it can go
        assertEquals(204, send("DELETE", "/vers-b/doc.txt", null, null).statusCode());   // a marker again
        assertEquals(409, send("DELETE", "/vers-b", null, null).statusCode(), "versions and a marker are left");
        String all = send("GET", "/vers-b", "versions", null).body();
        Matcher m = Pattern.compile("<VersionId>([^<]+)</VersionId>").matcher(all);
        while (m.find()) send("DELETE", "/vers-b/doc.txt", "versionId=" + m.group(1), null);
        assertEquals(204, send("DELETE", "/vers-b", null, null).statusCode());
    }

    @Test
    void lifecycleConfigurationRoundTripsAndRefusesWhatItCannotDo() throws Exception {
        assertEquals(200, send("PUT", "/life-b", null, null).statusCode());
        String rule = "<LifecycleConfiguration><Rule><ID>old</ID><Filter><Prefix>logs/</Prefix></Filter><Status>Enabled</Status>"
                + "<NoncurrentVersionExpiration><NoncurrentDays>30</NoncurrentDays></NoncurrentVersionExpiration></Rule></LifecycleConfiguration>";
        assertEquals(200, send("PUT", "/life-b", "lifecycle", rule).statusCode());
        String got = send("GET", "/life-b", "lifecycle", null).body();
        assertTrue(got.contains("<NoncurrentDays>30</NoncurrentDays>") && got.contains("<Prefix>logs/</Prefix>"), got);
        String transition = "<LifecycleConfiguration><Rule><ID>t</ID><Filter><Prefix></Prefix></Filter><Status>Enabled</Status>"
                + "<Transition><Days>30</Days><StorageClass>GLACIER</StorageClass></Transition></Rule></LifecycleConfiguration>";
        assertEquals(501, send("PUT", "/life-b", "lifecycle", transition).statusCode(), "not stored and silently ignored");
        assertEquals(204, send("DELETE", "/life-b", "lifecycle", null).statusCode());
        assertEquals(404, send("GET", "/life-b", "lifecycle", null).statusCode());
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
