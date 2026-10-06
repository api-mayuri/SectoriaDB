package org.example.sectoriadb.repository.metastore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.repository.BlobFileRepository;
import org.example.sectoriadb.repository.JsonOperationLogRepository;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.PoolRepository;
import org.example.sectoriadb.service.BlobService;
import org.example.sectoriadb.service.FileStorageService;
import org.example.sectoriadb.service.HashTableCache;
import org.example.sectoriadb.service.ObjectVerificationService;
import org.example.sectoriadb.service.OperationLogService;
import org.example.sectoriadb.service.PoolService;
import org.example.sectoriadb.service.SmallBlobCache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The whole storage object graph (without Spring) over a metastore in {@code root/meta}; {@link #reopen()} simulates a restart. */
public final class StorageRig implements AutoCloseable {

    public static final int CHUNK = 4096;

    public final Path root;
    public final StorageProperties props = new StorageProperties();
    public MetaStore store;
    public PoolRepository pools;
    public BlobFileRepository blobs;
    public ManifestRepository manifests;
    public HashTableCache cache;
    public SmallBlobCache smallCache;
    public BlobService blobService;
    public PoolService poolService;
    public FileStorageService files;
    public ObjectVerificationService verifier;

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
        cache = new HashTableCache(props);
        smallCache = new SmallBlobCache(props);
        OperationLogService opLog = new OperationLogService(new JsonOperationLogRepository(mapper, props), mapper);
        blobService = new BlobService(blobs, cache, smallCache, props, opLog);
        poolService = new PoolService(pools, blobs, opLog, props, cache, smallCache);
        files = new FileStorageService(manifests, blobService, cache, smallCache, opLog, props);
        verifier = new ObjectVerificationService(files);
    }

    /** Closes everything and builds a new graph over the same files, as after a process restart. */
    public void reopen() {
        close();
        open();
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
