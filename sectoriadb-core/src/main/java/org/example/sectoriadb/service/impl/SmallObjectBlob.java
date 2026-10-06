package org.example.sectoriadb.service.impl;

import org.example.sectoriadb.format.InvalidBlobHeaderException;
import org.example.sectoriadb.format.SmallBlobLayout;
import org.example.sectoriadb.format.SmallBlobLayout.Checkpoint;
import org.example.sectoriadb.format.SmallBlobLayout.RecordHeader;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.example.sectoriadb.format.SmallBlobLayout.*;

/**
 * An append-only, log-structured file holding whole small objects (see {@link SmallBlobLayout} for the format and
 * {@code docs/architecture/04-small-objects.md} for the protocol).
 *
 * <ul>
 *   <li><b>Append</b>: serialized by a per-file lock. The record (header + data + padding) is written at the tail,
 *       forced (unless fsync is off), and only then the in-memory tail is published. A crash before the force
 *       leaves a torn record beyond the last published tail.</li>
 *   <li><b>Read</b>: lock-free positional IO. A read never looks past the published tail and verifies header CRC,
 *       state, length and data CRC32C.</li>
 *   <li><b>Delete</b>: a single-byte state flip (ACTIVE to DELETED). Space is not reclaimed here; the live/dead
 *       byte counters in {@link Stats} are the hook for a later compaction stage.</li>
 *   <li><b>Recovery</b> (on {@link #open}): all record headers are walked; records at or after the header
 *       checkpoint additionally have their data CRC verified. The first invalid record with nothing valid after
 *       it is the torn tail: it is cut off and later appends overwrite it. An invalid region followed by valid
 *       records is damage in the middle of the log: the blob is then opened read-only ({@link #isWritable()} is
 *       false), nothing is overwritten, and the problem is logged and listed by {@link #scrub()}.</li>
 * </ul>
 */
public final class SmallObjectBlob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SmallObjectBlob.class);

    /** Largest single record payload (it is buffered in memory). */
    public static final int MAX_RECORD_DATA = 64 << 20;
    private static final int RESYNC_WINDOW = 1 << 20;

    /** Where an object was stored. {@code offset} is the record start inside the blob file. */
    public record Location(long offset, int length, int crc32c) {
    }

    /** Space accounting. Bytes are record spans on disk (header + data + padding). */
    public record Stats(long fileBytes, long liveRecords, long deadRecords, long liveBytes, long deadBytes,
                        boolean writable) {
    }

    /** Result of {@link #scrub()}. {@code problems} is capped at 100 lines. */
    public record ScrubReport(long records, long active, long deleted, long ok, long corrupt, List<String> problems) {
    }

    private final String id;
    private final Path path;
    private final boolean fsync;
    private final long maxFileBytes;
    private final long checkpointInterval;
    private final FileChannel channel;
    private final StorageMetrics metrics;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile long tail;
    private volatile boolean writable = true;
    private volatile boolean closed;

    // guarded by lock
    private long nextSequence;
    private long liveRecords, deadRecords, liveBytes, deadBytes;
    private long checkpointTail;
    private long checkpointGeneration;

    private SmallObjectBlob(String id, Path path, boolean fsync, long maxFileBytes, long checkpointInterval,
                            FileChannel channel, StorageMetrics metrics) {
        this.metrics = metrics;
        this.id = id;
        this.path = path;
        this.fsync = fsync;
        this.maxFileBytes = maxFileBytes;
        this.checkpointInterval = checkpointInterval;
        this.channel = channel;
    }

    // ── Creation / opening ─────────────────────────────────────────────────────

    /** Creates a new, empty small-object blob file (fails if it exists). */
    public static void create(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer header = encodeHeader(System.currentTimeMillis());
            FileChannelWrites.writeFully(fc, header, 0);
            fc.force(true);
        }
    }

    /** Opens an existing blob and runs recovery. */
    public static SmallObjectBlob open(String id, Path path, boolean fsync, long maxFileBytes,
                                       long checkpointIntervalBytes) throws IOException {
        return open(id, path, fsync, maxFileBytes, checkpointIntervalBytes, StorageMetrics.NOOP);
    }

    /** As above; append lock waits, IO latencies and CRC failures are reported to {@code metrics}. */
    public static SmallObjectBlob open(String id, Path path, boolean fsync, long maxFileBytes,
                                       long checkpointIntervalBytes, StorageMetrics metrics) throws IOException {
        FileChannel fc = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            SmallObjectBlob b = new SmallObjectBlob(id, path, fsync, maxFileBytes, checkpointIntervalBytes, fc, metrics);
            b.recover();
            return b;
        } catch (IOException | RuntimeException e) {
            try { fc.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    public static SmallObjectBlob open(String id, Path path, boolean fsync, long maxFileBytes) throws IOException {
        return open(id, path, fsync, maxFileBytes, 16L << 20);
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public String id() { return id; }
    public Path path() { return path; }

    /** Offset where the next record will be written (= size of the valid log). */
    public long tail() { return tail; }

    /** False when mid-log damage was found at open: the blob serves reads but takes no more appends. */
    public boolean isWritable() { return writable; }

    /** True if a record of {@code dataLength} bytes can be appended without exceeding the size limit. */
    public boolean hasRoom(int dataLength) {
        long t = tail;
        return writable && (t == DATA_START || t + recordSpan(dataLength) <= maxFileBytes);
    }

    public Stats stats() {
        lock.lock();
        try {
            return new Stats(tail, liveRecords, deadRecords, liveBytes, deadBytes, writable);
        } finally {
            lock.unlock();
        }
    }

    // ── Append ─────────────────────────────────────────────────────────────────

    /**
     * Appends one record. Returns its location, or {@code null} if the blob has no room left (the caller must
     * pick another blob; an empty blob always accepts one record).
     */
    public Location append(byte[] data, int ownerTag) throws IOException {
        if (data.length > MAX_RECORD_DATA) {
            throw new IllegalArgumentException("Record too large for a small-object blob: " + data.length);
        }
        int dataCrc = crc(data, 0, data.length);
        int span = (int) recordSpan(data.length);
        long lockT0 = System.nanoTime();
        lock.lock();
        metrics.smallAppendLockWait(System.nanoTime() - lockT0);
        try {
            ensureOpen();
            if (!writable) {
                return null;
            }
            long at = tail;
            if (at != DATA_START && at + span > maxFileBytes) {
                return null;
            }
            byte[] rec = new byte[span];
            encodeRecordHeader(rec, 0, STATE_ACTIVE, data.length, dataCrc, nextSequence, ownerTag);
            System.arraycopy(data, 0, rec, RECORD_HEADER_SIZE, data.length);
            try {
                long w0 = System.nanoTime();
                FileChannelWrites.writeFully(channel, ByteBuffer.wrap(rec), at);
                metrics.diskWrite(StorageMetrics.Target.SMALL, System.nanoTime() - w0, rec.length);
                if (fsync) {
                    long f0 = System.nanoTime();
                    channel.force(false);
                    metrics.fsync(StorageMetrics.Target.SMALL, System.nanoTime() - f0);
                }
            } catch (IOException e) {
                try { channel.truncate(at); } catch (IOException ignored) { }
                throw e;
            }
            nextSequence++;
            liveRecords++;
            liveBytes += span;
            tail = at + span;       // publish: readers may now see the record
            if (tail - checkpointTail >= checkpointInterval) {
                writeCheckpoint();
            }
            return new Location(at, data.length, dataCrc);
        } finally {
            lock.unlock();
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    /**
     * Reads and verifies the record at {@code offset}: header CRC, state ACTIVE, length and CRC equal to the
     * values the manifest holds, and the CRC32C of the data.
     */
    public byte[] read(long offset, int length, int expectedCrc) throws IOException {
        long t = tail;
        if (offset < DATA_START || offset % ALIGNMENT != 0 || offset + RECORD_HEADER_SIZE > t) {
            throw new SmallObjectCorruptedException(id, offset, "offset outside the valid log (tail=" + t + ")");
        }
        RecordHeader h = parseRecordHeader(readFully(offset, RECORD_HEADER_SIZE), 0);
        if (h == null) {
            metrics.crcFailure(StorageMetrics.CrcKind.SMALL_RECORD);
            throw new SmallObjectCorruptedException(id, offset, "bad record header (magic/CRC/state)");
        }
        if (h.state() != STATE_ACTIVE) {
            throw new SmallObjectCorruptedException(id, offset, "record is marked DELETED");
        }
        if (h.dataLength() != length) {
            throw new SmallObjectCorruptedException(id, offset,
                    "length " + h.dataLength() + " but the manifest expects " + length);
        }
        if (h.dataCrc32c() != expectedCrc) {
            metrics.crcFailure(StorageMetrics.CrcKind.SMALL_RECORD);
            throw new SmallObjectCorruptedException(id, offset, "record CRC differs from the manifest CRC");
        }
        if (offset + h.span() > t) {
            throw new SmallObjectCorruptedException(id, offset, "record extends beyond the valid log");
        }
        byte[] data = readFully(offset + RECORD_HEADER_SIZE, length);
        if (crc(data, 0, length) != expectedCrc) {
            metrics.crcFailure(StorageMetrics.CrcKind.SMALL_RECORD);
            throw new SmallObjectCorruptedException(id, offset, "data CRC32C mismatch");
        }
        return data;
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    /** Marks the record DELETED. Returns false if it already was. Idempotent, crash-safe (one byte write). */
    public boolean markDeleted(long offset) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            if (offset < DATA_START || offset % ALIGNMENT != 0 || offset + RECORD_HEADER_SIZE > tail) {
                throw new SmallObjectCorruptedException(id, offset, "offset outside the valid log");
            }
            RecordHeader h = parseRecordHeader(readFully(offset, RECORD_HEADER_SIZE), 0);
            if (h == null) {
                throw new SmallObjectCorruptedException(id, offset, "bad record header (magic/CRC/state)");
            }
            if (h.state() == STATE_DELETED) {
                return false;
            }
            FileChannelWrites.writeFully(channel, ByteBuffer.wrap(new byte[]{STATE_DELETED}), offset + STATE_OFFSET);
            forceTimed();
            long span = h.span();
            liveRecords--;
            deadRecords++;
            liveBytes -= span;
            deadBytes += span;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ── Scrub ──────────────────────────────────────────────────────────────────

    /** Walks the whole valid log and verifies every record, data CRC included. */
    public ScrubReport scrub() throws IOException {
        Scan s = scan(DATA_START, tail, 0, true);
        return new ScrubReport(s.liveRecords + s.deadRecords, s.liveRecords, s.deadRecords,
                s.liveRecords - s.badData, s.badData + s.damagedRegions, s.problems);
    }

    // ── Close ──────────────────────────────────────────────────────────────────

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            try {
                if (writable && tail != checkpointTail) {
                    writeCheckpoint();
                }
            } finally {
                closed = true;
                channel.close();
            }
        } finally {
            lock.unlock();
        }
    }

    private void forceTimed() throws IOException {
        if (fsync) {
            long t0 = System.nanoTime();
            channel.force(false);
            metrics.fsync(StorageMetrics.Target.SMALL, System.nanoTime() - t0);
        }
    }

    // ── Checkpoint ─────────────────────────────────────────────────────────────

    /** Caller holds the lock; all records below {@code tail} were forced already. */
    private void writeCheckpoint() throws IOException {
        long gen = checkpointGeneration + 1;
        int slot = (gen % 2 == 1) ? CHECKPOINT_SLOT_A : CHECKPOINT_SLOT_B;
        FileChannelWrites.writeFully(channel, ByteBuffer.wrap(encodeCheckpoint(tail, gen)), slot);
        forceTimed();
        checkpointGeneration = gen;
        checkpointTail = tail;
    }

    // ── Recovery ───────────────────────────────────────────────────────────────

    private void recover() throws IOException {
        byte[] header = readUpTo(0, HEADER_SIZE);
        verifyHeader(header, id);
        Checkpoint a = decodeCheckpoint(header, CHECKPOINT_SLOT_A);
        Checkpoint b = decodeCheckpoint(header, CHECKPOINT_SLOT_B);
        Checkpoint best = a == null ? b : (b == null ? a : (a.generation() >= b.generation() ? a : b));
        long fileSize = channel.size();
        long cp = DATA_START;
        if (best != null) {
            checkpointGeneration = best.generation();
            if (best.tail() >= DATA_START && best.tail() % ALIGNMENT == 0 && best.tail() <= fileSize) {
                cp = best.tail();
            } else {
                log.warn("Small blob {}: ignoring implausible checkpoint tail {}", id, best.tail());
            }
        }
        checkpointTail = cp;

        Scan s = scan(DATA_START, fileSize, cp, false);
        liveRecords = s.liveRecords;
        deadRecords = s.deadRecords;
        liveBytes = s.liveBytes;
        deadBytes = s.deadBytes;
        nextSequence = s.maxSequence + 1;
        writable = s.damagedRegions == 0;
        tail = s.tail;
        if (!writable) {
            log.error("Small blob {} has {} damaged region(s) inside the log; it is read-only. Problems: {}",
                    id, s.damagedRegions, s.problems);
        } else if (s.tail < fileSize) {
            log.warn("Small blob {}: dropping torn tail of {} byte(s) at offset {}", id, fileSize - s.tail, s.tail);
            channel.truncate(s.tail);
            if (fsync) {
                channel.force(true);
            }
        }
        if (s.badData > 0) {
            log.error("Small blob {}: {} record(s) fail their data CRC (will be reported on read): {}",
                    id, s.badData, s.problems);
        }
    }

    private static final class Scan {
        long tail;
        long liveRecords, deadRecords, liveBytes, deadBytes, maxSequence;
        long badData, damagedRegions;
        final List<String> problems = new ArrayList<>();

        void problem(String msg) {
            if (problems.size() < 100) {
                problems.add(msg);
            }
        }
    }

    /**
     * Walks records in [from, end). Data CRCs are verified for records starting at or after {@code verifyFrom}.
     * The result's tail is the end of the last good record, or {@code end} if damage was found in the middle.
     * In {@code scrubMode} ({@code end} is the published tail) nothing counts as a torn tail: every problem is reported.
     */
    private Scan scan(long from, long end, long verifyFrom, boolean scrubMode) throws IOException {
        Scan s = new Scan();
        long pos = from;
        while (pos < end) {
            RecordHeader h = headerAt(pos, end);
            if (h != null) {
                long span = h.span();
                boolean verify = pos >= verifyFrom && h.state() == STATE_ACTIVE;
                boolean dataOk = true;
                if (verify) {
                    byte[] data = readFully(pos + RECORD_HEADER_SIZE, h.dataLength());
                    dataOk = crc(data, 0, data.length) == h.dataCrc32c();
                }
                if (!dataOk) {
                    if (!scrubMode && pos + span >= end) {
                        // last record, data not intact: torn tail
                        break;
                    }
                    s.badData++;
                    s.problem("offset " + pos + ": data CRC32C mismatch (len=" + h.dataLength() + ")");
                }
                if (h.state() == STATE_ACTIVE) {
                    s.liveRecords++;
                    s.liveBytes += span;
                } else {
                    s.deadRecords++;
                    s.deadBytes += span;
                }
                s.maxSequence = Math.max(s.maxSequence, h.sequence());
                pos += span;
                continue;
            }
            long next = resync(pos + ALIGNMENT, end);
            if (next < 0) {
                if (scrubMode) {
                    s.damagedRegions++;
                    s.problem("offset " + pos + ": unreadable record(s) up to the end of the log");
                }
                break;                      // nothing valid after: torn tail starting at pos
            }
            s.damagedRegions++;
            s.problem("offset " + pos + ": damaged region of " + (next - pos) + " byte(s) before the next valid record");
            pos = next;
        }
        s.tail = s.damagedRegions > 0 ? end : pos;
        return s;
    }

    /** Header at {@code pos} if it is valid and the whole record fits before {@code end}; else null. */
    private RecordHeader headerAt(long pos, long end) throws IOException {
        if (pos + RECORD_HEADER_SIZE > end) {
            return null;
        }
        RecordHeader h = parseRecordHeader(readFully(pos, RECORD_HEADER_SIZE), 0);
        if (h == null || pos + h.span() > end) {
            return null;
        }
        return h;
    }

    /** Finds the next offset (8-aligned, from {@code from}) that holds a fully valid record, or -1. */
    private long resync(long from, long end) throws IOException {
        long base = from;
        while (base + RECORD_HEADER_SIZE <= end) {
            int n = (int) Math.min(RESYNC_WINDOW, end - base);
            byte[] w = readFully(base, n);
            ByteBuffer wb = ByteBuffer.wrap(w);
            for (int i = 0; i + 4 <= n; i += ALIGNMENT) {
                if (wb.getInt(i) != RECORD_MAGIC) {
                    continue;
                }
                long p = base + i;
                RecordHeader h = headerAt(p, end);
                if (h == null) {
                    continue;
                }
                if (h.state() == STATE_ACTIVE) {
                    byte[] data = readFully(p + RECORD_HEADER_SIZE, h.dataLength());
                    if (crc(data, 0, data.length) != h.dataCrc32c()) {
                        continue;
                    }
                }
                return p;
            }
            if (n < RESYNC_WINDOW) {
                break;                      // that was the last window
            }
            base += n;                      // RESYNC_WINDOW is a multiple of 8: stays aligned
        }
        return -1;
    }

    // ── IO helpers ─────────────────────────────────────────────────────────────

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new java.nio.channels.ClosedChannelException();
        }
    }

    private byte[] readFully(long pos, int len) throws IOException {
        long t0 = System.nanoTime();
        try {
            return readFully0(pos, len);
        } finally {
            metrics.diskRead(StorageMetrics.Target.SMALL, System.nanoTime() - t0, len);
        }
    }

    private byte[] readFully0(long pos, int len) throws IOException {
        byte[] out = new byte[len];
        ByteBuffer b = ByteBuffer.wrap(out);
        long p = pos;
        while (b.hasRemaining()) {
            int n = channel.read(b, p);
            if (n < 0) {
                throw new IOException("Unexpected end of file in " + path + " at offset " + p);
            }
            p += n;
        }
        return out;
    }

    /** Reads up to {@code len} bytes; the result is zero-padded if the file is shorter. */
    private byte[] readUpTo(long pos, int len) throws IOException {
        byte[] out = new byte[len];
        ByteBuffer b = ByteBuffer.wrap(out);
        long p = pos;
        while (b.hasRemaining()) {
            int n = channel.read(b, p);
            if (n < 0) {
                break;
            }
            p += n;
        }
        if (p - pos < len) {
            throw new InvalidBlobHeaderException("Small-object blob " + id + ": file shorter than the header");
        }
        return out;
    }
}
