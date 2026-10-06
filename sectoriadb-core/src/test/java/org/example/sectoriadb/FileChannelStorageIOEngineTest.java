package org.example.sectoriadb;

import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** C4/C5: full reads and writes, EOF handling, durable engine. */
class FileChannelStorageIOEngineTest {

    @TempDir
    Path tmp;

    @Test
    void readFully_retriesShortReads() throws IOException {
        byte[] src = new byte[100];
        new Random(1).nextBytes(src);
        ByteBuffer dst = ByteBuffer.allocate(100);
        FileChannelStorageIOEngine.readFully((buf, pos) -> {
            int n = Math.min(3, buf.remaining());
            buf.put(src, (int) pos, n);
            return n;
        }, dst, 0);
        assertArrayEquals(src, dst.array());
    }

    @Test
    void readFully_throwsOnEofBeforeBufferIsFull() {
        ByteBuffer dst = ByteBuffer.allocate(10);
        assertThrows(IOException.class, () -> FileChannelStorageIOEngine.readFully((buf, pos) -> {
            if (pos >= 4) return -1;
            buf.put((byte) 1);
            return 1;
        }, dst, 0));
    }

    @Test
    void writeFully_retriesShortWrites() throws IOException {
        byte[] src = new byte[50];
        new Random(2).nextBytes(src);
        byte[] out = new byte[50];
        FileChannelStorageIOEngine.writeFully((buf, pos) -> {
            int n = Math.min(7, buf.remaining());
            buf.get(out, (int) pos, n);
            return n;
        }, ByteBuffer.wrap(src), 0);
        assertArrayEquals(src, out);
    }

    @Test
    void roundTrip_andReadPastEndFails() throws IOException {
        BlobFile bf = new BlobFile("x", tmp.resolve("x.raw"), 0);
        byte[] data = new byte[1 << 20];
        new Random(3).nextBytes(data);
        try (FileChannelStorageIOEngine e = new FileChannelStorageIOEngine(true)) {
            e.writeChunk(bf, 128, ByteBuffer.wrap(data));
            e.force(bf);
            ByteBuffer back = e.readChunk(bf, 128, data.length);
            assertArrayEquals(data, back.array());
            assertThrows(IOException.class, () -> e.readChunk(bf, 128 + data.length - 10, 100));
        }
    }
}
