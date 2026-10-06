package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** put / get / update / delete and index consistency of every tree behind the three repositories. */
class MetaStoreRepositoriesTest {

    @TempDir
    Path dir;

    MetaStore store;
    PoolRepository pools;
    BlobFileRepository blobs;
    ManifestRepository manifests;

    @BeforeEach
    void open() {
        store = MetaStore.open(dir.resolve("t.db"), MetaStoreOptions.defaults().fsync(false));
        MetaStoreProvider p = MetaStoreProvider.of(store);
        pools = new MetaStorePoolRepository(p);
        blobs = new MetaStoreBlobFileRepository(p);
        manifests = new MetaStoreManifestRepository(p);
    }

    @AfterEach
    void close() {
        MetaInvariants.check(store);
        store.close();
    }

    private PoolEntity pool(String id, String name) {
        PoolEntity p = new PoolEntity(id, name, "/data/" + name, Instant.parse("2026-01-01T00:00:00Z"));
        return pools.save(p);
    }

    private BlobFileEntity blob(String id, String poolId, BlobKind kind) {
        BlobFileEntity b = new BlobFileEntity();
        b.setId(id);
        b.setPoolId(poolId);
        b.setKind(kind);
        b.setFileName(id + ".raw");
        b.setFilePath("/data/" + id + ".raw");
        b.setNumBuckets(kind == BlobKind.CUCKOO ? 64 : 0);
        b.setChunkSize(kind == BlobKind.CUCKOO ? 4096 : 0);
        b.setTotalBytes(1234);
        b.setCreatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return blobs.save(b);
    }

    private ManifestEntity manifest(String id, PoolEntity pool, String blobId, String key, long... chunkKeys) {
        ManifestEntity m = new ManifestEntity();
        m.setId(id);
        m.setPoolId(pool.getId());
        m.setBlobFileId(blobId);
        m.setStorageKind(StorageKind.CHUNKED);
        m.setChunkSize(4096);
        m.setChunkKeyArray(chunkKeys);
        m.setTotalChunks(chunkKeys.length);
        m.setTotalBytes(chunkKeys.length * 4096L);
        m.setLastChunkSize(4096);
        m.setCreatedAt(Instant.parse("2026-02-03T04:05:06Z"));
        m.setSourceFileName("f");
        if (key != null) {
            m.setBucketName(pool.getName());
            m.setObjectKey(key);
            m.setEtag("\"e-" + id + "\"");
            m.setContentType("text/plain");
        }
        return m;
    }

    // ── pools ────────────────────────────────────────────────────────────────

    @Test
    void poolPutGetUpdateDelete() {
        PoolEntity a = pool("p1", "alpha");
        a.setPolicy("{\"x\":1}");
        a.setAcl("public-read");
        pools.save(a);

        PoolEntity back = pools.findById("p1").orElseThrow();
        assertEquals("alpha", back.getName());
        assertEquals("public-read", back.getAcl());
        assertEquals("{\"x\":1}", back.getPolicy());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), back.getCreatedAt());
        assertEquals("p1", pools.findByName("alpha").orElseThrow().getId());
        assertTrue(pools.existsById("p1"));
        assertTrue(pools.existsByName("alpha"));
        assertEquals(1, pools.count());

        Optional<PoolEntity> updated = pools.updateByName("alpha", p -> p.setAcl("private"));
        assertEquals("private", updated.orElseThrow().getAcl());
        assertEquals("private", pools.findById("p1").orElseThrow().getAcl());
        assertTrue(pools.updateByName("nope", p -> fail()).isEmpty());

        pools.delete(a);
        assertTrue(pools.findById("p1").isEmpty());
        assertTrue(pools.findByName("alpha").isEmpty());
        assertEquals(0, pools.count());
    }

    @Test
    void poolNamesAreUniqueAndRenameMovesTheIndex() {
        pool("p1", "alpha");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> pool("p2", "alpha"));
        assertTrue(e.getMessage().contains("already exists"));
        assertEquals(1, pools.count(), "the failed save changed nothing");
        assertTrue(pools.findById("p2").isEmpty());

        PoolEntity renamed = pools.findById("p1").orElseThrow();
        renamed.setName("beta");
        pools.save(renamed);
        assertTrue(pools.findByName("alpha").isEmpty(), "old name is free again");
        assertEquals("p1", pools.findByName("beta").orElseThrow().getId());
        pool("p2", "alpha");   // and can be reused
        assertEquals(2, pools.findAll().size());
    }

    // ── blobs ────────────────────────────────────────────────────────────────

    @Test
    void blobsAreIndexedByPool() {
        PoolEntity a = pool("pa", "a");
        PoolEntity b = pool("pb", "b");
        blob("b1", "pa", BlobKind.CUCKOO);
        blob("b2", "pa", BlobKind.SMALL);
        blob("b3", "pb", BlobKind.CUCKOO);

        assertEquals(2, blobs.countByPoolId("pa"));
        assertEquals(1, blobs.countByPoolId("pb"));
        assertEquals(List.of("b1", "b2"), blobs.findByPoolId("pa").stream().map(BlobFileEntity::getId).sorted().toList());
        BlobFileEntity b2 = blobs.findById("b2").orElseThrow();
        assertEquals(BlobKind.SMALL, b2.getKind());
        assertEquals("a", b2.getPool().getName(), "pool reference is resolved");
        assertEquals(3, blobs.count());
        assertEquals(3, blobs.findAll().size());

        // moving a blob to another pool moves its index entry
        BlobFileEntity b1 = blobs.findById("b1").orElseThrow();
        b1.setPoolId("pb");
        blobs.save(b1);
        assertEquals(1, blobs.countByPoolId("pa"));
        assertEquals(2, blobs.countByPoolId("pb"));

        blobs.deleteUnreferenced("b3");
        assertFalse(blobs.existsById("b3"));
        assertEquals(1, blobs.countByPoolId("pb"));
    }

    @Test
    void blobWithLiveManifestsCannotBeDeleted() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        manifests.commitObject(manifest("m1", p, "blob", "k", 1, 2));
        var e = assertThrows(BlobFileRepository.BlobInUseException.class, () -> blobs.deleteUnreferenced("blob"));
        assertEquals(1, e.liveManifests());
        assertTrue(blobs.existsById("blob"));

        manifests.deleteObject("bkt", "k");
        blobs.deleteUnreferenced("blob");   // only a dead manifest is left: it is purged with the blob
        assertFalse(blobs.existsById("blob"));
        assertTrue(manifests.findById("m1").isEmpty());
        assertEquals(0, manifests.gcQueueSize());
    }

    // ── manifests, objects, GC queue ─────────────────────────────────────────

    @Test
    void manifestRoundTripKeepsEveryField() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        ManifestEntity m = manifest("m1", p, "blob", "dir/k", 0x8000000000000001L, -1L, 0L, Long.MAX_VALUE);
        m.setAcl("public-read");
        m.setUserMetadata(Map.of("x-amz-meta-a", "1", "x-amz-meta-b", "é中"));
        m.setCacheControl("no-cache");
        m.setContentDisposition("attachment");
        m.setContentEncoding("gzip");
        m.setContentLanguage("de");
        m.setCrc32c("AAAAAA==");
        m.setChecksumAlgorithm(org.example.sectoriadb.checksum.ChecksumAlgorithm.SHA256);
        m.setChecksumValue("abc");
        m.setChecksumType(org.example.sectoriadb.checksum.ChecksumType.FULL_OBJECT);
        manifests.commitObject(m);

        ManifestEntity r = manifests.findCurrent("bkt", "dir/k").orElseThrow();
        assertEquals("m1", r.getId());
        assertArrayEquals(new long[]{0x8000000000000001L, -1L, 0L, Long.MAX_VALUE}, r.chunkKeyArray());
        assertEquals(m.getUserMetadata(), r.getUserMetadata());
        assertEquals("public-read", r.getAcl());
        assertEquals("no-cache", r.getCacheControl());
        assertEquals("attachment", r.getContentDisposition());
        assertEquals("gzip", r.getContentEncoding());
        assertEquals("de", r.getContentLanguage());
        assertEquals("AAAAAA==", r.getCrc32c());
        assertEquals(org.example.sectoriadb.checksum.ChecksumAlgorithm.SHA256, r.getChecksumAlgorithm());
        assertEquals("abc", r.getChecksumValue());
        assertEquals(m.getEtag(), r.getEtag());
        assertEquals(Instant.parse("2026-02-03T04:05:06Z"), r.getCreatedAt());
        assertEquals("blob", r.getBlobFile().getId(), "blob reference is resolved");
        assertEquals("bkt", r.getBlobFile().getPool().getName(), "and so is its pool");
    }

    @Test
    void chunkKeysAreStoredAsABinaryArray() {
        ManifestEntity m = new ManifestEntity();
        m.setId("m");
        m.setStorageKind(StorageKind.CHUNKED);
        long[] keys = new long[10_000];
        for (int i = 0; i < keys.length; i++) keys[i] = 0xDEADBEEF00000000L + i;
        m.setChunkKeyArray(keys);
        byte[] value = ManifestCodec.encode(m);
        // 8 bytes per key (the old comma separated hex string needed 17), plus a small JSON part
        assertTrue(value.length < 8 * keys.length + 1000, "encoded size " + value.length);
        assertArrayEquals(keys, ManifestCodec.decode(value).chunkKeyArray());
        assertEquals(0, ManifestCodec.decode(value, false).chunkKeyArray().length, "listings skip the key array");
        assertThrows(IllegalStateException.class, () -> ManifestCodec.decode(java.util.Arrays.copyOf(value, value.length - 3)));
    }

    @Test
    void commitReplacesTheCurrentVersionAndQueuesTheOldOne() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        manifests.commitObject(manifest("v1", p, "blob", "k", 1));
        assertEquals(0, manifests.gcQueueSize());

        ManifestRepository.CommitResult r = manifests.commitObject(manifest("v2", p, "blob", "k", 2));
        assertEquals("v2", r.current().getId());
        assertEquals("v1", r.superseded().orElseThrow().getId());
        assertEquals("v2", manifests.findCurrent("bkt", "k").orElseThrow().getId());
        assertTrue(manifests.findById("v1").orElseThrow().isDeleted(), "old manifest is kept, flagged deleted");
        assertEquals(List.of("v1"), manifests.gcQueue(10).stream().map(ManifestRepository.GcEntry::manifestId).toList());
        assertEquals(List.of("v2"), manifests.findAllLive().stream().map(ManifestEntity::getId).toList());
        assertEquals(1, manifests.countLiveByBlobId("blob"));
        assertEquals(2, manifests.findByBlobId("blob", false).size(), "the manifests_by_blob index still has the dead one");
        assertEquals(1, manifests.findByBlobId("blob", true).size());
        assertEquals(2, manifests.count());

        // committing the same manifest again (e.g. a retry) neither queues it nor loses the entry
        manifests.commitObject(r.current());
        assertEquals(1, manifests.gcQueueSize());
        assertEquals("v2", manifests.findCurrent("bkt", "k").orElseThrow().getId());
    }

    @Test
    void deleteObjectUnlinksAndQueues() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        manifests.commitObject(manifest("v1", p, "blob", "k", 1));
        manifests.commitObject(manifest("other", p, "blob", "other", 2));

        assertEquals("v1", manifests.deleteObject("bkt", "k").orElseThrow().getId());
        assertTrue(manifests.findCurrent("bkt", "k").isEmpty());
        assertFalse(manifests.existsCurrent("bkt", "k"));
        assertTrue(manifests.existsCurrent("bkt", "other"));
        assertEquals(1, manifests.gcQueueSize());
        assertTrue(manifests.deleteObject("bkt", "k").isEmpty(), "deleting again is a no-op");
        assertEquals(1, manifests.gcQueueSize(), "and queues nothing");
        assertTrue(manifests.deleteObject("bkt", "never").isEmpty());
    }

    @Test
    void deleteByManifestIdUnlinksTheObjectToo() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        manifests.commitObject(manifest("v1", p, "blob", "k", 1));
        ManifestEntity shell = manifests.save(manifest("shell", p, "blob", null, 5));   // stored from the shell: no key

        assertEquals(2, manifests.findAllLive().size());
        assertTrue(manifests.deleteManifest("v1").isPresent());
        assertTrue(manifests.findCurrent("bkt", "k").isEmpty());
        assertTrue(manifests.deleteManifest(shell.getId()).isPresent());
        assertTrue(manifests.deleteManifest("v1").isEmpty(), "already retired");
        assertTrue(manifests.deleteManifest("missing").isEmpty());
        assertEquals(0, manifests.findAllLive().size());
        assertEquals(2, manifests.gcQueueSize());
    }

    @Test
    void updateCurrentIsAnAtomicReadModifyWrite() {
        PoolEntity p = pool("p", "bkt");
        blob("blob", "p", BlobKind.CUCKOO);
        manifests.commitObject(manifest("v1", p, "blob", "k", 1));
        ManifestEntity u = manifests.updateCurrent("bkt", "k", m -> m.setAcl("public-read")).orElseThrow();
        assertEquals("v1", u.getId());
        assertEquals("public-read", manifests.findCurrent("bkt", "k").orElseThrow().getAcl());
        assertTrue(manifests.updateCurrent("bkt", "missing", m -> fail()).isEmpty());
        assertThrows(IllegalStateException.class, () -> manifests.updateCurrent("bkt", "k", m -> {
            m.setAcl("private");
            throw new IllegalStateException("abort");
        }));
        assertEquals("public-read", manifests.findCurrent("bkt", "k").orElseThrow().getAcl(), "an exception rolls the change back");
    }

    @Test
    void commitIntoAMissingPoolIsRejectedAndLeavesNothing() {
        PoolEntity ghost = new PoolEntity("ghost", "ghost", "/x", Instant.now());
        assertThrows(ManifestRepository.PoolNotFoundException.class,
                () -> manifests.commitObject(manifest("m", ghost, null, "k")));
        assertEquals(0, manifests.count());
        assertFalse(manifests.hasObjects("ghost"));
    }

    @Test
    void bucketStatsAndHasObjectsAreScopedToTheBucket() {
        PoolEntity a = pool("pa", "a");
        PoolEntity ab = pool("pab", "a-b");
        manifests.commitObject(manifest("1", a, null, "x", 1, 2));
        manifests.commitObject(manifest("2", a, null, "y", 1));
        manifests.commitObject(manifest("3", ab, null, "x", 1, 2, 3));
        assertEquals(new ManifestRepository.BucketStats(2, 3 * 4096L), manifests.countAndSize("a"));
        assertEquals(new ManifestRepository.BucketStats(1, 3 * 4096L), manifests.countAndSize("a-b"));
        assertEquals(new ManifestRepository.BucketStats(0, 0), manifests.countAndSize("none"));
        assertTrue(manifests.hasObjects("a"));
        assertFalse(manifests.hasObjects("a-"), "a bucket name that is a prefix of another one does not match it");
    }

    // ── bucket delete and resize commit ──────────────────────────────────────

    @Test
    void deleteBucketRefusesNonEmptyBucketsAndPurgesEverythingElse() {
        PoolEntity p = pool("p", "bkt");
        PoolEntity other = pool("q", "other");
        blob("c", "p", BlobKind.CUCKOO);
        blob("s", "p", BlobKind.SMALL);
        blob("oc", "q", BlobKind.CUCKOO);
        manifests.commitObject(manifest("keep", other, "oc", "k", 1));
        manifests.commitObject(manifest("v1", p, "c", "k", 1));
        manifests.commitObject(manifest("v2", p, "c", "k", 2));   // v1 is now dead and queued
        ManifestEntity empty = manifest("e", p, null, "empty");   // an object with no blob at all
        empty.setStorageKind(StorageKind.EMPTY);
        manifests.commitObject(empty);
        manifests.deleteObject("bkt", "empty");                   // dead, no blob, only the queue and pool id know it
        manifests.save(manifest("shell", p, "s", null, 9));      // live, stored from the shell

        var e = assertThrows(IllegalStateException.class, () -> pools.deleteBucket("p"));
        assertTrue(e.getMessage().startsWith("BucketNotEmpty"), e.getMessage());
        assertTrue(pools.existsById("p"), "nothing was removed");
        assertEquals(2, blobs.countByPoolId("p"));

        manifests.deleteObject("bkt", "k");
        List<BlobFileEntity> removed = pools.deleteBucket("p");
        assertEquals(List.of("c", "s"), removed.stream().map(BlobFileEntity::getId).sorted().toList());
        assertFalse(pools.existsById("p"));
        assertFalse(pools.existsByName("bkt"));
        assertEquals(0, blobs.countByPoolId("p"));
        assertTrue(manifests.findById("v1").isEmpty());
        assertTrue(manifests.findById("v2").isEmpty());
        assertTrue(manifests.findById("e").isEmpty());
        assertTrue(manifests.findById("shell").isEmpty());
        assertEquals(0, manifests.gcQueueSize());
        // the other bucket is untouched
        assertEquals("keep", manifests.findCurrent("other", "k").orElseThrow().getId());
        assertEquals(1, blobs.countByPoolId("q"));
        assertThrows(IllegalArgumentException.class, () -> pools.deleteBucket("p"));
    }

    @Test
    void resizeCommitRepointsEveryManifestInOneTransaction() {
        PoolEntity p = pool("p", "bkt");
        blob("old", "p", BlobKind.CUCKOO);
        blob("small", "p", BlobKind.SMALL);
        for (int i = 0; i < 20; i++) manifests.commitObject(manifest("m" + i, p, "old", "k" + i, i + 1));
        manifests.commitObject(manifest("m0b", p, "old", "k0", 100));   // supersedes m0: dead but still on the old blob
        ManifestEntity onSmall = manifest("sm", p, null, "small-key");
        onSmall.setStorageKind(StorageKind.SMALL);
        onSmall.setSmallBlobId("small");
        manifests.commitObject(onSmall);

        BlobFileEntity replacement = new BlobFileEntity();
        replacement.setId("new");
        replacement.setPoolId("p");
        replacement.setFileName("new.raw");
        replacement.setFilePath("/data/new.raw");
        replacement.setNumBuckets(128);
        replacement.setChunkSize(4096);
        replacement.setCreatedAt(Instant.now());
        assertEquals(21, blobs.replaceBlob("old", replacement), "all 20 + m0b, live and dead");

        assertFalse(blobs.existsById("old"));
        assertEquals("new", blobs.findById("new").orElseThrow().getId());
        assertEquals(21, manifests.findByBlobId("new", false).size());
        assertEquals(0, manifests.findByBlobId("old", false).size());
        assertEquals("new", manifests.findCurrent("bkt", "k5").orElseThrow().getBlobFile().getId());
        assertEquals("new", manifests.findById("m0").orElseThrow().getBlobFileId(), "dead manifests move too");
        assertEquals("small", manifests.findCurrent("bkt", "small-key").orElseThrow().getSmallBlobId(), "other blobs untouched");
        assertEquals(1, manifests.gcQueueSize());
        assertEquals(List.of("new", "small"), blobs.findByPoolId("p").stream().map(BlobFileEntity::getId).sorted().toList());
        assertThrows(IllegalStateException.class, () -> blobs.replaceBlob("old", replacement), "old blob is gone");
    }
}
