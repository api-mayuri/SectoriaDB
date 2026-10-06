package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.OperationLogEntity;
import org.example.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes to the operation log for audit and data-recovery purposes.
 *
 * A STORE entry contains the full chunk-key list — enough to reconstruct
 * the manifest record manually if the manifest file is lost.
 *
 * Failures here are swallowed and logged; the audit log must never prevent
 * the main operation from completing.
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED  = "FAILED";

    private final OperationLogRepository repo;
    private final ObjectMapper mapper;

    public OperationLogService(OperationLogRepository repo, ObjectMapper mapper) {
        this.repo   = repo;
        this.mapper = mapper;
    }

    public void success(String type, String entityId, String entityName,
                        Map<String, Object> details, long durationMs) {
        write(type, entityId, entityName, details, STATUS_SUCCESS, durationMs, null);
    }

    public void failure(String type, String entityId, String entityName,
                        Throwable error, long durationMs) {
        write(type, entityId, entityName, null, STATUS_FAILED, durationMs, error);
    }

    private void write(String type, String entityId, String entityName,
                       Map<String, Object> details, String status, long durationMs, Throwable error) {
        try {
            OperationLogEntity e = new OperationLogEntity();
            e.setId(UUID.randomUUID().toString());
            e.setTimestamp(Instant.now());
            e.setOperationType(type);
            e.setEntityId(entityId);
            e.setEntityName(entityName);
            e.setStatus(status);
            e.setDurationMs(durationMs);
            if (error != null) e.setErrorMessage(error.getMessage());
            if (details != null) {
                try {
                    e.setDetails(mapper.writeValueAsString(details));
                } catch (Exception ex) {
                    e.setDetails("{\"error\":\"serialization_failed\"}");
                }
            }
            repo.save(e);
        } catch (Exception ex) {
            log.error("Failed to write operation log (type={} entity={}): {}", type, entityId, ex.getMessage());
        }
    }
}
