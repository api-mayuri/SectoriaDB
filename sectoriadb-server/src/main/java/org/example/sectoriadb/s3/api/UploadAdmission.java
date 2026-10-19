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
 * </ul>
 * Deletes are never refused here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class UploadAdmission extends OncePerRequestFilter {

    private static final String HELD = UploadAdmission.class.getName() + ".held";

    private final AtomicLong reserved = new AtomicLong();
    private final Path spool;
    private final List<Path> volumes;
    private final long floor;
    private final ToLongFunction<Path> usableSpace;

    @org.springframework.beans.factory.annotation.Autowired
    public UploadAdmission(StorageProperties props,
                           @Value("${sectoriadb.s3.limits.upload-free-space-reserve-bytes:67108864}") long reserve,
                           @Value("${sectoriadb.storage.min-free-bytes:1073741824}") long minFree) {
        this(props.uploadTmpPath(), List.of(Path.of(props.getMetaDir()), Path.of(props.getDataDir())),
                Math.max(reserve, minFree), UploadAdmission::usable);
    }

    /** @param floor free bytes that must stay on every volume after the admitted bytes are written */
    UploadAdmission(Path spool, List<Path> volumes, long floor, ToLongFunction<Path> usableSpace) {
        this.spool = spool;
        this.volumes = volumes;
        this.floor = floor;
        this.usableSpace = usableSpace;
    }

    /** Bytes currently promised to admitted requests (a gauge for tests and diagnostics). */
    public long reservedBytes() {
        return reserved.get();
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
        AtomicLong held = (AtomicLong) request.getAttribute(HELD);
        if (held == null) {
            held = new AtomicLong();
            request.setAttribute(HELD, held);
        }
        held.addAndGet(bytes);
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

    private static long usable(Path path) {
        try {
            Path p = path;
            while (p != null && !Files.exists(p)) p = p.getParent();   // a directory not created yet: its volume
            return Files.getFileStore(p == null ? Path.of(".") : p).getUsableSpace();
        } catch (IOException e) {
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
            if (held instanceof AtomicLong h) {
                reserved.addAndGet(-h.getAndSet(0));
            }
        }
    }
}
