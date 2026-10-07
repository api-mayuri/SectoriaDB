package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.model.ChunkEntry;
import org.example.sectoriadb.repository.ChunkRepository;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link ChunkRepository} over the {@code chunks}, {@code chunks_by_blob}, {@code chunk_gc} and {@code chunk_orphans} trees. */
@Repository
public class MetaStoreChunkRepository implements ChunkRepository {

    private final MetaStoreProvider stores;

    public MetaStoreChunkRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    @Override
    public Optional<ChunkEntry> find(String poolId, long chunkKey) {
        return store().read(tx -> Chunks.get(tx, poolId, chunkKey));
    }

    @Override
    public Map<Long, ChunkEntry> findAll(String poolId, long[] chunkKeys) {
        return store().read(tx -> {
            var chunks = tx.tree(Chunks.CHUNKS);
            Map<Long, ChunkEntry> out = new HashMap<>(Math.max(16, chunkKeys.length * 2));
            for (long k : chunkKeys) {
                if (out.containsKey(k)) continue;
                chunks.get(Chunks.key(poolId, k)).ifPresent(v -> out.put(k, ChunkCodec.decode(v)));
            }
            return out;
        });
    }

    @Override
    public long countByBlob(String blobId) {
        return store().read(tx -> {
            long n = 0;
            try (Cursor c = tx.tree(Chunks.CHUNKS_BY_BLOB).scanPrefix(Keys.of(blobId))) {
                while (c.next()) n++;
            }
            return n;
        });
    }

    @Override
    public long countReferencedByBlob(String poolId, String blobId) {
        return store().read(tx -> Chunks.referencedChunksOfBlob(tx, poolId, blobId));
    }

    @Override
    public Stats stats() {
        return store().read(tx -> new Stats(
                tx.tree(Chunks.CHUNKS).size(),
                Chunks.totalRefs(tx),
                tx.tree(Chunks.CHUNK_GC).size(),
                tx.tree(Chunks.CHUNK_ORPHANS).size()));
    }

    @Override
    public List<GcRow> gcQueue(int limit) {
        return store().read(tx -> {
            List<GcRow> out = new ArrayList<>();
            try (Cursor c = tx.tree(Chunks.CHUNK_GC).scan()) {
                while (out.size() < limit && c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String pool = r.string();
                    long key = r.longUnsigned();
                    long txId = r.longUnsigned();
                    out.add(new GcRow(pool, key, txId, ByteBuffer.wrap(c.value()).getLong()));
                }
            }
            return out;
        });
    }

    @Override
    public List<Orphan> orphans(int limit) {
        return store().read(tx -> {
            List<Orphan> out = new ArrayList<>();
            try (Cursor c = tx.tree(Chunks.CHUNK_ORPHANS).scan()) {
                while (out.size() < limit && c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    String blob = r.string();
                    out.add(new Orphan(blob, r.longUnsigned(), ByteBuffer.wrap(c.value()).getLong()));
                }
            }
            return out;
        });
    }
}
