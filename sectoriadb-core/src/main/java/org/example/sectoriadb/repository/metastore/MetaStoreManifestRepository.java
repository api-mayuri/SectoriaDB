package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.repository.ManifestRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link ManifestRepository} over the {@code manifests}, {@code objects}, {@code manifests_by_blob} and
 * {@code deleted_manifests} trees. Every public method is a single metastore transaction.
 */
@Repository
public class MetaStoreManifestRepository implements ManifestRepository {

    private final MetaStoreProvider stores;

    public MetaStoreManifestRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    // ------------------------------------------------------------------ plain records

    @Override
    public ManifestEntity save(ManifestEntity entity) {
        return store().write(tx -> {
            Trees.putManifest(tx, entity);
            return entity;
        });
    }

    @Override
    public Optional<ManifestEntity> findById(String id) {
        return store().read(tx -> Trees.manifest(tx, id).map(m -> Trees.resolve(tx, m)));
    }

    @Override
    public boolean existsById(String id) {
        return store().read(tx -> tx.tree(Trees.MANIFESTS).containsKey(Trees.idKey(id)));
    }

    @Override
    public long count() {
        return store().read(tx -> tx.tree(Trees.MANIFESTS).size());
    }

    // ------------------------------------------------------------------ S3 objects

    @Override
    public Optional<ManifestEntity> findCurrent(String bucketName, String objectKey) {
        return store().read(tx -> currentId(tx, bucketName, objectKey)
                .flatMap(id -> Trees.manifest(tx, id))
                .map(m -> Trees.resolve(tx, m)));
    }

    @Override
    public boolean existsCurrent(String bucketName, String objectKey) {
        return store().read(tx -> tx.tree(Trees.OBJECTS).containsKey(Trees.objectKey(bucketName, objectKey)));
    }

    private static Optional<String> currentId(ReadTxn tx, String bucket, String key) {
        return tx.tree(Trees.OBJECTS).get(Trees.objectKey(bucket, key)).map(Trees::str);
    }

    @Override
    public CommitResult commitObject(ManifestEntity entity) {
        String bucket = entity.getBucketName();
        String key = entity.getObjectKey();
        if (bucket == null || key == null) throw new IllegalArgumentException("bucketName and objectKey are required");
        if (entity.getPoolId() == null) throw new IllegalArgumentException("poolId is required");
        try (WriteTxn tx = store().beginWrite()) {
            if (!tx.tree(Trees.POOLS).containsKey(Trees.idKey(entity.getPoolId()))) {
                throw new PoolNotFoundException(entity.getPoolId());
            }
            entity.setDeleted(false);
            Trees.putManifest(tx, entity);
            BTree objects = tx.tree(Trees.OBJECTS);
            byte[] objectKey = Trees.objectKey(bucket, key);
            Optional<String> previous = objects.get(objectKey).map(Trees::str);
            objects.put(objectKey, Trees.bytes(entity.getId()));
            Optional<ManifestEntity> superseded = Optional.empty();
            if (previous.isPresent() && !previous.get().equals(entity.getId())) {
                superseded = Trees.retire(tx, previous.get());
            }
            tx.commit();
            return new CommitResult(entity, superseded);
        }
    }

    @Override
    public Optional<ManifestEntity> deleteObject(String bucketName, String objectKey) {
        try (WriteTxn tx = store().beginWrite()) {
            BTree objects = tx.tree(Trees.OBJECTS);
            byte[] k = Trees.objectKey(bucketName, objectKey);
            Optional<String> id = objects.get(k).map(Trees::str);
            if (id.isEmpty()) return Optional.empty();
            objects.delete(k);
            Optional<ManifestEntity> removed = Trees.retire(tx, id.get());
            tx.commit();
            return removed;
        }
    }

    @Override
    public Optional<ManifestEntity> deleteManifest(String manifestId) {
        try (WriteTxn tx = store().beginWrite()) {
            Optional<ManifestEntity> current = Trees.manifest(tx, manifestId);
            if (current.isEmpty() || current.get().isDeleted()) return Optional.empty();
            ManifestEntity m = current.get();
            if (m.getBucketName() != null && m.getObjectKey() != null) {
                BTree objects = tx.tree(Trees.OBJECTS);
                byte[] k = Trees.objectKey(m.getBucketName(), m.getObjectKey());
                if (objects.get(k).map(v -> Trees.str(v).equals(manifestId)).orElse(false)) objects.delete(k);
            }
            Optional<ManifestEntity> removed = Trees.retire(tx, manifestId);
            tx.commit();
            return removed;
        }
    }

    @Override
    public Optional<ManifestEntity> updateCurrent(String bucketName, String objectKey, Consumer<ManifestEntity> mutator) {
        try (WriteTxn tx = store().beginWrite()) {
            Optional<ManifestEntity> found = currentId(tx, bucketName, objectKey).flatMap(id -> Trees.manifest(tx, id));
            if (found.isEmpty()) return Optional.empty();
            ManifestEntity m = found.get();
            mutator.accept(m);
            Trees.putManifest(tx, m);
            Trees.resolve(tx, m);
            tx.commit();
            return Optional.of(m);
        }
    }

    // ------------------------------------------------------------------ listing

    @Override
    public ObjectListing listObjects(String bucketName, String prefix, String delimiter, String startAfter, int maxKeys) {
        if (prefix == null) prefix = "";
        boolean grouped = delimiter != null && !delimiter.isEmpty();
        try (ReadTxn tx = store().beginRead()) {
            BTree objects = tx.tree(Trees.OBJECTS);
            BTree manifests = tx.tree(Trees.MANIFESTS);

            byte[] rangeEnd = Keys.prefixEnd(Keys.builder().string(bucketName).stringPrefix(prefix).build());
            byte[] from = Keys.builder().string(bucketName).string(prefix).build();
            if (startAfter != null && !startAfter.isEmpty()) {
                from = max(from, Keys.builder().string(bucketName).string(startAfter).build());
                // the marker is itself a common prefix: everything under it was already returned
                if (grouped && startAfter.startsWith(prefix)) {
                    int d = startAfter.indexOf(delimiter, prefix.length());
                    if (d >= 0 && d + delimiter.length() == startAfter.length()) {
                        byte[] past = Keys.prefixEnd(Keys.builder().string(bucketName).stringPrefix(startAfter).build());
                        if (past == null) return new ObjectListing(List.of(), List.of(), false, null);
                        from = max(from, past);
                    }
                }
            }

            List<ObjectSummary> found = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            boolean truncated = false;
            String last = null;
            int count = 0;
            if (rangeEnd != null && Arrays.compareUnsigned(from, rangeEnd) >= 0) {
                return new ObjectListing(found, prefixes, false, null);
            }
            Cursor c = objects.scan(from, rangeEnd);
            try {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    r.string();                       // bucket
                    String key = r.string();
                    if (startAfter != null && key.equals(startAfter)) continue;
                    if (grouped) {
                        int d = key.indexOf(delimiter, prefix.length());
                        if (d >= 0) {
                            if (count == maxKeys) {
                                truncated = true;
                                break;
                            }
                            String common = key.substring(0, d + delimiter.length());
                            prefixes.add(common);
                            last = common;
                            count++;
                            // skip the whole range of this common prefix instead of visiting its keys
                            byte[] next = Keys.prefixEnd(Keys.builder().string(bucketName).stringPrefix(common).build());
                            c.close();
                            if (next == null || (rangeEnd != null && Arrays.compareUnsigned(next, rangeEnd) >= 0)) {
                                break;
                            }
                            c = objects.scan(next, rangeEnd);
                            continue;
                        }
                    }
                    if (count == maxKeys) {
                        truncated = true;
                        break;
                    }
                    String manifestId = Trees.str(c.value());
                    ManifestEntity m = tx.tree(Trees.MANIFESTS).get(Trees.idKey(manifestId))
                            .map(v -> ManifestCodec.decode(v, false)).orElse(null);
                    if (m == null) continue;          // cannot happen inside one snapshot; be defensive
                    found.add(new ObjectSummary(key, manifestId, m.getEtag(), m.getTotalBytes(), m.getCreatedAt()));
                    last = key;
                    count++;
                }
            } finally {
                c.close();
            }
            return new ObjectListing(found, prefixes, truncated, last);
        }
    }

    private static byte[] max(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b) >= 0 ? a : b;
    }

    @Override
    public boolean hasObjects(String bucketName) {
        return store().read(tx -> {
            try (Cursor c = tx.tree(Trees.OBJECTS).scanPrefix(Keys.of(bucketName))) {
                return c.next();
            }
        });
    }

    @Override
    public BucketStats countAndSize(String bucketName) {
        return store().read(tx -> {
            long objects = 0;
            long bytes = 0;
            BTree manifests = tx.tree(Trees.MANIFESTS);
            try (Cursor c = tx.tree(Trees.OBJECTS).scanPrefix(Keys.of(bucketName))) {
                while (c.next()) {
                    objects++;
                    Optional<byte[]> v = manifests.get(Trees.idKey(Trees.str(c.value())));
                    if (v.isPresent()) bytes += ManifestCodec.decode(v.get(), false).getTotalBytes();
                }
            }
            return new BucketStats(objects, bytes);
        });
    }

    // ------------------------------------------------------------------ admin queries

    @Override
    public List<ManifestEntity> findAllLive() {
        return store().read(tx -> {
            List<ManifestEntity> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value());
                    if (!m.isDeleted()) out.add(m);
                }
            }
            for (ManifestEntity m : out) Trees.resolve(tx, m);
            return out;
        });
    }

    @Override
    public List<ManifestEntity> findByBlobId(String blobId, boolean liveOnly) {
        return store().read(tx -> {
            List<ManifestEntity> out = new ArrayList<>();
            for (String id : Trees.manifestIdsOfBlob(tx, blobId)) {
                Trees.manifest(tx, id).ifPresent(m -> {
                    if (!liveOnly || !m.isDeleted()) out.add(Trees.resolve(tx, m));
                });
            }
            return out;
        });
    }

    @Override
    public long countLiveByBlobId(String blobId) {
        return store().read(tx -> {
            long n = 0;
            for (String id : Trees.manifestIdsOfBlob(tx, blobId)) {
                if (Trees.manifest(tx, id).map(m -> !m.isDeleted()).orElse(false)) n++;
            }
            return n;
        });
    }

    // ------------------------------------------------------------------ GC queue

    @Override
    public long gcQueueSize() {
        return store().read(tx -> tx.tree(Trees.DELETED_MANIFESTS).size());
    }

    @Override
    public List<GcEntry> gcQueue(int limit) {
        return store().read(tx -> {
            List<GcEntry> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.DELETED_MANIFESTS).scan()) {
                while (out.size() < limit && c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    out.add(new GcEntry(r.longUnsigned(), r.string()));
                }
            }
            return out;
        });
    }
}
