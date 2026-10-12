package org.example.sectoriadb;

import org.example.sectoriadb.catalog.ManifestRepository.CommitPrecondition;
import org.example.sectoriadb.s3.auth.AwsChunkedInputStream;
import org.example.sectoriadb.s3.error.PayloadVerificationException;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.LimitedInputStream;
import org.example.sectoriadb.s3.http.MinRateInputStream;
import org.example.sectoriadb.s3.http.UrlCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the request-body guards (no Spring context). */
class StreamGuardsTest {

    private static InputStream chunked(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] drain(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    // ── aws-chunked ──────────────────────────────────────────────────────────

    @Test
    void unsignedChunksAreDecoded() throws Exception {
        var in = new AwsChunkedInputStream(chunked("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"), null, null);
        assertEquals("hello world", new String(drain(in), StandardCharsets.UTF_8));
    }

    @Test
    void aHugeDeclaredChunkWithNoDataFailsWithoutAllocatingIt() {
        // 7FFFFF = 8 MiB - 1, accepted size-wise, but only 3 bytes follow: the buffer must grow with the data, not be allocated up front
        var in = new AwsChunkedInputStream(chunked("7FFFFF\r\nabc"), null, null);
        var e = assertThrows(PayloadVerificationException.class, () -> drain(in));
        assertTrue(e.getMessage().contains("inside a chunk"), e.getMessage());
    }

    @Test
    void aChunkOverTheCapIsRejected() {
        var in = new AwsChunkedInputStream(chunked("1000000\r\n"), null, null);   // 16 MiB
        assertThrows(PayloadVerificationException.class, () -> drain(in));
    }

    @Test
    void anOverlongChunkHeaderLineIsRejected() {
        String header = "5;chunk-signature=" + "a".repeat(2000) + "\r\nhello\r\n0\r\n\r\n";
        assertThrows(PayloadVerificationException.class, () -> drain(new AwsChunkedInputStream(chunked(header), null, null)));
    }

    @Test
    void tooManyTrailersAreRejected() {
        StringBuilder sb = new StringBuilder("1\r\na\r\n0\r\n");
        for (int i = 0; i < 40; i++) sb.append("x-amz-meta-").append(i).append(":v\r\n");
        sb.append("\r\n");
        assertThrows(PayloadVerificationException.class, () -> drain(new AwsChunkedInputStream(chunked(sb.toString()), null, null)));
    }

    @Test
    void theDecodedLengthIsEnforcedBothWays() throws Exception {
        String body = "5\r\nhello\r\n0\r\n\r\n";
        assertEquals("hello", new String(drain(new AwsChunkedInputStream(chunked(body), null, null, 5)), StandardCharsets.UTF_8));
        assertThrows(PayloadVerificationException.class, () -> drain(new AwsChunkedInputStream(chunked(body), null, null, 4)));
        assertThrows(PayloadVerificationException.class, () -> drain(new AwsChunkedInputStream(chunked(body), null, null, 9)));
    }

    // ── limits ───────────────────────────────────────────────────────────────

    @Test
    void limitedStreamStopsAtTheLimit() throws Exception {
        var ok = new LimitedInputStream(new ByteArrayInputStream(new byte[10]), 10, () -> new IllegalStateException("too big"));
        assertEquals(10, drain(ok).length);
        var over = new LimitedInputStream(new ByteArrayInputStream(new byte[11]), 10, () -> new IllegalStateException("too big"));
        assertThrows(IllegalStateException.class, () -> drain(over));
    }

    @Test
    void minRateStreamCutsATricklingClientAfterTheGracePeriod() throws Exception {
        AtomicLong now = new AtomicLong();
        InputStream slow = new InputStream() {
            @Override public int read() { now.addAndGet(1_000_000_000L); return 'x'; }   // one byte per simulated second
            @Override public int read(byte[] b, int off, int len) { b[off] = 'x'; now.addAndGet(1_000_000_000L); return 1; }
        };
        var guarded = new MinRateInputStream(slow, 5_000_000_000L, 1024, now::get);
        byte[] buf = new byte[16];
        for (int i = 0; i < 5; i++) assertEquals(1, guarded.read(buf, 0, 16));   // inside the grace period
        S3Exception e = assertThrows(S3Exception.class, () -> { while (true) guarded.read(buf, 0, 16); });
        assertEquals("RequestTimeout", e.getCode());
    }

    @Test
    void minRateStreamLetsAFastClientThrough() throws Exception {
        AtomicLong now = new AtomicLong();
        InputStream fast = new InputStream() {
            @Override public int read() { return 'x'; }
            @Override public int read(byte[] b, int off, int len) { now.addAndGet(1_000_000L); return len; }   // 1 MB per ms
        };
        var guarded = new MinRateInputStream(fast, 1_000_000_000L, 1024, now::get);
        byte[] buf = new byte[1 << 20];
        for (int i = 0; i < 100_000; i++) guarded.read(buf, 0, buf.length);
    }

    // ── query decoding ───────────────────────────────────────────────────────

    @Test
    void queryComponentsDecodeStrictly() {
        assertEquals("a b+c", UrlCodec.decodeQueryComponent("a+b%2Bc"));
        assertEquals("é", UrlCodec.decodeQueryComponent("%C3%A9"));
        for (String bad : new String[]{"%", "%4", "%zz", "a%", "%FF"}) {
            assertThrows(IllegalArgumentException.class, () -> UrlCodec.decodeQueryComponent(bad), bad);
        }
    }

    // ── conditional write semantics ──────────────────────────────────────────

    @Test
    void commitPreconditionsMatchS3() {
        var createOnly = new CommitPrecondition(true, null);
        assertTrue(createOnly.holds(null));
        assertFalse(createOnly.holds("\"a\""));
        var match = new CommitPrecondition(false, "\"abc\"");
        assertTrue(match.holds("\"abc\""));
        assertTrue(match.holds("abc"));
        assertFalse(match.holds("\"other\""));
        assertFalse(match.holds(null));
        assertTrue(new CommitPrecondition(false, "*").holds("\"x\""));
        assertTrue(CommitPrecondition.NONE.holds(null));
    }
}
