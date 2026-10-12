package org.example.sectoriadb.s3.auth;

import org.example.sectoriadb.s3.error.PayloadVerificationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Strips the aws-chunked transfer encoding framing from an InputStream and, when a
 * {@link ChunkSigningContext} is supplied, verifies every chunk signature.
 *
 * Each chunk in the aws-chunked format looks like:
 *   <hex-size>;chunk-signature=<sig>\r\n
 *   <data>\r\n
 * The final chunk has size 0:
 *   0;chunk-signature=<sig>\r\n
 *   [trailing headers]\r\n
 *
 * Signed streaming (STREAMING-AWS4-HMAC-SHA256-PAYLOAD): the signature of chunk N is
 *   HMAC(signingKey, "AWS4-HMAC-SHA256-PAYLOAD\n" + timestamp + "\n" + scope + "\n" + sig(N-1) + "\n"
 *                     + sha256("") + "\n" + sha256(chunkData))
 * where sig(-1) is the request (seed) signature. Data of a chunk is handed to the reader only after its
 * signature has been verified, and the stream must end with a valid zero-length chunk, otherwise an
 * IOException ({@link PayloadVerificationException}) is thrown, so truncated or tampered uploads are never
 * committed.
 *
 * Signed trailer (STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER): the trailer headers are covered by
 * x-amz-trailer-signature = HMAC(signingKey, "AWS4-HMAC-SHA256-TRAILER\n" + timestamp + "\n" + scope + "\n"
 *                     + sig(last chunk) + "\n" + sha256(canonical trailer)).
 *
 * Unsigned streaming (STREAMING-UNSIGNED-PAYLOAD-TRAILER) has no signatures. Without a signing context no
 * signature verification is done (authentication disabled or open setup mode).
 *
 * The trailing headers (x-amz-checksum-*) are handed to an optional {@link TrailerListener} once the final chunk
 * (and, if signed, the trailer signature) has been read; the listener owns the checksum comparison, which happens
 * before the upload is committed (see S3Checksums). The listener may throw an IOException to reject the upload.
 */
public class AwsChunkedInputStream extends InputStream {

    /** Largest chunk accepted (AWS SDKs use 64 KiB to 8 MiB); the buffer grows as bytes arrive, never up front. */
    private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024;
    /** A chunk header line ("hex;chunk-signature=64hex") is about 100 bytes. */
    private static final int MAX_HEADER_LINE = 1024;
    private static final int MAX_TRAILER_LINE = 8192;
    private static final int MAX_TRAILERS = 16;
    private static final int MAX_TRAILER_BYTES = 16 * 1024;
    private static final int READ_STEP = 64 * 1024;
    private static final String EMPTY_SHA256 = SigV4Utils.sha256HexEmpty();

    /** Receives the parsed trailer headers (lower-case names, signature excluded) after the final chunk. */
    @FunctionalInterface
    public interface TrailerListener {
        void onTrailers(Map<String, String> trailers) throws IOException;
    }

    private final InputStream delegate;
    private final ChunkSigningContext signing;   // null => no verification
    private final TrailerListener trailerListener;
    private String previousSignature;
    private byte[] currentChunk;
    private int pos;
    private boolean done;
    private final long declaredDecodedLength;   // x-amz-decoded-content-length, -1 when not sent
    private long decodedTotal;

    public AwsChunkedInputStream(InputStream delegate, ChunkSigningContext signing, TrailerListener trailerListener) {
        this(delegate, signing, trailerListener, -1);
    }

    public AwsChunkedInputStream(InputStream delegate, ChunkSigningContext signing, TrailerListener trailerListener,
                                 long declaredDecodedLength) {
        this.declaredDecodedLength = declaredDecodedLength;
        this.delegate = delegate;
        this.signing = signing;
        this.trailerListener = trailerListener;
        this.previousSignature = signing != null ? signing.seedSignature() : null;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (done) return -1;

        while (currentChunk == null || pos >= currentChunk.length) {
            if (!readNextChunk()) return -1;
        }

        int toRead = Math.min(currentChunk.length - pos, len);
        System.arraycopy(currentChunk, pos, buf, off, toRead);
        pos += toRead;
        return toRead;
    }

    private boolean readNextChunk() throws IOException {
        String header = readLine(MAX_HEADER_LINE);
        if (header == null) {
            throw PayloadVerificationException.malformedChunks("unexpected end of stream before the final chunk");
        }

        // "<hex>;chunk-signature=<sig>"
        int semi = header.indexOf(';');
        String hexSize = (semi >= 0 ? header.substring(0, semi) : header).trim();
        String chunkSignature = null;
        if (semi >= 0) {
            String ext = header.substring(semi + 1).trim();
            if (ext.startsWith("chunk-signature=")) {
                chunkSignature = ext.substring("chunk-signature=".length()).trim();
            }
        }

        int chunkSize;
        try {
            chunkSize = Integer.parseInt(hexSize, 16);
        } catch (NumberFormatException e) {
            throw PayloadVerificationException.malformedChunks("invalid chunk size");
        }
        if (chunkSize < 0) {
            throw PayloadVerificationException.malformedChunks("negative chunk size");
        }
        if (chunkSize > MAX_CHUNK_SIZE) {
            throw PayloadVerificationException.malformedChunks("chunk size exceeds the maximum of " + MAX_CHUNK_SIZE);
        }
        if (declaredDecodedLength >= 0 && decodedTotal + chunkSize > declaredDecodedLength) {
            throw PayloadVerificationException.malformedChunks(
                    "the body is longer than x-amz-decoded-content-length");
        }

        // The size comes from the (still unverified) header: never allocate it before the bytes have arrived
        byte[] data = new byte[Math.min(chunkSize, READ_STEP)];
        int totalRead = 0;
        while (totalRead < chunkSize) {
            if (totalRead == data.length) data = Arrays.copyOf(data, Math.min(chunkSize, data.length * 2));
            int read = delegate.read(data, totalRead, data.length - totalRead);
            if (read < 0) {
                throw PayloadVerificationException.malformedChunks("unexpected end of stream inside a chunk");
            }
            totalRead += read;
        }
        decodedTotal += chunkSize;

        if (signing != null) {
            verifyChunkSignature(chunkSignature, data);
        }

        if (chunkSize == 0) {
            // Final chunk: trailing headers up to an empty line
            if (declaredDecodedLength >= 0 && decodedTotal != declaredDecodedLength) {
                throw PayloadVerificationException.malformedChunks(
                        "the body is shorter than x-amz-decoded-content-length");
            }
            readTrailers();
            done = true;
            currentChunk = null;
            return false;
        }

        // Consume the CRLF that follows the data
        String after = readLine(MAX_HEADER_LINE);
        if (after == null || !after.isEmpty()) {
            throw PayloadVerificationException.malformedChunks("missing CRLF after chunk data");
        }

        currentChunk = data;
        pos = 0;
        return true;
    }

    private void verifyChunkSignature(String provided, byte[] data) throws IOException {
        if (provided == null) throw PayloadVerificationException.chunkSignature();
        String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + signing.timestamp() + "\n" + signing.scope() + "\n"
                + previousSignature + "\n" + EMPTY_SHA256 + "\n" + SigV4Utils.sha256Hex(data);
        String expected = SigV4Utils.computeSignature(signing.signingKey(), stringToSign);
        if (!constantTimeEquals(expected, provided)) {
            throw PayloadVerificationException.chunkSignature();
        }
        previousSignature = expected;
    }

    /**
     * Reads the trailing headers after the final chunk (x-amz-checksum-*, x-amz-trailer-signature),
     * up to an empty line or end of stream. For the signed-trailer variant the trailer signature is verified.
     */
    private void readTrailers() throws IOException {
        Map<String, String> trailers = new LinkedHashMap<>();
        String trailerSignature = null;
        int count = 0;
        int bytes = 0;
        while (true) {
            String line = readLine(MAX_TRAILER_LINE);
            if (line == null || line.isEmpty()) break;
            bytes += line.length();
            if (++count > MAX_TRAILERS || bytes > MAX_TRAILER_BYTES) {
                throw PayloadVerificationException.malformedChunks("too many or too large trailers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-amz-trailer-signature")) trailerSignature = value;
            else trailers.put(name, value);
        }
        if (signing != null && signing.trailerSigned()) {
            if (trailerSignature == null) throw PayloadVerificationException.chunkSignature();
            StringBuilder canonical = new StringBuilder();
            trailers.forEach((k, v) -> canonical.append(k).append(':').append(v).append('\n'));
            String stringToSign = "AWS4-HMAC-SHA256-TRAILER\n" + signing.timestamp() + "\n" + signing.scope() + "\n"
                    + previousSignature + "\n"
                    + SigV4Utils.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
            String expected = SigV4Utils.computeSignature(signing.signingKey(), stringToSign);
            if (!constantTimeEquals(expected, trailerSignature)) {
                throw PayloadVerificationException.chunkSignature();
            }
        }
        if (trailerListener != null) trailerListener.onTrailers(trailers);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads up to CRLF/LF; returns null at end of stream with nothing read. */
    private String readLine(int maxLine) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = delegate.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > maxLine) {
                throw PayloadVerificationException.malformedChunks("header line too long");
            }
        }
        if (c == -1 && sb.isEmpty()) return null;
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
