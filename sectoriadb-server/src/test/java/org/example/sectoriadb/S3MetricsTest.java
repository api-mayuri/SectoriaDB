package org.example.sectoriadb;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.sectoriadb.service.CredentialService;
import org.example.sectoriadb.service.PoolService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end: real S3 traffic (SigV4 enabled, random ports for the S3 and the management server), then the
 * Prometheus exposition is scraped from the management port and checked: series, labels, counts, bucket layout,
 * cardinality rules, and that /actuator is neither reachable nor authenticated through the S3 port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability   // @SpringBootTest turns metrics export off otherwise
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MetricsTest {

    static final String AK = "METRICSACCESSKEY0001";
    static final String SK = "metrics-test-secret-key";
    static final String BUCKET = "obs-bucket-zq";
    static final String KEY = "very-secret-object-key-xyz.bin";

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
        r.add("sectoriadb.s3.access-key",       () -> AK);
        r.add("sectoriadb.s3.secret-key",       () -> SK);
        r.add("sectoriadb.observability.gauge-cache-ms", () -> "0");
        r.add("sectoriadb.observability.slow-request-ms", () -> "1");   // every request is "slow": exercises the WARN log
        r.add("spring.shell.interactive.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @LocalManagementPort int mgmtPort;
    @Autowired PoolService poolService;
    @Autowired CredentialService credentials;

    final HttpClient http = HttpClient.newHttpClient();
    SigV4TestClient client;

    static ListAppender<ILoggingEvent> slowLog;

    /** Attached inside the test: Spring Boot re-initializes logback when the context starts, dropping earlier appenders. */
    static void captureSlowLog() {
        Logger l = (Logger) LoggerFactory.getLogger("org.example.sectoriadb.observability.SlowRequests");
        slowLog = new ListAppender<>();
        slowLog.start();
        l.addAppender(slowLog);
    }

    @AfterAll
    static void releaseSlowLog() {
        if (slowLog != null) {
            ((Logger) LoggerFactory.getLogger("org.example.sectoriadb.observability.SlowRequests")).detachAppender(slowLog);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SigV4TestClient client() throws Exception {
        if (client == null) {
            client = new SigV4TestClient(port, AK, SK);
            if (!poolService.existsByName(BUCKET)) poolService.createBucket(BUCKET);
        }
        return client;
    }

    private static byte[] body(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private String scrape() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"), "Prometheus text format");
        return r.body();
    }

    /** Value of the sample whose name is {@code name} and whose label set contains all of {@code labels} ("k=v"); NaN if none. */
    static double sample(String text, String name, String... labels) {
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*})? ([^ ]+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(text);
        outer:
        while (m.find()) {
            String l = m.group(1) == null ? "" : m.group(1);
            for (String want : labels) {
                String[] kv = want.split("=", 2);
                if (!l.contains(kv[0] + "=\"" + kv[1] + "\"")) continue outer;
            }
            return Double.parseDouble(m.group(2));
        }
        return Double.NaN;
    }

    static double sampleOr0(String text, String name, String... labels) {
        double v = sample(text, name, labels);
        return Double.isNaN(v) ? 0 : v;
    }

    private HttpResponse<String> plain(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void redMetricsForEveryOperationAndCardinalityRules() throws Exception {
        SigV4TestClient c = client();
        String before = scrape();
        String empty = SigV4TestClient.sha256(new byte[0]);

        byte[] small = body(300, 1);
        byte[] big = body(10_000, 2);          // > chunk size: stored in the cuckoo blob
        var put = c.put("/" + BUCKET + "/" + KEY, small, SigV4TestClient.sha256(small));
        assertEquals(200, put.statusCode(), put.body());
        var putBig = c.put("/" + BUCKET + "/big.bin", big, SigV4TestClient.sha256(big));
        assertEquals(200, putBig.statusCode(), putBig.body());

        var get = c.request("GET", "/" + BUCKET + "/big.bin", null, empty, null);
        assertEquals(200, get.statusCode());
        assertEquals(200, c.request("HEAD", "/" + BUCKET + "/" + KEY, null, empty, null).statusCode());
        assertEquals(200, c.request("GET", "/" + BUCKET, "list-type=2&prefix=very", empty, null).statusCode());
        assertEquals(200, c.request("GET", "/" + BUCKET, null, empty, null).statusCode());
        assertEquals(200, c.request("GET", "/", null, empty, null).statusCode());
        assertEquals(404, c.request("GET", "/" + BUCKET + "/missing-key", null, empty, null).statusCode());
        assertEquals(204, c.request("DELETE", "/" + BUCKET + "/" + KEY, null, empty, null).statusCode());

        String after = scrape();

        for (String[] row : new String[][]{
                {"PutObject", "2xx", "2"}, {"GetObject", "2xx", "1"}, {"GetObject", "4xx", "1"}, {"HeadObject", "2xx", "1"},
                {"ListObjectsV2", "2xx", "1"}, {"ListObjects", "2xx", "1"}, {"ListBuckets", "2xx", "1"},
                {"DeleteObject", "2xx", "1"}}) {
            double d = sampleOr0(after, "sectoriadb_s3_requests_seconds_count", "operation=" + row[0], "status_class=" + row[1])
                    - sampleOr0(before, "sectoriadb_s3_requests_seconds_count", "operation=" + row[0], "status_class=" + row[1]);
            assertEquals(Double.parseDouble(row[2]), d, row[0] + " " + row[1]);
        }

        // histogram layout: 15 explicit buckets + +Inf, cumulative, +Inf == count
        List<String> les = new ArrayList<>();
        Matcher m = Pattern.compile("^sectoriadb_s3_requests_seconds_bucket\\{[^}]*operation=\"PutObject\"[^}]*status_class=\"2xx\"[^}]*le=\"([^\"]+)\"} (\\S+)$",
                Pattern.MULTILINE).matcher(after);
        double prev = -1;
        while (m.find()) {
            les.add(m.group(1));
            double v = Double.parseDouble(m.group(2));
            assertTrue(v >= prev, "buckets are cumulative");
            prev = v;
        }
        assertEquals(16, les.size(), "15 explicit buckets + +Inf: " + les);
        assertEquals("+Inf", les.get(les.size() - 1));
        assertEquals(prev, sample(after, "sectoriadb_s3_requests_seconds_count", "operation=PutObject", "status_class=2xx"));
        assertTrue(after.contains("le=\"0.001\"") && after.contains("le=\"60.0\""));

        // error code counter
        assertEquals(1, sampleOr0(after, "sectoriadb_s3_errors_total", "operation=GetObject", "code=NoSuchKey")
                - sampleOr0(before, "sectoriadb_s3_errors_total", "operation=GetObject", "code=NoSuchKey"));

        // bytes counters and TTFB
        assertTrue(sampleOr0(after, "sectoriadb_s3_request_bytes_total", "operation=PutObject")
                - sampleOr0(before, "sectoriadb_s3_request_bytes_total", "operation=PutObject") >= 10_300);
        assertTrue(sampleOr0(after, "sectoriadb_s3_response_bytes_total", "operation=GetObject")
                - sampleOr0(before, "sectoriadb_s3_response_bytes_total", "operation=GetObject") >= 10_000);
        assertEquals(1, sampleOr0(after, "sectoriadb_s3_get_ttfb_seconds_count") - sampleOr0(before, "sectoriadb_s3_get_ttfb_seconds_count"));
        assertEquals(0, sampleOr0(after, "sectoriadb_s3_requests_inflight", "operation=GetObject"));

        // cardinality: no bucket / key / access key / secret anywhere in the exposition
        for (String secret : new String[]{BUCKET, KEY, "big.bin", "missing-key", AK, SK, "obs-bucket"}) {
            assertFalse(after.contains(secret), "metrics must not contain " + secret);
        }
        // every label NAME used by sectoriadb_* series is on the allow-list
        Matcher labelNames = Pattern.compile("^sectoriadb_[a-z0-9_]+\\{([^}]*)}", Pattern.MULTILINE).matcher(after);
        java.util.Set<String> allowed = java.util.Set.of("application", "operation", "status_class", "code", "reason", "target",
                "kind", "result", "dir", "le");
        while (labelNames.find()) {
            for (String pair : labelNames.group(1).split(",(?=[a-z_]+=\")")) {
                assertTrue(allowed.contains(pair.substring(0, pair.indexOf('='))), "unexpected label in " + labelNames.group());
            }
        }
        assertTrue(after.contains("application=\"SectoriaDB\""), "common tag from configuration");
    }

    @Test
    @Order(2)
    void authFailuresAreCountedByReason() throws Exception {
        SigV4TestClient c = client();
        String before = scrape();
        String empty = SigV4TestClient.sha256(new byte[0]);
        String path = "/" + BUCKET + "/" + KEY;

        // signature mismatch
        var wrong = new SigV4TestClient(port, AK, "not-the-secret").request("GET", path, null, empty, null);
        assertEquals(403, wrong.statusCode());
        assertTrue(wrong.body().contains("SignatureDoesNotMatch"));
        // unknown key
        assertEquals(403, new SigV4TestClient(port, "NOSUCHKEY00000000000", SK).request("GET", path, null, empty, null).statusCode());
        // clock skew
        assertEquals(403, c.request("GET", path, null, empty, null, Instant.now().minusSeconds(3600)).statusCode());
        // anonymous
        assertEquals(403, plain("GET", path).statusCode());
        // expired presigned URL
        assertEquals(403, c.get(c.presign(path, Instant.now().minusSeconds(7200), 60)).statusCode());
        // body does not match the signed sha256
        byte[] data = body(100, 5);
        var badHash = c.put("/" + BUCKET + "/bad-hash", data, SigV4TestClient.sha256(body(100, 6)));
        assertEquals(400, badHash.statusCode(), badHash.body());
        // tampered aws-chunked body
        var chunked = c.putChunked("/" + BUCKET + "/chunked", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD",
                new byte[][]{body(50, 7), body(50, 8)}, 1, false, null, false);
        assertEquals(403, chunked.statusCode(), chunked.body());
        // malformed Authorization header
        var malformed = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=broken, Signature=00").build(), BodyHandlers.ofString());
        assertEquals(400, malformed.statusCode());
        // disabled key
        credentials.register("DISABLEDKEY0000000001", "disabled-secret-key-0001", "t");
        credentials.setEnabled("DISABLEDKEY0000000001", false);
        assertEquals(403, new SigV4TestClient(port, "DISABLEDKEY0000000001", "disabled-secret-key-0001")
                .request("GET", path, null, empty, null).statusCode());

        String after = scrape();
        for (String reason : new String[]{"signature_mismatch", "unknown_key", "clock_skew", "anonymous_denied",
                "expired_presign", "payload_hash_mismatch", "chunk_signature_mismatch", "malformed_header", "disabled_key"}) {
            double d = sampleOr0(after, "sectoriadb_s3_auth_failures_total", "reason=" + reason)
                    - sampleOr0(before, "sectoriadb_s3_auth_failures_total", "reason=" + reason);
            assertTrue(d >= 1, reason + " should have been counted, delta=" + d);
        }
        // Series for every reason exist from the start (also the ones that cannot be provoked here)
        for (String reason : new String[]{"open_setup_denied", "unsigned_payload_denied"}) {
            assertFalse(Double.isNaN(sample(after, "sectoriadb_s3_auth_failures_total", "reason=" + reason)), reason);
        }
        // They are failed requests too: 4xx series and error codes
        assertTrue(sampleOr0(after, "sectoriadb_s3_errors_total", "code=SignatureDoesNotMatch") >= 1);
        assertTrue(sampleOr0(after, "sectoriadb_s3_errors_total", "code=RequestTimeTooSkewed") >= 1);
        assertTrue(sampleOr0(after, "sectoriadb_s3_errors_total", "code=AccessDenied") >= 1);
        assertTrue(sampleOr0(after, "sectoriadb_s3_errors_total", "code=InvalidAccessKeyId") >= 2);
        assertTrue(sampleOr0(after, "sectoriadb_s3_errors_total", "code=XAmzContentSHA256Mismatch") >= 1);
        assertTrue(sampleOr0(after, "sectoriadb_s3_requests_seconds_count", "status_class=4xx") >= 8);
        assertFalse(after.contains("DISABLEDKEY"), "access keys are never labels");
    }

    @Test
    @Order(3)
    void storageEngineAndStateMetricsAreExposed() throws Exception {
        System.gc();   // a GC pause histogram only exists after the first collection
        String text = scrape();
        for (int i = 0; i < 50 && !text.contains("jvm_gc_pause_seconds_bucket"); i++) {
            Thread.sleep(100);
            text = scrape();
        }
        // IO + fsync histograms of the three targets that were exercised by the PUTs above
        for (String target : new String[]{"cuckoo", "small", "metastore"}) {
            assertTrue(sampleOr0(text, "sectoriadb_storage_fsync_seconds_count", "target=" + target) >= 1, "fsync " + target);
            assertTrue(sampleOr0(text, "sectoriadb_storage_disk_write_seconds_count", "target=" + target) >= 1, "write " + target);
            assertTrue(sampleOr0(text, "sectoriadb_storage_disk_write_bytes_total", "target=" + target) > 0, "write bytes " + target);
        }
        assertTrue(sampleOr0(text, "sectoriadb_storage_disk_read_seconds_count", "target=cuckoo") >= 1, "the GET read chunks");
        assertTrue(sampleOr0(text, "sectoriadb_cuckoo_eviction_path_length_count") >= 3, "one insert per chunk of big.bin");
        assertTrue(text.contains("sectoriadb_cuckoo_eviction_path_length_bucket{application=\"SectoriaDB\",le=\"0.5\"}"));
        assertTrue(sampleOr0(text, "sectoriadb_metastore_commit_seconds_count") >= 3);
        assertTrue(sampleOr0(text, "sectoriadb_metastore_writer_lock_wait_seconds_count") >= 3);
        assertTrue(sampleOr0(text, "sectoriadb_small_append_lock_wait_seconds_count") >= 1);
        assertFalse(Double.isNaN(sample(text, "sectoriadb_cuckoo_table_full_total")));
        assertFalse(Double.isNaN(sample(text, "sectoriadb_cuckoo_dedup_hits_total")));
        assertFalse(Double.isNaN(sample(text, "sectoriadb_cuckoo_rekeys_total")));
        for (String kind : new String[]{"chunk", "small_record", "whole_object", "metastore_page", "slot_meta"}) {
            assertEquals(0, sample(text, "sectoriadb_integrity_crc_failures_total", "kind=" + kind), kind);
        }
        // state gauges (cache disabled in this test): 1 object left (big.bin; the small one was deleted)
        assertEquals(1, sample(text, "sectoriadb_objects_stored"));
        assertEquals(10_000, sample(text, "sectoriadb_objects_stored_bytes"));
        assertEquals(3, sample(text, "sectoriadb_cuckoo_slots_active"), "10 000 bytes = 3 chunks of 4096");
        assertEquals(2 * 256 * 4, sample(text, "sectoriadb_cuckoo_slots_capacity"));
        assertEquals(0, sample(text, "sectoriadb_cuckoo_slots_quarantined"));
        assertTrue(sample(text, "sectoriadb_small_dead_bytes") > 0, "the deleted small object is dead space");
        assertTrue(sample(text, "sectoriadb_metastore_file_bytes") > 0);
        assertTrue(sample(text, "sectoriadb_metastore_pages") > 2);
        assertTrue(sample(text, "sectoriadb_metastore_last_txid") >= 3);
        assertFalse(Double.isNaN(sample(text, "sectoriadb_metastore_free_pages")));
        assertFalse(Double.isNaN(sample(text, "sectoriadb_metastore_live_readers")));
        assertFalse(Double.isNaN(sample(text, "sectoriadb_gc_queue_length")));
        assertTrue(sample(text, "sectoriadb_disk_free_bytes", "dir=data") > 0);
        assertTrue(sample(text, "sectoriadb_disk_free_bytes", "dir=meta") > 0);
        // saturation: JVM, process, Tomcat
        for (String s : new String[]{"jvm_memory_used_bytes", "jvm_threads_live_threads", "jvm_gc_pause_seconds_bucket",
                "process_files_open_files", "process_cpu_usage", "tomcat_threads_busy_threads", "tomcat_threads_config_max_threads"}) {
            assertTrue(text.contains("\n" + s) || text.startsWith(s), "missing " + s);
        }
    }

    @Test
    @Order(4)
    void requestIdHeadersMdcAndSlowLog() throws Exception {
        SigV4TestClient c = client();
        String empty = SigV4TestClient.sha256(new byte[0]);
        captureSlowLog();

        var ok = c.request("HEAD", "/" + BUCKET + "/big.bin", null, empty, null);
        assertEquals(1, ok.headers().allValues("x-amz-request-id").size(), "exactly one request id header");
        String id = ok.headers().firstValue("x-amz-request-id").orElseThrow();
        assertTrue(id.matches("[0-9A-F]{16}"), id);
        assertTrue(ok.headers().firstValue("x-amz-id-2").isPresent());

        var err = c.request("GET", "/" + BUCKET + "/nope-" + KEY, null, empty, null);
        assertEquals(404, err.statusCode());
        String errId = err.headers().firstValue("x-amz-request-id").orElseThrow();
        assertEquals(1, err.headers().allValues("x-amz-request-id").size());
        assertTrue(err.body().contains("<RequestId>" + errId + "</RequestId>"), "error body carries the same request id");

        // auth failures (SigV4 filter answers) have one too
        var denied = plain("GET", "/" + BUCKET + "/x");
        assertEquals(403, denied.statusCode());
        String deniedId = denied.headers().firstValue("x-amz-request-id").orElseThrow();
        assertTrue(denied.body().contains(deniedId));

        // slow-request log (threshold 1 ms): WARN with id/operation/status in the MDC and the message, no key
        List<ILoggingEvent> events = new ArrayList<>(slowLog.list);
        ILoggingEvent headEvent = events.stream().filter(e -> id.equals(e.getMDCPropertyMap().get("requestId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no slow log for " + id + " in " + events));
        assertEquals(ch.qos.logback.classic.Level.WARN, headEvent.getLevel());
        assertEquals("HeadObject", headEvent.getMDCPropertyMap().get("operation"));
        String msg = headEvent.getFormattedMessage();
        assertTrue(msg.contains("requestId=" + id) && msg.contains("operation=HeadObject") && msg.contains("status=200")
                && msg.contains("durationMs=") && msg.contains("responseBytes="), msg);
        for (ILoggingEvent e : events) {
            assertFalse(e.getFormattedMessage().contains(KEY), "keys are not logged at WARN");
            assertFalse(e.getFormattedMessage().contains(BUCKET), "buckets are not logged at WARN");
        }
        assertTrue(scrape().contains("sectoriadb_s3_requests_slow_total"));
    }

    @Test
    @Order(5)
    void actuatorLivesOnlyOnTheManagementPort() throws Exception {
        // management port: no SigV4, only health and prometheus
        var health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/health")).build(), BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"UP\""));
        for (String hidden : new String[]{"/actuator/env", "/actuator/metrics", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"}) {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + hidden)).build(), BodyHandlers.ofString());
            assertEquals(404, r.statusCode(), hidden);
        }
        // the management port does not serve the S3 API
        var s3OnMgmt = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/" + BUCKET + "/big.bin")).build(), BodyHandlers.ofString());
        assertEquals(404, s3OnMgmt.statusCode());

        // S3 port: SigV4 is enforced and /actuator is not served, not even with valid credentials
        for (String p : new String[]{"/actuator/prometheus", "/actuator/health", "/actuator"}) {
            var anon = plain("GET", p);
            assertEquals(403, anon.statusCode(), p);
            assertFalse(anon.body().contains("# TYPE"), p);
            var signed = client().request("GET", p, null, SigV4TestClient.sha256(new byte[0]), null);
            assertTrue(signed.statusCode() == 404 || signed.statusCode() == 403, p + " -> " + signed.statusCode());
            assertFalse(signed.body().contains("# TYPE"), p);
            assertFalse(signed.body().contains("\"status\":\"UP\""), p);
        }
        // and the management scrape works without credentials although keys exist
        assertTrue(scrape().contains("sectoriadb_s3_requests_seconds_count"));
    }
}
