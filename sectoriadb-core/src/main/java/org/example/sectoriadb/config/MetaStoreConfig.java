package org.example.sectoriadb.config;

import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.repository.metastore.MetaStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The embedded metadata store: one file, {@code ${sectoriadb.meta-dir}/sectoria.db}, durable commits according to
 * {@code sectoriadb.fsync}, closed (and its file lock released) when the Spring context closes.
 *
 * <p>Group commit limits (docs/architecture/09-multi-blob-pool.md): {@code sectoriadb.metastore.group.max-batch-size},
 * {@code max-batch-pages}, {@code max-wait-micros}, {@code queue-capacity}. A failed commit makes the store read-only
 * until it recovers in place (doc 10, part B); {@code sectoriadb.metastore.recovery-backoff} is the pause between attempts.
 *
 * <p>The bean is {@link Lazy} and the repositories reach it through {@link MetaStoreProvider}. In server mode the store
 * is opened when the context starts, so a second server on the same directory fails at startup; the admin CLI
 * ({@code sectoriadb.metastore.lazy=true}) opens it only if a command really needs it, so credential commands keep
 * working next to a running server (the file is single-process, see docs/architecture/06-btree-metastore.md).
 */
@Configuration
public class MetaStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(MetaStoreConfig.class);

    public static final String FILE_NAME = "sectoria.db";

    @Bean(destroyMethod = "close")
    @Lazy
    public MetaStore metaStore(StorageProperties props, StorageMetrics metrics,
                               @Value("${sectoriadb.metastore.group.max-batch-size:256}") int maxBatchSize,
                               @Value("${sectoriadb.metastore.group.max-batch-pages:1024}") int maxBatchPages,
                               @Value("${sectoriadb.metastore.group.max-wait-micros:0}") long maxWaitMicros,
                               @Value("${sectoriadb.metastore.group.queue-capacity:4096}") int queueCapacity,
                               @Value("${sectoriadb.metastore.recovery-backoff:1s}") java.time.Duration recoveryBackoff) {
        Path dir = Path.of(props.getMetaDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path file = dir.resolve(FILE_NAME);
        try {
            MetaStore store = MetaStore.open(file, MetaStoreOptions.defaults().fsync(props.isFsync()).metrics(metrics)
                    .groupCommit(maxBatchSize, maxBatchPages, maxWaitMicros, queueCapacity)
                    .recoveryBackoff(recoveryBackoff.toMillis()));
            log.info("Metadata store opened: {} (fsync={})", file.toAbsolutePath(), props.isFsync());
            return store;
        } catch (RuntimeException e) {
            if (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("already open")) {
                throw new IllegalStateException("The metadata store " + file.toAbsolutePath()
                        + " is in use by another SectoriaDB process (is the server running?). It can be opened by one "
                        + "process only: use the S3 API or the server's interactive shell instead.", e);
            }
            throw e;
        }
    }

    @Bean
    public MetaStoreProvider metaStoreProvider(ObjectProvider<MetaStore> store) {
        return store::getObject;
    }

    /** Fail fast: open the store while the context starts (server mode). */
    @Bean
    public SmartInitializingSingleton metaStoreStarter(MetaStoreProvider provider,
                                                       @Value("${sectoriadb.metastore.lazy:false}") boolean lazy) {
        return () -> {
            if (!lazy) provider.get();
        };
    }
}
