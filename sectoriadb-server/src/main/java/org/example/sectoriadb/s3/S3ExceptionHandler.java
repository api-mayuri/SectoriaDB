package org.example.sectoriadb.s3;

import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.s3.auth.PayloadVerificationException;
import org.example.sectoriadb.s3.xml.S3Error;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice(basePackages = "org.example.sectoriadb.s3")
public class S3ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(S3ExceptionHandler.class);

    /**
     * Handle typed S3Exception with proper code, status, and message.
     */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<S3Error> handleS3Exception(S3Exception ex) {
        String requestId = UUID.randomUUID().toString();
        log.debug("S3 error [{}]: {} - {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());

        S3Error error = new S3Error(ex.getCode(), ex.getMessage(), ex.getResource(), requestId);
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_XML)
                .body(error);
    }

    /**
     * The request body did not match its signed SHA-256 / chunk signatures. Raised while the body is being
     * read, i.e. before the object is committed.
     */
    @ExceptionHandler(PayloadVerificationException.class)
    public ResponseEntity<S3Error> handlePayloadVerification(PayloadVerificationException ex) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Rejected request body [{}]: {}", ex.getS3Code(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(ex.getS3Code(), ex.getMessage(), null, requestId));
    }

    /** A declared Content-MD5 / x-amz-checksum-* did not match the received bytes; nothing was committed. */
    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<S3Error> handleChecksumMismatch(ChecksumMismatchException ex) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Rejected upload [BadDigest]: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("BadDigest", ex.getMessage(), null, requestId));
    }

    /**
     * The body of a GET could not be completed after its headers were sent. Rethrown on purpose: the container
     * then aborts the connection (no clean end of body, no appended error document).
     */
    @ExceptionHandler(ResponseAbortedException.class)
    public void handleResponseAborted(ResponseAbortedException ex) {
        throw ex;
    }

    /**
     * Fallback for IllegalArgumentException (from legacy code paths).
     * Tries to classify the error, but convert to S3Exception where possible.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<S3Error> handleIllegalArgument(IllegalArgumentException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        String requestId = UUID.randomUUID().toString();
        log.debug("S3 error (IllegalArgumentException): {}", msg);

        // Classify based on message content
        String code;
        HttpStatus status;

        if (msg.contains("Bucket") || msg.contains("Pool") || msg.contains("pool") || msg.contains("not found")) {
            code = "NoSuchBucket";
            status = HttpStatus.NOT_FOUND;
        } else if (msg.contains("already exists")) {
            code = "BucketAlreadyExists";
            status = HttpStatus.CONFLICT;
        } else {
            code = "NoSuchKey";
            status = HttpStatus.NOT_FOUND;
        }

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, msg, null, requestId));
    }

    /**
     * Handle IllegalStateException, typically from bucket deletion conflicts.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<S3Error> handleIllegalState(IllegalStateException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        String requestId = UUID.randomUUID().toString();
        log.debug("S3 conflict (IllegalStateException): {}", msg);

        String code = "BucketNotEmpty";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, msg, null, requestId));
    }

    /**
     * Catch-all for unexpected errors.
     */
    /** The client went away mid-download (player seek/close): not an error, and nothing can be written anymore. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientAbort(Exception ex) {
        log.debug("Client disconnected: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<S3Error> handleGeneral(Exception ex) {
        String requestId = UUID.randomUUID().toString();
        log.error("S3 internal error [{}]: {}", requestId, ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("InternalError",
                        "We encountered an internal error. Please try again.",
                        null, requestId));
    }
}
