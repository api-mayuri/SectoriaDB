package org.example.scheduler;

import org.example.config.StorageProperties;
import org.example.model.BlobFileEntity;
import org.example.repository.BlobFileRepository;
import org.example.service.HashTableCache;
import org.example.service.ResizeService;
import org.example.service.impl.CuckooHashTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Background task that monitors fill ratios of all blob files and triggers
 * an automatic resize when a blob exceeds the configured threshold.
 *
 * The check interval is read once at startup from
 * {@code sectoriadb.auto-resize.check-interval-ms} (default 60 s).
 */
@Component
public class AutoResizeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoResizeScheduler.class);

    private final BlobFileRepository blobRepo;
    private final HashTableCache cache;
    private final ResizeService resizeService;
    private final StorageProperties props;

    public AutoResizeScheduler(BlobFileRepository blobRepo, HashTableCache cache,
                                ResizeService resizeService, StorageProperties props) {
        this.blobRepo      = blobRepo;
        this.cache         = cache;
        this.resizeService = resizeService;
        this.props         = props;
    }

    @Scheduled(fixedDelayString = "${sectoriadb.auto-resize.check-interval-ms:60000}")
    public void checkFillRatios() {
        StorageProperties.AutoResize ar = props.getAutoResize();
        if (!ar.isEnabled()) return;

        List<BlobFileEntity> blobs = blobRepo.findAll();
        if (blobs.isEmpty()) return;

        log.debug("Auto-resize check: {} blob file(s), threshold={}%", blobs.size(), ar.getThresholdPercent());

        for (BlobFileEntity blob : blobs) {
            try {
                CuckooHashTable.FillStats stats = cache.get(blob).getFillStats();
                double fill = stats.fillPercent();
                log.debug("Blob {} fill={}%", blob.getId(), String.format("%.1f", fill));

                if (fill >= ar.getThresholdPercent()) {
                    int newBuckets = resizeService.computeExpandedBuckets(blob.getNumBuckets());
                    log.warn("AUTO-RESIZE triggered: blob={} fill={}% >= {}% → expanding to {} buckets",
                            blob.getId(), String.format("%.1f", fill), ar.getThresholdPercent(), newBuckets);
                    System.out.printf("%n[AUTO-RESIZE] Blob %s fill=%.1f%% — expanding %d → %d buckets%n",
                            blob.getId(), fill, blob.getNumBuckets(), newBuckets);
                    resizeService.resizeBlobFile(blob.getId(), newBuckets);
                    System.out.printf("[AUTO-RESIZE] Done.%n");
                }
            } catch (IOException e) {
                log.error("Auto-resize check failed for blob {}: {}", blob.getId(), e.getMessage());
            }
        }
    }
}
