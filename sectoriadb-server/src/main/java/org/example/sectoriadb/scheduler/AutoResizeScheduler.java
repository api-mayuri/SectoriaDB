package org.example.sectoriadb.scheduler;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Background task that grows pools: when the aggregate fill of a pool's cuckoo blobs reaches
 * {@code sectoriadb.pool.grow-threshold-percent} a new blob is added (the writers do the same check before every
 * chunked upload, this is the backstop for idle pools). Existing blobs are never rewritten; the manual shell command
 * {@code resize} remains for expanding one blob in place.
 *
 * The check interval is read once at startup from {@code sectoriadb.auto-resize.check-interval-ms} (default 60 s).
 */
@Component
public class AutoResizeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoResizeScheduler.class);

    private final PoolService poolService;
    private final BlobService blobService;
    private final StorageProperties props;
    private final StorageMetrics metrics;

    public AutoResizeScheduler(PoolService poolService, BlobService blobService,
                               StorageProperties props, StorageMetrics metrics) {
        this.poolService = poolService;
        this.blobService = blobService;
        this.props       = props;
        this.metrics     = metrics;
    }

    @Scheduled(fixedDelayString = "${sectoriadb.auto-resize.check-interval-ms:60000}")
    public void checkFillRatios() {
        if (!props.getAutoResize().isEnabled()) return;
        metrics.autoResizeRun();
        for (PoolEntity pool : poolService.listAll()) {
            try {
                BlobService.PoolFill fill = blobService.poolFill(pool);
                Optional<BlobFileEntity> grown = blobService.growIfOverThreshold(pool);
                if (grown.isPresent()) {
                    log.warn("POOL-GROW: pool={} fill={}% >= {}% -> added blob {} ({} blobs now)", pool.getName(),
                            String.format("%.1f", fill.fillPercent()), props.getPool().getGrowThresholdPercent(),
                            grown.get().getId(), fill.blobs() + 1);
                } else if (fill.blobs() >= props.getPool().getMaxBlobs()
                        && fill.fillPercent() >= props.getPool().getGrowThresholdPercent()) {
                    log.warn("Pool {} is {}% full and already has the maximum of {} blobs: raise sectoriadb.pool.max-blobs"
                            + " or expand a blob with the 'resize' command", pool.getName(),
                            String.format("%.1f", fill.fillPercent()), fill.blobs());
                }
            } catch (IOException | RuntimeException e) {
                log.error("Pool growth check failed for pool {}: {}", pool.getName(), e.toString());
            }
        }
    }
}
