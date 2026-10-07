package org.example.sectoriadb.service;

import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Removes {@code blob_*.raw} files that nobody needs any more (doc 10, part B).
 *
 * <ul>
 *   <li><b>Replaced files.</b> After a resize commits, the old file is kept for the grace period like the old file of a
 *       small-object compaction ({@link #scheduleDeletion}); the collector's pass deletes it when it is due
 *       ({@link #releaseDue}). No reader needs it for long: reads of the replaced blob are redirected to the new one and a
 *       thread that still holds the old table keeps its (unlinked but open) file until it lets go.</li>
 *   <li><b>Leftovers.</b> A crash during a resize leaves the half-built new file (never registered), a crash after the
 *       commit leaves the old file (no longer registered). {@link #deleteUnregistered} removes files that no blob record
 *       names, are not being built ({@link #beginBuilding}), not resident in the cache and not waiting in the release
 *       queue; at startup ({@link #reconcileAtStartup}) there are no readers and no resize in flight, so every unregistered
 *       file goes at once, otherwise only files older than {@code max(minAge, 60 s)}.</li>
 * </ul>
 */
@Service
public class BlobFileReaper {

    private static final Logger log = LoggerFactory.getLogger(BlobFileReaper.class);
    private static final long MIN_FILE_AGE_MILLIS = 60_000;

    private record Pending(Path path, long dueMillis) {
    }

    private final BlobFileRepository blobRepo;
    private final PoolRepository poolRepo;
    private final HashTableCache cache;
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final Set<Path> building = ConcurrentHashMap.newKeySet();

    @Autowired
    public BlobFileReaper(BlobFileRepository blobRepo, PoolRepository poolRepo, HashTableCache cache) {
        this.blobRepo = blobRepo;
        this.poolRepo = poolRepo;
        this.cache = cache;
    }

    /** Queues a file (of a blob that is no longer registered) for deletion at {@code dueMillis} (epoch milliseconds). */
    public void scheduleDeletion(Path file, long dueMillis) {
        pending.add(new Pending(file.toAbsolutePath().normalize(), dueMillis));
    }

    /** Files waiting for their grace period. */
    public int pending() {
        return pending.size();
    }

    /** Marks a file that is being built (a resize's new blob): the cleaners leave it alone. Close the token when it is registered or abandoned. */
    public AutoCloseable beginBuilding(Path file) {
        Path p = file.toAbsolutePath().normalize();
        building.add(p);
        return () -> building.remove(p);
    }

    /**
     * Deletes the queued files that are due ({@code nowMillis} is compared with the due time; pass
     * {@link Long#MAX_VALUE} to release all of them, as {@code gc run --grace 0s} does). A file that cannot be deleted
     * is queued again a minute later. Returns the number deleted.
     */
    public int releaseDue(long nowMillis) {
        int n = 0;
        for (Pending f : List.copyOf(pending)) {
            if (f.dueMillis() > nowMillis) continue;
            if (!pending.remove(f)) continue;
            try {
                if (Files.deleteIfExists(f.path())) {
                    n++;
                    log.info("Deleted the replaced blob file {}", f.path());
                }
            } catch (IOException e) {
                log.warn("Could not delete the replaced blob file {}: {}", f.path(), e.getMessage());
                pending.add(new Pending(f.path(), (nowMillis == Long.MAX_VALUE ? System.currentTimeMillis() : nowMillis) + 60_000));
            }
        }
        return n;
    }

    /** Startup: nothing runs yet, so a {@code blob_*.raw} that no record names is garbage whatever its age. */
    public int reconcileAtStartup() {
        return deleteUnregistered(0, true);
    }

    /** Periodic cleanup of leftovers older than {@code max(minAgeMillis, 60 s)}. */
    public int deleteUnregistered(long minAgeMillis) {
        return deleteUnregistered(minAgeMillis, false);
    }

    private int deleteUnregistered(long minAgeMillis, boolean startup) {
        int n = 0;
        Set<Path> keep = new HashSet<>(building);
        for (Pending f : pending) keep.add(f.path());
        long cutoff = startup ? Long.MAX_VALUE : System.currentTimeMillis() - Math.max(minAgeMillis, MIN_FILE_AGE_MILLIS);
        for (PoolEntity pool : poolRepo.findAll()) {
            Set<Path> registered = registeredPaths(pool);
            Path dir = Path.of(pool.getBasePath());
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "blob_*.raw")) {
                for (Path f : files) {
                    Path p = f.toAbsolutePath().normalize();
                    if (registered.contains(p) || keep.contains(p) || building.contains(p)) continue;
                    if (!startup && Files.getLastModifiedTime(f).toMillis() > cutoff) continue;
                    if (isResident(p)) continue;
                    // re-check the registry: a blob registered after the listing must survive
                    if (registeredPaths(pool).contains(p) || building.contains(p)) continue;
                    if (Files.deleteIfExists(f)) {
                        n++;
                        log.warn("Deleted the unregistered blob file {} (leftover of a resize that did not finish)", f);
                    }
                }
            } catch (IOException e) {
                log.warn("Could not scan {} for unregistered blob files: {}", dir, e.getMessage());
            }
        }
        return n;
    }

    private Set<Path> registeredPaths(PoolEntity pool) {
        Set<Path> registered = new HashSet<>();
        for (BlobFileEntity b : blobRepo.findByPoolId(pool.getId())) {
            if (b.getKind() == BlobKind.CUCKOO) registered.add(Path.of(b.getFilePath()).toAbsolutePath().normalize());
        }
        return registered;
    }

    private boolean isResident(Path p) {
        for (var t : cache.residentTables()) {
            if (t.getBlobFile().path().toAbsolutePath().normalize().equals(p)) return true;
        }
        return false;
    }
}
