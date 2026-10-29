package org.example.sectoriadb.s3.error;

import org.springframework.http.HttpStatus;

/** Typed S3 API error: maps directly to an S3 XML error response. */
public class S3Exception extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String resource;
    /** Headers of the error response (x-amz-delete-marker, x-amz-version-id, Allow). */
    private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    public S3Exception(HttpStatus status, String code, String message, String resource) {
        super(message);
        this.status = status;
        this.code = code;
        this.resource = resource;
    }

    public S3Exception(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public S3Exception withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public java.util.Map<String, String> getHeaders() { return headers; }

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

    public static S3Exception noSuchVersion(String bucket, String key, String versionId) {
        return new S3Exception(HttpStatus.NOT_FOUND, "NoSuchVersion",
                "The specified version does not exist.", "/" + bucket + "/" + key + "?versionId=" + versionId);
    }

    public static S3Exception noSuchUpload(String uploadId) {
        return new S3Exception(HttpStatus.NOT_FOUND, "NoSuchUpload",
                "The specified upload does not exist.", uploadId);
    }

    public static S3Exception invalidArgument(String message) {
        return new S3Exception(HttpStatus.BAD_REQUEST, "InvalidArgument", message);
    }

    public static S3Exception notImplemented(String message) {
        return new S3Exception(HttpStatus.NOT_IMPLEMENTED, "NotImplemented", message);
    }

}
