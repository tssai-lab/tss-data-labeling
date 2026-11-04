package com.tss.platform.repository;

import com.tss.platform.entity.CodeApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CodeApprovalRecordRepository extends JpaRepository<CodeApprovalRecord, String> {

    Optional<CodeApprovalRecord> findTopByVersionIdOrderByCreatedAtDescIdDesc(String versionId);
}
