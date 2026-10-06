package org.example.s3;

import org.springframework.http.HttpStatus;

/** Typed S3 API error: maps directly to an S3 XML error response. */
public class S3Exception extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String resource;

    public S3Exception(HttpStatus status, String code, String message, String resource) {
        super(message);
        this.status = status;
        this.code = code;
        this.resource = resource;
    }

    public S3Exception(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getResource() { return resource; }

    public static S3Exception noSuchBucket(String bucket) {
        return new S3Exception(HttpStatus.NOT_FOUND, "NoSuchBucket",
                "The specified bucket does not exist", "/" + bucket);
    }

    public static S3Exception noSuchKey(String bucket, String key) {
        return new S3Exception(HttpStatus.NOT_FOUND, "NoSuchKey",
                "The specified key does not exist.", "/" + bucket + "/" + key);
    }

    public static S3Exception noSuchUpload(String uploadId) {
        return new S3Exception(HttpStatus.NOT_FOUND, "NoSuchUpload",
                "The specified upload does not exist.", uploadId);
    }

    public static S3Exception invalidArgument(String message) {
        return new S3Exception(HttpStatus.BAD_REQUEST, "InvalidArgument", message);
    }

    public static S3Exception accessDenied(String message) {
        return new S3Exception(HttpStatus.FORBIDDEN, "AccessDenied", message);
    }
}
