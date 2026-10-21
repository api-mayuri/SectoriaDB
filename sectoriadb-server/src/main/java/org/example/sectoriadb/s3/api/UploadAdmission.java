package org.example.sectoriadb.s3.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.s3.error.S3Exception;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * Disk admission for everything that is spooled before it is stored (PutObject, UploadPart, CopyObject, UploadPartCopy).
 *
 * <ul>
 *   <li>In-flight reservations: the declared size is added to a process-wide counter when the request is admitted and
 *   removed when it ends (this class is also the filter that releases it, on every exit path). Admission compares
 *   {@code free - reserved - declared} with the floor, so N concurrent uploads that each fit alone cannot overbook the
 *   volume (the earlier per-request check let 64 x 5 GiB through).</li>
 *   <li>Free-space floor {@code sectoriadb.storage.min-free-bytes} (and at least the upload reserve): a write that would
 *   leave less than that on the spool, metadata or data volume is refused with {@code 507 InsufficientStorage} before
 *   the volume is full, because a full volume fails metastore commits and stops every write, deletes included.
 *   When only the other in-flight uploads stand in the way it is {@code 503 SlowDown} (retry later).</li>
 *   <li>Heap: staging an upload keeps one entry per chunk (key, location, refcount bookkeeping) until it commits, about
 *   250 bytes per chunk, i.e. roughly 80 MB for a 5 GiB object of unique data. The estimated heap of the uploads in
 *   flight is a second counter against a budget of {@code sectoriadb.s3.limits.upload-heap-fraction} of the maximum
 *   heap; above it a new upload gets {@code 503 SlowDown} (the first one is always admitted), so a swarm of 5 GiB uploads
 *   cannot push the JVM into {@code OutOfMemoryError}.</li>
 * </ul>
 * Deletes are never refused here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class UploadAdmission extends OncePerRequestFilter {

    private static final String HELD = UploadAdmission.class.getName() + ".held";

    /** Heap held while a chunk of an upload is staged, per chunk (audit estimate: 5 GiB of 16 KiB chunks is about 80 MB). */
    static final long HEAP_BYTES_PER_CHUNK = 256;

    private final AtomicLong reserved = new AtomicLong();
    private final AtomicLong heapReserved = new AtomicLong();
    private final long chunkSize;
    private final long heapBudget;
    private final Path spool;
    private final List<Path> volumes;
    private final long floor;
    private final ToLongFunction<Path> usableSpace;

    @org.springframework.beans.factory.annotation.Autowired
    public UploadAdmission(StorageProperties props,
                           @Value("${sectoriadb.s3.limits.upload-free-space-reserve-bytes:67108864}") long reserve,
                           @Value("${sectoriadb.storage.min-free-bytes:1073741824}") long minFree,
                           @Value("${sectoriadb.s3.limits.upload-heap-fraction:0.25}") double heapFraction) {
        this(props.uploadTmpPath(), List.of(Path.of(props.getMetaDir()), Path.of(props.getDataDir())),
                Math.max(reserve, minFree), UploadAdmission::usable, Math.max(1, props.getDefaultChunkSize()),
                heapFraction <= 0 ? Long.MAX_VALUE : (long) (Runtime.getRuntime().maxMemory() * Math.min(heapFraction, 0.9)));
    }

    /**
     * @param floor      free bytes that must stay on every volume after the admitted bytes are written
     * @param chunkSize  chunk size of new buckets, to estimate the staging heap of an upload
     * @param heapBudget bytes of heap that the staging of all uploads in flight may use together
     */
    UploadAdmission(Path spool, List<Path> volumes, long floor, ToLongFunction<Path> usableSpace,
                    long chunkSize, long heapBudget) {
        this.chunkSize = chunkSize;
        this.heapBudget = heapBudget;
        this.spool = spool;
        this.volumes = volumes;
        this.floor = floor;
        this.usableSpace = usableSpace;
    }

    /** Bytes currently promised to admitted requests (a gauge for tests and diagnostics). */
    public long reservedBytes() {
        return reserved.get();
    }

    /** Estimated staging heap of the admitted requests. */
    public long reservedHeapBytes() {
        return heapReserved.get();
    }

    /** Heap held while {@code bytes} of payload are staged. */
    long heapEstimate(long bytes) {
        return (bytes / chunkSize + 1) * HEAP_BYTES_PER_CHUNK;
    }

    private static final class Held {
        final AtomicLong disk = new AtomicLong();
        final AtomicLong heap = new AtomicLong();
    }

    /**
     * Admits {@code bytes} of spool for this request, or refuses with 507 (the volume cannot take them) / 503 (other
     * uploads in flight hold the space). The reservation lasts until the request ends.
     */
    public void admit(HttpServletRequest request, long bytes) {
        for (Path volume : volumes) {
            if (usableSpace.applyAsLong(volume) < floor) throw insufficient();
        }
        while (true) {
            long free = usableSpace.applyAsLong(spool);
            long promised = reserved.get();
            if (free - bytes < floor) throw insufficient();
            if (free - promised - bytes < floor) {
                throw new S3Exception(HttpStatus.SERVICE_UNAVAILABLE, "SlowDown",
                        "The server does not have enough free space for this upload while other uploads are in progress. "
                        + "Please retry later.");
            }
            if (reserved.compareAndSet(promised, promised + bytes)) break;
        }
        long heap = heapEstimate(bytes);
        while (true) {
            long inFlight = heapReserved.get();
            if (inFlight > 0 && inFlight + heap > heapBudget) {
                reserved.addAndGet(-bytes);
                throw new S3Exception(HttpStatus.SERVICE_UNAVAILABLE, "SlowDown",
                        "Too many large uploads are being processed right now. Please retry later.");
            }
            if (heapReserved.compareAndSet(inFlight, inFlight + heap)) break;
        }
        Held held = (Held) request.getAttribute(HELD);
        if (held == null) {
            held = new Held();
            request.setAttribute(HELD, held);
        }
        held.disk.addAndGet(bytes);
        held.heap.addAndGet(heap);
    }

    /** The free-space floor only, for writes that spool nothing (CreateBucket, CreateMultipartUpload). */
    public void requireWritable() {
        for (Path volume : volumes) {
            if (usableSpace.applyAsLong(volume) < floor) throw insufficient();
        }
        if (usableSpace.applyAsLong(spool) < floor) throw insufficient();
    }

    private static S3Exception insufficient() {
        return new S3Exception(HttpStatus.INSUFFICIENT_STORAGE, "InsufficientStorage",
                "The server is low on free disk space and refuses writes until space is freed.");
    }

    /** FileStore lookup scans the mount table (milliseconds): resolved once per path, statvfs on the cached store is cheap. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, java.nio.file.FileStore> STORES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long usable(Path path) {
        try {
            java.nio.file.FileStore store = STORES.get(path);
            if (store == null) {
                Path p = path;
                while (p != null && !Files.exists(p)) p = p.getParent();   // a directory not created yet: its volume
                store = Files.getFileStore(p == null ? Path.of(".") : p);
                if (Files.exists(path)) STORES.put(path, store);
            }
            return store.getUsableSpace();
        } catch (IOException e) {
            STORES.remove(path);
            return Long.MAX_VALUE;   // cannot be inspected: the write itself will report a real problem
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            Object held = request.getAttribute(HELD);
            if (held instanceof Held h) {
                reserved.addAndGet(-h.disk.getAndSet(0));
                heapReserved.addAndGet(-h.heap.getAndSet(0));
            }
        }
    }
}
