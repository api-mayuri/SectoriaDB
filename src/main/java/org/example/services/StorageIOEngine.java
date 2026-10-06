package org.example.services;

import org.example.entities.BlobFile;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface StorageIOEngine {
    ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException;
    void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException;
}
