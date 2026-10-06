package org.example.sectoriadb.repository;

import org.example.sectoriadb.model.OperationLogEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationLogRepository {
    OperationLogEntity save(OperationLogEntity entity);
    Optional<OperationLogEntity> findById(String id);
    List<OperationLogEntity> findAll();

    List<OperationLogEntity> findByOperationTypeOrderByTimestampDesc(String operationType, int limit);
    List<OperationLogEntity> findByEntityIdOrderByTimestampDesc(String entityId, int limit);
    List<OperationLogEntity> findByTimestampAfterOrderByTimestampDesc(Instant since, int limit);
    List<OperationLogEntity> findByOperationTypeAndEntityIdOrderByTimestampDesc(
            String operationType, String entityId, int limit);
    List<OperationLogEntity> findTopNByOrderByTimestampDesc(int n);
}
