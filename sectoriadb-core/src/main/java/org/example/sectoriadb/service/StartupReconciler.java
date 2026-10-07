package org.example.sectoriadb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cleans up what a crash can leave behind, once, when the application context is up and before requests are served: the
 * half-built new blob of a resize that never committed, the old blob of a resize that committed but did not get to delete
 * its file. After a restart no reader and no resize exists, so a {@code blob_*.raw} that no blob record names is garbage
 * (doc 10, part B). Skipped for the lazy admin CLI, which must not open the metadata store of a running server.
 */
@Component
public class StartupReconciler implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final BlobFileReaper reaper;
    private final boolean enabled;

    @Autowired
    public StartupReconciler(BlobFileReaper reaper,
                             @Value("${sectoriadb.metastore.lazy:false}") boolean lazyMetaStore,
                             @Value("${sectoriadb.reconcile-on-start:true}") boolean reconcile) {
        this.reaper = reaper;
        this.enabled = reconcile && !lazyMetaStore;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) return;
        try {
            int n = reaper.reconcileAtStartup();
            if (n > 0) log.warn("Startup reconciliation removed {} unregistered blob file(s)", n);
        } catch (RuntimeException e) {
            log.warn("Startup reconciliation of blob files failed (will be retried by the collector): {}", e.toString());
        }
    }
}
