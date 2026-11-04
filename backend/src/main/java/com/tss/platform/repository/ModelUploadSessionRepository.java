package com.tss.platform.repository;

import com.tss.platform.entity.ModelUploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ModelUploadSessionRepository extends JpaRepository<ModelUploadSession, String> {
    Optional<ModelUploadSession> findFirstByFileFingerprintAndStatusOrderByUpdatedAtDesc(
            String fileFingerprint,
            String status
    );

    Optional<ModelUploadSession> findFirstByFileFingerprintAndStatusAndOwnerUserIdOrderByUpdatedAtDesc(
            String fileFingerprint,
            String status,
            Integer ownerUserId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update ModelUploadSession s
            set s.status = :nextStatus, s.updatedAt = :updatedAt
            where s.id = :id
              and s.ownerUserId = :ownerUserId
              and s.status = :expectedStatus
            """)
    int updateStatusIfCurrent(
            @Param("id") String id,
            @Param("ownerUserId") Integer ownerUserId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("updatedAt") Instant updatedAt
    );
}
