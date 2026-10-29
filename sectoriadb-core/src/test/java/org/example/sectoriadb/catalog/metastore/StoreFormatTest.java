package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.metastore.WriteTxn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFormatTest {

    @TempDir
    Path dir;

    MetaStore open() {
        return MetaStore.open(dir.resolve("sectoria.db"), MetaStoreOptions.defaults().fsync(false));
    }

    static StoreFormat.Migration step(int from, List<String> log) {
        return new StoreFormat.Migration() {
            @Override public int from() { return from; }
            @Override public String description() { return "test step " + from; }
            @Override public void apply(WriteTxn tx, Path dataDir) {
                log.add("from " + from);
                tx.tree("migrated").put(new byte[]{(byte) from}, new byte[]{1});
            }
        };
    }

    @Test
    void aNewStoreIsCreatedAtTheCurrentVersionWithItsDataDirectory() {
        try (MetaStore s = open()) {
            assertEquals(0, StoreFormat.open(s, dir));
            assertEquals(Optional.of(StoreFormat.CURRENT), StoreFormat.stored(s));
            assertEquals(Optional.of(dir.toAbsolutePath().normalize()), StoreFormat.recordedDataDir(s));
            assertEquals(StoreFormat.CURRENT, StoreFormat.open(s, dir), "the second open finds the version");
        }
    }

    @Test
    void aStoreWithoutAVersionIsVersionOneAndMigratesKeepingItsData() {
        try (MetaStore s = open()) {
            s.writeVoid(tx -> tx.tree("objects").put("k".getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8)));
            assertEquals(Optional.empty(), StoreFormat.stored(s), "a store written before versioning");
            assertEquals(0, StoreFormat.open(s, dir));
            assertEquals(Optional.of(StoreFormat.CURRENT), StoreFormat.stored(s));
            assertArrayEquals("v".getBytes(StandardCharsets.UTF_8), s.read(tx -> tx.tree("objects").get("k".getBytes(StandardCharsets.UTF_8)).orElseThrow()));
        }
    }

    @Test
    void olderStoresAreMigratedStepByStepAndAStoppedMigrationResumes() {
        List<String> log = new ArrayList<>();
        try (MetaStore s = open()) {
            StoreFormat.open(s, 1, List.of(), dir);                                       // written at version 1
            StoreFormat.open(s, 3, List.of(step(1, log), step(2, log)), dir);
            assertEquals(List.of("from 1", "from 2"), log);
            assertEquals(Optional.of(3), StoreFormat.stored(s));
            assertEquals(2L, (long) s.read(tx -> tx.tree("migrated").size()));

            // a migration that fails leaves the store at the last version reached; the next open resumes there
            StoreFormat.Migration broken = new StoreFormat.Migration() {
                @Override public int from() { return 3; }
                @Override public String description() { return "fails"; }
                @Override public void apply(WriteTxn tx, Path dataDir) {
                    tx.tree("migrated").put(new byte[]{9}, new byte[]{9});
                    throw new IllegalStateException("disk full");
                }
            };
            assertThrows(IllegalStateException.class, () -> StoreFormat.open(s, 4, List.of(broken), dir));
            assertEquals(Optional.of(3), StoreFormat.stored(s), "the failed step changed nothing");
            assertEquals(2L, (long) s.read(tx -> tx.tree("migrated").size()));
            log.clear();
            StoreFormat.open(s, 4, List.of(step(3, log)), dir);
            assertEquals(List.of("from 3"), log);
        }
    }

    @Test
    void aNewerStoreIsRefusedAndNotRewritten() {
        try (MetaStore s = open()) {
            s.writeVoid(tx -> tx.tree(StoreFormat.TREE).put("version".getBytes(StandardCharsets.US_ASCII), ByteBuffer.allocate(4).putInt(7).array()));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> StoreFormat.open(s, dir));
            assertTrue(e.getMessage().contains("format version 7"), e.getMessage());
            assertEquals(Optional.of(7), StoreFormat.stored(s));
        }
    }

    @Test
    void aMissingMigrationIsAnError() {
        try (MetaStore s = open()) {
            StoreFormat.open(s, 1, List.of(), dir);
            assertThrows(IllegalStateException.class, () -> StoreFormat.open(s, 2, List.of(), dir));
            assertEquals(Optional.of(1), StoreFormat.stored(s));
        }
    }
}
