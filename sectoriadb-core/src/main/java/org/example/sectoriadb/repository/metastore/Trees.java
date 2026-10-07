package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Names, key encodings and the transaction-scoped primitives shared by the three metastore repositories.
 * Every method takes the caller's transaction, so a repository operation that touches several trees is one
 * atomic commit. See docs/architecture/07-metastore-integration.md for the table of trees.
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
        return m;
    }

    /** Blobs a manifest is indexed under in {@code manifests_by_blob}: only the small-object blob of a SMALL object. */
    private static Set<String> blobsOf(ManifestEntity m) {
        Set<String> s = new HashSet<>(1);
        if (m.getSmallBlobId() != null) s.add(m.getSmallBlobId());
        return s;
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

    static void enqueueDeleted(WriteTxn tx, String manifestId) {
        tx.tree(DELETED_MANIFESTS).put(gcKey(tx.txId() + 1, manifestId), EMPTY);
    }

    /**
     * Marks a live manifest as no longer current: sets {@code deleted} and puts it on the GC queue. A CHUNKED manifest
     * releases its chunk references in the same transaction and becomes a tombstone without its chunk list (the
     * references are gone, so keeping the list would only invite a second release; a 1 GiB object is 512 KiB of keys).
     * Returns the manifest with resolved blobs, or empty if it does not exist or is already retired.
     */
    static Optional<ManifestEntity> retire(WriteTxn tx, String manifestId) {
        Optional<ManifestEntity> found = manifest(tx, manifestId);
        if (found.isEmpty() || found.get().isDeleted()) return Optional.empty();
        ManifestEntity m = found.get();
        m.setDeleted(true);
        if (m.getStorageKind() == StorageKind.CHUNKED && m.getPoolId() != null && m.chunkKeyArray().length > 0) {
            Chunks.releaseRefs(tx, m.getPoolId(), m.chunkKeyArray());
            m.setChunkKeyArray(new long[0]);
        }
        putManifest(tx, m);
        enqueueDeleted(tx, manifestId);
        return Optional.of(resolve(tx, m));
    }

    static List<String> manifestIdsOfBlob(ReadTxn tx, String blobId) {
        List<String> ids = new ArrayList<>();
        try (Cursor c = tx.tree(MANIFESTS_BY_BLOB).scanPrefix(Keys.of(blobId))) {
            while (c.next()) ids.add(secondOf(c.key()));
        }
        return ids;
    }

    /**
     * Removes every manifest record that references one of {@code blobIds}, and every GC-queue entry whose manifest
     * belongs to those blobs, to {@code poolId} (if not null) or no longer exists. Used when blobs go away.
     */
    static void purgeManifests(WriteTxn tx, Collection<String> blobIds, String poolId) {
        Set<String> doomed = new HashSet<>();
        for (String blobId : blobIds) doomed.addAll(manifestIdsOfBlob(tx, blobId));
        if (poolId != null) {
            try (Cursor c = tx.tree(MANIFESTS_BY_POOL).scanPrefix(Keys.of(poolId))) {
                while (c.next()) doomed.add(secondOf(c.key()));
            }
        }

        BTree queue = tx.tree(DELETED_MANIFESTS);
        List<byte[]> queueKeys = new ArrayList<>();
        try (Cursor c = queue.scan()) {
            while (c.next()) {
                byte[] key = c.key();
                Keys.Reader r = new Keys.Reader(key);
                r.longUnsigned();
                String manifestId = r.string();
                Optional<ManifestEntity> m = manifest(tx, manifestId);
                if (m.isEmpty()) {
                    queueKeys.add(key);
                } else if (doomed.contains(manifestId) || (poolId != null && poolId.equals(m.get().getPoolId()))) {
                    doomed.add(manifestId);
                    queueKeys.add(key);
                }
            }
        }
        for (String id : doomed) manifest(tx, id).ifPresent(m -> deleteManifestRecord(tx, m));
        for (byte[] k : queueKeys) queue.delete(k);
    }

    // ------------------------------------------------------------------ object totals (cheap gauges)

    /** {objects, bytes} as last committed, or null if the totals have never been initialized. */
    static long[] totals(ReadTxn tx) {
        return tx.tree(STATS).get(TOTALS_KEY).map(v -> {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(v);
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
        byte[] v = java.nio.ByteBuffer.allocate(16).putLong(objects).putLong(bytes).array();
        tx.tree(STATS).put(TOTALS_KEY, v);
    }

    private static void requireText(String v, String what) {
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(what + " must not be empty");
    }
}
