package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
            Set<String> deleted = new HashSet<>();
            Set<String> liveS3 = new HashSet<>();
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value());
                    for (String b : new String[]{m.getBlobFileId(), m.getSmallBlobId()}) {
                        if (b == null) continue;
                        blobRefs++;
                        assertTrue(tx.tree(Trees.MANIFESTS_BY_BLOB).containsKey(Trees.pairKey(b, m.getId())),
                                "manifests_by_blob entry of " + m.getId());
                        assertTrue(blobs.containsKey(b), "blob " + b + " of manifest " + m.getId() + " exists");
                    }
                    if (m.isDeleted()) deleted.add(m.getId());
                    else if (m.getBucketName() != null) liveS3.add(m.getId());
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
            assertEquals(deleted, queued, "deleted manifests == GC queue");

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
            assertEquals(liveS3, pointedAt, "every live S3 manifest is the current version of its key");
        }
    }
}
