package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.storage.multipart.MultipartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Schedules the expiry sweep of multipart uploads (the transactions are {@link MultipartService#cleanup}): uploads without
 * activity for {@code sectoriadb.multipart.stale-after} are aborted and their parts released to the garbage collector;
 * COMPLETED and ABORTED records are removed {@code completed-retention} after they were written.
 */
@Component
public class MultipartCleanup {

    private static final Logger log = LoggerFactory.getLogger(MultipartCleanup.class);

    private final MultipartService multipart;
    private final Duration staleAfter;
    private final Duration completedRetention;

    public MultipartCleanup(MultipartService multipart,
                            @Value("${sectoriadb.multipart.stale-after:7d}") Duration staleAfter,
                            @Value("${sectoriadb.multipart.completed-retention:24h}") Duration completedRetention) {
        this.multipart = multipart;
        this.staleAfter = staleAfter;
        this.completedRetention = completedRetention;
    }

    @Scheduled(fixedDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}",
               initialDelayString = "${sectoriadb.multipart.cleanup-interval-ms:3600000}")
    public void cleanupStaledUploads() {
        MultipartRepository.CleanupReport r = multipart.cleanup(staleAfter, completedRetention);
        if (r.expired() > 0 || r.recordsRemoved() > 0 || r.failed() > 0) {
            log.info("Multipart cleanup: {} upload(s) expired ({} part(s) released), {} record(s) removed, {} failed",
                    r.expired(), r.partsRetired(), r.recordsRemoved(), r.failed());
        }
    }
}
