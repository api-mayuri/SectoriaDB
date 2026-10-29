package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.LifecycleRule;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link ManifestRepository} over the {@code manifests}, {@code objects}, {@code manifests_by_blob} and
 * {@code deleted_manifests} trees. Every public method is one atomic metastore write; the hot ones run as group-commit
 * bodies ({@link MetaStore#writeGrouped}), i.e. share a commit (and its fsyncs) with concurrent writers.
 */
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
        return store().writeGrouped(tx -> {
            Trees.putManifest(tx, entity);
            return entity;
        });
    }

    @Override
    public ManifestEntity saveNew(ManifestEntity entity) {
        if (entity.getPoolId() == null) throw new IllegalArgumentException("poolId is required");
        return store().writeGrouped(tx -> {
            if (Trees.manifest(tx, entity.getId()).isPresent()) {
                throw new IllegalArgumentException("Manifest already exists: " + entity.getId());
            }
            if (entity.getStorageKind() == StorageKind.CHUNKED) {
                Chunks.addRefs(tx, entity.getPoolId(), entity.chunkKeyArray(), entity.getStagedChunks());
            }
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
        return commitObject(entity, CommitPrecondition.NONE);
    }

    @Override
    public CommitResult commitObject(ManifestEntity entity, CommitPrecondition precondition) {
        String bucket = entity.getBucketName();
        String key = entity.getObjectKey();
        if (bucket == null || key == null) throw new IllegalArgumentException("bucketName and objectKey are required");
        if (entity.getPoolId() == null) throw new IllegalArgumentException("poolId is required");
        return store().writeGrouped(tx -> commitInTxn(tx, entity, precondition));
    }

    /**
     * The commit of an object manifest inside the caller's transaction (also used by CompleteMultipartUpload, whose
     * MULTIPART manifest takes over the references of the part manifests instead of adding new ones).
     */
    static CommitResult commitInTxn(WriteTxn tx, ManifestEntity entity, CommitPrecondition precondition) {
        String bucket = entity.getBucketName();
        String key = entity.getObjectKey();
        PoolEntity pool = Trees.pool(tx, entity.getPoolId()).orElseThrow(() -> new PoolNotFoundException(entity.getPoolId()));
        String versioning = pool.getVersioning();
        Trees.ensureTotals(tx);
        BTree objects = tx.tree(Trees.OBJECTS);
        byte[] objectKey = Trees.objectKey(bucket, key);
        Optional<String> previous = objects.get(objectKey).map(Trees::str);
        if (!precondition.isNone()) {
            String currentEtag = previous.flatMap(id -> Trees.manifest(tx, id)).map(ManifestEntity::getEtag).orElse(null);
            if (!precondition.holds(previous.isPresent() && currentEtag == null ? "" : currentEtag)) {
                throw new CommitPreconditionException(previous.isEmpty());
            }
        }
        long previousBytes = previous.flatMap(id -> Trees.manifest(tx, id)).map(ManifestEntity::getTotalBytes).orElse(0L);
        entity.setDeleted(false);
        // the version id: the manifest id under Enabled, the null version otherwise (unversioned, Suspended)
        entity.setVersionId(ENABLED.equals(versioning) ? entity.getId() : null);
        Optional<ManifestEntity> existing = Trees.manifest(tx, entity.getId());
        if (entity.getStorageKind() == StorageKind.CHUNKED && (existing.isEmpty() || existing.get().isDeleted())) {
            // references first, retirement of the replaced version after: a chunk both versions share never
            // passes through zero and is never queued for collection
            Chunks.addRefs(tx, entity.getPoolId(), entity.chunkKeyArray(), entity.getStagedChunks());
        }
        Trees.putManifest(tx, entity);
        objects.put(objectKey, Trees.bytes(entity.getId()));
        Trees.adjustTotals(tx, previous.isPresent() ? 0 : 1, entity.getTotalBytes() - previousBytes);
        Optional<ManifestEntity> superseded = Optional.empty();
        if (previous.isPresent() && !previous.get().equals(entity.getId())) {
            if (versioning == null) {
                superseded = Trees.retire(tx, previous.get());
            } else {
                superseded = pushBack(tx, bucket, key, previous.get(), versioning);
            }
        }
        if (versioning != null) {
            if (SUSPENDED.equals(versioning)) removeNullRow(tx, bucket, key);   // the new null version replaces it
            if (ENABLED.equals(versioning) && Versions.byId(tx, bucket, key, entity.getId()).isEmpty()) {   // a retried commit has its row
                Versions.add(tx, bucket, key, Versions.VERSION, entity.getId(), entity.getId(), millis(entity));
            }
        }
        return new CommitResult(entity, superseded);
    }

    static final String ENABLED = "Enabled", SUSPENDED = "Suspended";

    private static long millis(ManifestEntity m) {
        return m.getCreatedAt() != null ? m.getCreatedAt().toEpochMilli() : System.currentTimeMillis();
    }

    /**
     * The previous current version of a key in a versioned bucket gives way to a newer one. A real version already has its
     * row and simply stays; the null version gets a row under {@code Enabled}, and is replaced (removed for good) under
     * {@code Suspended}. Returns the version removed for good, if any.
     */
    private static Optional<ManifestEntity> pushBack(WriteTxn tx, String bucket, String key, String previousId, String versioning) {
        Optional<ManifestEntity> prev = Trees.manifest(tx, previousId);
        if (prev.isEmpty() || prev.get().getVersionId() != null) return Optional.empty();
        if (ENABLED.equals(versioning)) {
            Versions.add(tx, bucket, key, Versions.VERSION, Versions.NULL_ID, previousId, millis(prev.get()));
            return Optional.empty();
        }
        return Trees.retire(tx, previousId);
    }

    /** Removes a noncurrent null version or null delete marker of a key (Suspended writes replace the null version). */
    private static void removeNullRow(WriteTxn tx, String bucket, String key) {
        Versions.byId(tx, bucket, key, Versions.NULL_ID).ifPresent(r -> {
            Versions.remove(tx, r);
            if (!r.marker()) Trees.retire(tx, r.manifestId());
        });
    }

    @Override
    public Optional<ManifestEntity> deleteObject(String bucketName, String objectKey) {
        return store().writeGrouped(tx -> {
            BTree objects = tx.tree(Trees.OBJECTS);
            byte[] k = Trees.objectKey(bucketName, objectKey);
            Optional<String> id = objects.get(k).map(Trees::str);
            if (id.isEmpty()) return Optional.empty();
            Trees.ensureTotals(tx);
            objects.delete(k);
            Optional<ManifestEntity> removed = Trees.retire(tx, id.get());
            Trees.adjustTotals(tx, -1, -removed.map(ManifestEntity::getTotalBytes).orElse(0L));
            return removed;
        });
    }

    @Override
    public DeleteOutcome delete(String bucketName, String objectKey, String versionId) {
        return store().writeGrouped(tx -> deleteInTxn(tx, bucketName, objectKey, versionId, System.currentTimeMillis()));
    }

    private static DeleteOutcome deleteInTxn(WriteTxn tx, String bucket, String key, String versionId, long now) {
        String versioning = Trees.poolByName(tx, bucket).map(PoolEntity::getVersioning).orElse(null);
        BTree objects = tx.tree(Trees.OBJECTS);
        byte[] k = Trees.objectKey(bucket, key);
        Optional<ManifestEntity> current = objects.get(k).map(Trees::str).flatMap(id -> Trees.manifest(tx, id));
        Trees.ensureTotals(tx);
        if (versionId == null) {
            if (versioning == null) {
                if (current.isEmpty()) return new DeleteOutcome(Optional.empty(), null, false);
                objects.delete(k);
                Optional<ManifestEntity> removed = Trees.retire(tx, current.get().getId());
                Trees.adjustTotals(tx, -1, -removed.map(ManifestEntity::getTotalBytes).orElse(0L));
                return new DeleteOutcome(removed, null, false);
            }
            Optional<ManifestEntity> removed = Optional.empty();
            if (current.isPresent()) {
                objects.delete(k);
                Trees.adjustTotals(tx, -1, -current.get().getTotalBytes());
                removed = pushBack(tx, bucket, key, current.get().getId(), versioning);
            }
            String markerId;
            if (ENABLED.equals(versioning)) {
                markerId = Versions.newMarkerId();
            } else {
                removeNullRow(tx, bucket, key);
                markerId = Versions.NULL_ID;
            }
            Versions.add(tx, bucket, key, Versions.MARKER, markerId, null, now);
            return new DeleteOutcome(removed.map(m -> Trees.resolve(tx, m)), markerId, true);
        }
        // a version or marker for good
        if (Versions.NULL_ID.equals(versionId) && current.isPresent() && current.get().getVersionId() == null) {
            objects.delete(k);
            Trees.adjustTotals(tx, -1, -current.get().getTotalBytes());
            Optional<ManifestEntity> removed = Trees.retire(tx, current.get().getId());
            promote(tx, bucket, key);
            return new DeleteOutcome(removed, versionId, false);
        }
        Optional<Versions.Row> row = Versions.byId(tx, bucket, key, versionId);
        if (row.isEmpty()) return new DeleteOutcome(Optional.empty(), versionId, false);   // S3: deleting a missing version is a no-op
        Versions.Row r = row.get();
        Versions.remove(tx, r);
        Optional<ManifestEntity> removed = Optional.empty();
        if (!r.marker()) {
            if (current.isPresent() && current.get().getId().equals(r.manifestId())) {
                objects.delete(k);
                Trees.adjustTotals(tx, -1, -current.get().getTotalBytes());
            }
            removed = Trees.retire(tx, r.manifestId());
        }
        if (!objects.containsKey(k)) promote(tx, bucket, key);
        return new DeleteOutcome(removed, r.versionId(), r.marker());
    }

    /**
     * After the newest version of a key went away: the next one becomes current, unless it is a delete marker. A null
     * version that becomes current leaves the history again: the current null version is never a row (otherwise it would
     * be listed twice, and deleting it would leave a row that points at nothing and keeps the bucket from being empty).
     */
    private static void promote(WriteTxn tx, String bucket, String key) {
        Optional<Versions.Row> next = Versions.newest(tx, bucket, key).filter(r -> !r.marker());
        if (next.isEmpty()) return;
        Optional<ManifestEntity> m = Trees.manifest(tx, next.get().manifestId());
        if (m.isEmpty()) {   // a row without its version (must not exist): drop it and look further
            Versions.remove(tx, next.get());
            promote(tx, bucket, key);
            return;
        }
        if (m.get().getVersionId() == null) Versions.remove(tx, next.get());
        tx.tree(Trees.OBJECTS).put(Trees.objectKey(bucket, key), Trees.bytes(m.get().getId()));
        Trees.adjustTotals(tx, 1, m.get().getTotalBytes());
    }

    @Override
    public VersionLookup findVersion(String bucketName, String objectKey, String versionId) {
        return store().read(tx -> {
            if (Versions.NULL_ID.equals(versionId)) {
                Optional<ManifestEntity> cur = currentId(tx, bucketName, objectKey).flatMap(id -> Trees.manifest(tx, id))
                        .filter(m -> m.getVersionId() == null);
                if (cur.isPresent()) return new VersionLookup(cur.map(m -> Trees.resolve(tx, m)), false, true);
            }
            Optional<Versions.Row> r = Versions.byId(tx, bucketName, objectKey, versionId);
            if (r.isEmpty()) return VersionLookup.NONE;
            if (r.get().marker()) return new VersionLookup(Optional.empty(), true, true);
            Optional<ManifestEntity> m = Trees.manifest(tx, r.get().manifestId()).map(x -> Trees.resolve(tx, x));
            return m.isPresent() ? new VersionLookup(m, false, true) : VersionLookup.NONE;
        });
    }

    @Override
    public Optional<String> latestDeleteMarker(String bucketName, String objectKey) {
        return store().read(tx -> {
            if (tx.tree(Trees.OBJECTS).containsKey(Trees.objectKey(bucketName, objectKey))) return Optional.empty();
            return Versions.newest(tx, bucketName, objectKey).filter(Versions.Row::marker).map(Versions.Row::versionId);
        });
    }

    @Override
    public boolean hasVersions(String bucketName) {
        return store().read(tx -> Versions.any(tx, bucketName));
    }

    @Override
    public Optional<ManifestEntity> deleteManifest(String manifestId) {
        return store().writeGrouped(tx -> {
            Optional<ManifestEntity> current = Trees.manifest(tx, manifestId);
            if (current.isEmpty() || current.get().isDeleted()) return Optional.empty();
            ManifestEntity m = current.get();
            if (m.getBucketName() != null && m.getObjectKey() != null) {
                BTree objects = tx.tree(Trees.OBJECTS);
                byte[] k = Trees.objectKey(m.getBucketName(), m.getObjectKey());
                boolean wasCurrent = objects.get(k).map(v -> Trees.str(v).equals(manifestId)).orElse(false);
                if (wasCurrent) {
                    Trees.ensureTotals(tx);
                    objects.delete(k);
                    Trees.adjustTotals(tx, -1, -m.getTotalBytes());
                }
                // a version of a versioned bucket also leaves its history
                Versions.byId(tx, m.getBucketName(), m.getObjectKey(), Versions.idOf(m))
                        .filter(r -> manifestId.equals(r.manifestId())).ifPresent(r -> Versions.remove(tx, r));
                if (wasCurrent) {
                    Trees.ensureTotals(tx);
                    promote(tx, m.getBucketName(), m.getObjectKey());
                }
            }
            return Trees.retire(tx, manifestId);
        });
    }

    @Override
    public Optional<ManifestEntity> updateCurrent(String bucketName, String objectKey, Consumer<ManifestEntity> mutator) {
        return store().writeGrouped(tx -> {
            Optional<ManifestEntity> found = currentId(tx, bucketName, objectKey).flatMap(id -> Trees.manifest(tx, id));
            if (found.isEmpty()) return Optional.empty();
            ManifestEntity m = found.get();
            mutator.accept(m);
            Trees.putManifest(tx, m);
            Trees.resolve(tx, m);
            return Optional.of(m);
        });
    }

    // ------------------------------------------------------------------ listing

    @Override
    public VersionListing listVersions(String bucketName, String prefix, String delimiter, String keyMarker,
                                       String versionIdMarker, int maxKeys) {
        String pfx = prefix == null ? "" : prefix;
        boolean grouped = delimiter != null && !delimiter.isEmpty();
        if (maxKeys <= 0) return new VersionListing(List.of(), List.of(), false, null, null);
        try (ReadTxn tx = store().beginRead()) {
            byte[] range = Keys.builder().string(bucketName).stringPrefix(pfx).build();
            List<VersionEntry> out = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            int count = 0;
            boolean truncated = false;
            String nextKey = null, nextVersion = null;
            try (Cursor oc = tx.tree(Trees.OBJECTS).scanPrefix(range); Cursor vc = Versions.scan(tx, bucketName, pfx)) {
                boolean oHas = oc.next(), vHas = vc.next();
                Versions.Row vRow = vHas ? Versions.decodeRow(vc) : null;
                String lastPrefix = null;
                outer:
                while (oHas || vRow != null) {
                    String oKey = null;
                    if (oHas) {
                        Keys.Reader r = new Keys.Reader(oc.key());
                        r.string();
                        oKey = r.string();
                    }
                    String key = oKey == null ? vRow.key() : vRow == null ? oKey
                            : (compareKeys(oKey, vRow.key()) <= 0 ? oKey : vRow.key());
                    List<VersionEntry> group = new ArrayList<>();
                    boolean nullCurrent = false;
                    if (key.equals(oKey)) {
                        Optional<ManifestEntity> m = Trees.manifest(tx, Trees.str(oc.value()));
                        if (m.isPresent() && m.get().getVersionId() == null) {
                            nullCurrent = true;
                            group.add(entry(key, Versions.NULL_ID, true, m.get()));
                        }
                        oHas = oc.next();
                    }
                    boolean first = true;
                    while (vRow != null && vRow.key().equals(key)) {
                        boolean latest = first && !nullCurrent;
                        if (vRow.marker()) {
                            group.add(new VersionEntry(key, vRow.versionId(), latest, true, null, 0, Instant.ofEpochMilli(vRow.timeMillis())));
                        } else {
                            Optional<ManifestEntity> m = Trees.manifest(tx, vRow.manifestId());
                            if (m.isPresent()) group.add(entry(key, vRow.versionId(), latest, m.get()));
                        }
                        first = false;
                        vRow = vc.next() ? Versions.decodeRow(vc) : null;
                    }
                    // the markers: keys before keyMarker, and the versions up to versionIdMarker of keyMarker, are done
                    if (keyMarker != null && !keyMarker.isEmpty()) {
                        int cmp = compareKeys(key, keyMarker);
                        if (cmp < 0) continue;
                        if (cmp == 0) {
                            if (versionIdMarker == null || versionIdMarker.isEmpty()) continue;
                            int at = -1;
                            for (int i = 0; i < group.size(); i++) if (group.get(i).versionId().equals(versionIdMarker)) at = i;
                            group = at < 0 ? List.of() : group.subList(at + 1, group.size());
                        }
                    }
                    if (grouped) {
                        int d = key.indexOf(delimiter, pfx.length());
                        if (d >= 0) {
                            String common = key.substring(0, d + delimiter.length());
                            if (common.equals(lastPrefix)) continue;
                            if (count == maxKeys) {
                                truncated = true;
                                break;
                            }
                            prefixes.add(common);
                            lastPrefix = common;
                            nextKey = common;
                            nextVersion = null;
                            count++;
                            continue;
                        }
                    }
                    for (VersionEntry e : group) {
                        if (count == maxKeys) {
                            truncated = true;
                            break outer;
                        }
                        out.add(e);
                        nextKey = e.key();
                        nextVersion = e.versionId();
                        count++;
                    }
                }
            }
            return new VersionListing(out, prefixes, truncated, truncated ? nextKey : null, truncated ? nextVersion : null);
        }
    }

    private static VersionEntry entry(String key, String versionId, boolean latest, ManifestEntity m) {
        return new VersionEntry(key, versionId, latest, false, m.getEtag(), m.getTotalBytes(), m.getCreatedAt());
    }

    /** The order of keys in the trees (their encoded form compared unsigned), which is not String.compareTo. */
    private static int compareKeys(String a, String b) {
        return Arrays.compareUnsigned(Keys.builder().string(a).build(), Keys.builder().string(b).build());
    }

    @Override
    public int applyLifecycle(String bucketName, List<LifecycleRule> rules, long nowMillis, int limit) {
        if (rules == null || rules.isEmpty() || limit <= 0) return 0;
        // find what is due on a snapshot, then remove it in one write (each removal re-checks its row)
        List<String[]> due = new ArrayList<>();   // {key, versionId}
        try (ReadTxn tx = store().beginRead()) {
            for (LifecycleRule rule : rules) {
                if (!rule.enabled()) continue;
                try (Cursor vc = Versions.scan(tx, bucketName, rule.prefix())) {
                    String key = null;
                    long newerTime = 0;
                    boolean newestOfKey = false, keyHasCurrent = false;
                    List<Versions.Row> keyRows = new ArrayList<>();
                    while (due.size() < limit) {
                        Versions.Row r = vc.next() ? Versions.decodeRow(vc) : null;
                        if (r == null || !r.key().equals(key)) {
                            if (key != null && rule.expiredObjectDeleteMarker() && !keyHasCurrent && keyRows.size() == 1
                                    && keyRows.get(0).marker()) {
                                due.add(new String[]{key, keyRows.get(0).versionId()});   // a marker with nothing behind it
                            }
                            if (r == null) break;
                            key = r.key();
                            keyRows = new ArrayList<>();
                            String k = key;
                            Optional<ManifestEntity> cur = currentId(tx, bucketName, k).flatMap(id -> Trees.manifest(tx, id));
                            keyHasCurrent = cur.isPresent();
                            // with a null current version every row is noncurrent since that version was written
                            newestOfKey = cur.isEmpty() || cur.get().getVersionId() != null;
                            newerTime = cur.filter(m -> m.getVersionId() == null).map(MetaStoreManifestRepository::millis).orElse(0L);
                        }
                        keyRows.add(r);
                        if (newestOfKey) {
                            newestOfKey = false;   // the newest row is current (or the newest marker): not noncurrent
                        } else if (rule.noncurrentDays() != null
                                && nowMillis - newerTime >= rule.noncurrentDays() * 86_400_000L) {
                            due.add(new String[]{r.key(), r.versionId()});
                            keyRows.remove(keyRows.size() - 1);
                        }
                        newerTime = r.timeMillis();
                    }
                }
            }
        }
        if (due.isEmpty()) return 0;
        return store().writeGrouped(tx -> {
            int n = 0;
            for (String[] d : due) {
                DeleteOutcome o = deleteInTxn(tx, bucketName, d[0], d[1], nowMillis);
                if (o.removed().isPresent() || o.deleteMarker()) n++;
            }
            return n;
        });
    }

    @Override
    public ObjectListing listObjects(String bucketName, String prefix, String delimiter, String startAfter, int maxKeys) {
        if (prefix == null) prefix = "";
        boolean grouped = delimiter != null && !delimiter.isEmpty();
        // S3: MaxKeys=0 is an empty, non-truncated answer
        if (maxKeys <= 0) return new ObjectListing(List.of(), List.of(), false, null);
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

    @Override
    public BucketStats totals() {
        long[] t = store().read(Trees::totals);
        if (t == null) {
            t = store().writeGrouped(tx -> {
                Trees.ensureTotals(tx);
                return Trees.totals(tx);
            });
        }
        return new BucketStats(t[0], t[1]);
    }

    // ------------------------------------------------------------------ admin queries

    @Override
    public List<ManifestEntity> findAllLive() {
        return store().read(tx -> {
            List<ManifestEntity> out = new ArrayList<>();
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value());
                    if (!m.isDeleted() && !m.isMultipartPart()) out.add(m);
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
                ManifestEntity m = Trees.manifest(tx, id).orElse(null);
                if (m == null || m.isDeleted()) continue;
                if (m.getStorageKind() == StorageKind.MULTIPART) {
                    n += m.getSegments().stream()
                            .filter(sg -> sg.kind() == StorageKind.SMALL && blobId.equals(sg.smallBlobId())).count();
                } else {
                    n++;
                }
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
