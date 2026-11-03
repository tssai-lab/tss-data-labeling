package com.tss.platform.repository;

import com.tss.platform.entity.DatasetUploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DatasetUploadSessionRepository extends JpaRepository<DatasetUploadSession, String> {
    Optional<DatasetUploadSession> findByImportJobId(String importJobId);

    List<DatasetUploadSession> findByStatusAndUpdatedAtBefore(String status, Instant updatedBefore);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DatasetUploadSession s where s.id = :id")
    Optional<DatasetUploadSession> findByIdForUpdate(@Param("id") String id);

    Optional<DatasetUploadSession> findFirstByFileFingerprintAndStatusOrderByUpdatedAtDesc(
            String fileFingerprint,
            String status
    );

    Optional<DatasetUploadSession> findFirstByFileFingerprintAndStatusAndOwnerUserIdOrderByUpdatedAtDesc(
            String fileFingerprint,
            String status,
            Integer ownerUserId
    );

    Optional<DatasetUploadSession> findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
            String fileFingerprint,
            Collection<String> statuses,
            Integer ownerUserId
    );

    Optional<DatasetUploadSession> findFirstByVersionIdAndUploadPurposeOrderByCreatedAtDesc(
            String versionId,
            String uploadPurpose
    );

    List<DatasetUploadSession> findByVersionIdAndStatusIn(
            String versionId,
            List<String> statuses
    );

    List<DatasetUploadSession> findByVersionId(String versionId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DatasetUploadSession s where s.versionId = :versionId")
    List<DatasetUploadSession> findByVersionIdForUpdate(
            @Param("versionId") String versionId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update DatasetUploadSession s
            set s.status = :nextStatus,
                s.completionErrorCode = null,
                s.completionErrorMessage = null,
                s.completionErrorDetails = null,
                s.completionFailedAt = null,
                s.updatedAt = :updatedAt
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
