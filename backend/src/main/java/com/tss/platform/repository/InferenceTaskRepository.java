package com.tss.platform.repository;

import com.tss.platform.entity.InferenceTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InferenceTaskRepository extends JpaRepository<InferenceTask, String> {
    Optional<InferenceTask> findById(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InferenceTask t where t.id = :id")
    Optional<InferenceTask> findByIdForUpdate(@Param("id") String id);

    Page<InferenceTask> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<InferenceTask> findAllByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<InferenceTask> findAllByOwnerUserIdOrderByCreatedAtDesc(Integer ownerUserId, Pageable pageable);

    Page<InferenceTask> findAllByOwnerUserIdAndStatusOrderByCreatedAtDesc(
            Integer ownerUserId,
            String status,
            Pageable pageable
    );

    // resource-monitor queue queries
    List<InferenceTask> findByServerIpAndStatus(String serverIp, String status);

    List<InferenceTask> findByServerIpAndStatusIn(String serverIp, List<String> statuses);

    List<InferenceTask> findByServerIpNotNullAndStatusIn(List<String> statuses);

    long countByScriptVersionId(String scriptVersionId);

    List<InferenceTask> findByStatus(String status);

    List<InferenceTask> findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<String> statuses,
            Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InferenceTask t
            set t.status = 'scheduled',
                t.progress = 0,
                t.updatedAt = :now
            where t.id = :id
              and t.currentAttempt = :attempt
              and t.status in ('pending', 'queued')
            """)
    int claimForSubmission(
            @Param("id") String id,
            @Param("attempt") Integer attempt,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update InferenceTask t
            set t.status = 'pending',
                t.progress = 0,
                t.serverIp = null,
                t.updatedAt = :now
            where t.id = :id
              and t.currentAttempt = :attempt
              and t.status = 'scheduled'
              and t.updatedAt < :staleBefore
            """)
    int resetStaleSubmission(
            @Param("id") String id,
            @Param("attempt") Integer attempt,
            @Param("staleBefore") Instant staleBefore,
            @Param("now") Instant now
    );
}
