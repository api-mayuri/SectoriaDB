package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.s3.auth.AwsChunkedInputStream;
import org.example.sectoriadb.s3.auth.ChunkSigningContext;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.LimitedInputStream;
import org.example.sectoriadb.s3.http.S3Checksums;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Every way a controller reads a request body: size-bounded reads of small documents (policy, ACL, XML) and the
 * streaming read of object data (aws-chunked decoding, a hard cap, a refusal up front when the declared size cannot fit).
 */
@Component
public class RequestBodies {

    /** S3 limit for one PutObject / UploadPart request: 5 GiB. */
    public static final long MAX_SINGLE_UPLOAD_BYTES = 5L * 1024 * 1024 * 1024;
    public static final long MAX_POLICY_BYTES = 20 * 1024;
    public static final long MAX_ACL_BYTES = 64 * 1024;
    public static final long MAX_XML_BYTES = 2L * 1024 * 1024;

    private final UploadAdmission admission;

    public RequestBodies(UploadAdmission admission) {
        this.admission = admission;
    }

    /** Reads a small document; more than {@code max} bytes is refused with the given error before it is buffered. */
    public static byte[] readLimited(HttpServletRequest request, long max, HttpStatus status, String code, String message)
            throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > max) throw new S3Exception(status, code, message);
        InputStream in = request.getInputStream();
        byte[] data = in.readNBytes((int) max + 1);
        if (data.length > max) throw new S3Exception(status, code, message);
        return data;
    }

    /** The body of an XML request ({@link #MAX_XML_BYTES}); too large is {@code MalformedXML}. */
    public static InputStream xmlBody(HttpServletRequest request) throws IOException {
        byte[] data = readLimited(request, MAX_XML_BYTES, HttpStatus.BAD_REQUEST, "MalformedXML",
                "The XML you provided was larger than the allowed " + MAX_XML_BYTES + " bytes");
        return new ByteArrayInputStream(data);
    }

    /**
     * Admission check of an upload (PutObject / UploadPart) before a body byte is read: the size must be declared
     * (Content-Length, or x-amz-decoded-content-length for aws-chunked), within the 5 GiB limit, and fit on the volume
     * the body is spooled to together with the uploads already in flight ({@link UploadAdmission}).
     */
    public void requireAcceptableUpload(HttpServletRequest request) {
        long declared = declaredSize(request);
        if (declared < 0) {
            throw new S3Exception(HttpStatus.LENGTH_REQUIRED, "MissingContentLength",
                    "You must provide the Content-Length HTTP header.");
        }
        if (declared > MAX_SINGLE_UPLOAD_BYTES) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "EntityTooLarge",
                    "Your proposed upload exceeds the maximum allowed size (5 GiB for one request; use multipart upload)");
        }
        admission.admit(request, declared);
    }

    /**
     * Admission of a server-side copy (CopyObject, UploadPartCopy): the logical size of the source (or range) is
     * spooled, so the same 5 GiB cap and the same disk reservation apply as for an upload.
     */
    public void requireAcceptableCopy(HttpServletRequest request, long logicalBytes) {
        if (logicalBytes > MAX_SINGLE_UPLOAD_BYTES) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidRequest",
                    "The specified copy source is larger than the maximum allowable size for a copy source: "
                    + MAX_SINGLE_UPLOAD_BYTES);
        }
        admission.admit(request, logicalBytes);
    }

    /** Free-space floor for writes that spool nothing (CreateMultipartUpload): 507 when the volumes are nearly full. */
    public void requireWritable() {
        admission.requireWritable();
    }

    /** True for an aws-chunked body: {@code Content-Encoding: aws-chunked} or a STREAMING-* payload hash. */
    static boolean isAwsChunked(HttpServletRequest request) {
        String encoding = request.getHeader("Content-Encoding");
        String sha = request.getHeader("x-amz-content-sha256");
        return (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (sha != null && sha.startsWith("STREAMING-"));
    }

    /**
     * Declared payload size, -1 when unknown. For aws-chunked it is the size without framing; on any other request
     * x-amz-decoded-content-length means nothing (the body is stored as sent) and must not lower the size.
     */
    static long declaredSize(HttpServletRequest request) {
        String decoded = request.getHeader("x-amz-decoded-content-length");
        if (decoded != null && isAwsChunked(request)) {
            try {
                long v = Long.parseLong(decoded.trim());
                if (v < 0) throw new NumberFormatException();
                return v;
            } catch (NumberFormatException e) {
                throw S3Exception.invalidArgument("x-amz-decoded-content-length must be a non-negative integer");
            }
        }
        return request.getContentLengthLong();
    }

    /**
     * The request body with aws-chunked framing removed (Content-Encoding: aws-chunked, or x-amz-content-sha256:
     * STREAMING-*) and capped at {@link #MAX_SINGLE_UPLOAD_BYTES}; a trailing checksum is handed to {@code declared}.
     */
    public InputStream openBody(HttpServletRequest request, S3Checksums.Declared declared) throws IOException {
        InputStream raw = request.getInputStream();
        InputStream body = raw;
        if (isAwsChunked(request)) {
            Object ctx = request.getAttribute(ChunkSigningContext.REQUEST_ATTRIBUTE);
            body = new AwsChunkedInputStream(raw, ctx instanceof ChunkSigningContext c ? c : null,
                    declared != null ? declared::acceptTrailers : null,
                    request.getHeader("x-amz-decoded-content-length") != null ? declaredSize(request) : -1);
        }
        return new LimitedInputStream(body, MAX_SINGLE_UPLOAD_BYTES, () -> new S3Exception(HttpStatus.BAD_REQUEST,
                "EntityTooLarge", "Your proposed upload exceeds the maximum allowed size"));
    }
}
