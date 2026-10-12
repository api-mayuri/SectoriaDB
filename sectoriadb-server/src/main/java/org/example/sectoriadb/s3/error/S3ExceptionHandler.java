package org.example.sectoriadb.s3.error;

import org.example.sectoriadb.checksum.ChecksumMismatchException;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.observability.AuthFailure;
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
import org.example.sectoriadb.s3.http.ResponseAbortedException;
import org.example.sectoriadb.observability.RequestIds;


@RestControllerAdvice(basePackages = "org.example.sectoriadb.s3")
public class S3ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(S3ExceptionHandler.class);

    /**
     * Handle typed S3Exception with proper code, status, and message.
     */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<S3Error> handleS3Exception(S3Exception ex) {
        String requestId = RequestIds.current();
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
        String requestId = RequestIds.current();
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
        String requestId = RequestIds.current();
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
     * An IllegalArgumentException that reached the web layer is a rejected argument (a malformed number, a range
     * outside the object, ...), never "not found": the classification is by type, not by message text, and the
     * internal message is logged but never sent to the client. Missing buckets and keys are raised as typed
     * {@link S3Exception}s by the controllers.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<S3Error> handleIllegalArgument(IllegalArgumentException ex) {
        String requestId = RequestIds.current();
        log.debug("S3 error (IllegalArgumentException): {}", ex.getMessage());
        ObservabilityAttributes.noteErrorCode("InvalidArgument");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("InvalidArgument", "A request argument is invalid.", null, requestId));
    }

    /**
     * The client stopped sending the body (Tomcat connection timeout): not a server fault. Answered 400 RequestTimeout like S3
     * (the connection is usually gone, so the write may fail silently); logged at INFO without a stack trace.
     */
    @ExceptionHandler(java.net.SocketTimeoutException.class)
    public ResponseEntity<S3Error> handleSocketTimeout(java.net.SocketTimeoutException ex) {
        log.info("Request body timed out [{}]", RequestIds.current());
        ObservabilityAttributes.noteErrorCode("RequestTimeout");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("RequestTimeout",
                        "Your socket connection to the server was not read from or written to within the timeout period.",
                        null, RequestIds.current()));
    }

    /** A conditional write (If-None-Match: * / If-Match) did not hold at commit time; nothing was committed. */
    @ExceptionHandler(ManifestRepository.CommitPreconditionException.class)
    public ResponseEntity<S3Error> handleCommitPrecondition(ManifestRepository.CommitPreconditionException ex) {
        String requestId = RequestIds.current();
        boolean missing = ex.keyMissing();
        String code = missing ? "NoSuchKey" : "PreconditionFailed";
        ObservabilityAttributes.noteErrorCode(code);
        return ResponseEntity.status(missing ? HttpStatus.NOT_FOUND : HttpStatus.PRECONDITION_FAILED)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, missing ? "The specified key does not exist."
                        : "At least one of the pre-conditions you specified did not hold.", null, requestId));
    }

    /** The bucket was deleted between the start of a PUT and its commit; nothing was committed. */
    @ExceptionHandler(ManifestRepository.PoolNotFoundException.class)
    public ResponseEntity<S3Error> handlePoolGone(ManifestRepository.PoolNotFoundException ex) {
        String requestId = RequestIds.current();
        ObservabilityAttributes.noteErrorCode("NoSuchBucket");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("NoSuchBucket", "The specified bucket does not exist", null, requestId));
    }

    /**
     * The chunks of an upload cannot be committed because the garbage collector freed one the upload had found in the
     * index (COLLECTED), or a resize / key collision raced with it. Nothing was written; the client retries the whole
     * request, which is what S3 clients do on a 503 (doc 09, 10).
     */
    @ExceptionHandler(org.example.sectoriadb.catalog.ChunkRepository.ChunkPlacementException.class)
    public ResponseEntity<S3Error> handleChunkPlacement(org.example.sectoriadb.catalog.ChunkRepository.ChunkPlacementException ex) {
        String requestId = RequestIds.current();
        log.warn("Upload rejected, retry expected [{}]: {} ({})", requestId, ex.reason(), ex.getMessage());
        ObservabilityAttributes.noteErrorCode("ServiceUnavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("ServiceUnavailable",
                        "The object could not be committed because storage was reorganized under the upload. Please retry.",
                        null, requestId));
    }

    /**
     * The metadata store refuses writes because its last commit failed and it has not recovered yet (reads are not
     * affected and keep working). A full disk is {@code 507 InsufficientStorage}; any other fault is
     * {@code 503 SlowDown} with {@code Retry-After}: the store recovers by itself once the disk works, a retry is meaningful.
     */
    @ExceptionHandler(org.example.sectoriadb.metastore.MetaStoreUnavailableException.class)
    public ResponseEntity<S3Error> handleMetaStoreUnavailable(org.example.sectoriadb.metastore.MetaStoreUnavailableException ex) {
        String requestId = RequestIds.current();
        if (isNoSpaceLeft(ex)) {
            log.warn("Write refused, the metadata disk is full [{}]: {}", requestId, ex.getMessage());
            ObservabilityAttributes.noteErrorCode("InsufficientStorage");
            return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                    .header("Retry-After", "5")
                    .contentType(MediaType.APPLICATION_XML)
                    .body(new S3Error("InsufficientStorage",
                            "The server has run out of storage space for metadata. Try again after space has been freed.",
                            null, requestId));
        }
        log.warn("Write refused, the metadata store is recovering [{}]: {}", requestId, ex.getMessage());
        ObservabilityAttributes.noteErrorCode("SlowDown");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("SlowDown", "The storage engine is recovering from a disk failure. Please retry.",
                        null, requestId));
    }

    /**
     * IllegalStateException is a server-side state problem (for example the metadata store refusing work after a failed
     * commit, "must be reopened"): 503, the internal message is only logged. A bucket that is not empty is decided
     * by the controller (it checks the index), not by parsing this message.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<S3Error> handleIllegalState(IllegalStateException ex) {
        String requestId = RequestIds.current();
        log.error("S3 request failed, the storage engine is in an unusable state [{}]: {}", requestId, ex.getMessage(), ex);
        ObservabilityAttributes.noteErrorCode("ServiceUnavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("ServiceUnavailable",
                        "The storage engine cannot serve this request right now. Please try again later.",
                        null, requestId));
    }

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
            String name = ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m ? m.getName()
                    : ex instanceof org.springframework.web.bind.MissingServletRequestParameterException m ? m.getParameterName() : null;
            message = body ? "The request body could not be read."
                    : name != null ? "Invalid or missing value of the request parameter '" + name + "'"
                    : "Invalid request parameter or header.";
        } else {
            code = S3MvcErrors.codeFor(status);
            message = S3MvcErrors.messageFor(status);
        }
        log.debug("S3 client error [{}]: {} - {}", status.value(), code, ex.getMessage());
        ObservabilityAttributes.noteErrorCode(code);
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error(code, message, null, RequestIds.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<S3Error> handleGeneral(Exception ex) {
        String requestId = RequestIds.current();
        if (isNoSpaceLeft(ex)) {
            // The data (or metadata) volume is full: nothing is half-written (the object was never committed), the
            // operator must free space. 507 tells monitoring and clients apart from a transient 500.
            log.error("S3 request failed, the disk is full [{}]: {}", requestId, ex.getMessage());
            ObservabilityAttributes.noteErrorCode("InsufficientStorage");
            return ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(new S3Error("InsufficientStorage",
                            "The server has run out of storage space. Try again after space has been freed.",
                            null, requestId));
        }
        log.error("S3 internal error [{}]: {}", requestId, ex.getMessage(), ex);
        ObservabilityAttributes.noteErrorCode("InternalError");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_XML)
                .body(new S3Error("InternalError",
                        "We encountered an internal error. Please try again.",
                        null, requestId));
    }

    /** java.io.IOException "No space left on device" (ENOSPC) anywhere in the cause chain. */
    static boolean isNoSpaceLeft(Throwable t) {
        for (int depth = 0; t != null && depth < 8; t = t.getCause(), depth++) {
            if (t instanceof java.io.IOException && t.getMessage() != null
                    && t.getMessage().contains("No space left on device")) {
                return true;
            }
        }
        return false;
    }
}
