package org.example.sectoriadb.s3.auth;

import java.io.IOException;

/**
 * Raised while the request body is being read when the body does not match what was signed.
 * Being an IOException it aborts the upload before the object is committed.
 */
public class PayloadVerificationException extends IOException {

    private final String s3Code;
    private final int httpStatus;

    public PayloadVerificationException(String s3Code, int httpStatus, String message) {
        super(message);
        this.s3Code = s3Code;
        this.httpStatus = httpStatus;
    }

    public String getS3Code() { return s3Code; }
    public int getHttpStatus() { return httpStatus; }

    public static PayloadVerificationException sha256Mismatch() {
        return new PayloadVerificationException("XAmzContentSHA256Mismatch", 400,
                "The provided 'x-amz-content-sha256' header does not match what was computed.");
    }

    public static PayloadVerificationException chunkSignature() {
        return new PayloadVerificationException("SignatureDoesNotMatch", 403,
                "The chunk signature we calculated does not match the signature you provided.");
    }

    public static PayloadVerificationException malformedChunks(String detail) {
        return new PayloadVerificationException("IncompleteBody", 400,
                "The aws-chunked request body is malformed: " + detail);
    }
}
