package org.example.service;

import org.example.model.BlobFile;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface StorageIOEngine {
    ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException;
    void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException;
}
