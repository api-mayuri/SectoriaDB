package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.StorageIOEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

public class FileChannelStorageIOEngine implements StorageIOEngine {
    @Override
    public ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(
                blobFile.path(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            channel.position(offset);
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                throw new IOException("Достигнут конец файла на оффсете " + offset);
            }
            buffer.flip();
        }
        return buffer;
    }

    @Override
    public void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException {
        try (FileChannel channel = FileChannel.open(
                blobFile.path(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            channel.position(offset);
            int resultCode = channel.write(chunk);
        } catch (IOException e) {
            throw new IOException(e);
        }
    }
}
