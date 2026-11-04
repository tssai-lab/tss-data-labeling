package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeRiskAssessmentDetail;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeRiskFinding;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeRiskFindingRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2CodeRiskQueryServiceTest {

    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeRiskAssessmentRepository assessmentRepository =
            mock(CodeRiskAssessmentRepository.class);
    private final CodeRiskFindingRepository findingRepository =
            mock(CodeRiskFindingRepository.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final V2CodeRiskQueryService service = new V2CodeRiskQueryService(
            versionRepository, assetRepository, assessmentRepository,
            findingRepository, authContext
    );

    @Test
    void ownerReadsSanitizedCurrentAssessmentAndFindings() {
        CodeVersion version = version();
        CodeAsset asset = asset();
        CodeRiskAssessment assessment = assessment();
        CodeRiskFinding finding = finding();
        when(authContext.currentUserId()).thenReturn(7);
        when(versionRepository.findByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of(version));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset));
        when(assessmentRepository.findById("risk-1"))
                .thenReturn(Optional.of(assessment));
        when(findingRepository.findByRiskAssessmentIdOrderByFilePathAscLineStartAscIdAsc(
                "risk-1"
        )).thenReturn(List.of(finding));

        V2CodeRiskAssessmentDetail result = service.get("version-1");

        assertEquals("RISK_REVIEW_REQUIRED", result.reasonCode());
        assertEquals("NETWORK_ACCESS", result.findings().get(0).ruleId());
        assertEquals("Network access capability requires review",
                result.findings().get(0).description());
    }

    @Test
    void crossOwnerIsHiddenAsNotFound() {
        CodeVersion version = version();
        when(authContext.currentUserId()).thenReturn(8);
        when(versionRepository.findByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of(version));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset()));

        assertThrows(CodeAssetAccessException.class, () -> service.get("version-1"));
    }

    private static CodeVersion version() {
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setArtifactSha256("a".repeat(64));
        version.setLatestRiskAssessmentId("risk-1");
        version.setDeleted(false);
        return version;
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeRiskAssessment assessment() {
        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("risk-1");
        assessment.setVersionId("version-1");
        assessment.setValidationRunId("validation-1");
        assessment.setArtifactSha256("a".repeat(64));
        assessment.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        assessment.setScannerVersion(CodeStaticRiskScanner.SCANNER_VERSION);
        assessment.setStatus("COMPLETED");
        assessment.setRiskLevel("HIGH");
        assessment.setDisposition("MANUAL_REVIEW");
        assessment.setFindingCount(1);
        assessment.setCreatedAt(Instant.EPOCH);
        assessment.setCompletedAt(Instant.EPOCH);
        return assessment;
    }

    private static CodeRiskFinding finding() {
        CodeRiskFinding finding = new CodeRiskFinding();
        finding.setId("finding-1");
        finding.setRiskAssessmentId("risk-1");
        finding.setRuleId("NETWORK_ACCESS");
        finding.setSeverity("HIGH");
        finding.setCategory("NETWORK");
        finding.setFilePath("train.py");
        finding.setLineStart(1);
        finding.setLineEnd(1);
        finding.setDescription("Network access capability requires review");
        finding.setCreatedAt(Instant.EPOCH);
        return finding;
    }
}
