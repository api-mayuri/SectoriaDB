package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The format version of the whole store (the trees of sectoria.db and what they say about the blob files), kept in the
 * tree {@value #TREE}. Checked every time the store is opened:
 * <ul>
 *   <li>no version stored: a store written before versioning existed, which is version 1, the layout this class was
 *       introduced with; the version is written;</li>
 *   <li>an older version: the migrations from it to {@link #CURRENT} run in order, each in its own transaction that also
 *       stores the version it reaches, so a crash in between resumes at the next migration;</li>
 *   <li>a newer version: opening stops (an older build must neither interpret nor rewrite a newer store).</li>
 * </ul>
 * The files themselves carry their own versions (meta page of sectoria.db, header of {@code blob_*.raw} and
 * {@code small_*.sob}, record codecs); this number covers changes of the metadata model, which no single file shows.
 * A change of the stored format adds a migration here, raises {@link #CURRENT}, and adds the store written by the
 * previous version to the compatibility test (docs/architecture/metadata-model.md, "Format versions").
 */
public final class StoreFormat {

    private static final Logger log = LoggerFactory.getLogger(StoreFormat.class);

    /**
     * The format this build writes.
     * <ol>
     *   <li>the layout of all stores written before versioning;</li>
     *   <li>the data directory the store was written with is recorded ({@value #DATA_DIR_KEY}), so a store opened from
     *       another directory (a restored backup, a volume mounted elsewhere) can repoint its file paths.</li>
     * </ol>
     */
    public static final int CURRENT = 2;
    static final String TREE = "format";
    private static final byte[] KEY = "version".getBytes(StandardCharsets.US_ASCII);
    static final String DATA_DIR_KEY = "data-dir";
    private static final byte[] DATA_DIR = DATA_DIR_KEY.getBytes(StandardCharsets.US_ASCII);

    /** One step of the format: from {@code from()} to {@code from() + 1}, inside the caller's transaction. */
    public interface Migration {
        int from();

        String description();

        /** @param dataDir the data directory of this run (absolute, normalised) */
        void apply(WriteTxn tx, Path dataDir);
    }

    /** The migrations of this build, oldest first. */
    static final List<Migration> MIGRATIONS = List.of(new Migration() {
        @Override
        public int from() {
            return 1;
        }

        @Override
        public String description() {
            return "record the data directory the store was written with";
        }

        @Override
        public void apply(WriteTxn tx, Path dataDir) {
            putDataDir(tx, inferDataDir(tx).orElse(dataDir));
        }
    });

    private StoreFormat() {
    }

    /**
     * Checks and, if needed, migrates the store to {@link #CURRENT}, then repoints the stored file paths when the store is
     * opened from another data directory than it was written with. Returns the version it was found at (0: none).
     */
    public static int open(MetaStore store, Path dataDir) {
        Path dir = dataDir.toAbsolutePath().normalize();
        int found = open(store, CURRENT, MIGRATIONS, dir);
        relocate(store, dir);
        return found;
    }

    static int open(MetaStore store, int current, List<Migration> migrations, Path dataDir) {
        Optional<Integer> stored = store.read(tx -> tx.tree(TREE).get(KEY).map(v -> ByteBuffer.wrap(v).getInt()));
        if (stored.isEmpty() && store.treeSizes().isEmpty()) {
            // a new, empty store: it is created at the current version, nothing to migrate
            store.writeVoid(tx -> {
                put(tx, current);
                putDataDir(tx, dataDir);
            });
            return 0;
        }
        int found = stored.orElse(0);
        int version = stored.orElse(1);
        if (version > current) {
            throw new IllegalStateException("The store in sectoria.db has format version " + version + "; this build "
                    + "writes version " + current + " and cannot open a newer store. Start the SectoriaDB version that "
                    + "wrote it, or restore a backup made with this one.");
        }
        while (version < current) {
            int from = version;
            Migration m = migrations.stream().filter(x -> x.from() == from).findFirst().orElseThrow(() ->
                    new IllegalStateException("no migration from store format version " + from + " (this build: " + current + ")"));
            log.info("Migrating the store from format version {} to {}: {}", from, from + 1, m.description());
            long t0 = System.nanoTime();
            store.writeVoid(tx -> {
                m.apply(tx, dataDir);
                put(tx, from + 1);
            });
            log.info("Store format version {} reached in {} ms", from + 1, (System.nanoTime() - t0) / 1_000_000);
            version = from + 1;
        }
        if (stored.isEmpty() && current == 1) {
            store.writeVoid(tx -> put(tx, 1));   // only when version 1 is current (tests of the mechanism)
        }
        return found;
    }

    /**
     * The data directory of a store written before it was recorded: the directory holding the pools that are named after
     * their directory (every bucket is {@code <data-dir>/<bucket>}); empty for a store without such a pool.
     */
    static Optional<Path> inferDataDir(ReadTxn tx) {
        Map<Path, Integer> votes = new HashMap<>();
        try (Cursor c = tx.tree(Trees.POOLS).scan()) {
            while (c.next()) {
                PoolEntity p = PoolCodec.decode(c.value());
                if (p.getBasePath() == null) continue;
                Path base = Path.of(p.getBasePath()).toAbsolutePath().normalize();
                if (base.getFileName() != null && base.getFileName().toString().equals(p.getName()) && base.getParent() != null) {
                    votes.merge(base.getParent(), 1, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    /** The data directory recorded in the store (from format version 2). */
    public static Optional<Path> recordedDataDir(MetaStore store) {
        return store.read(tx -> tx.tree(TREE).get(DATA_DIR).map(v -> Path.of(new String(v, StandardCharsets.UTF_8))));
    }

    /**
     * A store opened from another data directory than the recorded one (a restored backup, the volume mounted at another
     * path, a run outside the container) has file paths that point into the old directory. If its files are in the new
     * directory, the paths of the pools and blob files below the old directory are repointed in one transaction and the
     * new directory is recorded. If they are not there, opening stops: pointing the store at an empty directory would
     * make every object unreadable (most likely sectoriadb.data-dir is wrong). Pools created outside the data directory
     * ({@code pool-create --path}) keep their paths.
     */
    static void relocate(MetaStore store, Path dataDir) {
        Optional<Path> recorded = recordedDataDir(store);
        if (recorded.isEmpty()) {
            store.writeVoid(tx -> putDataDir(tx, dataDir));
            return;
        }
        Path old = recorded.get();
        if (old.equals(dataDir)) return;
        List<PoolEntity> pools = new ArrayList<>();
        List<BlobFileEntity> blobs = new ArrayList<>();
        store.read(tx -> {
            try (Cursor c = tx.tree(Trees.POOLS).scan()) {
                while (c.next()) {
                    PoolEntity p = PoolCodec.decode(c.value());
                    if (p.getBasePath() != null && under(p.getBasePath(), old)) pools.add(p);
                }
            }
            try (Cursor c = tx.tree(Trees.BLOBS).scan()) {
                while (c.next()) {
                    BlobFileEntity b = BlobCodec.decode(c.value());
                    if (b.getFilePath() != null && under(b.getFilePath(), old)) blobs.add(b);
                }
            }
            return null;
        });
        for (BlobFileEntity b : blobs) {
            Path moved = moved(b.getFilePath(), old, dataDir);
            if (!Files.exists(moved)) {
                throw new IllegalStateException("The store was written with the data directory " + old + " and is opened with "
                        + dataDir + ", but its files are not there (missing: " + moved + "). Set sectoriadb.data-dir "
                        + "(SECTORIADB_DATA_DIR) to the directory that holds the blob files, or move them to " + dataDir + ".");
            }
        }
        store.writeVoid(tx -> {
            for (PoolEntity p : pools) {
                p.setBasePath(moved(p.getBasePath(), old, dataDir).toString());
                tx.tree(Trees.POOLS).put(Trees.idKey(p.getId()), PoolCodec.encode(p));
            }
            for (BlobFileEntity b : blobs) {
                b.setFilePath(moved(b.getFilePath(), old, dataDir).toString());
                tx.tree(Trees.BLOBS).put(Trees.idKey(b.getId()), BlobCodec.encode(b));
            }
            putDataDir(tx, dataDir);
        });
        log.warn("The data directory moved from {} to {}: repointed {} pool(s) and {} blob file(s)", old, dataDir,
                pools.size(), blobs.size());
    }

    private static boolean under(String path, Path dir) {
        return Path.of(path).toAbsolutePath().normalize().startsWith(dir);
    }

    private static Path moved(String path, Path from, Path to) {
        return to.resolve(from.relativize(Path.of(path).toAbsolutePath().normalize()));
    }

    private static void putDataDir(WriteTxn tx, Path dir) {
        tx.tree(TREE).put(DATA_DIR, dir.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The stored version, or empty for a store written before versioning. */
    public static Optional<Integer> stored(MetaStore store) {
        return store.read(tx -> tx.tree(TREE).get(KEY).map(v -> ByteBuffer.wrap(v).getInt()));
    }

    private static void put(WriteTxn tx, int version) {
        tx.tree(TREE).put(KEY, ByteBuffer.allocate(4).putInt(version).array());
    }
}
