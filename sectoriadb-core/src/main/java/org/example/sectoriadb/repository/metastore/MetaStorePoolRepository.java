package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.PoolRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** {@link PoolRepository} over the {@code pools} and {@code pools_by_name} trees. */
@Repository
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

    @Override
    public List<BlobFileEntity> deleteBucket(String poolId) {
        try (WriteTxn tx = store().beginWrite()) {
            PoolEntity pool = Trees.pool(tx, poolId)
                    .orElseThrow(() -> new IllegalArgumentException("Pool not found: " + poolId));
            long objects = countObjects(tx, pool.getName());
            if (objects > 0) {
                throw new IllegalStateException("BucketNotEmpty: bucket '" + pool.getName() + "' still has "
                        + objects + " object(s).");
            }
            List<String> blobIds = Trees.blobIdsOfPool(tx, poolId);
            List<BlobFileEntity> removed = new ArrayList<>();
            for (String id : blobIds) {
                Trees.blob(tx, id, false).ifPresent(b -> {
                    removed.add(b);
                    Trees.deleteBlobRecord(tx, b);
                });
            }
            Trees.purgeManifests(tx, blobIds, poolId);
            Chunks.purgePool(tx, poolId, blobIds);
            Trees.deletePoolRecord(tx, pool);
            tx.commit();
            return removed;
        }
    }

    private static long countObjects(ReadTxn tx, String bucket) {
        long n = 0;
        try (Cursor c = tx.tree(Trees.OBJECTS).scanPrefix(Keys.of(bucket))) {
            while (c.next()) n++;
        }
        return n;
    }
}
