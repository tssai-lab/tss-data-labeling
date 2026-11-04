package com.tss.platform.repository;

import com.tss.platform.entity.ImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, String> {
    List<ImportJob> findByDatasetVersionId(String datasetVersionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ImportJob j where j.datasetVersionId = :datasetVersionId")
    List<ImportJob> findByDatasetVersionIdForUpdate(
            @Param("datasetVersionId") String datasetVersionId
    );

    Optional<ImportJob> findByDatasetVersionIdAndPackageId(
            String datasetVersionId,
            String packageId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ImportJob j where j.id = :id")
    Optional<ImportJob> findByIdForUpdate(@Param("id") String id);

    List<ImportJob> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<ImportJob> findByStatusAndFinishedAtBefore(String status, Instant finishedBefore);

    List<ImportJob> findByDatasetVersionIdIn(Collection<String> datasetVersionIds);

    @Query("""
            select count(j.id)
            from ImportJob j
            where j.packageId = :packageId
              and j.status <> 'SUPERSEDED'
              and exists (
                  select v.id
                  from DatasetVersion v
                  where v.id = j.datasetVersionId
                    and v.deleted = false
              )
            """)
    long countActiveByPackageId(@Param("packageId") String packageId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.status = :runningStatus,
                j.executorId = :executorId,
                j.startedAt = :now,
                j.heartbeatAt = :now,
                j.updatedAt = :now,
                j.finishedAt = null,
                j.errorMessage = null,
                j.errorCode = null,
                j.errorDetailsJson = null
            where j.id = :id
              and j.status = :pendingStatus
              and j.executorId is null
            """)
    int claimPending(
            @Param("id") String id,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus,
            @Param("executorId") String executorId,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.heartbeatAt = :now, j.updatedAt = :now
            where j.id = :id
              and j.status = :runningStatus
              and j.executorId = :executorId
            """)
    int updateHeartbeatIfOwned(
            @Param("id") String id,
            @Param("executorId") String executorId,
            @Param("runningStatus") String runningStatus,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.status = 'SUCCESS',
                j.progress = 100,
                j.totalSamples = :totalSamples,
                j.importedSamples = :totalSamples,
                j.errorMessage = null,
                j.errorCode = null,
                j.errorDetailsJson = null,
                j.finishedAt = :finishedAt,
                j.updatedAt = :updatedAt,
                j.heartbeatAt = :updatedAt
            where j.id = :id
              and j.status = :runningStatus
              and j.executorId = :executorId
            """)
    int completeSuccessIfOwned(
            @Param("id") String id,
            @Param("executorId") String executorId,
            @Param("runningStatus") String runningStatus,
            @Param("totalSamples") Integer totalSamples,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.status = 'FAILED',
                j.progress = 0,
                j.importedSamples = 0,
                j.errorMessage = :errorMessage,
                j.errorCode = :errorCode,
                j.errorDetailsJson = :errorDetailsJson,
                j.finishedAt = :finishedAt,
                j.updatedAt = :finishedAt,
                j.heartbeatAt = :finishedAt
            where j.id = :id
              and j.status = :runningStatus
              and j.executorId = :executorId
            """)
    int markFailedIfOwned(
            @Param("id") String id,
            @Param("executorId") String executorId,
            @Param("runningStatus") String runningStatus,
            @Param("errorMessage") String errorMessage,
            @Param("errorCode") String errorCode,
            @Param("errorDetailsJson") String errorDetailsJson,
            @Param("finishedAt") Instant finishedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.status = 'PARTIAL',
                j.progress = :progress,
                j.totalSamples = :totalSamples,
                j.importedSamples = :importedSamples,
                j.errorMessage = :errorMessage,
                j.errorCode = :errorCode,
                j.errorDetailsJson = :errorDetailsJson,
                j.finishedAt = :finishedAt,
                j.updatedAt = :finishedAt,
                j.heartbeatAt = :finishedAt
            where j.id = :id
              and j.status = :runningStatus
              and j.executorId = :executorId
            """)
    int markPartialIfOwned(
            @Param("id") String id,
            @Param("executorId") String executorId,
            @Param("runningStatus") String runningStatus,
            @Param("progress") Integer progress,
            @Param("totalSamples") Integer totalSamples,
            @Param("importedSamples") Integer importedSamples,
            @Param("errorMessage") String errorMessage,
            @Param("errorCode") String errorCode,
            @Param("errorDetailsJson") String errorDetailsJson,
            @Param("finishedAt") Instant finishedAt
    );

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ImportJob j
            set j.status = :pendingStatus,
                j.executorId = null,
                j.startedAt = null,
                j.heartbeatAt = null,
                j.updatedAt = :updatedAt
            where j.status = :runningStatus
              and coalesce(j.heartbeatAt, j.startedAt, j.updatedAt, j.createdAt) < :staleBefore
            """)
    int resetStaleRunning(
            @Param("runningStatus") String runningStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("staleBefore") Instant staleBefore,
            @Param("updatedAt") Instant updatedAt
    );
}
