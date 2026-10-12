package org.example.sectoriadb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.BlobFileRepository;
import org.example.sectoriadb.audit.JsonOperationLogRepository;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolRepository;
import org.example.sectoriadb.storage.BlobService;
import org.example.sectoriadb.storage.ChunkStore;
import org.example.sectoriadb.storage.FileStorageService;
import org.example.sectoriadb.storage.HashTableCache;
import org.example.sectoriadb.storage.ObjectVerificationService;
import org.example.sectoriadb.audit.OperationLogService;
import org.example.sectoriadb.storage.PoolService;
import org.example.sectoriadb.maintenance.ResizeService;
import org.example.sectoriadb.storage.SmallBlobCache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.example.sectoriadb.catalog.metastore.MetaStoreBlobFileRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreChunkRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreGcRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreManifestRepository;
import org.example.sectoriadb.catalog.metastore.MetaStorePoolRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreProvider;

/** The whole storage object graph (without Spring) over a metastore in {@code root/meta}; {@link #reopen()} simulates a restart. */
public final class StorageRig implements AutoCloseable {

    public static final int CHUNK = 4096;

    public final Path root;
    public final StorageProperties props = new StorageProperties();
    public final org.example.sectoriadb.metrics.RecordingMetrics metrics = new org.example.sectoriadb.metrics.RecordingMetrics();
    public ResizeService resizeService;
    public org.example.sectoriadb.maintenance.BlobFileReaper reaper;
    public MetaStore store;
    public org.example.sectoriadb.audit.OperationLogService opLog;
    public PoolRepository pools;
    public BlobFileRepository blobs;
    public ManifestRepository manifests;
    public org.example.sectoriadb.catalog.ChunkRepository chunks;
    public ChunkStore chunkStore;
    public HashTableCache cache;
    public SmallBlobCache smallCache;
    /** IO engine of the cuckoo tables (null: the file channel engine); applied by every {@link #open()} / {@link #reopen()}. */
    public java.util.function.Supplier<org.example.sectoriadb.engine.io.StorageIOEngine> engineFactory;

    /** The table of a blob, pinned for the rest of the test (tests do not release it: nothing is evicted in a rig). */
    public org.example.sectoriadb.engine.blob.CuckooHashTable table(org.example.sectoriadb.catalog.BlobFileEntity b) throws java.io.IOException {
        return cache.acquire(b).get();
    }

    /** The open small-object blob, pinned for the rest of the test. */
    public org.example.sectoriadb.engine.blob.SmallObjectBlob small(org.example.sectoriadb.catalog.BlobFileEntity b) throws java.io.IOException {
        return smallCache.acquire(b).get();
    }
    public BlobService blobService;
    public PoolService poolService;
    public FileStorageService files;
    public ObjectVerificationService verifier;
    public org.example.sectoriadb.catalog.GcRepository gcRepo;
    public org.example.sectoriadb.maintenance.gc.SmallBlobCompactor compactor;
    public org.example.sectoriadb.maintenance.gc.GarbageCollector gc;

    public StorageRig(Path root) {
        this.root = root;
        props.setMetaDir(root.resolve("meta").toString());
        props.setDataDir(root.resolve("data").toString());
        props.setDefaultChunkSize(CHUNK);
        props.setDefaultNumBuckets(64);
        props.setFsync(false);
        open();
    }

    private void open() {
        try {
            Files.createDirectories(root.resolve("meta"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store = MetaStore.open(root.resolve("meta").resolve("sectoria.db"), MetaStoreOptions.defaults().fsync(false));
        MetaStoreProvider stores = MetaStoreProvider.of(store);
        pools = new MetaStorePoolRepository(stores);
        blobs = new MetaStoreBlobFileRepository(stores);
        manifests = new MetaStoreManifestRepository(stores);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        cache = new HashTableCache(props, metrics);
        if (engineFactory != null) cache.setEngineFactory(engineFactory);
        smallCache = new SmallBlobCache(props);
        opLog = new OperationLogService(new JsonOperationLogRepository(mapper, props), mapper);
        blobService = new BlobService(blobs, cache, smallCache, props, opLog);
        poolService = new PoolService(pools, blobs, opLog, props, cache, smallCache, blobService);
        chunks = new MetaStoreChunkRepository(stores);
        chunkStore = new ChunkStore(chunks, blobService, cache);
        files = new FileStorageService(manifests, blobService, cache, smallCache, chunkStore, opLog, props);
        reaper = new org.example.sectoriadb.maintenance.BlobFileReaper(blobs, pools, cache);
        resizeService = new ResizeService(blobs, pools, cache, props, opLog, reaper);
        verifier = new ObjectVerificationService(files);
        gcRepo = new MetaStoreGcRepository(stores);
        compactor = new org.example.sectoriadb.maintenance.gc.SmallBlobCompactor(blobs, manifests, pools, gcRepo, blobService,
                smallCache, chunkStore, props, metrics);
        gc = new org.example.sectoriadb.maintenance.gc.GarbageCollector(gcRepo, chunks, manifests, blobs, pools, blobService,
                cache, smallCache, chunkStore, compactor, reaper, props, metrics);
    }

    /**
     * Closes everything and builds a new graph over the same files, as after a process restart (including the startup
     * reconciliation of leftover blob files that the application performs).
     */
    public void reopen() {
        close();
        open();
        reaper.reconcileAtStartup();
    }

    public PoolEntity bucket(String name) {
        try {
            return poolService.createBucket(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        cache.closeAll();
        smallCache.closeAll();
        store.close();
    }
}
