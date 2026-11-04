package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeRiskAssessmentDetail;
import com.tss.platform.dto.v2.V2CodeRiskFinding;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeRiskFindingRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class V2CodeRiskQueryService {

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeRiskAssessmentRepository assessmentRepository;
    private final CodeRiskFindingRepository findingRepository;
    private final AuthContext authContext;

    public V2CodeRiskQueryService(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeRiskAssessmentRepository assessmentRepository,
            CodeRiskFindingRepository findingRepository,
            AuthContext authContext
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.assessmentRepository = assessmentRepository;
        this.findingRepository = findingRepository;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public V2CodeRiskAssessmentDetail get(String versionId) {
        CodeVersion version = versionRepository.findByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        Integer currentUserId;
        try {
            currentUserId = authContext.currentUserId();
        } catch (RuntimeException exception) {
            throw new CodeAssetAccessException();
        }
        if (!Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())
                || !Objects.equals(asset.getOwnerUserId(), currentUserId)
                || version.getLatestRiskAssessmentId() == null) {
            throw new CodeAssetAccessException();
        }
        CodeRiskAssessment assessment = assessmentRepository.findById(
                version.getLatestRiskAssessmentId()
        ).filter(value -> Objects.equals(value.getVersionId(), version.getId()))
                .filter(value -> Objects.equals(value.getArtifactSha256(),
                        version.getArtifactSha256()))
                .orElseThrow(CodeAssetAccessException::new);
        List<V2CodeRiskFinding> findings = findingRepository
                .findByRiskAssessmentIdOrderByFilePathAscLineStartAscIdAsc(
                        assessment.getId()
                ).stream()
                .map(V2CodeRiskFinding::from)
                .toList();
        return V2CodeRiskAssessmentDetail.from(assessment, findings);
    }
}
