package org.example.sectoriadb.service;

import org.example.sectoriadb.model.BlobFile;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Positional IO over a single blob file. An engine instance is bound to one blob file
 * and keeps its resources (file channel) until {@link #close()}.
 */
public interface StorageIOEngine extends AutoCloseable {
    /** Reads exactly {@code length} bytes at {@code offset}; the result is flipped and ready to read. */
    ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException;

    /** Writes the whole remaining content of {@code chunk} at {@code offset}. */
    void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException;

    /** Makes all previous writes durable (no-op for engines without durability). */
    default void force(BlobFile blobFile) throws IOException {
    }

    @Override
    default void close() throws IOException {
    }
}
