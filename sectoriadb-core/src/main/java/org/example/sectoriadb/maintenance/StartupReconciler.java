package org.example.sectoriadb.maintenance;

import org.example.sectoriadb.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Cleans up what a crash can leave behind, once, when the application context is up and before requests are served: the
 * half-built new blob of a resize that never committed, the old blob of a resize that committed but did not get to delete
 * its file. After a restart no reader and no resize exists, so a {@code blob_*.raw} that no blob record names is garbage
 * (doc 10, part B). Skipped for the lazy admin CLI, which must not open the metadata store of a running server.
 */
public class StartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final BlobFileReaper reaper;
    private final StorageProperties props;
    private final boolean enabled;

    public StartupReconciler(BlobFileReaper reaper, StorageProperties props) {
        this.reaper = reaper;
        this.props = props;
        this.enabled = props.isReconcileOnStart() && !props.getMetastore().isLazy();
    }

    /** Runs the reconciliation (when enabled); called once by the composition root after all beans exist. */
    public void run() {
        if (!props.getMetastore().isLazy()) removeLegacyMultipartDirectory();
        if (!enabled) return;
        try {
            int n = reaper.reconcileAtStartup();
            if (n > 0) log.warn("Startup reconciliation removed {} unregistered blob file(s)", n);
        } catch (RuntimeException e) {
            log.warn("Startup reconciliation of blob files failed (will be retried by the collector): {}", e.toString());
        }
    }

    /**
     * Before stage 13 multipart uploads lived in files under {@code <data-dir>/.multipart}. They are not read any more
     * (the owner decided against a migration): uploads that were in flight at the upgrade are dropped, their directory is
     * deleted once, with a log line saying how many.
     */
    void removeLegacyMultipartDirectory() {
        Path dir = Path.of(props.getDataDir()).resolve(".multipart");
        if (!Files.isDirectory(dir)) return;
        int uploads = 0;
        try (var entries = Files.list(dir)) {
            uploads = (int) entries.filter(Files::isDirectory).count();
        } catch (IOException ignored) {
            // only used for the log line
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            log.warn("Removed the legacy multipart directory {} ({} upload(s) that were in flight before the upgrade are dropped;"
                    + " multipart state now lives in the metadata store)", dir, uploads);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not remove the legacy multipart directory {}: {}", dir, e.toString());
        }
    }
}
