package com.tss.platform.repository;

import com.tss.platform.entity.MinioDeleteTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MinioDeleteTaskRepository extends JpaRepository<MinioDeleteTask, String> {

    List<MinioDeleteTask> findTop50ByStatusOrderByCreatedAtAsc(String status);

    Optional<MinioDeleteTask> findFirstByBucketAndObjectNameAndStatusIn(
            String bucket,
            String objectName,
            Collection<String> statuses
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MinioDeleteTask t
            set t.status = :processingStatus, t.updatedAt = :updatedAt
            where t.id = :id
              and t.status = :pendingStatus
            """)
    int claimPending(
            @Param("id") String id,
            @Param("pendingStatus") String pendingStatus,
            @Param("processingStatus") String processingStatus,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MinioDeleteTask t
            set t.status = :pendingStatus, t.updatedAt = :updatedAt
            where t.status = :processingStatus
              and (t.updatedAt is null or t.updatedAt < :staleBefore)
            """)
    int resetStaleProcessing(
            @Param("processingStatus") String processingStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("staleBefore") Instant staleBefore,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update MinioDeleteTask t
            set t.status = :pendingStatus,
                t.retryCount = 0,
                t.failedResetCount = coalesce(t.failedResetCount, 0) + 1,
                t.updatedAt = :updatedAt
            where t.status = :failedStatus
              and coalesce(t.failedResetCount, 0) < :failedResetLimit
            """)
    int resetFailedForRetry(
            @Param("failedStatus") String failedStatus,
            @Param("pendingStatus") String pendingStatus,
            @Param("failedResetLimit") int failedResetLimit,
            @Param("updatedAt") Instant updatedAt
    );
}
