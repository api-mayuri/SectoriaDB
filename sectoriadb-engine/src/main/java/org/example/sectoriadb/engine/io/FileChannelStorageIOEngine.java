package org.example.sectoriadb.engine.io;

import org.example.sectoriadb.engine.metrics.EngineListener;

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
 * Interrupting a thread that is inside channel IO closes the shared channel (JDK behaviour,
 * {@link java.nio.channels.ClosedByInterruptException}). The engine survives that: when an operation finds the channel
 * closed although the engine is not, it reopens the file and repeats the operation once (with the interrupt flag
 * cleared for the retry and restored afterwards, so the caller still sees its interrupt).
 */
public class FileChannelStorageIOEngine implements StorageIOEngine {

    private final boolean fsync;
    private final EngineListener listener;
    private FileChannel channel;
    private Path channelPath;
    private boolean closed;

    /** Engine with durability enabled. */
    public FileChannelStorageIOEngine() {
        this(true);
    }

    /** @param fsync if false, {@link #force} is a no-op (faster, not crash-safe; for tests). */
    public FileChannelStorageIOEngine(boolean fsync) {
        this(fsync, EngineListener.NOOP);
    }

    /** As {@link #FileChannelStorageIOEngine(boolean)}; reads, writes and forces are reported to {@code listener}. */
    public FileChannelStorageIOEngine(boolean fsync, EngineListener listener) {
        this.fsync = fsync;
        this.listener = listener;
    }

    @Override
    public ByteBuffer readChunk(BlobFile blobFile, long offset, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("negative length: " + length);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long t0 = System.nanoTime();
        withChannel(blobFile, ch -> {
            buffer.clear();                 // a retry starts from scratch
            PositionalIo.readFully(ch::read, buffer, offset);
            return null;
        });
        listener.onDiskRead(EngineListener.Target.CUCKOO, System.nanoTime() - t0, length);
        buffer.flip();
        return buffer;
    }

    @Override
    public void writeChunk(BlobFile blobFile, long offset, ByteBuffer chunk) throws IOException {
        int bytes = chunk.remaining();
        int start = chunk.position();
        long t0 = System.nanoTime();
        withChannel(blobFile, ch -> {
            chunk.position(start);          // a retry rewrites the whole chunk (positional writes are idempotent)
            PositionalIo.writeFully(ch::write, chunk, offset);
            return null;
        });
        listener.onDiskWrite(EngineListener.Target.CUCKOO, System.nanoTime() - t0, bytes);
    }

    @Override
    public long size(BlobFile blobFile) throws IOException {
        return withChannel(blobFile, FileChannel::size);
    }

    @Override
    public void force(BlobFile blobFile) throws IOException {
        if (fsync) {
            long t0 = System.nanoTime();
            withChannel(blobFile, ch -> {
                ch.force(false);
                return null;
            });
            listener.onFsync(EngineListener.Target.CUCKOO, System.nanoTime() - t0);
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

    private interface ChannelOp<T> {
        T run(FileChannel ch) throws IOException;
    }

    private <T> T withChannel(BlobFile blobFile, ChannelOp<T> op) throws IOException {
        FileChannel ch = channel(blobFile);
        try {
            return op.run(ch);
        } catch (ClosedChannelException e) {
            synchronized (this) {
                if (closed) throw e;                    // closed on purpose
            }
            boolean interrupted = Thread.interrupted(); // the flag would close the new channel at once
            try {
                return op.run(reopen(blobFile, ch));
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Replaces the channel that was found closed (unless another thread already did). */
    private synchronized FileChannel reopen(BlobFile blobFile, FileChannel broken) throws IOException {
        if (channel == broken) {
            channel = null;
            try {
                broken.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        return channel(blobFile);
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
