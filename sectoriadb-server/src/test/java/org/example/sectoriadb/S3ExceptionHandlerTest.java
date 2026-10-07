package org.example.sectoriadb;

import org.example.sectoriadb.s3.S3ExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.*;

/** Mapping of unexpected exceptions to S3 error responses (no Spring context needed). */
class S3ExceptionHandlerTest {

    private final S3ExceptionHandler handler = new S3ExceptionHandler();

    /** Full-disk test (bench/faults/full-disk.sh): ENOSPC used to be a plain 500 InternalError. */
    @Test
    void noSpaceLeftOnDeviceIsInsufficientStorage507() {
        var r = handler.handleGeneral(new IOException("No space left on device"));
        assertEquals(507, r.getStatusCode().value());
        assertEquals("InsufficientStorage", r.getBody().getCode());
        // wrapped in other exceptions, as the engine does
        var wrapped = handler.handleGeneral(new UncheckedIOException(new IOException("write failed", new IOException("No space left on device"))));
        assertEquals(507, wrapped.getStatusCode().value());
    }

    @Test
    void otherFailuresStayInternalError500() {
        ResponseEntity<?> r = handler.handleGeneral(new IOException("Input/output error"));
        assertEquals(500, r.getStatusCode().value());
    }

    /** After a failed metastore commit every request got 409 BucketNotEmpty (even GET): wrong code, wrong family. */
    @Test
    void illegalStateIsBucketNotEmptyOnlyForBucketConflicts() {
        var conflict = handler.handleIllegalState(new IllegalStateException("BucketNotEmpty: bucket 'b' still has 1 object(s)."));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("BucketNotEmpty", conflict.getBody().getCode());
        var broken = handler.handleIllegalState(new IllegalStateException("store failed during a commit and must be reopened"));
        assertEquals(503, broken.getStatusCode().value());
        assertEquals("ServiceUnavailable", broken.getBody().getCode());
    }
}
