package com.tss.platform.repository;

import com.tss.platform.entity.DatasetWorkspaceAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetWorkspaceAuditLogRepository
        extends JpaRepository<DatasetWorkspaceAuditLog, String> {

    Page<DatasetWorkspaceAuditLog> findByDatasetVersionIdOrderByCreatedAtDescIdDesc(
            String datasetVersionId,
            Pageable pageable
    );
}
