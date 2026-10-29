package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.GcRepository;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Names, key encodings and the transaction-scoped primitives shared by the three metastore repositories.
 * Every method takes the caller's transaction, so a repository operation that touches several trees is one
 * atomic commit. See docs/architecture/metadata-model.md for the table of trees.
 */
final class Trees {

    /** poolId -> pool */
    static final String POOLS = "pools";
    /** pool name -> poolId (unique) */
    static final String POOLS_BY_NAME = "pools_by_name";
    /** blobId -> blob */
    static final String BLOBS = "blobs";
    /** (poolId, blobId) -> empty */
    static final String BLOBS_BY_POOL = "blobs_by_pool";
    /** manifestId -> manifest */
    static final String MANIFESTS = "manifests";
    /** (bucket, objectKey) -> manifestId of the current version */
    static final String OBJECTS = "objects";
    /** (blobId, manifestId) -> empty */
    static final String MANIFESTS_BY_BLOB = "manifests_by_blob";
    /** (poolId, manifestId) -> empty: manifests that are not S3 objects (shell-stored files), so deleting a pool finds them */
    static final String MANIFESTS_BY_POOL = "manifests_by_pool";
    /** (deletingTxId u64, manifestId) -> empty: superseded / deleted manifests waiting for the garbage collector */
    static final String DELETED_MANIFESTS = "deleted_manifests";
    /**
     * (poolId, manifestId) -> deletingTxId u64: the same retired manifests by pool, so that deleting a bucket (or a blob
     * going away) finds the tombstones with a lookup or a range scan instead of reading the whole queue of all pools.
     * Rows are created and dropped together with the queue row; the queue row's value carries the pool id (after the
     * 8-byte queueing time). Manifests without a pool are indexed under the empty pool id.
     */
    static final String DELETED_BY_POOL = "deleted_manifests_by_pool";
    /** poolId -> blob ids (newline separated): a deleted bucket whose rows are still being removed in small transactions */
    static final String POOL_PURGES = "pool_purges";

    /** {@code "totals"} -> (objects u64, bytes u64): number and total size of the current S3 object versions */
    static final String STATS = "stats";
    private static final byte[] TOTALS_KEY = Keys.of("totals");

    static final byte[] EMPTY = new byte[0];

    private Trees() {
    }

    // ------------------------------------------------------------------ keys

    static byte[] idKey(String id) {
        return Keys.of(id);
    }

    static byte[] pairKey(String first, String second) {
        return Keys.builder().string(first).string(second).build();
    }

    /** Key of the {@code objects} tree. */
    static byte[] objectKey(String bucket, String key) {
        return pairKey(bucket, key);
    }

    static byte[] gcKey(long txId, String manifestId) {
        return Keys.builder().longUnsigned(txId).string(manifestId).build();
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** The second string component of a (string, string) key. */
    static String secondOf(byte[] key) {
        Keys.Reader r = new Keys.Reader(key);
        r.string();
        return r.string();
    }

    // ------------------------------------------------------------------ pools

    static Optional<PoolEntity> pool(ReadTxn tx, String id) {
        return tx.tree(POOLS).get(idKey(id)).map(PoolCodec::decode);
    }

    static Optional<PoolEntity> poolByName(ReadTxn tx, String name) {
        return tx.tree(POOLS_BY_NAME).get(Keys.of(name)).flatMap(id -> pool(tx, str(id)));
    }

    /** Upsert; the name must be unique among pools. */
    static void putPool(WriteTxn tx, PoolEntity pool) {
        requireText(pool.getId(), "pool id");
        requireText(pool.getName(), "pool name");
        BTree byName = tx.tree(POOLS_BY_NAME);
        byte[] nameKey = Keys.of(pool.getName());
        Optional<byte[]> holder = byName.get(nameKey);
        if (holder.isPresent() && !str(holder.get()).equals(pool.getId())) {
            throw new IllegalArgumentException("Pool already exists: " + pool.getName());
        }
        Optional<PoolEntity> prev = pool(tx, pool.getId());
        if (prev.isPresent() && !prev.get().getName().equals(pool.getName())) {
            byName.delete(Keys.of(prev.get().getName()));
        }
        tx.tree(POOLS).put(idKey(pool.getId()), PoolCodec.encode(pool));
        byName.put(nameKey, bytes(pool.getId()));
    }

    static void deletePoolRecord(WriteTxn tx, PoolEntity pool) {
        tx.tree(POOLS).delete(idKey(pool.getId()));
        byte[] nameKey = Keys.of(pool.getName());
        BTree byName = tx.tree(POOLS_BY_NAME);
        // only drop the name entry if it still belongs to this pool
        if (byName.get(nameKey).map(v -> str(v).equals(pool.getId())).orElse(false)) byName.delete(nameKey);
    }

    // ------------------------------------------------------------------ blobs

    static Optional<BlobFileEntity> blob(ReadTxn tx, String id, boolean resolvePool) {
        Optional<BlobFileEntity> b = tx.tree(BLOBS).get(idKey(id)).map(BlobCodec::decode);
        if (resolvePool && b.isPresent() && b.get().getPoolId() != null) {
            pool(tx, b.get().getPoolId()).ifPresent(b.get()::setPool);
        }
        return b;
    }

    static void putBlob(WriteTxn tx, BlobFileEntity blob) {
        requireText(blob.getId(), "blob id");
        Optional<BlobFileEntity> prev = blob(tx, blob.getId(), false);
        BTree byPool = tx.tree(BLOBS_BY_POOL);
        if (prev.isPresent() && prev.get().getPoolId() != null
                && !prev.get().getPoolId().equals(blob.getPoolId())) {
            byPool.delete(pairKey(prev.get().getPoolId(), blob.getId()));
        }
        tx.tree(BLOBS).put(idKey(blob.getId()), BlobCodec.encode(blob));
        if (blob.getPoolId() != null) byPool.put(pairKey(blob.getPoolId(), blob.getId()), EMPTY);
    }

    static void deleteBlobRecord(WriteTxn tx, BlobFileEntity blob) {
        tx.tree(BLOBS).delete(idKey(blob.getId()));
        if (blob.getPoolId() != null) tx.tree(BLOBS_BY_POOL).delete(pairKey(blob.getPoolId(), blob.getId()));
    }

    static List<String> blobIdsOfPool(ReadTxn tx, String poolId) {
        List<String> ids = new ArrayList<>();
        try (Cursor c = tx.tree(BLOBS_BY_POOL).scanPrefix(Keys.of(poolId))) {
            while (c.next()) ids.add(secondOf(c.key()));
        }
        return ids;
    }

    // ------------------------------------------------------------------ manifests

    /** The raw record, without resolved blob references. */
    static Optional<ManifestEntity> manifest(ReadTxn tx, String id) {
        return tx.tree(MANIFESTS).get(idKey(id)).map(ManifestCodec::decode);
    }

    /** Fills in {@code smallBlob} (and its pool) from the blobs tree. */
    static ManifestEntity resolve(ReadTxn tx, ManifestEntity m) {
        if (m.getSmallBlobId() != null) blob(tx, m.getSmallBlobId(), true).ifPresent(m::setSmallBlob);
        if (!m.getSegments().isEmpty()) {
            Map<String, BlobFileEntity> blobs = new HashMap<>();
            for (String id : m.smallBlobIds()) blob(tx, id, true).ifPresent(b -> blobs.put(id, b));
            m.setSegmentBlobs(blobs);
        }
        return m;
    }

    /**
     * Blobs a manifest is indexed under in {@code manifests_by_blob}: the small-object blob of a SMALL object, the blobs
     * of the SMALL segments of a MULTIPART object.
     */
    private static Set<String> blobsOf(ManifestEntity m) {
        return new HashSet<>(m.smallBlobIds());
    }

    /** Upsert of the manifest record and of its {@code manifests_by_blob} entries. Does not touch {@code objects}. */
    static void putManifest(WriteTxn tx, ManifestEntity m) {
        requireText(m.getId(), "manifest id");
        BTree byBlob = tx.tree(MANIFESTS_BY_BLOB);
        Set<String> before = manifest(tx, m.getId()).map(Trees::blobsOf).orElse(Set.of());
        Set<String> after = blobsOf(m);
        for (String b : before) {
            if (!after.contains(b)) byBlob.delete(pairKey(b, m.getId()));
        }
        for (String b : after) {
            if (!before.contains(b)) byBlob.put(pairKey(b, m.getId()), EMPTY);
        }
        if (isShellManifest(m)) tx.tree(MANIFESTS_BY_POOL).put(pairKey(m.getPoolId(), m.getId()), EMPTY);
        tx.tree(MANIFESTS).put(idKey(m.getId()), ManifestCodec.encode(m));
    }

    private static boolean isShellManifest(ManifestEntity m) {
        return m.getBucketName() == null && m.getPoolId() != null;
    }

    static void deleteManifestRecord(WriteTxn tx, ManifestEntity m) {
        if (isShellManifest(m)) tx.tree(MANIFESTS_BY_POOL).delete(pairKey(m.getPoolId(), m.getId()));
        tx.tree(MANIFESTS).delete(idKey(m.getId()));
        BTree byBlob = tx.tree(MANIFESTS_BY_BLOB);
        for (String b : blobsOf(m)) byBlob.delete(pairKey(b, m.getId()));
    }

    /** Marks a tombstone value that carries its small-object records (older values are the time, then the pool id). */
    private static final byte TOMBSTONE_WITH_REFS = 0x01;

    /**
     * Queues the tombstone of a retired version. The value is the queueing time (the collector waits for the grace period),
     * the pool id (the queue row is dropped together with its by-pool row) and the small-object records the collector
     * must mark DELETED: {@code time(8) 0x01 poolLen(2) pool n(4) n × [blobLen(2) blob offset(8)]}. A pool id is a UUID
     * string, so its first byte is never 0x01 and the old {@code time pool} values stay readable.
     */
    static void enqueueDeleted(WriteTxn tx, ManifestEntity m, List<GcRepository.SmallRef> refs) {
        long txId = tx.txId() + 1;
        byte[] pool = m.getPoolId() == null ? new byte[0] : bytes(m.getPoolId());
        int size = 8 + 1 + 2 + pool.length + 4;
        List<byte[]> blobs = new ArrayList<>(refs.size());
        for (GcRepository.SmallRef r : refs) {
            byte[] b = bytes(r.blobId());
            blobs.add(b);
            size += 2 + b.length + 8;
        }
        ByteBuffer v = ByteBuffer.allocate(size).putLong(System.currentTimeMillis()).put(TOMBSTONE_WITH_REFS)
                .putShort((short) pool.length).put(pool).putInt(refs.size());
        for (int i = 0; i < refs.size(); i++) v.putShort((short) blobs.get(i).length).put(blobs.get(i)).putLong(refs.get(i).offset());
        tx.tree(DELETED_MANIFESTS).put(gcKey(txId, m.getId()), v.array());
        tx.tree(DELETED_BY_POOL).put(deletedByPoolKey(m.getPoolId(), m.getId()), ByteBuffer.allocate(8).putLong(txId).array());
    }

    /** Pool id of a queue row's value ("" if the row predates it). A damaged row: {@link IllegalStateException}. */
    static String tombstonePool(byte[] value) {
        if (value == null || value.length <= 8) return "";
        if (value[8] == TOMBSTONE_WITH_REFS) {
            ByteBuffer b = ByteBuffer.wrap(value, 9, value.length - 9);
            return new String(field(b, value.length), StandardCharsets.UTF_8);
        }
        return new String(value, 8, value.length - 8, StandardCharsets.UTF_8);
    }

    /**
     * Small-object records of a queue row's value, or {@code null} for a row of the old format (read the manifest). A
     * damaged row (lengths that do not fit the value, found by fuzzing): {@link IllegalStateException}, never an
     * underflow or a huge allocation.
     */
    static List<GcRepository.SmallRef> tombstoneRefs(byte[] value) {
        if (value == null || value.length <= 8 || value[8] != TOMBSTONE_WITH_REFS) return null;
        ByteBuffer b = ByteBuffer.wrap(value, 9, value.length - 9);
        field(b, value.length);   // skip the pool
        if (b.remaining() < 4) throw corruptRow("no record count");
        int n = b.getInt();
        if (n < 0 || n > b.remaining() / 10) throw corruptRow("record count " + n);   // a record is at least 2 + 8 bytes
        List<GcRepository.SmallRef> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] blob = field(b, value.length);
            if (b.remaining() < 8) throw corruptRow("record " + i + " without its offset");
            out.add(new GcRepository.SmallRef(new String(blob, StandardCharsets.UTF_8), b.getLong()));
        }
        return out;
    }

    /** A length-prefixed (u16) byte field of a tombstone row. */
    private static byte[] field(ByteBuffer b, int rowLength) {
        if (b.remaining() < 2) throw corruptRow("truncated field");
        int len = b.getShort() & 0xFFFF;
        if (len > b.remaining()) throw corruptRow("field of " + len + " bytes in a row of " + rowLength);
        byte[] out = new byte[len];
        b.get(out);
        return out;
    }

    private static IllegalStateException corruptRow(String why) {
        return new IllegalStateException("corrupt tombstone row: " + why);
    }

    /** The small-object records a version occupies: its own record, or the SMALL segments of a multipart object. */
    static List<GcRepository.SmallRef> smallRefs(ManifestEntity m) {
        List<GcRepository.SmallRef> out = new ArrayList<>();
        if (m.getStorageKind() == StorageKind.SMALL && m.getSmallBlobId() != null) {
            out.add(new GcRepository.SmallRef(m.getSmallBlobId(), m.getSmallOffset()));
        } else if (m.getStorageKind() == StorageKind.MULTIPART) {
            for (PartSegment sg : m.getSegments()) {
                if (sg.kind() == StorageKind.SMALL && sg.smallBlobId() != null) {
                    out.add(new GcRepository.SmallRef(sg.smallBlobId(), sg.smallOffset()));
                }
            }
        }
        return out;
    }

    static byte[] deletedByPoolKey(String poolId, String manifestId) {
        return Keys.builder().string(poolId == null ? "" : poolId).string(manifestId).build();
    }

    /** Removes a queue row and its by-pool row ({@code value} is the row's value as read from the queue). */
    static void dropQueueRow(WriteTxn tx, byte[] queueKey, byte[] value) {
        tx.tree(DELETED_MANIFESTS).delete(queueKey);
        if (value != null && value.length >= 8) {   // rows queued before the by-pool index have no pool and no index row
            Keys.Reader r = new Keys.Reader(queueKey);
            r.longUnsigned();
            String manifestId = r.string();
            String pool;
            try {
                pool = tombstonePool(value);
            } catch (IllegalStateException damaged) {
                return;   // the pool is unreadable: only the queue row can go (a by-pool row may stay; DeleteBucket tolerates it)
            }
            tx.tree(DELETED_BY_POOL).delete(deletedByPoolKey(pool, manifestId));
        }
    }

    /** Drops the queue row of a retired manifest (and its by-pool row), if it has one. */
    static void dropTombstone(WriteTxn tx, ManifestEntity m) {
        byte[] idx = deletedByPoolKey(m.getPoolId(), m.getId());
        Optional<byte[]> txId = tx.tree(DELETED_BY_POOL).get(idx);
        if (txId.isEmpty()) return;
        byte[] queueKey = gcKey(ByteBuffer.wrap(txId.get()).getLong(), m.getId());
        dropQueueRow(tx, queueKey, tx.tree(DELETED_MANIFESTS).get(queueKey).orElse(null));
        tx.tree(DELETED_BY_POOL).delete(idx);
    }

    /**
     * Takes a version out of service in the caller's transaction: its chunk references are released (chunks that drop to
     * zero go to {@code chunk_gc}, which has its own grace period) and its manifest record is REMOVED at once. A reader that
     * is still streaming the old version holds it in its snapshot, so the record is not needed to finish; the grace period
     * protects the data (chunks and small-object records), not the record. Only a version with small-object records gets
     * a tombstone, which carries those records for the collector to mark DELETED after the grace period.
     * Keeping whole retired manifests until the grace period was over cost a full manifest per delete or overwrite for 15
     * minutes (most of sectoria.db under churn) and deepened every tree a commit has to copy.
     * Returns the retired manifest (with resolved blobs, flagged deleted), or empty if it does not exist or is already retired.
     */
    static Optional<ManifestEntity> retire(WriteTxn tx, String manifestId) {
        Optional<ManifestEntity> found = manifest(tx, manifestId);
        if (found.isEmpty() || found.get().isDeleted()) return Optional.empty();
        ManifestEntity m = found.get();
        ManifestEntity resolved = resolve(tx, m);
        List<GcRepository.SmallRef> refs = smallRefs(m);
        if ((m.getStorageKind() == StorageKind.CHUNKED || m.getStorageKind() == StorageKind.MULTIPART)
                && m.getPoolId() != null && m.chunkKeyArray().length > 0) {
            Chunks.releaseRefs(tx, m.getPoolId(), m.chunkKeyArray());
        }
        deleteManifestRecord(tx, m);
        enqueueDeleted(tx, m, refs);
        resolved.setDeleted(true);
        resolved.setChunkKeyArray(new long[0]);
        return Optional.of(resolved);
    }

    static List<String> manifestIdsOfBlob(ReadTxn tx, String blobId) {
        List<String> ids = new ArrayList<>();
        try (Cursor c = tx.tree(MANIFESTS_BY_BLOB).scanPrefix(Keys.of(blobId))) {
            while (c.next()) ids.add(secondOf(c.key()));
        }
        return ids;
    }

    /**
     * Removes every manifest record that references one of {@code blobIds} (blobs go away). The GC queue is not read:
     * a queue row whose manifest is gone only loses its row when the collector reaches it (that is what it does with
     * any row of a manifest that no longer exists), so this stays proportional to the blobs' own rows.
     */
    static void purgeManifests(WriteTxn tx, Collection<String> blobIds) {
        Set<String> doomed = new HashSet<>();
        for (String blobId : blobIds) doomed.addAll(manifestIdsOfBlob(tx, blobId));
        for (String id : doomed) {
            manifest(tx, id).ifPresent(m -> {
                dropTombstone(tx, m);
                deleteManifestRecord(tx, m);
            });
        }
    }

    /**
     * One slice of a bucket deletion: removes at most about {@code budget} rows of the pool's manifests (shell-stored ones,
     * those referencing its blobs and its tombstones, with their queue rows). Returns true when nothing of that is left.
     */
    static boolean purgePoolManifestsSlice(WriteTxn tx, String poolId, List<String> blobIds, int budget) {
        int left = budget;
        for (String blobId : blobIds) {
            if (left <= 0) return false;
            List<byte[]> rows = new ArrayList<>();
            try (Cursor c = tx.tree(MANIFESTS_BY_BLOB).scanPrefix(Keys.of(blobId))) {
                while (rows.size() < left && c.next()) rows.add(c.key());
            }
            for (byte[] row : rows) {
                manifest(tx, secondOf(row)).ifPresent(m -> deleteManifestRecord(tx, m));
                tx.tree(MANIFESTS_BY_BLOB).delete(row);   // also when the record was already gone: never loop on a dangling row
            }
            left -= rows.size();
        }
        if (left > 0) {
            List<byte[]> rows = new ArrayList<>();
            try (Cursor c = tx.tree(MANIFESTS_BY_POOL).scanPrefix(Keys.of(poolId))) {
                while (rows.size() < left && c.next()) rows.add(c.key());
            }
            for (byte[] row : rows) {
                manifest(tx, secondOf(row)).ifPresent(m -> deleteManifestRecord(tx, m));
                tx.tree(MANIFESTS_BY_POOL).delete(row);
            }
            left -= rows.size();
        }
        if (left > 0) {
            List<byte[]> rows = new ArrayList<>();
            List<byte[]> txIds = new ArrayList<>();
            try (Cursor c = tx.tree(DELETED_BY_POOL).scanPrefix(Keys.of(poolId))) {
                while (rows.size() < left && c.next()) {
                    rows.add(c.key());
                    txIds.add(c.value());
                }
            }
            BTree queue = tx.tree(DELETED_MANIFESTS);
            for (int i = 0; i < rows.size(); i++) {
                String manifestId = secondOf(rows.get(i));
                byte[] queueKey = gcKey(ByteBuffer.wrap(txIds.get(i)).getLong(), manifestId);
                manifest(tx, manifestId).ifPresent(m -> deleteManifestRecord(tx, m));
                dropQueueRow(tx, queueKey, queue.get(queueKey).orElse(null));
                tx.tree(DELETED_BY_POOL).delete(rows.get(i));   // also when the queue row was gone: never loop on a dangling row
            }
            left -= rows.size();
        }
        return left > 0;
    }

    // ------------------------------------------------------------------ object totals (cheap gauges)

    /** {objects, bytes} as last committed, or null if the totals have never been initialized. */
    static long[] totals(ReadTxn tx) {
        return tx.tree(STATS).get(TOTALS_KEY).map(v -> {
            ByteBuffer b = ByteBuffer.wrap(v);
            return new long[]{b.getLong(0), b.getLong(8)};
        }).orElse(null);
    }

    /**
     * Makes sure the totals exist; the first call on a store that predates them counts the {@code objects} tree
     * once (inside the caller's transaction, before it changes anything). Afterwards they are only adjusted.
     */
    static void ensureTotals(WriteTxn tx) {
        if (totals(tx) != null) return;
        long objects = 0;
        long bytes = 0;
        BTree manifests = tx.tree(MANIFESTS);
        try (Cursor c = tx.tree(OBJECTS).scan()) {
            while (c.next()) {
                objects++;
                Optional<byte[]> v = manifests.get(idKey(str(c.value())));
                if (v.isPresent()) bytes += ManifestCodec.decode(v.get(), false).getTotalBytes();
            }
        }
        putTotals(tx, objects, bytes);
    }

    /** Adds the deltas to the totals (they must have been initialized with {@link #ensureTotals}). */
    static void adjustTotals(WriteTxn tx, long objectsDelta, long bytesDelta) {
        long[] t = totals(tx);
        if (t == null) throw new IllegalStateException("object totals not initialized");
        putTotals(tx, Math.max(0, t[0] + objectsDelta), Math.max(0, t[1] + bytesDelta));
    }

    private static void putTotals(WriteTxn tx, long objects, long bytes) {
        byte[] v = ByteBuffer.allocate(16).putLong(objects).putLong(bytes).array();
        tx.tree(STATS).put(TOTALS_KEY, v);
    }

    private static void requireText(String v, String what) {
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(what + " must not be empty");
    }
}
