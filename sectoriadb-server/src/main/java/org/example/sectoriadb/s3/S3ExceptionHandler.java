package org.example.sectoriadb.s3;

import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.s3.auth.AuthFailure;
import org.example.sectoriadb.s3.auth.PayloadVerificationException;
import org.example.sectoriadb.s3.xml.S3Error;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice(basePackages = "org.example.sectoriadb.s3")
public class S3ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(S3ExceptionHandler.class);

    /**
     * Handle typed S3Exception with proper code, status, and message.
     */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<S3Error> handleS3Exception(S3Exception ex) {
        String requestId = S3Support.requestId();
        log.debug("S3 error [{}]: {} - {}", ex.getStatus().value(), ex.getCode(), ex.getMessage());

        ObservabilityAttributes.noteErrorCode(ex.getCode());
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
        String requestId = S3Support.requestId();
        log.warn("Rejected request body [{}]: {}", ex.getS3Code(), ex.getMessage());
        ObservabilityAttributes.noteErrorCode(ex.getS3Code());
        ObservabilityAttributes.noteAuthFailure(ex.getS3Code().equals("XAmzContentSHA256Mismatch")
                ? AuthFailure.PAYLOAD_HASH_MISMATCH
                : ex.getS3Code().equals("SignatureDoesNotMatch") ? AuthFailure.CHUNK_SIGNATURE_MISMATCH : null);
        return ResponseEntity.status(ex.getHttpStatus())
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(ex.getS3Code(), ex.getMessage(), null, requestId));
    }

    /** A declared Content-MD5 / x-amz-checksum-* did not match the received bytes; nothing was committed. */
    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<S3Error> handleChecksumMismatch(ChecksumMismatchException ex) {
        String requestId = S3Support.requestId();
        log.warn("Rejected upload [BadDigest]: {}", ex.getMessage());
        ObservabilityAttributes.noteErrorCode("BadDigest");
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
        String requestId = S3Support.requestId();
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

        ObservabilityAttributes.noteErrorCode(code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, msg, null, requestId));
    }

    /** The bucket was deleted between the start of a PUT and its commit; nothing was committed. */
    @ExceptionHandler(ManifestRepository.PoolNotFoundException.class)
    public ResponseEntity<S3Error> handlePoolGone(ManifestRepository.PoolNotFoundException ex) {
        String requestId = S3Support.requestId();
        ObservabilityAttributes.noteErrorCode("NoSuchBucket");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("NoSuchBucket", "The specified bucket does not exist", null, requestId));
    }

    /**
     * Handle IllegalStateException, typically from bucket deletion conflicts.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<S3Error> handleIllegalState(IllegalStateException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        String requestId = S3Support.requestId();
        log.debug("S3 conflict (IllegalStateException): {}", msg);

        String code = "BucketNotEmpty";
        ObservabilityAttributes.noteErrorCode(code);
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

    /**
     * Spring MVC's own request errors are client errors, not server faults: wrong method on a known path (405),
     * unsupported or unacceptable media type (415/406), a missing or malformed query parameter (400
     * InvalidArgument), an unreadable body, an unmapped path (404). Without this they fell into the catch-all
     * below and were reported as 500 InternalError.
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class, ServletRequestBindingException.class,
            TypeMismatchException.class, HttpMessageNotReadableException.class,
            NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<S3Error> handleSpringMvc(Exception ex) {
        HttpStatusCode status = ex instanceof ErrorResponse er ? er.getStatusCode() : HttpStatus.BAD_REQUEST;
        HttpHeaders headers = ex instanceof ErrorResponse er ? er.getHeaders() : HttpHeaders.EMPTY;
        String code;
        String message;
        if (status.value() == 400) {
            // bad or missing parameter values are InvalidArgument in S3; an unreadable body is InvalidRequest
            boolean body = ex instanceof HttpMessageNotReadableException;
            code = body ? "InvalidRequest" : "InvalidArgument";
            message = body ? "The request body could not be read." : "Invalid or missing request parameter: " + ex.getMessage();
        } else {
            code = S3MvcErrors.codeFor(status);
            message = S3MvcErrors.messageFor(status);
        }
        log.debug("S3 client error [{}]: {} - {}", status.value(), code, ex.getMessage());
        ObservabilityAttributes.noteErrorCode(code);
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, message, null, S3Support.requestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<S3Error> handleGeneral(Exception ex) {
        String requestId = S3Support.requestId();
        log.error("S3 internal error [{}]: {}", requestId, ex.getMessage(), ex);
        ObservabilityAttributes.noteErrorCode("InternalError");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("InternalError",
                        "We encountered an internal error. Please try again.",
                        null, requestId));
    }
}
