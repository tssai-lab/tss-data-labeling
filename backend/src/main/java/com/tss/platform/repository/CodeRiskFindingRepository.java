package com.tss.platform.repository;

import com.tss.platform.entity.CodeRiskFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CodeRiskFindingRepository extends JpaRepository<CodeRiskFinding, String> {

    List<CodeRiskFinding> findByRiskAssessmentIdOrderByFilePathAscLineStartAscIdAsc(
            String riskAssessmentId
    );

    long countByRiskAssessmentId(String riskAssessmentId);
}
