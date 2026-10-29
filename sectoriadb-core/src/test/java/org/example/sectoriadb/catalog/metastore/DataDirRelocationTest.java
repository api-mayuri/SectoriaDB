package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.BlobKind;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A store opened from another data directory than it was written with (restored backup, volume mounted elsewhere). */
class DataDirRelocationTest {

    @TempDir
    Path root;

    MetaStore open() {
        return MetaStore.open(root.resolve("sectoria.db"), MetaStoreOptions.defaults().fsync(false));
    }

    static PoolEntity pool(String id, String name, Path base) {
        PoolEntity p = new PoolEntity();
        p.setId(id);
        p.setName(name);
        p.setBasePath(base.toString());
        return p;
    }

    static BlobFileEntity blob(String id, String poolId, Path file) {
        BlobFileEntity b = new BlobFileEntity();
        b.setId(id);
        b.setPoolId(poolId);
        b.setKind(BlobKind.SMALL);
        b.setFileName(file.getFileName().toString());
        b.setFilePath(file.toString());
        return b;
    }

    /** A store as written before format 2: pools under {@code oldDir}, one pool elsewhere, no version, no data dir. */
    void legacyStore(MetaStore s, Path oldDir, Path elsewhere) {
        s.writeVoid(tx -> {
            Trees.putPool(tx, pool("p1", "photos", oldDir.resolve("photos")));
            Trees.putPool(tx, pool("p2", "logs", oldDir.resolve("logs")));
            Trees.putPool(tx, pool("p3", "archive", elsewhere));
            Trees.putBlob(tx, blob("b1", "p1", oldDir.resolve("photos/small_1.sob")));
            Trees.putBlob(tx, blob("b2", "p3", elsewhere.resolve("small_2.sob")));
        });
    }

    @Test
    void theMigrationRecordsTheOldDirectoryAndTheOpenRepointsToTheNewOne() throws Exception {
        Path oldDir = Path.of("/data/storage"), newDir = root.resolve("restored"), elsewhere = root.resolve("archive");
        Files.createDirectories(newDir.resolve("photos"));
        Files.writeString(newDir.resolve("photos/small_1.sob"), "x");
        try (MetaStore s = open()) {
            legacyStore(s, oldDir, elsewhere);
            StoreFormat.open(s, newDir);
            assertEquals(Optional.of(StoreFormat.CURRENT), StoreFormat.stored(s));
            assertEquals(Optional.of(newDir.toAbsolutePath().normalize()), StoreFormat.recordedDataDir(s));
            s.read(tx -> {
                assertEquals(newDir.resolve("photos").toString(), Trees.pool(tx, "p1").orElseThrow().getBasePath());
                assertEquals(newDir.resolve("logs").toString(), Trees.pool(tx, "p2").orElseThrow().getBasePath());
                assertEquals(elsewhere.toString(), Trees.pool(tx, "p3").orElseThrow().getBasePath(), "outside the data dir: kept");
                assertEquals(newDir.resolve("photos/small_1.sob").toString(), Trees.blob(tx, "b1", false).orElseThrow().getFilePath());
                assertEquals(elsewhere.resolve("small_2.sob").toString(), Trees.blob(tx, "b2", false).orElseThrow().getFilePath());
                return null;
            });
        }
    }

    @Test
    void aDataDirectoryWithoutTheFilesIsRefusedAndNothingChanges() {
        Path oldDir = Path.of("/data/storage"), wrong = root.resolve("empty");
        try (MetaStore s = open()) {
            legacyStore(s, oldDir, root.resolve("archive"));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> StoreFormat.open(s, wrong));
            assertTrue(e.getMessage().contains("/data/storage") && e.getMessage().contains("SECTORIADB_DATA_DIR"), e.getMessage());
            assertEquals(Optional.of(oldDir), StoreFormat.recordedDataDir(s), "the migration ran, the relocation did not");
            assertEquals(oldDir.resolve("photos/small_1.sob").toString(),
                    s.read(tx -> Trees.blob(tx, "b1", false).orElseThrow().getFilePath()));
        }
    }

    @Test
    void theSameDirectoryChangesNothing() {
        Path dir = root.resolve("data");
        try (MetaStore s = open()) {
            StoreFormat.open(s, dir);
            s.writeVoid(tx -> Trees.putPool(tx, pool("p1", "b", dir.resolve("b"))));
            StoreFormat.open(s, dir);
            assertEquals(dir.resolve("b").toString(), s.read(tx -> Trees.pool(tx, "p1").orElseThrow().getBasePath()));
        }
    }
}
