package org.example.sectoriadb;

import org.example.sectoriadb.s3.api.RequestBodies;
import org.example.sectoriadb.s3.api.UploadAdmission;
import org.example.sectoriadb.s3.error.S3Exception;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Disk admission: in-flight reservations, the free-space floor, the declared-size rules. No Spring context. */
class UploadAdmissionTest {

    private static final long GIB = 1L << 30;
    private static final Path SPOOL = Path.of("spool");
    private static final Path META = Path.of("meta");

    private static UploadAdmission admission(AtomicLong spoolFree, AtomicLong metaFree, long floor) throws Exception {
        return admission(spoolFree, metaFree, floor, Long.MAX_VALUE);
    }

    private static UploadAdmission admission(AtomicLong spoolFree, AtomicLong metaFree, long floor, long heapBudget) throws Exception {
        ToLongFunction<Path> usable = p -> p.equals(SPOOL) ? spoolFree.get() : metaFree.get();
        Constructor<UploadAdmission> c = UploadAdmission.class.getDeclaredConstructor(Path.class, List.class, long.class,
                ToLongFunction.class, long.class, long.class);
        c.setAccessible(true);
        return c.newInstance(SPOOL, List.of(META), floor, usable, 16_384L, heapBudget);
    }

    private static void finish(UploadAdmission admission, MockHttpServletRequest request) throws Exception {
        admission.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void concurrentUploadsThatFitAloneCannotOverbookTheVolume() throws Exception {
        var admission = admission(new AtomicLong(100 * GIB), new AtomicLong(100 * GIB), GIB);
        // each 5 GiB upload fits alone; 19 of them fit together (95 GiB + 1 GiB floor <= 100 GiB), the 20th does not
        var requests = new java.util.ArrayList<MockHttpServletRequest>();
        for (int i = 0; i < 19; i++) {
            var r = new MockHttpServletRequest();
            requests.add(r);
            admission.admit(r, 5 * GIB);
        }
        assertEquals(95 * GIB, admission.reservedBytes());
        S3Exception busy = assertThrows(S3Exception.class, () -> admission.admit(new MockHttpServletRequest(), 5 * GIB));
        assertEquals(503, busy.getStatus().value());
        assertEquals("SlowDown", busy.getCode());
        assertEquals(95 * GIB, admission.reservedBytes(), "a refused admission reserves nothing");

        // finishing one request (the filter releases on every exit path) frees its share
        finish(admission, requests.get(0));
        assertEquals(90 * GIB, admission.reservedBytes());
        admission.admit(new MockHttpServletRequest(), 5 * GIB);
    }

    @Test
    void anUploadThatCanNeverFitIsInsufficientStorageNotSlowDown() throws Exception {
        var admission = admission(new AtomicLong(3 * GIB), new AtomicLong(100 * GIB), GIB);
        S3Exception e = assertThrows(S3Exception.class, () -> admission.admit(new MockHttpServletRequest(), 5 * GIB));
        assertEquals(507, e.getStatus().value());
        assertEquals("InsufficientStorage", e.getCode());
        assertEquals(0, admission.reservedBytes());
    }

    @Test
    void theFloorProtectsTheMetastoreVolumeEvenForTinyWrites() throws Exception {
        var metaFree = new AtomicLong(100 * GIB);
        var admission = admission(new AtomicLong(100 * GIB), metaFree, GIB);
        admission.admit(new MockHttpServletRequest(), 1);
        admission.requireWritable();
        metaFree.set(GIB - 1);
        assertEquals(507, assertThrows(S3Exception.class, () -> admission.admit(new MockHttpServletRequest(), 1)).getStatus().value());
        assertEquals(507, assertThrows(S3Exception.class, admission::requireWritable).getStatus().value());
    }

    @Test
    void aReservationIsReleasedEvenWhenTheRequestFails() throws Exception {
        var admission = admission(new AtomicLong(100 * GIB), new AtomicLong(100 * GIB), GIB);
        var request = new MockHttpServletRequest();
        assertThrows(IllegalStateException.class, () -> admission.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    admission.admit(request, 5 * GIB);
                    throw new IllegalStateException("handler failed");
                }));
        assertEquals(0, admission.reservedBytes());
    }

    @Test
    void theStagingHeapOfUploadsInFlightIsBoundedByTheBudget() throws Exception {
        // a 5 GiB upload of 16 KiB chunks is estimated at 327681 * 256 B = about 80 MB; the budget holds two of them
        long one = 80L << 20;
        var admission = admission(new AtomicLong(1000 * GIB), new AtomicLong(1000 * GIB), GIB, 2 * one + one / 2);
        var a = new MockHttpServletRequest();
        var b = new MockHttpServletRequest();
        admission.admit(a, 5 * GIB);
        admission.admit(b, 5 * GIB);
        S3Exception e = assertThrows(S3Exception.class, () -> admission.admit(new MockHttpServletRequest(), 5 * GIB));
        assertEquals(503, e.getStatus().value());
        assertEquals(10 * GIB, admission.reservedBytes(), "the refused one also gives back its disk reservation");
        admission.admit(new MockHttpServletRequest(), 1L << 20);   // small uploads still fit next to them
        finish(admission, a);
        admission.admit(new MockHttpServletRequest(), 5 * GIB);
    }

    @Test
    void theFirstUploadIsAlwaysAdmittedEvenIfItAloneExceedsTheHeapBudget() throws Exception {
        var admission = admission(new AtomicLong(1000 * GIB), new AtomicLong(1000 * GIB), GIB, 1L << 20);
        admission.admit(new MockHttpServletRequest(), 5 * GIB);
        assertThrows(S3Exception.class, () -> admission.admit(new MockHttpServletRequest(), 5 * GIB));
    }

    // ── declared size ────────────────────────────────────────────────────────

    private static RequestBodies bodies() throws Exception {
        return new RequestBodies(admission(new AtomicLong(1000 * GIB), new AtomicLong(1000 * GIB), GIB));
    }

    /** A PUT that declares {@code length} bytes (the mock derives Content-Length from the content, so it is overridden). */
    private static MockHttpServletRequest sized(long length) {
        return new MockHttpServletRequest("PUT", "/b/k") {
            @Override public long getContentLengthLong() {
                return length;
            }
        };
    }

    @Test
    void decodedContentLengthDoesNotLowerTheSizeOfAPlainBody() throws Exception {
        var request = sized(6 * GIB);
        request.addHeader("x-amz-decoded-content-length", "1");
        request.addHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
        S3Exception e = assertThrows(S3Exception.class, () -> bodies().requireAcceptableUpload(request));
        assertEquals("EntityTooLarge", e.getCode());
    }

    @Test
    void decodedContentLengthIsTheSizeOfAnAwsChunkedBody() throws Exception {
        var request = sized(6 * GIB);
        request.addHeader("Content-Encoding", "aws-chunked");
        request.addHeader("x-amz-decoded-content-length", String.valueOf(GIB));
        request.addHeader("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
        var bodies = bodies();
        bodies.requireAcceptableUpload(request);   // 1 GiB of payload in 6 GiB of framing is fine
    }

    @Test
    void aCopySourceAboveFiveGibIsRefused() throws Exception {
        var bodies = bodies();
        bodies.requireAcceptableCopy(new MockHttpServletRequest(), 5 * GIB);
        S3Exception e = assertThrows(S3Exception.class, () -> bodies.requireAcceptableCopy(new MockHttpServletRequest(), 5 * GIB + 1));
        assertEquals(400, e.getStatus().value());
        assertEquals("InvalidRequest", e.getCode());
    }
}
