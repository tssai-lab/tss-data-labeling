package com.tss.platform.service;

import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalDecisionSource;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.model.CodeRiskAssessmentStatus;
import com.tss.platform.model.CodeRiskDisposition;
import com.tss.platform.model.CodeRiskLevel;
import com.tss.platform.model.TrainingCodeReviewPolicy;
import com.tss.platform.repository.CodeApprovalRecordRepository;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class CodeArtifactResolver {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeValidationRunRepository validationRunRepository;
    private final CodeApprovalRecordRepository approvalRecordRepository;
    private final CodeRiskAssessmentRepository riskAssessmentRepository;
    private final CodeArtifactStorageService storageService;

    public CodeArtifactResolver(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeValidationRunRepository validationRunRepository,
            CodeApprovalRecordRepository approvalRecordRepository,
            CodeRiskAssessmentRepository riskAssessmentRepository,
            CodeArtifactStorageService storageService
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.validationRunRepository = validationRunRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.storageService = storageService;
    }

    @Transactional
    public ResolvedCodeArtifact resolve(String versionId, Integer consumerOwnerId) {
        if (consumerOwnerId == null) {
            throw new CodeAssetAccessException();
        }
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(CodeAssetAccessException::new);
        boolean isDemo = Boolean.TRUE.equals(asset.getIsDemo());
        // 演示代码为平台预置、已验证审批的可信样例：跳过"使用者==拥有者"强绑定，对全平台共享；
        // 校验/审批/风险证据链校验仍完整保留。
        if (!isDemo
                && (asset.getOwnerUserId() == null
                || !consumerOwnerId.equals(asset.getOwnerUserId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())
                || !Objects.equals(asset.getId(), version.getAssetId()))) {
            throw new CodeAssetAccessException();
        }

        require("READY".equals(version.getStatus()),
                "VERSION_NOT_READY", "Code version is not ready");
        require(version.getArtifactSha256() != null
                        && SHA256.matcher(version.getArtifactSha256()).matches(),
                "ARTIFACT_EVIDENCE_INVALID", "Code artifact evidence is invalid");
        require(version.getSizeBytes() != null && version.getSizeBytes() >= 0,
                "ARTIFACT_EVIDENCE_INVALID", "Code artifact evidence is invalid");
        require("PASSED".equals(version.getValidationStatus())
                        && CodeArtifactAssembler.POLICY_VERSION.equals(
                                version.getValidationPolicyVersion()),
                "VALIDATION_NOT_CURRENT", "Code version validation is not current");

        CodeValidationRun validationRun = validationRunRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElseThrow(() -> validation(
                        "VALIDATION_EVIDENCE_MISSING",
                        "Code version validation evidence is missing"
                ));
        require("PASSED".equals(validationRun.getStatus())
                        && Objects.equals(validationRun.getArtifactSha256(),
                                version.getArtifactSha256())
                        && CodeArtifactAssembler.POLICY_VERSION.equals(
                                validationRun.getPolicyVersion()),
                "VALIDATION_EVIDENCE_STALE", "Code version validation evidence is stale");

        require(CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus()),
                "APPROVAL_REQUIRED", "Code version is not approved");
        CodeApprovalRecord approval = approvalRecordRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElseThrow(() -> validation(
                        "APPROVAL_EVIDENCE_MISSING",
                        "Code version approval evidence is missing"
                ));
        require(CodeApprovalStatus.APPROVED.equals(approval.getDecision())
                        && Objects.equals(approval.getValidationRunId(), validationRun.getId())
                        && Objects.equals(approval.getArtifactSha256(), version.getArtifactSha256())
                        && CodeArtifactAssembler.POLICY_VERSION.equals(approval.getPolicyVersion()),
                "APPROVAL_EVIDENCE_STALE", "Code version approval evidence is stale");
        CodeRiskAssessment riskAssessment = requireRiskEvidence(version, validationRun, approval);

        String storagePath = version.getStoragePath();
        String prefix = "users/" + consumerOwnerId
                + "/codes/" + asset.getId()
                + "/versions/" + version.getId() + "/";
        String leaf;
        if (isDemo) {
            // 演示代码存储路径归属系统账户（非当前使用者），取最后一段文件名做路径健全性校验
            leaf = storagePath != null && storagePath.contains("/")
                    ? storagePath.substring(storagePath.lastIndexOf('/') + 1)
                    : null;
        } else {
            leaf = storagePath != null && storagePath.startsWith(prefix)
                    ? storagePath.substring(prefix.length())
                    : null;
        }
        require(storagePath != null
                        && !storagePath.isBlank()
                        && leaf != null
                        && !leaf.isBlank()
                        && !leaf.contains("/")
                        && !leaf.contains("\\")
                        && !leaf.contains("?")
                        && !leaf.contains("#")
                        && !".".equals(leaf)
                        && !"..".equals(leaf),
                "STORAGE_REFERENCE_INVALID", "Code artifact storage reference is invalid");

        StoredCodeArtifact stored;
        try {
            stored = storageService.read(storagePath);
        } catch (CodeArtifactStorageException exception) {
            throw validation("STORAGE_READ_FAILED", "Code artifact could not be read");
        }
        require(stored != null,
                "STORAGE_READ_FAILED", "Code artifact could not be read");
        require(Objects.equals(storagePath, stored.objectName()),
                "STORAGE_REFERENCE_INVALID", "Code artifact storage reference is invalid");
        require(Objects.equals(version.getArtifactSha256(), stored.artifactSha256()),
                "ARTIFACT_SHA256_MISMATCH", "Code artifact hash does not match");
        require(version.getSizeBytes() == stored.sizeBytes(),
                "ARTIFACT_SIZE_MISMATCH", "Code artifact size does not match");

        return new ResolvedCodeArtifact(
                asset.getId(),
                version.getId(),
                version.getPurpose(),
                version.getRuntime(),
                normalizedEntryScript(version),
                version.getTrainingType(),
                version.getTrainingProfile(),
                version.getArtifactSha256(),
                validationRun.getId(),
                validationRun.getPolicyVersion(),
                approval.getId(),
                approval.getDecisionSource(),
                riskAssessment == null ? null : riskAssessment.getId(),
                riskAssessment == null ? null : riskAssessment.getRiskLevel(),
                riskAssessment == null ? null : riskAssessment.getRiskPolicyVersion(),
                storagePath
        );
    }

    private CodeRiskAssessment requireRiskEvidence(
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeApprovalRecord approval
    ) {
        if (CodeApprovalDecisionSource.SYSTEM_CONFIG.equals(
                approval.getDecisionSource())) {
            return requireDirectPassEvidence(version, validationRun, approval);
        }
        if (approval.getRiskAssessmentId() == null) {
            require(approval.getApprovalPolicyVersion() == null,
                    "APPROVAL_EVIDENCE_STALE", "Code approval evidence is stale");
            return null;
        }
        CodeRiskAssessment assessment = riskAssessmentRepository.findById(
                approval.getRiskAssessmentId()
        ).orElseThrow(() -> validation(
                "RISK_EVIDENCE_MISSING", "Code risk evidence is missing"
        ));
        require("COMPLETED".equals(assessment.getStatus())
                        && !"BLOCK".equals(assessment.getDisposition())
                        && CodeStaticRiskScanner.RISK_POLICY_VERSION.equals(
                                assessment.getRiskPolicyVersion())
                        && Objects.equals(assessment.getVersionId(), version.getId())
                        && Objects.equals(assessment.getValidationRunId(), validationRun.getId())
                        && Objects.equals(assessment.getArtifactSha256(),
                                version.getArtifactSha256())
                        && CodeApprovalService.APPROVAL_POLICY_VERSION.equals(
                                approval.getApprovalPolicyVersion()),
                "RISK_EVIDENCE_STALE", "Code risk evidence is stale");
        return assessment;
    }

    private CodeRiskAssessment requireDirectPassEvidence(
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeApprovalRecord approval
    ) {
        require(approval.getRiskAssessmentId() != null,
                "DIRECT_PASS_EVIDENCE_MISSING",
                "Direct-pass approval evidence is missing");
        CodeRiskAssessment assessment = riskAssessmentRepository.findById(
                approval.getRiskAssessmentId()
        ).orElseThrow(() -> validation(
                "DIRECT_PASS_EVIDENCE_MISSING",
                "Direct-pass approval evidence is missing"
        ));
        require(CodeRiskAssessmentStatus.COMPLETED.equals(assessment.getStatus())
                        && CodeRiskLevel.UNKNOWN.equals(assessment.getRiskLevel())
                        && CodeRiskDisposition.DIRECT_PASS.equals(
                                assessment.getDisposition())
                        && TrainingCodeReviewPolicy.DIRECT_PASS_RISK_POLICY_VERSION.equals(
                                assessment.getRiskPolicyVersion())
                        && Objects.equals(assessment.getVersionId(), version.getId())
                        && Objects.equals(
                                assessment.getValidationRunId(),
                                validationRun.getId()
                        )
                        && Objects.equals(
                                assessment.getArtifactSha256(),
                                version.getArtifactSha256()
                        )
                        && TrainingCodeReviewPolicy
                                .DIRECT_PASS_APPROVAL_POLICY_VERSION
                                .equals(approval.getApprovalPolicyVersion()),
                "DIRECT_PASS_EVIDENCE_STALE",
                "Direct-pass approval evidence is stale");
        return assessment;
    }

    private static String normalizedEntryScript(CodeVersion version) {
        if (version.getEntryScript() != null && !version.getEntryScript().isBlank()) {
            return version.getEntryScript().trim();
        }
        return null;
    }

    private static void require(boolean condition, String reasonCode, String message) {
        if (!condition) {
            throw validation(reasonCode, message);
        }
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }
}
