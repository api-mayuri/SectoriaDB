package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.StorageKind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-tree invariants of the chunk index: the reference count of every chunk is exactly the number of positions
 * in live CHUNKED manifests that hold its key; zero-reference chunks are exactly the queued ones; the by-blob index
 * and the orphan records agree with the entries and the blobs.
 */
public final class ChunkInvariants {

    private ChunkInvariants() {
    }

    public static void check(MetaStore store) {
        try (ReadTxn tx = store.beginRead()) {
            // shell-stored manifests are findable by pool
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                long shell = 0;
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value(), false);
                    if (m.getBucketName() == null && m.getPoolId() != null) {
                        shell++;
                        assertTrue(tx.tree(Trees.MANIFESTS_BY_POOL).containsKey(Trees.pairKey(m.getPoolId(), m.getId())),
                                "manifests_by_pool row of " + m.getId());
                    }
                }
                assertEquals(shell, tx.tree(Trees.MANIFESTS_BY_POOL).size(), "manifests_by_pool has no stray rows");
            }
            // what live manifests reference
            Map<String, Long> expected = new HashMap<>();
            try (Cursor c = tx.tree(Trees.MANIFESTS).scan()) {
                while (c.next()) {
                    ManifestEntity m = ManifestCodec.decode(c.value());
                    if (m.getStorageKind() != StorageKind.CHUNKED) continue;
                    if (m.isDeleted()) {
                        assertEquals(0, m.chunkKeyArray().length, "a retired manifest is a tombstone without chunk list: " + m.getId());
                        continue;
                    }
                    for (long k : m.chunkKeyArray()) expected.merge(m.getPoolId() + "/" + k, 1L, Long::sum);
                }
            }

            Map<String, ChunkEntry> entries = new HashMap<>();
            long refSum = 0;
            try (Cursor c = tx.tree(Chunks.CHUNKS).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String pool = r.string();
                    long key = r.longUnsigned();
                    ChunkEntry e = ChunkCodec.decode(c.value());
                    entries.put(pool + "/" + key, e);
                    refSum += e.refcount();
                    assertTrue(tx.tree(Trees.BLOBS).containsKey(Trees.idKey(e.blobId())), "blob of chunk " + key);
                    assertTrue(tx.tree(Chunks.CHUNKS_BY_BLOB)
                            .containsKey(Keys.builder().string(e.blobId()).longUnsigned(key).build()), "chunks_by_blob row of " + key);
                    assertTrue(tx.tree(Trees.POOLS).containsKey(Trees.idKey(pool)), "pool of chunk " + key);
                }
            }
            for (Map.Entry<String, Long> x : expected.entrySet()) {
                ChunkEntry e = entries.get(x.getKey());
                assertNotNull(e, "referenced chunk is indexed: " + x.getKey());
                assertEquals(x.getValue(), e.refcount(), "refcount of " + x.getKey());
            }
            for (Map.Entry<String, ChunkEntry> x : entries.entrySet()) {
                if (!expected.containsKey(x.getKey())) {
                    assertEquals(0, x.getValue().refcount(), "unreferenced chunk has refcount 0: " + x.getKey());
                }
            }
            assertEquals(refSum, Chunks.totalRefs(tx), "maintained total of references");

            long byBlob = 0;
            try (Cursor c = tx.tree(Chunks.CHUNKS_BY_BLOB).scan()) {
                while (c.next()) {
                    byBlob++;
                    Keys.Reader r = new Keys.Reader(c.key());
                    String blob = r.string();
                    long key = r.longUnsigned();
                    String pool = Trees.blob(tx, blob, false).orElseThrow().getPoolId();
                    ChunkEntry e = entries.get(pool + "/" + key);
                    assertNotNull(e, "chunks_by_blob row has an entry: " + blob + "/" + key);
                    assertEquals(blob, e.blobId());
                }
            }
            assertEquals(entries.size(), byBlob, "chunks_by_blob has no stray rows and no missing ones");

            Set<String> queued = new HashSet<>();
            try (Cursor c = tx.tree(Chunks.CHUNK_GC).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String id = r.string() + "/" + r.longUnsigned();
                    ChunkEntry e = entries.get(id);
                    assertNotNull(e, "gc row has an entry: " + id);
                    assertEquals(0, e.refcount(), "only zero-reference chunks are queued: " + id);
                    queued.add(id);
                }
            }
            Set<String> zero = new HashSet<>();
            entries.forEach((k, v) -> {
                if (v.refcount() == 0) zero.add(k);
            });
            assertEquals(zero, queued, "zero-reference chunks == collection queue");

            try (Cursor c = tx.tree(Chunks.CHUNK_ORPHANS).scan()) {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String blob = r.string();
                    long key = r.longUnsigned();
                    var b = Trees.blob(tx, blob, false);
                    assertTrue(b.isPresent(), "orphan blob exists: " + blob);
                    ChunkEntry e = entries.get(b.get().getPoolId() + "/" + key);
                    assertNotNull(e, "an orphan copy has an indexed original");
                    assertNotEquals(blob, e.blobId(), "an orphan is a copy in a blob the index does not point to");
                }
            }
        }
    }
}
