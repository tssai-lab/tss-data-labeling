package com.tss.platform.repository;

import com.tss.platform.entity.CodeAssetAuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface CodeAssetAuditLogRepository extends Repository<CodeAssetAuditLog, String> {

    <S extends CodeAssetAuditLog> S save(S auditLog);

    List<CodeAssetAuditLog> findByAssetIdOrderByCreatedAtDescIdDesc(String assetId);
}
