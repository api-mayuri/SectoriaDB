package org.example.sectoriadb.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.model.BlobFileEntity;
import org.example.sectoriadb.model.BlobKind;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.ChunkRepository;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.metastore.MetaStoreProvider;
import org.example.sectoriadb.service.ChunkStore;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.gc.SmallBlobCompactor;
import org.example.sectoriadb.service.SmallBlobCache;
import org.example.sectoriadb.service.impl.CuckooHashTable;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.ToDoubleFunction;

/**
 * Gauges that read engine state. All of them are served from one {@link Snapshot} that is recomputed at most once per
 * {@code sectoriadb.observability.gauge-cache-ms} (15 s) when a scrape asks for it, so a scrape never scans anything and
 * several scrapers cannot multiply the cost. Everything in the snapshot is O(blobs): counters kept by the engine,
 * one metastore stats call, a few {@code statfs} calls, the (tiny) blobs tree.
 */
@Component
public class StorageGauges {

    private static final Logger log = LoggerFactory.getLogger(StorageGauges.class);

    /** One consistent reading of everything the gauges report. */
    record Snapshot(
            long slotsActive, long slotsTotal, long slotsQuarantined, long cuckooTablesLoaded, long cuckooUsedBytes,
            long cuckooBlobs, long smallBlobs, long cuckooCapacityBytes,
            long smallLiveBytes, long smallDeadBytes, long smallLiveRecords, long smallDeadRecords, long smallBlobsOpen,
            long metaFileBytes, long metaPages, long metaFreePages, long metaLastTxId, long metaLiveReaders, long metaPageSize,
            long gcQueue, long objects, long objectBytes,
            long chunksIndexed, long chunkRefs, long chunkGcQueue, long chunkOrphans, long poolBlobsMax,
            long dataFree, long dataTotal, long metaDirFree, long metaDirTotal, long uploadHolds, long oldSmallFilesPending) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private final MeterRegistry registry;
    private final HashTableCache tables;
    private final SmallBlobCache smallBlobs;
    private final MetaStoreProvider stores;
    private final ManifestRepository manifests;
    private final ChunkRepository chunks;
    private final BlobFileRepository blobs;
    private final ChunkStore chunkStore;
    private final SmallBlobCompactor compactor;
    private final StorageProperties props;
    private final long ttlNanos;

    private volatile Snapshot cached = Snapshot.EMPTY;
    private volatile long cachedAt;
    private volatile boolean everComputed;

    public StorageGauges(MeterRegistry registry, HashTableCache tables, SmallBlobCache smallBlobs,
                         MetaStoreProvider stores, ManifestRepository manifests, ChunkRepository chunks, BlobFileRepository blobs,
                         ChunkStore chunkStore, SmallBlobCompactor compactor,
                         StorageProperties props, ObservabilityProperties obs) {
        this.registry = registry;
        this.tables = tables;
        this.smallBlobs = smallBlobs;
        this.stores = stores;
        this.manifests = manifests;
        this.chunks = chunks;
        this.blobs = blobs;
        this.chunkStore = chunkStore;
        this.compactor = compactor;
        this.props = props;
        this.ttlNanos = Math.max(0, obs.getGaugeCacheMs()) * 1_000_000L;
    }

    @PostConstruct
    void register() {
        g("sectoriadb.cuckoo.slots.active", "Active (occupied) slots, summed over all loaded cuckoo blobs", null, s -> s.slotsActive);
        g("sectoriadb.cuckoo.slots.capacity", "Total slots (capacity), summed over all loaded cuckoo blobs", null, s -> s.slotsTotal);
        g("sectoriadb.cuckoo.slots.quarantined", "Slots quarantined because their metadata entry failed its CRC", null, s -> s.slotsQuarantined);
        g("sectoriadb.cuckoo.tables.loaded", "Cuckoo blobs loaded into memory in this process", null, s -> s.cuckooTablesLoaded);
        g("sectoriadb.cuckoo.used.bytes", "Chunk bytes held by active slots (loaded blobs)", "bytes", s -> s.cuckooUsedBytes);
        g("sectoriadb.cuckoo.capacity.bytes", "Chunk capacity of all cuckoo blobs (slots x chunk size), loaded or not", "bytes", s -> s.cuckooCapacityBytes);
        g("sectoriadb.blobs", "Registered blob files by kind", null, "kind", "cuckoo", s -> s.cuckooBlobs);
        g("sectoriadb.blobs", "Registered blob files by kind", null, "kind", "small", s -> s.smallBlobs);
        g("sectoriadb.small.live.bytes", "Record bytes of live small objects (open small-object blobs)", "bytes", s -> s.smallLiveBytes);
        g("sectoriadb.small.dead.bytes", "Record bytes of deleted small objects not yet reclaimed", "bytes", s -> s.smallDeadBytes);
        g("sectoriadb.small.live.records", "Live small-object records", null, s -> s.smallLiveRecords);
        g("sectoriadb.small.dead.records", "Deleted small-object records not yet reclaimed", null, s -> s.smallDeadRecords);
        g("sectoriadb.small.blobs.open", "Small-object blobs opened in this process", null, s -> s.smallBlobsOpen);
        g("sectoriadb.metastore.file.bytes", "Size of sectoria.db", "bytes", s -> s.metaFileBytes);
        g("sectoriadb.metastore.pages", "Pages in the metastore file", null, s -> s.metaPages);
        g("sectoriadb.metastore.free.pages", "Free (reusable or waiting for readers) metastore pages", null, s -> s.metaFreePages);
        g("sectoriadb.metastore.last.txid", "Id of the last committed metastore transaction", null, s -> s.metaLastTxId);
        g("sectoriadb.metastore.live.readers", "Open metastore read transactions", null, s -> s.metaLiveReaders);
        g("sectoriadb.metastore.page.size.bytes", "Metastore page size", "bytes", s -> s.metaPageSize);
        g("sectoriadb.gc.queue.length", "Retired manifests waiting for the garbage collector", null, s -> s.gcQueue);
        g("sectoriadb.objects.stored", "Current S3 object versions (maintained in the commit transactions)", null, s -> s.objects);
        g("sectoriadb.objects.stored.bytes", "Total size of current S3 object versions", "bytes", s -> s.objectBytes);
        g("sectoriadb.chunks.indexed", "Entries of the pool-wide chunk index (referenced or waiting for collection)", null, s -> s.chunksIndexed);
        g("sectoriadb.chunks.refs", "Sum of the reference counts of all indexed chunks (chunk positions of live manifests)", null, s -> s.chunkRefs);
        g("sectoriadb.chunks.gc.queue.length", "Chunks whose reference count reached zero, waiting for the collector", null, s -> s.chunkGcQueue);
        g("sectoriadb.chunks.orphans", "Physical chunk copies recorded as orphans (written twice by concurrent uploads)", null, s -> s.chunkOrphans);
        g("sectoriadb.gc.upload.holds", "Uploads between 'data written' and 'committed' (they keep the collector away from stray copies)", null, s -> s.uploadHolds);
        g("sectoriadb.gc.old.small.files.pending", "Compacted small-object files kept until the grace period is over", null, s -> s.oldSmallFilesPending);
        g("sectoriadb.pool.blobs.max", "Largest number of cuckoo blobs in one pool", null, s -> s.poolBlobsMax);
        g("sectoriadb.disk.free.bytes", "Usable space of the file system holding the directory", "bytes", "dir", "data", s -> s.dataFree);
        g("sectoriadb.disk.free.bytes", "Usable space of the file system holding the directory", "bytes", "dir", "meta", s -> s.metaDirFree);
        g("sectoriadb.disk.total.bytes", "Size of the file system holding the directory", "bytes", "dir", "data", s -> s.dataTotal);
        g("sectoriadb.disk.total.bytes", "Size of the file system holding the directory", "bytes", "dir", "meta", s -> s.metaDirTotal);
    }

    private void g(String name, String desc, String unit, ToDoubleFunction<Snapshot> f) {
        g(name, desc, unit, null, null, f);
    }

    private void g(String name, String desc, String unit, String tagKey, String tagValue, ToDoubleFunction<Snapshot> f) {
        Gauge.Builder<StorageGauges> b = Gauge.builder(name, this, o -> f.applyAsDouble(o.snapshot()))
                .description(desc).strongReference(true);
        if (unit != null) b.baseUnit(unit);
        if (tagKey != null) b.tag(tagKey, tagValue);
        b.register(registry);
    }

    Snapshot snapshot() {
        long now = System.nanoTime();
        if (everComputed && now - cachedAt < ttlNanos) return cached;
        synchronized (this) {
            now = System.nanoTime();
            if (everComputed && now - cachedAt < ttlNanos) return cached;
            try {
                cached = compute();
            } catch (RuntimeException e) {
                log.debug("Could not refresh storage gauges, keeping the previous values: {}", e.toString());
            }
            everComputed = true;
            cachedAt = System.nanoTime();
            return cached;
        }
    }

    private Snapshot compute() {
        long active = 0, total = 0, quarantined = 0, used = 0;
        var loaded = tables.loadedTables();
        for (CuckooHashTable t : loaded) {
            CuckooHashTable.FillStats f = t.getFillStats();
            active += f.activeSlots();
            total += f.totalSlots();
            quarantined += f.quarantinedSlots();
            used += f.usedBytes(t.getChunkSize());
        }
        long smallLive = 0, smallDead = 0, smallLiveRec = 0, smallDeadRec = 0, smallOpen = 0;
        for (SmallObjectBlob b : smallBlobs.openBlobs()) {
            SmallObjectBlob.Stats s = b.stats();
            smallLive += s.liveBytes();
            smallDead += s.deadBytes();
            smallLiveRec += s.liveRecords();
            smallDeadRec += s.deadRecords();
            smallOpen++;
        }
        long cuckooBlobs = 0, smallBlobCount = 0, capacity = 0, poolBlobsMax = 0;
        java.util.Map<String, Long> perPool = new java.util.HashMap<>();
        for (BlobFileEntity e : blobs.findAll()) {
            if (e.getKind() == BlobKind.CUCKOO) {
                cuckooBlobs++;
                poolBlobsMax = Math.max(poolBlobsMax, perPool.merge(String.valueOf(e.getPoolId()), 1L, Long::sum));
                capacity += 2L * e.getNumBuckets() * CuckooHashTable.SLOTS_PER_BUCKET * e.getChunkSize();
            } else {
                smallBlobCount++;
            }
        }
        MetaStore.Stats ms = stores.get().stats();
        ManifestRepository.BucketStats totals = manifests.totals();
        long gc = manifests.gcQueueSize();
        ChunkRepository.Stats cs = chunks.stats();
        long[] data = space(props.getDataDir());
        long[] meta = space(props.getMetaDir());
        return new Snapshot(active, total, quarantined, loaded.size(), used, cuckooBlobs, smallBlobCount, capacity,
                smallLive, smallDead, smallLiveRec, smallDeadRec, smallOpen,
                ms.fileSize(), ms.pageCount(), ms.freePages(), ms.lastTxId(), ms.liveReaders(), ms.pageSize(),
                gc, totals.objects(), totals.bytes(),
                cs.chunks(), cs.refs(), cs.gcQueue(), cs.orphans(), poolBlobsMax,
                data[0], data[1], meta[0], meta[1], chunkStore.gate().inFlight(), compactor.pendingFiles());
    }

    /** {usable, total} bytes of the file system holding {@code dir} (or its nearest existing parent). */
    private static long[] space(String dir) {
        try {
            Path p = Path.of(dir).toAbsolutePath();
            while (p != null && !Files.exists(p)) p = p.getParent();
            if (p == null) return new long[]{0, 0};
            var store = Files.getFileStore(p);
            return new long[]{store.getUsableSpace(), store.getTotalSpace()};
        } catch (IOException | RuntimeException e) {
            return new long[]{0, 0};
        }
    }
}
