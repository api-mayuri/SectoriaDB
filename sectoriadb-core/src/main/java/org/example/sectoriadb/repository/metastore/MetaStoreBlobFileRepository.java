package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link BlobFileRepository} over the {@code blobs} and {@code blobs_by_pool} trees. */
@Repository
public class MetaStoreBlobFileRepository implements BlobFileRepository {

    private final MetaStoreProvider stores;

    public MetaStoreBlobFileRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    @Override
    public BlobFileEntity save(BlobFileEntity entity) {
        return store().writeGrouped(tx -> {
            Trees.putBlob(tx, entity);
            return entity;
        });
    }

    @Override
    public Optional<BlobFileEntity> findById(String id) {
        return store().read(tx -> Trees.blob(tx, id, true));
    }

    @Override
    public List<BlobFileEntity> findAll() {
        return store().read(tx -> {
            List<BlobFileEntity> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.BLOBS).scan()) {
                while (c.next()) out.add(BlobCodec.decode(c.value()));
            }
            for (BlobFileEntity b : out) {
                if (b.getPoolId() != null) Trees.pool(tx, b.getPoolId()).ifPresent(b::setPool);
            }
            return out;
        });
    }

    @Override
    public boolean existsById(String id) {
        return store().read(tx -> tx.tree(Trees.BLOBS).containsKey(Trees.idKey(id)));
    }

    @Override
    public long count() {
        return store().read(tx -> tx.tree(Trees.BLOBS).size());
    }

    @Override
    public List<BlobFileEntity> findByPoolId(String poolId) {
        return store().read(tx -> {
            List<BlobFileEntity> out = new ArrayList<>();
            for (String id : Trees.blobIdsOfPool(tx, poolId)) Trees.blob(tx, id, true).ifPresent(out::add);
            return out;
        });
    }

    @Override
    public long countByPoolId(String poolId) {
        return store().read(tx -> {
            long n = 0;
            try (Cursor c = tx.tree(Trees.BLOBS_BY_POOL).scanPrefix(Keys.of(poolId))) {
                while (c.next()) n++;
            }
            return n;
        });
    }

    @Override
    public void deleteUnreferenced(String blobId) {
        try (WriteTxn tx = store().beginWrite()) {
            Optional<BlobFileEntity> blob = Trees.blob(tx, blobId, false);
            if (blob.isEmpty()) return;
            long live = 0;
            for (String mid : Trees.manifestIdsOfBlob(tx, blobId)) {
                if (Trees.manifest(tx, mid).map(m -> !m.isDeleted()).orElse(false)) live++;
            }
            if (live > 0) throw new BlobInUseException(blobId, live);
            Trees.deleteBlobRecord(tx, blob.get());
            Trees.purgeManifests(tx, List.of(blobId), null);
            tx.commit();
        }
    }

    @Override
    public int replaceBlob(String oldBlobId, BlobFileEntity replacement) {
        try (WriteTxn tx = store().beginWrite()) {
            BlobFileEntity old = Trees.blob(tx, oldBlobId, false)
                    .orElseThrow(() -> new IllegalStateException("Blob not found: " + oldBlobId));
            Trees.putBlob(tx, replacement);
            int moved = 0;
            for (String mid : Trees.manifestIdsOfBlob(tx, oldBlobId)) {
                ManifestEntity m = Trees.manifest(tx, mid).orElse(null);
                if (m == null || !oldBlobId.equals(m.getBlobFileId())) continue;
                m.setBlobFileId(replacement.getId());
                Trees.putManifest(tx, m);   // moves its manifests_by_blob entry from the old blob to the new one
                moved++;
            }
            Trees.deleteBlobRecord(tx, old);
            tx.commit();
            return moved;
        }
    }
}
