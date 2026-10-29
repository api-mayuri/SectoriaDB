package org.example.sectoriadb.config;

import org.example.sectoriadb.audit.JsonOperationLogRepository;
import org.example.sectoriadb.audit.OperationLogRepository;
import org.example.sectoriadb.audit.OperationLogService;
import org.example.sectoriadb.catalog.BlobFileRepository;
import org.example.sectoriadb.catalog.CatalogInfo;
import org.example.sectoriadb.catalog.ChunkRepository;
import org.example.sectoriadb.catalog.GcRepository;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.PoolRepository;
import org.example.sectoriadb.catalog.metastore.CatalogGuard;
import org.example.sectoriadb.catalog.metastore.MetaStoreBlobFileRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreCatalogInfo;
import org.example.sectoriadb.catalog.metastore.MetaStoreChunkRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreGcRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreHolder;
import org.example.sectoriadb.catalog.metastore.MetaStoreManifestRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreMultipartRepository;
import org.example.sectoriadb.catalog.metastore.MetaStorePoolRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreProvider;
import org.example.sectoriadb.engine.hash.XxHash64BytesHasher;
import org.example.sectoriadb.maintenance.BlobFileReaper;
import org.example.sectoriadb.maintenance.ResizeService;
import org.example.sectoriadb.maintenance.StartupReconciler;
import org.example.sectoriadb.maintenance.gc.GarbageCollector;
import org.example.sectoriadb.maintenance.gc.SmallBlobCompactor;
import org.example.sectoriadb.metrics.StorageMetrics;
import org.example.sectoriadb.security.CredentialRepository;
import org.example.sectoriadb.security.CredentialService;
import org.example.sectoriadb.security.JsonCredentialRepository;
import org.example.sectoriadb.storage.BlobGate;
import org.example.sectoriadb.storage.BlobService;
import org.example.sectoriadb.storage.ChunkStore;
import org.example.sectoriadb.storage.HashTableCache;
import org.example.sectoriadb.storage.ObjectCatalog;
import org.example.sectoriadb.storage.ObjectReader;
import org.example.sectoriadb.storage.ObjectVerificationService;
import org.example.sectoriadb.storage.ObjectWriter;
import org.example.sectoriadb.storage.PoolService;
import org.example.sectoriadb.storage.SmallBlobCache;
import org.example.sectoriadb.storage.multipart.MultipartService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/**
 * The composition root of the core module: every core component is a plain Java class and is wired here, in one place.
 * Nothing below this class knows Spring; the only annotations the core uses are this one and
 * {@code @ConfigurationProperties} on {@link StorageProperties}.
 *
 * The repositories are wrapped by {@link CatalogGuard}, which turns the metastore's "unavailable" exception into the
 * catalog's own {@code CatalogUnavailableException}.
 *
 * <p>Layers, from the bottom: the metadata store and the catalog repositories on top of it; the storage layer (caches,
 * blob registry, chunk store, object reader / writer / catalog); the maintenance jobs (garbage collector, small-blob
 * compactor, resize, blob file reaper) that the server's schedulers drive.
 */
@Configuration
public class CoreConfiguration {

    // ── metrics ─────────────────────────────────────────────────────────────────

    /**
     * Default (no-op) {@link StorageMetrics}. A real implementation (the server's Micrometer adapter) is declared
     * {@code @Primary} and wins; without one (CLI, tests) the engine simply reports nowhere.
     */
    @Bean
    public StorageMetrics noopStorageMetrics() {
        return StorageMetrics.NOOP;
    }

    // ── metadata store and catalog ──────────────────────────────────────────────

    /**
     * The embedded metadata store, opened on first use and closed with the context. In server mode
     * {@link #metaStoreStarter} opens it when the context starts, so a second server on the same directory fails at
     * startup; the lazy admin CLI ({@code sectoriadb.metastore.lazy=true}) opens it only if a command needs it.
     */
    @Bean(destroyMethod = "close")
    public MetaStoreHolder metaStoreProvider(StorageProperties props, StorageMetrics metrics) {
        return new MetaStoreHolder(props, metrics);
    }

    /** Fail fast: open the store while the context starts (server mode). */
    @Bean
    public SmartInitializingSingleton metaStoreStarter(MetaStoreProvider provider, StorageProperties props) {
        return () -> {
            if (!props.getMetastore().isLazy()) provider.get();
        };
    }

    @Bean
    public ManifestRepository manifestRepository(MetaStoreProvider stores) {
        return CatalogGuard.guard(ManifestRepository.class, new MetaStoreManifestRepository(stores));
    }

    @Bean
    public ChunkRepository chunkRepository(MetaStoreProvider stores) {
        return CatalogGuard.guard(ChunkRepository.class, new MetaStoreChunkRepository(stores));
    }

    @Bean
    public BlobFileRepository blobFileRepository(MetaStoreProvider stores) {
        return CatalogGuard.guard(BlobFileRepository.class, new MetaStoreBlobFileRepository(stores));
    }

    @Bean
    public PoolRepository poolRepository(MetaStoreProvider stores) {
        return CatalogGuard.guard(PoolRepository.class, new MetaStorePoolRepository(stores));
    }

    @Bean
    public CatalogInfo catalogInfo(MetaStoreProvider stores) {
        return CatalogGuard.guard(CatalogInfo.class, new MetaStoreCatalogInfo(stores));
    }

    @Bean
    public MultipartRepository multipartRepository(MetaStoreProvider stores,
            @Value("${sectoriadb.multipart.min-part-size:5MB}")
            DataSize minPartSize) {
        return CatalogGuard.guard(MultipartRepository.class,
                new MetaStoreMultipartRepository(stores, minPartSize.toBytes()));
    }

    @Bean
    public GcRepository gcRepository(MetaStoreProvider stores) {
        return CatalogGuard.guard(GcRepository.class, new MetaStoreGcRepository(stores));
    }

    // ── security and audit (JSON files next to the metastore) ──────────────────

    @Bean
    public CredentialRepository credentialRepository(ObjectMapper mapper, StorageProperties props) {
        return new JsonCredentialRepository(mapper, props);
    }

    @Bean
    public CredentialService credentialService(CredentialRepository repo) {
        return new CredentialService(repo);
    }

    @Bean
    public OperationLogRepository operationLogRepository(ObjectMapper mapper, StorageProperties props) {
        return new JsonOperationLogRepository(mapper, props);
    }

    @Bean
    public OperationLogService operationLogService(OperationLogRepository repo, ObjectMapper mapper) {
        return new OperationLogService(repo, mapper);
    }

    // ── storage ─────────────────────────────────────────────────────────────────

    @Bean
    public BlobGate blobGate() {
        return new BlobGate();
    }

    @Bean
    public HashTableCache hashTableCache(StorageProperties props, StorageMetrics metrics, BlobGate blobGate) {
        return new HashTableCache(props, metrics, blobGate);
    }

    @Bean
    public SmallBlobCache smallBlobCache(StorageProperties props, StorageMetrics metrics) {
        return new SmallBlobCache(props, metrics);
    }

    @Bean
    public BlobService blobService(BlobFileRepository blobRepo, HashTableCache cache, BlobGate blobGate,
                                   SmallBlobCache smallCache, StorageProperties props, OperationLogService opLog) {
        return new BlobService(blobRepo, cache, blobGate, smallCache, props, opLog);
    }

    @Bean
    public PoolService poolService(PoolRepository poolRepo, BlobFileRepository blobRepo, OperationLogService opLog,
                                   StorageProperties props, HashTableCache cache, SmallBlobCache smallCache,
                                   BlobService blobService) {
        return new PoolService(poolRepo, blobRepo, opLog, props, cache, smallCache, blobService);
    }

    @Bean
    public ChunkStore chunkStore(ChunkRepository index, BlobService blobs, HashTableCache cache, BlobGate blobGate,
                                 StorageProperties props) {
        return new ChunkStore(index, blobs, cache, blobGate, new XxHash64BytesHasher(), props);
    }

    @Bean
    public ObjectCatalog objectCatalog(ManifestRepository manifestRepo, OperationLogService opLog) {
        return new ObjectCatalog(manifestRepo, opLog);
    }

    @Bean
    public ObjectWriter objectWriter(ManifestRepository manifestRepo, BlobService blobService, HashTableCache cache,
                                     BlobGate blobGate, SmallBlobCache smallCache, ChunkStore chunkStore,
                                     OperationLogService opLog, StorageProperties props) {
        return new ObjectWriter(manifestRepo, blobService, cache, blobGate, smallCache, chunkStore, opLog, props);
    }

    @Bean
    public ObjectReader objectReader(ChunkStore chunkStore, SmallBlobCache smallCache, ObjectCatalog catalog,
                                     OperationLogService opLog, StorageMetrics metrics) {
        return new ObjectReader(chunkStore, smallCache, catalog, opLog, metrics);
    }

    @Bean
    public MultipartService multipartService(MultipartRepository repo, PoolRepository pools, ObjectWriter writer,
                                             ObjectReader reader) {
        return new MultipartService(repo, pools, writer, reader);
    }

    @Bean
    public ObjectVerificationService objectVerificationService(ObjectCatalog catalog, ObjectReader reader) {
        return new ObjectVerificationService(catalog, reader);
    }

    // ── maintenance ─────────────────────────────────────────────────────────────

    @Bean
    public BlobFileReaper blobFileReaper(BlobFileRepository blobRepo, PoolRepository poolRepo, HashTableCache cache) {
        return new BlobFileReaper(blobRepo, poolRepo, cache);
    }

    @Bean
    public ResizeService resizeService(BlobFileRepository blobRepo, PoolRepository poolRepo, HashTableCache cache,
                                       BlobGate blobGate, StorageProperties props, OperationLogService opLog,
                                       BlobFileReaper reaper) {
        return new ResizeService(blobRepo, poolRepo, cache, blobGate, props, opLog, reaper);
    }

    @Bean
    public SmallBlobCompactor smallBlobCompactor(BlobFileRepository blobRepo, ManifestRepository manifestRepo,
                                                 PoolRepository poolRepo, GcRepository gcRepo, BlobService blobs,
                                                 SmallBlobCache smallCache, ChunkStore chunkStore,
                                                 StorageProperties props, StorageMetrics metrics) {
        return new SmallBlobCompactor(blobRepo, manifestRepo, poolRepo, gcRepo, blobs, smallCache, chunkStore, props,
                metrics);
    }

    @Bean
    public org.example.sectoriadb.maintenance.LifecycleService lifecycleService(PoolRepository poolRepo, ManifestRepository manifestRepo) {
        return new org.example.sectoriadb.maintenance.LifecycleService(poolRepo, manifestRepo);
    }

    @Bean
    public GarbageCollector garbageCollector(GcRepository gcRepo, ChunkRepository chunkRepo,
                                             ManifestRepository manifestRepo, BlobFileRepository blobRepo,
                                             PoolRepository poolRepo, BlobService blobs, HashTableCache cache,
                                             BlobGate blobGate, SmallBlobCache smallCache, ChunkStore chunkStore,
                                             SmallBlobCompactor compactor, BlobFileReaper reaper,
                                             StorageProperties props, StorageMetrics metrics) {
        return new GarbageCollector(gcRepo, chunkRepo, manifestRepo, blobRepo, poolRepo, blobs, cache, blobGate, smallCache,
                chunkStore, compactor, reaper, props, metrics);
    }

    /** Cleans up what a crash can leave behind, once, when all beans exist and before requests are served. */
    @Bean
    public SmartInitializingSingleton startupReconciler(BlobFileReaper reaper, StorageProperties props) {
        StartupReconciler reconciler = new StartupReconciler(reaper, props);
        return reconciler::run;
    }
}
