package com.tss.platform.repository;

import com.tss.platform.entity.ImportJobSampleFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ImportJobSampleFailureRepository
        extends JpaRepository<ImportJobSampleFailure, String> {

    long countByImportJobIdAndStatus(String importJobId, String status);

    long countByImportJobIdAndStatusIn(
            String importJobId,
            Collection<String> statuses
    );

    List<ImportJobSampleFailure> findByImportJobIdAndStatusOrderBySampleIndexAsc(
            String importJobId,
            String status
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJobSampleFailure f
            set f.status = :toStatus,
                f.updatedAt = :updatedAt,
                f.attemptCount = f.attemptCount + case when :toStatus = 'RETRYING' then 1 else 0 end
            where f.importJobId = :importJobId
              and f.status = :fromStatus
            """)
    int markStatusByImportJobId(
            @Param("importJobId") String importJobId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("updatedAt") Instant updatedAt
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJobSampleFailure f
            set f.status = 'RESOLVED',
                f.resolvedAt = :resolvedAt,
                f.updatedAt = :resolvedAt,
                f.errorCode = null,
                f.errorMessage = null,
                f.errorDetailsJson = null
            where f.id = :id
            """)
    int markResolved(
            @Param("id") String id,
            @Param("resolvedAt") Instant resolvedAt
    );

    @Transactional
    long deleteByImportJobId(String importJobId);
}
