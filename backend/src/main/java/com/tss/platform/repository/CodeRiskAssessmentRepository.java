package com.tss.platform.repository;

import com.tss.platform.entity.CodeRiskAssessment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface CodeRiskAssessmentRepository
        extends JpaRepository<CodeRiskAssessment, String> {

    Optional<CodeRiskAssessment> findTopByVersionIdOrderByCreatedAtDescIdDesc(
            String versionId
    );

    List<CodeRiskAssessment> findByVersionIdOrderByCreatedAtDescIdDesc(String versionId);

    List<CodeRiskAssessment> findByStatusOrderByCreatedAtAscIdAsc(String status);

    List<CodeRiskAssessment> findByStatusOrderByCreatedAtAscIdAsc(
            String status,
            Pageable pageable
    );

    List<CodeRiskAssessment> findByStatusOrderByCreatedAtDescIdDesc(
            String status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assessment from CodeRiskAssessment assessment "
            + "where assessment.id = :id and assessment.versionId = :versionId")
    Optional<CodeRiskAssessment> findByIdAndVersionIdForUpdate(
            @Param("id") String id,
            @Param("versionId") String versionId
    );
}
