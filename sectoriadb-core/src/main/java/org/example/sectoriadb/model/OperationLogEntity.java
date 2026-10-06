package org.example.sectoriadb.model;

import java.time.Instant;

/**
 * Audit / recovery log entry.
 *
 * For STORE operations the {@code details} JSON field contains the full list
 * of chunk keys. If the manifest file is ever lost, chunk keys can be
 * extracted from this log and the file reconstructed manually.
 *
 * Stored as JSON Lines (one object per line) in sectoriadb-meta/oplogs.jsonl.
 */
public class OperationLogEntity {

    private String id;
    private Instant timestamp;
    /** STORE | DELETE | RESTORE | RESTORE_RANGE | RESIZE | BLOB_CREATE | BLOB_DELETE | POOL_CREATE | POOL_DELETE */
    private String operationType;
    private String entityId;
    private String entityName;
    /** JSON payload — for STORE includes the full chunk-key list. */
    private String details;
    /** STARTED | SUCCESS | FAILED */
    private String status;
    private Long durationMs;
    private String errorMessage;

    public OperationLogEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
