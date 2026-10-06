package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.StorageIOEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@link StorageIOEngine} on top of one long-lived {@link FileChannel}.
 *
 * The channel is opened lazily on first use and kept until {@link #close()}. All IO is positional
 * ({@code channel.read(buf, pos)} / {@code channel.write(buf, pos)}), which does not touch the channel
 * position and is therefore safe to call from many threads at once.
 *
 * Short reads/writes are retried until the buffer is complete. A read that hits end-of-file before
 * the buffer is full throws {@link IOException}: blob files are fully preallocated (sparse files return
 * zeros inside their size), so EOF means the file was truncated or the offset is out of range, and silently
 * zero-filling would hide corruption.
 *
 * Note: interrupting a thread that is blocked in channel IO closes the shared channel (JDK behaviour,
 * {@link java.nio.channels.ClosedByInterruptException}); later calls on this engine then fail.
 */
public class FileChannelStorageIOEngine implements StorageIOEngine {

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

    private final boolean fsync;
    private final StorageMetrics metrics;
    private FileChannel channel;
    private Path channelPath;
    private boolean closed;

    /** Engine with durability enabled. */
    public FileChannelStorageIOEngine() {
        this(true);
    }

    /** @param fsync if false, {@link #force} is a no-op (faster, not crash-safe; for tests). */
    public FileChannelStorageIOEngine(boolean fsync) {
        this(fsync, StorageMetrics.NOOP);
    }

    /** As {@link #FileChannelStorageIOEngine(boolean)}; reads, writes and forces are reported to {@code metrics}. */
    public FileChannelStorageIOEngine(boolean fsync, StorageMetrics metrics) {
        this.fsync = fsync;
        this.metrics = metrics;
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

    @Override
    public ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        FileChannel ch = channel(blobFile);
        long t0 = System.nanoTime();
        readFully(ch::read, buffer, offset);
        metrics.diskRead(StorageMetrics.Target.CUCKOO, System.nanoTime() - t0, length);
        buffer.flip();
        return buffer;
    }

    @Override
    public void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException {
        FileChannel ch = channel(blobFile);
        int bytes = chunk.remaining();
        long t0 = System.nanoTime();
        writeFully(ch::write, chunk, offset);
        metrics.diskWrite(StorageMetrics.Target.CUCKOO, System.nanoTime() - t0, bytes);
    }

    @Override
    public void force(BlobFile blobFile) throws IOException {
        if (fsync) {
            FileChannel ch = channel(blobFile);
            long t0 = System.nanoTime();
            ch.force(false);
            metrics.fsync(StorageMetrics.Target.CUCKOO, System.nanoTime() - t0);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (channel != null) {
            FileChannel ch = channel;
            channel = null;
            ch.close();
        }
    }

    private synchronized FileChannel channel(BlobFile blobFile) throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        if (channel == null) {
            channel = FileChannel.open(blobFile.path(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            channelPath = blobFile.path();
        } else if (!channelPath.equals(blobFile.path())) {
            throw new IllegalArgumentException("Engine is bound to " + channelPath
                    + " but was asked to access " + blobFile.path());
        }
        return channel;
    }
}
