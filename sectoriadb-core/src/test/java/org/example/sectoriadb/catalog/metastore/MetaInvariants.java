package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-tree consistency of the metastore: every index entry has its record and vice versa. */
public final class MetaInvariants {

    private MetaInvariants() {
    }

    public static void check(MetaStore store) {
        store.verify();   // page level: CRCs, order, no leaked or doubly used pages
        try (ReadTxn tx = store.beginRead()) {
            // pools <-> pools_by_name
            Map<String, PoolEntity> pools = new HashMap<>();
            try (Cursor c = tx.tree(Trees.POOLS).scan()) {
                while (c.next()) {
                    PoolEntity p = PoolCodec.decode(c.value());
                    pools.put(p.getId(), p);
                    assertEquals(p.getId(), Trees.str(tx.tree(Trees.POOLS_BY_NAME).get(Keys.of(p.getName())).orElseThrow()),
                            "name index of pool " + p.getName());
                }
            }
            assertEquals(pools.size(), tx.tree(Trees.POOLS_BY_NAME).size(), "pools_by_name has no stray entries");

            // blobs <-> blobs_by_pool
            Map<String, BlobFileEntity> blobs = new HashMap<>();
            int withPool = 0;
            try (Cursor c = tx.tree(Trees.BLOBS).scan()) {
                while (c.next()) {
                    BlobFileEntity b = BlobCodec.decode(c.value());
                    blobs.put(b.getId(), b);
                    if (b.getPoolId() != null) {
                        withPool++;
                        assertTrue(tx.tree(Trees.BLOBS_BY_POOL).containsKey(Trees.pairKey(b.getPoolId(), b.getId())),
                                "blobs_by_pool entry of blob " + b.getId());
                        assertTrue(pools.containsKey(b.getPoolId()), "pool of blob " + b.getId());
                    }
                }
            }
            assertEquals(withPool, tx.tree(Trees.BLOBS_BY_POOL).size(), "blobs_by_pool has no stray entries");

            // manifests <-> manifests_by_blob, gc queue
            int blobRefs = 0;
            Map<String, ManifestEntity> hiddenParts = new HashMap<>();
            Set<String> deleted = new HashSet<>();
            Set<String> liveS3 = new HashSet<>();
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value());
                    for (String b : m.smallBlobIds()) {
                        blobRefs++;
                        assertTrue(tx.tree(Trees.MANIFESTS_BY_BLOB).containsKey(Trees.pairKey(b, m.getId())),
                                "manifests_by_blob entry of " + m.getId());
                        assertTrue(blobs.containsKey(b), "blob " + b + " of manifest " + m.getId() + " exists");
                    }
                    if (m.isDeleted()) deleted.add(m.getId());
                    else if (m.getBucketName() != null) liveS3.add(m.getId());
                    if (m.getStorageKind() == StorageKind.MULTIPART && !m.isDeleted()) checkMultipartLayout(m);
                    if (m.isMultipartPart()) hiddenParts.put(m.getId(), m);
                }
            }
            assertEquals(blobRefs, tx.tree(Trees.MANIFESTS_BY_BLOB).size(), "manifests_by_blob has no stray entries");

            Set<String> queued = new HashSet<>();
            try (Cursor c = tx.tree(Trees.DELETED_MANIFESTS).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    r.longUnsigned();
                    String id = r.string();
                    assertTrue(queued.add(id), "manifest queued once: " + id);
                }
            }
            // a retired version leaves no manifest record, only its queue row (which carries its small records)
            assertEquals(Set.of(), deleted, "no retired manifest record is kept");
            for (String id : queued) assertTrue(Trees.manifest(tx, id).isEmpty(), "a queued version has no live record: " + id);
            assertEquals(queued.size(), tx.tree(Trees.DELETED_BY_POOL).size(), "every queue row has its by-pool row and vice versa");
            try (Cursor c = tx.tree(Trees.DELETED_BY_POOL).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    r.string();
                    String id = r.string();
                    assertTrue(queued.contains(id), "by-pool row of a queued manifest: " + id);
                }
            }
            assertEquals(0, tx.tree(Trees.POOL_PURGES).size(), "no bucket deletion is left half done");

            // objects -> live manifests, one entry per live S3 manifest
            Set<String> pointedAt = new HashSet<>();
            try (Cursor c = tx.tree(Trees.OBJECTS).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String bucket = r.string();
                    String key = r.string();
                    String id = Trees.str(c.value());
                    ManifestEntity m = Trees.manifest(tx, id).orElse(null);
                    assertNotNull(m, "manifest " + id + " of " + bucket + "/" + key);
                    assertTrue(!m.isDeleted(), "current version is live: " + bucket + "/" + key);
                    assertEquals(bucket, m.getBucketName());
                    assertEquals(key, m.getObjectKey());
                    assertTrue(tx.tree(Trees.POOLS_BY_NAME).containsKey(Keys.of(bucket)), "bucket " + bucket + " exists");
                    assertTrue(pointedAt.add(id), "one objects entry per manifest");
                }
            }
            // a live S3 manifest is the current version of its key, or a noncurrent version kept in the version history
            Set<String> inHistory = new HashSet<>();
            try (Cursor c = tx.tree(Versions.VERSIONS).scan()) {
                while (c.next()) {
                    Versions.Row row = Versions.decodeRow(c);
                    if (!row.marker()) {
                        assertNotNull(Trees.manifest(tx, row.manifestId()).orElse(null), "version " + row.versionId() + " of " + row.key() + " has its manifest");
                        inHistory.add(row.manifestId());
                    }
                    assertTrue(Versions.byId(tx, row.bucket(), row.key(), row.versionId()).isPresent(), "version index entry of " + row.versionId());
                }
            }
            assertEquals(tx.tree(Versions.VERSIONS).size(), tx.tree(Versions.VERSION_IDS).size(), "one index entry per version row");
            Set<String> expected = new HashSet<>(liveS3);
            expected.removeAll(inHistory);
            Set<String> current = new HashSet<>(pointedAt);
            current.removeAll(inHistory);
            assertEquals(expected, current, "every live S3 manifest is the current version of its key or a kept version");

            checkMultipart(tx, hiddenParts);
        }
        ChunkInvariants.check(store);
    }

    /** Segments describe exactly the bytes and chunks of the manifest. */
    private static void checkMultipartLayout(ManifestEntity m) {
        long bytes = 0;
        int chunks = 0;
        for (PartSegment sg : m.getSegments()) {
            bytes += sg.length();
            if (sg.kind() == StorageKind.CHUNKED) {
                chunks += sg.chunkCount();
                assertTrue(sg.chunkCount() > 0 && sg.lastChunkSize() > 0 && sg.lastChunkSize() <= sg.chunkSize(),
                        "chunked segment of part " + sg.partNumber());
                assertEquals(sg.length(), (long) (sg.chunkCount() - 1) * sg.chunkSize() + sg.lastChunkSize(),
                        "chunk sizes add up to the length of part " + sg.partNumber() + " of " + m.getId());
            }
        }
        assertEquals(m.getTotalBytes(), bytes, "segment lengths add up to the object size: " + m.getId());
        assertEquals(m.chunkKeyArray().length, chunks, "segment chunk counts add up to the key list: " + m.getId());
    }

    /**
     * Multipart state: rows, hidden part manifests and the open-upload index agree. Every part row points at a live hidden
     * manifest of its upload and number; every live hidden manifest has its row; the bucket index holds exactly the OPEN uploads.
     */
    private static void checkMultipart(ReadTxn tx, Map<String, ManifestEntity> hiddenParts) {
        Map<String, MultipartUpload> uploads = new HashMap<>();
        Set<String> expectedIndex = new HashSet<>();
        try (Cursor c = tx.tree(MultipartTrees.UPLOADS).scan()) {
            while (c.next()) {
                MultipartUpload u = JsonValues.read(c.value(), 0, c.value().length, MultipartUpload.class);
                uploads.put(u.getUploadId(), u);
                if (u.getState() == MultipartUpload.State.OPEN) {
                    assertTrue(tx.tree(Trees.POOLS).containsKey(Trees.idKey(u.getPoolId())), "pool of upload " + u.getUploadId());
                    assertTrue(tx.tree(Trees.POOLS_BY_NAME).containsKey(Keys.of(u.getBucket())), "bucket of upload " + u.getUploadId());
                    expectedIndex.add(Trees.str(MultipartTrees.byBucketKey(u)));
                }
            }
        }
        Set<String> indexed = new HashSet<>();
        try (Cursor c = tx.tree(MultipartTrees.BY_BUCKET).scan()) {
            while (c.next()) indexed.add(Trees.str(c.key()));
        }
        assertEquals(expectedIndex, indexed, "mpu_by_bucket == OPEN uploads");

        Set<String> rowManifests = new HashSet<>();
        try (Cursor c = tx.tree(MultipartTrees.PARTS).scan()) {
            while (c.next()) {
                MultipartPart p = MultipartTrees.decodePart(c.value());
                Keys.Reader r = new Keys.Reader(c.key());
                String uploadId = r.string();
                assertEquals(p.partNumber(), (int) r.longUnsigned(), "part row key");
                MultipartUpload u = uploads.get(uploadId);
                assertNotNull(u, "upload of part row " + uploadId + "/" + p.partNumber());
                assertEquals(MultipartUpload.State.OPEN, u.getState(), "only OPEN uploads keep part rows");
                ManifestEntity m = hiddenParts.get(p.manifestId());
                assertNotNull(m, "hidden manifest of part " + p.partNumber());
                assertTrue(!m.isDeleted(), "a part row points at a live manifest: " + p.manifestId());
                assertEquals(uploadId, m.getMultipartUploadId());
                assertEquals(p.partNumber(), m.getPartNumber());
                assertEquals(p.size(), m.getTotalBytes());
                assertTrue(rowManifests.add(p.manifestId()), "one row per hidden manifest");
            }
        }
        assertEquals(hiddenParts.keySet().stream().filter(id -> !hiddenParts.get(id).isDeleted()).collect(Collectors.toSet()),
                rowManifests, "every live hidden part manifest has its row");
    }
}
