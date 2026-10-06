package org.example.sectoriadb.s3.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
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
 * Unsigned streaming (STREAMING-UNSIGNED-PAYLOAD-TRAILER) has no signatures; the trailing headers
 * (x-amz-checksum-*) are parsed and ignored. Without a signing context no verification is done
 * (authentication disabled or open setup mode).
 */
public class AwsChunkedInputStream extends InputStream {

    private static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;  // 16 MiB limit
    private static final int MAX_LINE = 8192;
    private static final String EMPTY_SHA256 = SigV4Utils.sha256HexEmpty();

    private final InputStream delegate;
    private final ChunkSigningContext signing;   // null => no verification
    private String previousSignature;
    private byte[] currentChunk;
    private int pos;
    private boolean done;

    public AwsChunkedInputStream(InputStream delegate) {
        this(delegate, null);
    }

    public AwsChunkedInputStream(InputStream delegate, ChunkSigningContext signing) {
        this.delegate = delegate;
        this.signing = signing;
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
        String header = readLine();
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
            throw new IOException("Chunk size exceeds maximum: " + chunkSize);
        }

        byte[] data = new byte[chunkSize];
        int totalRead = 0;
        while (totalRead < chunkSize) {
            int read = delegate.read(data, totalRead, chunkSize - totalRead);
            if (read < 0) {
                throw PayloadVerificationException.malformedChunks("unexpected end of stream inside a chunk");
            }
            totalRead += read;
        }

        if (signing != null) {
            verifyChunkSignature(chunkSignature, data);
        }

        if (chunkSize == 0) {
            // Final chunk: trailing headers up to an empty line
            readTrailers();
            done = true;
            currentChunk = null;
            return false;
        }

        // Consume the CRLF that follows the data
        String after = readLine();
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
        while (true) {
            String line = readLine();
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
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
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads up to CRLF/LF; returns null at end of stream with nothing read. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = delegate.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > MAX_LINE) {
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
