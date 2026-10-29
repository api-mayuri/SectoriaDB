package org.example.sectoriadb.maintenance;

import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the lifecycle rules of every bucket (docs/architecture/versioning.md): removes noncurrent versions past their
 * NoncurrentDays and delete markers without versions, in batches of {@value #BATCH} per transaction. What it removes goes
 * the usual way (tombstone, collector, grace period).
 */
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
    static final int BATCH = 1000;
    /** Batches per bucket and pass: a backlog is worked off over several passes, other work gets its turn. */
    static final int MAX_BATCHES = 50;

    private final PoolRepository pools;
    private final ManifestRepository manifests;

    public LifecycleService(PoolRepository pools, ManifestRepository manifests) {
        this.pools = pools;
        this.manifests = manifests;
    }

    /** One pass over all buckets with lifecycle rules; returns how many versions and markers were removed. */
    public int run(long nowMillis) {
        int total = 0;
        for (PoolEntity p : pools.findAll()) {
            if (p.getLifecycle() == null || p.getLifecycle().isEmpty()) continue;
            int removed = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int n = manifests.applyLifecycle(p.getName(), p.getLifecycle(), nowMillis, BATCH);
                removed += n;
                if (n < BATCH) break;
            }
            if (removed > 0) log.info("Lifecycle of bucket {}: removed {} noncurrent version(s) and delete marker(s)", p.getName(), removed);
            total += removed;
        }
        return total;
    }
}
