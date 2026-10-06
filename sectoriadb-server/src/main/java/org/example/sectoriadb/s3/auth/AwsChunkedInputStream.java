package org.example.sectoriadb.s3.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Strips the aws-chunked transfer encoding framing from an InputStream.
 *
 * Each chunk in the aws-chunked format looks like:
 *   <hex-size>;chunk-signature=<sig>\r\n
 *   <data>\r\n
 * The final chunk has size 0:
 *   0;chunk-signature=<sig>\r\n
 *   [trailing headers]\r\n
 *
 * Trailing headers (e.g., x-amz-checksum-*) are read but ignored.
 */
public class AwsChunkedInputStream extends InputStream {

    private static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;  // 16 MiB limit
    private static final int BUFFER_SIZE = 8192;

    private final InputStream delegate;
    private byte[] currentChunk;
    private int pos;
    private boolean done;
    private boolean trailingHeadersRead;

    public AwsChunkedInputStream(InputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        if (done) return -1;

        // Ensure we have data in current chunk
        while (currentChunk == null || pos >= currentChunk.length) {
            if (!readNextChunk()) return -1;
        }

        return currentChunk[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (done) return -1;

        while (currentChunk == null || pos >= currentChunk.length) {
            if (!readNextChunk()) return -1;
        }

        int available = currentChunk.length - pos;
        int toRead = Math.min(available, len);
        System.arraycopy(currentChunk, pos, buf, off, toRead);
        pos += toRead;
        return toRead;
    }

    private boolean readNextChunk() throws IOException {
        String header = readLine();
        if (header == null) {
            done = true;
            return false;
        }

        // Extract hex size from "<hex>;chunk-signature=..."
        int semi = header.indexOf(';');
        String hexSize = semi >= 0 ? header.substring(0, semi) : header;
        hexSize = hexSize.trim();
        if (hexSize.isEmpty()) {
            done = true;
            return false;
        }

        int chunkSize;
        try {
            chunkSize = Integer.parseInt(hexSize, 16);
        } catch (NumberFormatException e) {
            done = true;
            return false;
        }

        if (chunkSize < 0) {
            done = true;
            return false;
        }

        if (chunkSize > MAX_CHUNK_SIZE) {
            throw new IOException("Chunk size exceeds maximum: " + chunkSize);
        }

        if (chunkSize == 0) {
            // Final chunk: read trailing headers until empty line
            if (!trailingHeadersRead) {
                readTrailingHeaders();
                trailingHeadersRead = true;
            }
            done = true;
            return false;
        }

        // Read chunk data
        currentChunk = new byte[chunkSize];
        int totalRead = 0;
        while (totalRead < chunkSize) {
            int read = delegate.read(currentChunk, totalRead, chunkSize - totalRead);
            if (read < 0) {
                throw new IOException("Unexpected end of stream while reading chunk");
            }
            totalRead += read;
        }
        pos = 0;

        // Consume trailing \r\n after chunk data
        readLine();
        return true;
    }

    /**
     * Reads trailing headers after the final chunk.
     * In STREAMING-UNSIGNED-PAYLOAD-TRAILER format, there can be headers like x-amz-checksum-*.
     * Read until an empty line is encountered.
     */
    private void readTrailingHeaders() throws IOException {
        while (true) {
            String line = readLine();
            if (line == null || line.isEmpty()) {
                break;
            }
            // Trailing headers are ignored for now
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = delegate.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.isEmpty()) return null;
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
