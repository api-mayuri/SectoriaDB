package org.example.sectoriadb.engine.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Positional reads and writes that retry short transfers (a {@link FileChannel} may transfer less than asked). */
public final class PositionalIo {

    private PositionalIo() {
    }

    /** Positional read primitive, see {@link FileChannel#read(ByteBuffer, long)}. */
    @FunctionalInterface
    public interface PositionalReader {
        int read(ByteBuffer dst, long position) throws IOException;
    }

    /** Positional write primitive, see {@link FileChannel#write(ByteBuffer, long)}. */
    @FunctionalInterface
    public interface PositionalWriter {
        int write(ByteBuffer src, long position) throws IOException;
    }

    /** Fills {@code dst} completely, retrying short reads. Throws on EOF before the buffer is full. */
    public static void readFully(PositionalReader reader, ByteBuffer dst, long position) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int n = reader.read(dst, pos);
            if (n < 0) {
                throw new IOException("Unexpected end of file at offset " + pos
                        + " (" + dst.remaining() + " bytes missing)");
            }
            pos += n;
        }
    }

    /** Writes all of {@code src}, retrying short writes. */
    public static void writeFully(PositionalWriter writer, ByteBuffer src, long position) throws IOException {
        long pos = position;
        while (src.hasRemaining()) {
            pos += writer.write(src, pos);
        }
    }
}
