package org.example.sectoriadb.service.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Small helper: positional write that retries short writes. */
final class FileChannelWrites {
    private FileChannelWrites() {
    }

    static void writeFully(FileChannel ch, ByteBuffer src, long position) throws IOException {
        FileChannelStorageIOEngine.writeFully(ch::write, src, position);
    }
}
