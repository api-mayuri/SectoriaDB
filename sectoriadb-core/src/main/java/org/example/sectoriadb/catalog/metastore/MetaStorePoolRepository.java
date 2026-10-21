package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.PoolRepository;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** {@link PoolRepository} over the {@code pools} and {@code pools_by_name} trees. */
public class MetaStorePoolRepository implements PoolRepository {

    private final MetaStoreProvider stores;

    public MetaStorePoolRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    @Override
    public PoolEntity save(PoolEntity entity) {
        return store().writeGrouped(tx -> {
            Trees.putPool(tx, entity);
            return entity;
        });
    }

    @Override
    public Optional<PoolEntity> findById(String id) {
        return store().read(tx -> Trees.pool(tx, id));
    }

    @Override
    public Optional<PoolEntity> findByName(String name) {
        return store().read(tx -> Trees.poolByName(tx, name));
    }

    @Override
    public List<PoolEntity> findAll() {
        return store().read(tx -> {
            List<PoolEntity> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.POOLS).scan()) {
                while (c.next()) out.add(PoolCodec.decode(c.value()));
            }
            return out;
        });
    }

    @Override
    public boolean existsById(String id) {
        return store().read(tx -> tx.tree(Trees.POOLS).containsKey(Trees.idKey(id)));
    }

    @Override
    public boolean existsByName(String name) {
        return store().read(tx -> tx.tree(Trees.POOLS_BY_NAME).containsKey(Keys.of(name)));
    }

    @Override
    public long count() {
        return store().read(tx -> tx.tree(Trees.POOLS).size());
    }

    @Override
    public void delete(PoolEntity entity) {
        store().writeGroupedVoid(tx -> Trees.deletePoolRecord(tx, entity));
    }

    @Override
    public Optional<PoolEntity> updateByName(String name, Consumer<PoolEntity> mutator) {
        return store().writeGrouped(tx -> {
            Optional<PoolEntity> found = Trees.poolByName(tx, name);
            found.ifPresent(p -> {
                mutator.accept(p);
                Trees.putPool(tx, p);
            });
            return found;
        });
    }

    /** Rows removed per transaction while the leftovers of a deleted bucket are purged. */
    private static final int PURGE_SLICE = 1000;

    @Override
    public List<BlobFileEntity> deleteBucket(String poolId) {
        PoolEntity pool = store().read(tx -> Trees.pool(tx, poolId))
                .orElseThrow(() -> new IllegalArgumentException("Pool not found: " + poolId));
        long objects = store().read(tx -> countObjects(tx, pool.getName()));
        if (objects > 0) throw bucketNotEmpty(pool.getName(), objects);
        // uploads still in progress are discarded with their parts (their data lives in this pool), one per transaction
        for (int round = 0; round < 3; round++) {
            discardOpenUploads(pool.getName());
            Optional<List<BlobFileEntity>> done = unregister(poolId);
            if (done.isPresent()) {
                // everything else (chunk index, tombstones, manifests of the pool) goes in short transactions
                while (!purgeSlice(poolId)) {
                    // the next slice follows at once; writers run in between
                }
                return done.get();
            }
        }
        throw new IllegalStateException("BucketNotEmpty: bucket '" + pool.getName() + "' keeps receiving multipart uploads.");
    }

    private static IllegalStateException bucketNotEmpty(String bucket, long objects) {
        return new IllegalStateException("BucketNotEmpty: bucket '" + bucket + "' still has " + objects + " object(s).");
    }

    private void discardOpenUploads(String bucket) {
        while (true) {
            List<MultipartUpload> open = store().read(tx -> MultipartTrees.openUploadsOfBucket(tx, bucket, 1));
            if (open.isEmpty()) return;
            String id = open.get(0).getUploadId();
            store().writeGroupedVoid(tx -> MultipartTrees.upload(tx, id)
                    .filter(u -> u.getState() == MultipartUpload.State.OPEN)
                    .ifPresent(u -> {
                        MultipartTrees.discard(tx, u, System.currentTimeMillis());
                        tx.tree(MultipartTrees.UPLOADS).delete(MultipartTrees.uploadKey(id));   // the bucket is going away: no record to keep
                    }));
        }
    }

    /**
     * The short exclusive transaction of a bucket deletion: the pool, its name and its blob records disappear atomically (the
     * bucket is gone for clients from here on) and a purge record keeps what remains to be removed. Empty when an upload was
     * started meanwhile (the caller discards it and tries again).
     */
    private Optional<List<BlobFileEntity>> unregister(String poolId) {
        try (WriteTxn tx = store().beginWrite()) {
            PoolEntity pool = Trees.pool(tx, poolId)
                    .orElseThrow(() -> new IllegalArgumentException("Pool not found: " + poolId));
            long objects = countObjects(tx, pool.getName());
            if (objects > 0) throw bucketNotEmpty(pool.getName(), objects);
            if (!MultipartTrees.openUploadsOfBucket(tx, pool.getName(), 1).isEmpty()) return Optional.empty();
            List<String> blobIds = Trees.blobIdsOfPool(tx, poolId);
            List<BlobFileEntity> removed = new ArrayList<>();
            for (String id : blobIds) {
                Trees.blob(tx, id, false).ifPresent(b -> {
                    removed.add(b);
                    Trees.deleteBlobRecord(tx, b);
                });
            }
            Trees.deletePoolRecord(tx, pool);
            tx.tree(Trees.POOL_PURGES).put(Keys.of(poolId), Trees.bytes(String.join("\n", blobIds)));
            tx.commit();
            return Optional.of(removed);
        }
    }

    /** One slice of the purge of a deleted bucket; true when nothing is left (the purge record is gone then). */
    private boolean purgeSlice(String poolId) {
        return store().writeGrouped(tx -> {
            Optional<byte[]> rec = tx.tree(Trees.POOL_PURGES).get(Keys.of(poolId));
            if (rec.isEmpty()) return true;
            String text = Trees.str(rec.get());
            List<String> blobIds = text.isEmpty() ? List.of() : List.of(text.split("\n"));
            boolean manifestsDone = Trees.purgePoolManifestsSlice(tx, poolId, blobIds, PURGE_SLICE);
            boolean chunksDone = Chunks.purgePoolSlice(tx, poolId, blobIds, PURGE_SLICE);
            if (manifestsDone && chunksDone) {
                tx.tree(Trees.POOL_PURGES).delete(Keys.of(poolId));
                return true;
            }
            return false;
        });
    }

    @Override
    public int finishDeletions() {
        List<String> pending = store().read(tx -> {
            List<String> ids = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.POOL_PURGES).scan()) {
                while (c.next()) ids.add(new Keys.Reader(c.key()).string());
            }
            return ids;
        });
        for (String poolId : pending) {
            while (!purgeSlice(poolId)) {
                // slice by slice
            }
        }
        return pending.size();
    }

    private static long countObjects(ReadTxn tx, String bucket) {
        long n = 0;
        try (Cursor c = tx.tree(Trees.OBJECTS).scanPrefix(Keys.of(bucket))) {
            while (c.next()) n++;
        }
        return n;
    }
}
