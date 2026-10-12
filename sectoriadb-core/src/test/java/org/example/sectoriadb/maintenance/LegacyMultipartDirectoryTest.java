package org.example.sectoriadb.maintenance;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The old file-based multipart directory is not read any more; startup removes it. */
class LegacyMultipartDirectoryTest {

    @TempDir
    Path root;

    @Test
    void theLegacyDirectoryIsRemovedAtStartupWithItsUploads() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            Path legacy = Path.of(rig.props.getDataDir()).resolve(".multipart");
            Path upload = Files.createDirectories(legacy.resolve("11111111-2222-3333-4444-555555555555"));
            Files.writeString(upload.resolve("meta.properties"), "bucket=b\nkey=k\n");
            Files.write(upload.resolve("part-00001"), new byte[1000]);
            Files.createDirectories(legacy.resolve("66666666-2222-3333-4444-555555555555"));

            new StartupReconciler(rig.reaper, rig.props).run();
            assertFalse(Files.exists(legacy));
            new StartupReconciler(rig.reaper, rig.props).run();   // nothing to do the second time
        }
    }

    @Test
    void theLazyCliDoesNotTouchTheDataDirectory() throws Exception {
        try (StorageRig rig = new StorageRig(root)) {
            Path legacy = Files.createDirectories(Path.of(rig.props.getDataDir()).resolve(".multipart"));
            StorageProperties lazy = rig.props;
            lazy.getMetastore().setLazy(true);
            new StartupReconciler(rig.reaper, lazy).run();
            assertTrue(Files.exists(legacy), "the admin CLI must not delete anything under a running server");
        }
    }
}
