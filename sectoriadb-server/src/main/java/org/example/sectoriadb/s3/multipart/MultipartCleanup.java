package org.example.sectoriadb.s3.multipart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import static org.example.sectoriadb.s3.multipart.MultipartUploadStore.State;

/**
 * Removes abandoned uploads (no activity for {@code sectoriadb.multipart.stale-after}) and expired COMPLETED tombstones
 * ({@code completed-retention}); one bad entry never stops the sweep.
 */
@Component
public class MultipartCleanup {

    private static final Logger log = LoggerFactory.getLogger(MultipartCleanup.class);

    /** A Complete that has been "running" this long died with the server: the upload is usable again. */
    private static final long COMPLETING_TIMEOUT_MS = 3_600_000L;

    private final MultipartUploadStore store;
    private final long staleAfterMs;
    private final long completedRetentionMs;

    public MultipartCleanup(MultipartUploadStore store,
                            @Value("${sectoriadb.multipart.stale-after:7d}") java.time.Duration staleAfter,
                            @Value("${sectoriadb.multipart.completed-retention:24h}") java.time.Duration completedRetention) {
        this.store = store;
        this.staleAfterMs = staleAfter.toMillis();
        this.completedRetentionMs = completedRetention.toMillis();
    }

    @Scheduled(fixedDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}",
               initialDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}")
    public void cleanupStaledUploads() throws IOException {
        Path root = store.existingRoot();
        if (root == null) return;

        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path uploadPath : stream) {
                if (!Files.isDirectory(uploadPath)) continue;
                String uploadId = uploadPath.getFileName().toString();
                ReentrantLock lock = store.lockOf(uploadId);
                if (!lock.tryLock()) continue;   // busy: next time
                try {
                    sweep(new MultipartUploadStore.Upload(uploadId, uploadPath), now);
                } catch (Exception e) {
                    log.warn("Skipping multipart upload {} during cleanup: {}", uploadId, e.toString());
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private void sweep(MultipartUploadStore.Upload upload, long now) throws IOException {
        Properties state = store.readState(upload);
        State st = MultipartUploadStore.stateIn(state);
        long lastActivity = lastActivity(upload.dir());
        if (st == State.COMPLETED) {
            long completedAt = parseLong(state.getProperty("completedAt"), lastActivity);
            if (now - completedAt > completedRetentionMs) {
                log.info("Removing the record of completed upload {}", upload.id());
                MultipartUploadStore.deleteTree(upload.dir());
                store.forget(upload.id());
            }
            return;
        }
        if (st == State.COMPLETING && now - lastActivity > COMPLETING_TIMEOUT_MS) {
            // the server died during Complete: the upload is usable again
            store.writeState(upload, State.OPEN);
            return;
        }
        if (st == State.COMPLETING) return;
        if (now - lastActivity > staleAfterMs) {
            log.info("Cleaning up stale upload: {} (idle {} ms)", upload.id(), now - lastActivity);
            MultipartUploadStore.deleteTree(upload.dir());
            store.forget(upload.id());
        }
    }

    /** Last activity = newest of the initiation time and the modification times of the directory and its files. */
    private static long lastActivity(Path uploadPath) throws IOException {
        long newest = Files.getLastModifiedTime(uploadPath).toMillis();
        try (var files = Files.list(uploadPath)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
            }
        }
        Properties meta = new Properties();
        try (InputStream is = Files.newInputStream(uploadPath.resolve(MultipartUploadStore.META_FILE))) {
            meta.load(is);
            newest = Math.max(newest, parseLong(meta.getProperty("initiatedAt"), 0));
        } catch (IOException ignored) {
            // an upload without a readable meta file ages by the file times alone
        }
        return newest;
    }

    private static long parseLong(String v, long fallback) {
        try {
            return v == null ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;   // a corrupt number must not abort the sweep
        }
    }
}
