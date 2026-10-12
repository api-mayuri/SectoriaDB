package org.example.sectoriadb.s3;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.s3.auth.AwsChunkedInputStream;
import org.example.sectoriadb.s3.auth.ChunkSigningContext;
import org.example.sectoriadb.storage.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    private final FileStorageService fileService;
    private final long freeSpaceReserve;

    public RequestBodies(FileStorageService fileService,
                         @Value("${sectoriadb.s3.limits.upload-free-space-reserve-bytes:67108864}") long freeSpaceReserve) {
        this.fileService = fileService;
        this.freeSpaceReserve = freeSpaceReserve;
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
     * the body is spooled to.
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
        try {
            long usable = Files.getFileStore(fileService.uploadTmpDir()).getUsableSpace();
            if (usable < declared + freeSpaceReserve) {
                throw new S3Exception(HttpStatus.SERVICE_UNAVAILABLE, "SlowDown",
                        "The server does not have enough free space to accept this upload right now. Please retry later.");
            }
        } catch (IOException e) {
            // the volume cannot be inspected: the upload itself will report a real problem
        }
    }

    /** Declared payload size, -1 when unknown. For aws-chunked it is the size without framing. */
    static long declaredSize(HttpServletRequest request) {
        String decoded = request.getHeader("x-amz-decoded-content-length");
        if (decoded != null) {
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
        String encoding = request.getHeader("Content-Encoding");
        String sha = request.getHeader("x-amz-content-sha256");
        boolean chunked = (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("aws-chunked"))
                || (sha != null && sha.startsWith("STREAMING-"));
        InputStream body = raw;
        if (chunked) {
            Object ctx = request.getAttribute(ChunkSigningContext.REQUEST_ATTRIBUTE);
            body = new AwsChunkedInputStream(raw, ctx instanceof ChunkSigningContext c ? c : null,
                    declared != null ? declared::acceptTrailers : null,
                    request.getHeader("x-amz-decoded-content-length") != null ? declaredSize(request) : -1);
        }
        return new LimitedInputStream(body, MAX_SINGLE_UPLOAD_BYTES, () -> new S3Exception(HttpStatus.BAD_REQUEST,
                "EntityTooLarge", "Your proposed upload exceeds the maximum allowed size"));
    }
}
