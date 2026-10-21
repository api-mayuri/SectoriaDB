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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property-style fuzzing (seeded, about 10 s in total) of the request parsers behind the HTTP API, with auth disabled so the
 * mutated input reaches the parser: DeleteObjects / CompleteMultipartUpload / ACL XML, Range, x-amz-copy-source and
 * x-amz-copy-source-range. Every answer must be an S3 error document (4xx) or a success, never a 5xx, a hang or a dropped
 * connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpParserFuzzTest {

    private static final long BUDGET_MS = 1_200;

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
    private final Pattern errorCode = Pattern.compile("<Error>.*<Code>[A-Za-z]+</Code>", Pattern.DOTALL);

    private static final String DELETE_XML = "<Delete xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Quiet>true</Quiet>"
            + "<Object><Key>a</Key></Object><Object><Key>dir/b c</Key><VersionId>null</VersionId></Object></Delete>";
    private static final String COMPLETE_XML = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"d41d8cd98f00b204e9800998ecf8427e\"</ETag>"
            + "<ChecksumCRC32>AAAAAA==</ChecksumCRC32></Part><Part><PartNumber>2</PartNumber><ETag>\"x\"</ETag></Part></CompleteMultipartUpload>";
    private static final String ACL_XML = "<AccessControlPolicy><Owner><ID>o</ID></Owner><AccessControlList><Grant><Grantee xmlns:xsi="
            + "\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"Group\"><URI>http://acs.amazonaws.com/groups/global/AllUsers</URI></Grantee>"
            + "<Permission>READ</Permission></Grant></AccessControlList></AccessControlPolicy>";

    private HttpResponse<String> send(String method, String path, byte[] body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        return http.send(b.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void prepare() throws Exception {
        send("PUT", "/fzbucket", null);
        assertEquals(200, send("PUT", "/fzbucket/obj", "0123456789".getBytes(StandardCharsets.US_ASCII)).statusCode());
    }

    private String initiate() throws Exception {
        Matcher m = Pattern.compile("<UploadId>([^<]+)</UploadId>").matcher(send("POST", "/fzbucket/mp?uploads", null).body());
        assertTrue(m.find());
        return m.group(1);
    }

    /** A success, or a 4xx that carries an S3 error document. */
    private void expectS3Answer(String what, HttpResponse<String> r) {
        int s = r.statusCode();
        if (s >= 500) fail(what + " -> " + s + " " + abbreviate(r.body()));
        if (s >= 400 && !errorCode.matcher(r.body()).find()) fail(what + " -> " + s + " without an S3 error document: " + abbreviate(r.body()));
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String show(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        return (s.length() > 200 ? s.substring(0, 200) + "..." : s).replaceAll("\\p{Cntrl}", "?");
    }

    /** Runs {@code body} with the next random generator for {@code BUDGET_MS}; a client-side header rejection (invalid characters) is skipped. */
    private int loop(long seed, Consumer<Random> body) {
        Random rnd = new Random(seed);
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        while (System.nanoTime() < end) {
            body.accept(rnd);
            runs++;
        }
        assertTrue(runs > 100, "too few iterations: " + runs);
        return runs;
    }

    private interface Call { void run() throws Exception; }

    private static void unchecked(Call c) {
        try {
            c.run();
        } catch (IllegalArgumentException headerNotSendable) {
            // the JDK client refuses control characters in header values: nothing reached the server
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deleteObjectsXml() throws Exception {
        prepare();
        loop(FuzzSupport.SEED + 10, rnd -> unchecked(() -> {
            byte[] body = rnd.nextInt(8) == 0 ? FuzzSupport.tokens(rnd, 12).getBytes(StandardCharsets.UTF_8) : FuzzSupport.mutate(DELETE_XML.getBytes(StandardCharsets.UTF_8), rnd);
            expectS3Answer("DeleteObjects " + show(body), send("POST", "/fzbucket?delete", body));
        }));
    }

    @Test
    void completeMultipartXml() throws Exception {
        prepare();
        loop(FuzzSupport.SEED + 11, rnd -> unchecked(() -> {
            byte[] body = rnd.nextInt(8) == 0 ? FuzzSupport.tokens(rnd, 12).getBytes(StandardCharsets.UTF_8) : FuzzSupport.mutate(COMPLETE_XML.getBytes(StandardCharsets.UTF_8), rnd);
            expectS3Answer("CompleteMultipartUpload " + show(body), send("POST", "/fzbucket/mp?uploadId=" + initiate(), body));
        }));
    }

    @Test
    void aclXmlAndAclHeaders() throws Exception {
        prepare();
        loop(FuzzSupport.SEED + 12, rnd -> unchecked(() -> {
            byte[] body = rnd.nextInt(8) == 0 ? FuzzSupport.tokens(rnd, 12).getBytes(StandardCharsets.UTF_8) : FuzzSupport.mutate(ACL_XML.getBytes(StandardCharsets.UTF_8), rnd);
            expectS3Answer("PutBucketAcl " + show(body), send("PUT", "/fzbucket?acl", body));
            String hdr = FuzzSupport.mutate("uri=\"http://acs.amazonaws.com/groups/global/AllUsers\"", rnd);
            expectS3Answer("x-amz-grant-read: " + hdr, send("PUT", "/fzbucket?acl", null, "x-amz-grant-read", hdr));
            expectS3Answer("x-amz-acl: " + hdr, send("PUT", "/fzbucket/obj?acl", null, "x-amz-acl", FuzzSupport.mutate("public-read", rnd)));
        }));
        // the bucket must still answer
        assertEquals(200, send("GET", "/fzbucket?acl", null).statusCode());
    }

    @Test
    void rangeHeaders() throws Exception {
        prepare();
        loop(FuzzSupport.SEED + 13, rnd -> unchecked(() -> {
            String range = rnd.nextBoolean() ? FuzzSupport.mutate("bytes=" + rnd.nextInt(12) + "-" + rnd.nextInt(12), rnd) : "bytes=" + FuzzSupport.tokens(rnd, 5);
            var r = send("GET", "/fzbucket/obj", null, "Range", range);
            if (r.statusCode() != 200 && r.statusCode() != 206) expectS3Answer("Range: " + range, r);
        }));
    }

    @Test
    void aRangeHeaderWithoutAnyRangeIsIgnored() throws Exception {
        prepare();
        assertEquals(200, send("GET", "/fzbucket/obj", null, "Range", "bytes=").statusCode());
        assertEquals(200, send("GET", "/fzbucket/obj", null, "Range", "bytes=-").statusCode());
    }

    @Test
    void copySourceAndCopySourceRange() throws Exception {
        prepare();
        loop(FuzzSupport.SEED + 14, rnd -> unchecked(() -> {
            String src = rnd.nextInt(3) == 0 ? FuzzSupport.tokens(rnd, 8) : FuzzSupport.mutate("/fzbucket/obj", rnd);
            expectS3Answer("CopyObject source " + src, send("PUT", "/fzbucket/copy", null, "x-amz-copy-source", src));
            String range = FuzzSupport.mutate("bytes=0-4", rnd);
            expectS3Answer("UploadPartCopy " + src + " " + range,
                    send("PUT", "/fzbucket/mp?partNumber=1&uploadId=" + initiate(), null, "x-amz-copy-source", rnd.nextBoolean() ? "/fzbucket/obj" : src,
                            "x-amz-copy-source-range", range));
        }));
    }

    @Test
    void hostileXmlShapes() throws Exception {
        prepare();
        String deep = "<a>".repeat(60_000) + "</a>".repeat(60_000);
        String wide = "<Delete>" + "<Object><Key>k</Key></Object>".repeat(50_000) + "</Delete>";
        String entities = "<!DOCTYPE d [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]><Delete><Object><Key>&b;</Key></Object></Delete>";
        String external = "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Delete><Object><Key>&x;</Key></Object></Delete>";
        String manyAttrs = "<Delete " + "a=\"1\" ".repeat(1) + String.join(" ", java.util.stream.IntStream.range(0, 20_000).mapToObj(i -> "x" + i + "=\"1\"").toList()) + "/>";
        for (String xml : new String[]{deep, wide, entities, external, manyAttrs, "", "<", "\0\0\0", "﻿<Delete/>"}) {
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            String what = show(body);
            expectS3Answer("DeleteObjects " + what, send("POST", "/fzbucket?delete", body));
            expectS3Answer("CompleteMultipartUpload " + what, send("POST", "/fzbucket/mp?uploadId=" + initiate(), body));
            expectS3Answer("PutBucketAcl " + what, send("PUT", "/fzbucket?acl", body));
        }
        assertEquals(200, send("GET", "/fzbucket/obj", null).statusCode());
    }
}
