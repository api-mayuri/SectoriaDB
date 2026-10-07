package org.example.sectoriadb;

import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.StorageIOEngine;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A disk that loses what was not forced: every engine it hands out writes through to the real file (so reads see the
 * writes, like the page cache) but remembers the previous bytes of each write until a {@code force} covers it.
 * {@link #crash} then plays a power failure: every unforced write independently either survived or is rolled back (the
 * old bytes are put back), and every later operation fails (the process is gone). {@link #revive} makes the disk usable
 * again for the "restarted" process.
 */
public final class CrashSimulation {

    private record Undo(long seq, BlobFile file, long offset, byte[] old) {
    }

    private final Object lock = new Object();
    private final List<Undo> pending = new ArrayList<>();
    private long seq;
    private boolean dead;
    public final AtomicInteger forces = new AtomicInteger();
    public final AtomicInteger writes = new AtomicInteger();

    public StorageIOEngine newEngine() {
        return new Engine();
    }

    /** Writes that no force has covered yet. */
    public int unforced() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Power failure: each unforced write survives with probability {@code keep}, else it is undone; all IO fails afterwards. */
    public int crash(long seed, double keep) throws IOException {
        Random r = new Random(seed);
        int lost = 0;
        FileChannelStorageIOEngine raw = new FileChannelStorageIOEngine(false);
        synchronized (lock) {
            dead = true;
            for (int i = pending.size() - 1; i >= 0; i--) {
                Undo u = pending.get(i);
                if (r.nextDouble() >= keep) {
                    raw.writeChunk(u.file(), u.offset(), ByteBuffer.wrap(u.old()));
                    lost++;
                }
            }
            pending.clear();
        }
        raw.close();
        return lost;
    }

    /** The disk works again (a new process). */
    public void revive() {
        synchronized (lock) {
            dead = false;
            pending.clear();
        }
    }

    private final class Engine implements StorageIOEngine {
        private final FileChannelStorageIOEngine real = new FileChannelStorageIOEngine(false);

        @Override
        public ByteBuffer readChunk(BlobFile f, long offset, int length) throws IOException {
            synchronized (lock) {
                if (dead) throw new IOException("simulated crash: the disk is gone");
            }
            return real.readChunk(f, offset, length);
        }

        @Override
        public void writeChunk(BlobFile f, long offset, ByteBuffer chunk) throws IOException {
            synchronized (lock) {
                if (dead) throw new IOException("simulated crash: the disk is gone");
                int n = chunk.remaining();
                ByteBuffer old = real.readChunk(f, offset, n);
                byte[] oldBytes = new byte[n];
                old.get(oldBytes);
                pending.add(new Undo(++seq, f, offset, oldBytes));
                real.writeChunk(f, offset, chunk);
                writes.incrementAndGet();
            }
        }

        @Override
        public void force(BlobFile f) throws IOException {
            synchronized (lock) {
                if (dead) throw new IOException("simulated crash: the disk is gone");
                pending.removeIf(u -> u.file().path().equals(f.path()));   // an fsync of the file makes its writes durable
                forces.incrementAndGet();
            }
        }

        @Override
        public void close() throws IOException {
            real.close();
        }
    }
}
