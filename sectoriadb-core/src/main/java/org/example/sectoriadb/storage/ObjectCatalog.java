package org.example.sectoriadb.storage;

import org.example.sectoriadb.audit.OperationLogService;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lookup, listing and deletion of stored objects (manifests): the metadata operations that need no blob IO.
 */
public class ObjectCatalog {

    private static final Logger log = LoggerFactory.getLogger(ObjectCatalog.class);

    private final ManifestRepository manifestRepo;
    private final OperationLogService opLog;

    public ObjectCatalog(ManifestRepository manifestRepo, OperationLogService opLog) {
        this.manifestRepo = manifestRepo;
        this.opLog        = opLog;
    }


    /** Result of an S3 PUT as seen by a later read, used by tests and the shell. */
    public Optional<ManifestEntity> findObject(String bucket, String key) {
        return manifestRepo.findCurrent(bucket, key);
    }

    // ── List / Info ───────────────────────────────────────────────────────────

    /** Every live manifest (administrative full scan: shell {@code ls}, {@code verify-all}). */
    public List<ManifestEntity> listAll() {
        return manifestRepo.findAllLive();
    }

    public ManifestEntity getActiveManifest(String id) {
        ManifestEntity e = manifestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + id));
        if (e.isDeleted()) throw new IllegalArgumentException("File has been deleted: " + id);
        return e;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** S3 DeleteObject: one transaction unlinks the current version and queues it for GC. */
    public boolean deleteObject(String bucket, String key) {
        long t0 = System.currentTimeMillis();
        Optional<ManifestEntity> removed = manifestRepo.deleteObject(bucket, key);
        removed.ifPresent(m -> afterDelete(m, t0));
        return removed.isPresent();
    }

    /** Shell {@code rm}: retires one manifest by id (and unlinks it if it is the current version of an object). */
    public void delete(String manifestId) {
        long t0 = System.currentTimeMillis();
        getActiveManifest(manifestId);
        manifestRepo.deleteManifest(manifestId).ifPresent(m -> afterDelete(m, t0));
    }

    private void afterDelete(ManifestEntity entity, long t0) {
        String blobId = entity.getPhysicalBlob() != null ? entity.getPhysicalBlob().getId() : "";
        opLog.success("DELETE", entity.getId(), entity.getSourceFileName(),
                Map.of("blobFileId", blobId, "storageKind", entity.getStorageKind().name()),
                System.currentTimeMillis() - t0);
        log.debug("Object deleted: id={} name={}", entity.getId(), entity.getSourceFileName());
    }
}
