package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The embedded metadata store: one file, {@code ${sectoriadb.meta-dir}/sectoria.db}, durable commits according to
 * {@code sectoriadb.fsync}, closed (and its file lock released) by {@link #close()}.
 *
 * <p>Group commit limits (docs/architecture/metastore.md): {@code sectoriadb.metastore.group.*}. A failed
 * commit makes the store read-only until it recovers in place; {@code sectoriadb.metastore.recovery-backoff}
 * is the pause between attempts.
 *
 * <p>The repositories reach the store through {@link MetaStoreProvider} on every operation, which lets it open lazily:
 * in server mode the composition root opens it when the context starts, so a second server on the same directory fails
 * at startup; the admin CLI ({@code sectoriadb.metastore.lazy=true}) opens it only if a command really needs it, so
 * credential commands keep working next to a running server (the file is single-process, see
 * docs/architecture/metastore.md).
 */
public final class MetaStoreHolder implements MetaStoreProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetaStoreHolder.class);

    public static final String FILE_NAME = "sectoria.db";

    private final StorageProperties props;
    private final StorageMetrics metrics;
    private MetaStore store;
    private boolean closed;

    public MetaStoreHolder(StorageProperties props, StorageMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public synchronized MetaStore get() {
        if (closed) throw new IllegalStateException("The metadata store holder is closed");
        if (store == null) store = open();
        return store;
    }

    private MetaStore open() {
        Path dir = Path.of(props.getMetaDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path file = dir.resolve(FILE_NAME);
        StorageProperties.Group g = props.getMetastore().getGroup();
        try {
            MetaStore opened = MetaStore.open(file, MetaStoreOptions.defaults().fsync(props.isFsync())
                    .listener(new StorageMetricsMetaStoreListener(metrics))
                    .groupCommit(g.getMaxBatchSize(), g.getMaxBatchPages(), g.getMaxWaitMicros(), g.getQueueCapacity())
                    .recoveryBackoff(props.getMetastore().getRecoveryBackoff().toMillis()));
            log.info("Metadata store opened: {} (fsync={})", file.toAbsolutePath(), props.isFsync());
            return opened;
        } catch (RuntimeException e) {
            if (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("already open")) {
                throw new IllegalStateException("The metadata store " + file.toAbsolutePath()
                        + " is in use by another SectoriaDB process (is the server running?). It can be opened by one "
                        + "process only: use the S3 API or the server's interactive shell instead.", e);
            }
            throw e;
        }
    }

    /** Closes the store if it was ever opened. */
    @Override
    public synchronized void close() {
        closed = true;
        if (store != null) {
            store.close();
            store = null;
        }
    }
}
