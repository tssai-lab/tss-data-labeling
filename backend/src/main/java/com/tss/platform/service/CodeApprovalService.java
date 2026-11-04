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
import com.tss.platform.model.TrainingCodeReviewMode;
import com.tss.platform.model.TrainingCodeReviewPolicy;
import com.tss.platform.repository.CodeApprovalRecordRepository;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CodeApprovalService {

    public static final String APPROVAL_POLICY_VERSION = "code-approval-policy-v2";

    public enum Decision {
        APPROVE,
        REJECT,
        REVOKE
    }

    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeValidationRunRepository validationRunRepository;
    private final CodeRiskAssessmentRepository riskAssessmentRepository;
    private final CodeApprovalRecordRepository approvalRecordRepository;
    private final CodeArtifactStorageService storageService;
    private final CodeAssetAuditService auditService;
    private final AuthContext authContext;
    private final SystemConfigService systemConfigService;

    public CodeApprovalService(
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeValidationRunRepository validationRunRepository,
            CodeRiskAssessmentRepository riskAssessmentRepository,
            CodeApprovalRecordRepository approvalRecordRepository,
            CodeArtifactStorageService storageService,
            CodeAssetAuditService auditService,
            AuthContext authContext,
            SystemConfigService systemConfigService
    ) {
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.validationRunRepository = validationRunRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.storageService = storageService;
        this.auditService = auditService;
        this.authContext = authContext;
        this.systemConfigService = systemConfigService;
    }

    @Transactional
    public CodeApprovalRecord decide(String versionId, Decision decision, String reason) {
        return decide(versionId, decision, reason, null);
    }

    @Transactional
    public CodeApprovalRecord decide(
            String versionId,
            Decision decision,
            String reason,
            ApprovalExpectation expectation
    ) {
        requireAdministratorAuthority();
        if (decision == null) {
            throw validation("APPROVAL_DECISION_REQUIRED", "Approval decision is required");
        }

        String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        if (asset.getOwnerUserId() == null
                || !Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }

        return switch (decision) {
            case APPROVE -> approve(
                    asset, version, sanitizeOptionalReason(reason), expectation
            );
            case REJECT -> reject(asset, version, sanitizeRequiredReason(reason), expectation);
            case REVOKE -> revoke(asset, version, sanitizeRequiredReason(reason));
        };
    }

    /**
     * Performs the administrator gate without looking up a code resource. V2
     * request facades call this before validating request fields so denied users
     * always receive the same 403 response regardless of resource/request shape.
     */
    public void requireAdministratorAuthority() {
        if (!hasAdministratorAuthority()) {
            throw new CodeApprovalForbiddenException();
        }
    }

    private boolean hasAdministratorAuthority() {
        try {
            return authContext.isAdmin();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CodeApprovalRecord approve(
            CodeAsset asset,
            CodeVersion version,
            String reason,
            ApprovalExpectation expectation
    ) {
        String status = version.getApprovalStatus();
        if (CodeApprovalStatus.REJECTED.equals(status)
                || CodeApprovalStatus.REVOKED.equals(status)) {
            throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
        }
        if (!CodeApprovalStatus.PENDING.equals(status)
                && !CodeApprovalStatus.APPROVED.equals(status)) {
            throw validation("APPROVAL_STATE_INVALID", "Code version approval state is invalid");
        }
        if (!"READY".equals(version.getStatus())) {
            throw validation("VERSION_NOT_READY", "Code version is not ready");
        }

        CodeValidationRun validationRun = validationRunRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElseThrow(() -> validation(
                        "VALIDATION_EVIDENCE_MISSING",
                        "Passing validation evidence is required"
                ));
        requireCurrentPassingEvidence(version, validationRun);
        CodeRiskAssessment riskAssessment = requireCurrentReviewableRisk(
                version, validationRun, false
        );
        requireExpectation(expectation, version, validationRun, riskAssessment);
        requireActualArtifact(version);

        CodeApprovalRecord latest = approvalRecordRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElse(null);
        if (latest != null
                && (CodeApprovalStatus.REJECTED.equals(latest.getDecision())
                || CodeApprovalStatus.REVOKED.equals(latest.getDecision()))) {
            throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
        }
        if (CodeApprovalStatus.APPROVED.equals(status)
                && exactApprovedEvidence(latest, version, validationRun, riskAssessment)) {
            return latest;
        }

        CodeApprovalRecord record = newRecord(
                version,
                CodeApprovalStatus.APPROVED,
                reason,
                validationRun,
                riskAssessment,
                CodeApprovalDecisionSource.ADMIN,
                authContext.currentUserId()
        );
        approvalRecordRepository.saveAndFlush(record);
        version.setApprovalStatus(CodeApprovalStatus.APPROVED);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.approved(
                asset.getId(),
                version.getId(),
                version.getArtifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION
        );
        return record;
    }

    private CodeApprovalRecord reject(
            CodeAsset asset,
            CodeVersion version,
            String reason,
            ApprovalExpectation expectation
    ) {
        if (!CodeApprovalStatus.PENDING.equals(version.getApprovalStatus())) {
            throw validation("APPROVAL_TERMINAL", "Code version cannot be rejected");
        }
        CodeValidationRun validationRun = validationRunRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElse(null);
        CodeRiskAssessment riskAssessment = riskAssessmentRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElse(null);
        if (expectation != null) {
            if (validationRun == null || riskAssessment == null) {
                throw validation("APPROVAL_EVIDENCE_STALE", "Approval evidence has changed");
            }
            requireExpectation(expectation, version, validationRun, riskAssessment);
        }
        CodeApprovalRecord record = newRecord(
                version,
                CodeApprovalStatus.REJECTED,
                reason,
                validationRun,
                riskAssessment,
                CodeApprovalDecisionSource.ADMIN,
                authContext.currentUserId()
        );
        approvalRecordRepository.saveAndFlush(record);
        version.setApprovalStatus(CodeApprovalStatus.REJECTED);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.rejected(asset.getId(), version.getId());
        return record;
    }

    private CodeApprovalRecord revoke(CodeAsset asset, CodeVersion version, String reason) {
        if (!CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())) {
            throw validation("APPROVAL_STATE_INVALID", "Only approved code versions can be revoked");
        }
        CodeApprovalRecord record = newRecord(
                version,
                CodeApprovalStatus.REVOKED,
                reason,
                null,
                null,
                CodeApprovalDecisionSource.ADMIN,
                authContext.currentUserId()
        );
        approvalRecordRepository.saveAndFlush(record);
        version.setApprovalStatus(CodeApprovalStatus.REVOKED);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.revoked(asset.getId(), version.getId());
        return record;
    }

    private void requireCurrentPassingEvidence(CodeVersion version, CodeValidationRun run) {
        if (!"PASSED".equals(run.getStatus())) {
            throw validation("LATEST_VALIDATION_FAILED", "Latest validation did not pass");
        }
        if (!Objects.equals(run.getArtifactSha256(), version.getArtifactSha256())) {
            throw validation("VALIDATION_SHA_MISMATCH", "Validation evidence hash does not match");
        }
        if (!Objects.equals(run.getPolicyVersion(), version.getValidationPolicyVersion())
                || !CodeArtifactAssembler.POLICY_VERSION.equals(run.getPolicyVersion())) {
            throw validation("VALIDATION_POLICY_MISMATCH", "Validation policy is not current");
        }
        if (!"PASSED".equals(version.getValidationStatus())) {
            throw validation("VERSION_VALIDATION_NOT_PASSED", "Code version validation did not pass");
        }
    }

    private CodeRiskAssessment requireCurrentReviewableRisk(
            CodeVersion version,
            CodeValidationRun validationRun,
            boolean allowBlocked
    ) {
        CodeRiskAssessment assessment = riskAssessmentRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(version.getId())
                .orElseThrow(() -> validation(
                        "RISK_ASSESSMENT_REQUIRED",
                        "A completed risk assessment is required"
                ));
        if (!CodeRiskAssessmentStatus.COMPLETED.equals(assessment.getStatus())) {
            throw validation("RISK_ASSESSMENT_NOT_READY", "Risk assessment is not complete");
        }
        if (!allowBlocked && CodeRiskDisposition.BLOCK.equals(assessment.getDisposition())) {
            throw validation("RISK_POLICY_BLOCKED", "Risk policy blocks this code version");
        }
        if (!CodeStaticRiskScanner.RISK_POLICY_VERSION.equals(
                        assessment.getRiskPolicyVersion())
                || !Objects.equals(assessment.getId(), version.getLatestRiskAssessmentId())
                || !Objects.equals(assessment.getValidationRunId(), validationRun.getId())
                || !Objects.equals(assessment.getArtifactSha256(), version.getArtifactSha256())
                || !Objects.equals(assessment.getArtifactSha256(),
                        validationRun.getArtifactSha256())
                || !Objects.equals(assessment.getRiskPolicyVersion(),
                        version.getRiskPolicyVersion())
                || !Objects.equals(assessment.getStatus(), version.getRiskStatus())
                || !Objects.equals(assessment.getRiskLevel(), version.getRiskLevel())
                || !Objects.equals(assessment.getDisposition(),
                        version.getReviewDisposition())) {
            throw validation("RISK_EVIDENCE_STALE", "Risk assessment evidence is stale");
        }
        return assessment;
    }

    private static void requireExpectation(
            ApprovalExpectation expectation,
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeRiskAssessment assessment
    ) {
        if (expectation == null) {
            return;
        }
        if (!Objects.equals(expectation.validationRunId(), validationRun.getId())
                || !Objects.equals(expectation.riskAssessmentId(), assessment.getId())
                || !Objects.equals(expectation.artifactSha256(), version.getArtifactSha256())
                || !Objects.equals(expectation.policyVersion(),
                        assessment.getRiskPolicyVersion())) {
            throw validation("APPROVAL_EVIDENCE_STALE", "Approval evidence has changed");
        }
    }

    private void requireActualArtifact(CodeVersion version) {
        String prefix = "users/" + version.getOwnerUserId()
                + "/codes/" + version.getAssetId()
                + "/versions/" + version.getId() + "/";
        String leaf = version.getStoragePath() != null
                && version.getStoragePath().startsWith(prefix)
                ? version.getStoragePath().substring(prefix.length()) : null;
        if (leaf == null
                || leaf.isBlank()
                || leaf.contains("/")
                || leaf.contains("\\")
                || leaf.contains("?")
                || leaf.contains("#")
                || ".".equals(leaf)
                || "..".equals(leaf)) {
            throw validation("STORAGE_REFERENCE_INVALID", "Code artifact storage reference is invalid");
        }
        StoredCodeArtifact stored;
        try {
            stored = storageService.read(version.getStoragePath());
        } catch (RuntimeException exception) {
            throw validation("STORAGE_READ_FAILED", "Code artifact could not be read");
        }
        if (!Objects.equals(version.getStoragePath(), stored.objectName())) {
            throw validation("STORAGE_REFERENCE_INVALID", "Code artifact storage reference is invalid");
        }
        if (!Objects.equals(version.getArtifactSha256(), stored.artifactSha256())) {
            throw validation("ARTIFACT_SHA256_MISMATCH", "Code artifact hash does not match");
        }
        if (version.getSizeBytes() == null || version.getSizeBytes() != stored.sizeBytes()) {
            throw validation("ARTIFACT_SIZE_MISMATCH", "Code artifact size does not match");
        }
    }

    private static boolean exactApprovedEvidence(
            CodeApprovalRecord latest,
            CodeVersion version,
            CodeValidationRun run,
            CodeRiskAssessment assessment
    ) {
        return latest != null
                && CodeApprovalStatus.APPROVED.equals(latest.getDecision())
                && Objects.equals(latest.getValidationRunId(), run.getId())
                && Objects.equals(latest.getArtifactSha256(), version.getArtifactSha256())
                && Objects.equals(latest.getPolicyVersion(), CodeArtifactAssembler.POLICY_VERSION)
                && Objects.equals(latest.getRiskAssessmentId(), assessment.getId())
                && Objects.equals(latest.getApprovalPolicyVersion(), APPROVAL_POLICY_VERSION);
    }

    private CodeApprovalRecord newRecord(
            CodeVersion version,
            String decision,
            String reason,
            CodeValidationRun validationRun,
            CodeRiskAssessment riskAssessment,
            String decisionSource,
            Integer reviewerUserId
    ) {
        CodeApprovalRecord record = new CodeApprovalRecord();
        record.setId("code-approval-" + UUID.randomUUID().toString().replace("-", ""));
        record.setVersionId(version.getId());
        record.setArtifactSha256(
                validationRun == null ? null : version.getArtifactSha256()
        );
        record.setValidationRunId(validationRun == null ? null : validationRun.getId());
        record.setPolicyVersion(
                validationRun == null ? null : CodeArtifactAssembler.POLICY_VERSION
        );
        record.setDecisionSource(decisionSource);
        record.setRiskAssessmentId(riskAssessment == null ? null : riskAssessment.getId());
        record.setApprovalPolicyVersion(
                riskAssessment == null ? null : APPROVAL_POLICY_VERSION
        );
        record.setDecision(decision);
        record.setReason(reason);
        record.setReviewerUserId(reviewerUserId);
        record.setCreatedAt(Instant.now());
        return record;
    }

    @Transactional
    public CodeApprovalRecord approveBySystemConfiguration(
            String versionId,
            String riskAssessmentId
    ) {
        String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeRiskAssessment assessment = riskAssessmentRepository
                .findByIdAndVersionIdForUpdate(riskAssessmentId, versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeValidationRun validationRun = validationRunRepository.findById(
                assessment.getValidationRunId()
        ).orElseThrow(() -> validation(
                "VALIDATION_EVIDENCE_MISSING", "Validation evidence is missing"
        ));
        CodeValidationRun latestValidation = validationRunRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(versionId)
                .orElseThrow(() -> validation(
                        "VALIDATION_EVIDENCE_MISSING", "Validation evidence is missing"
                ));
        if (!Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        if (!"READY".equals(version.getStatus())) {
            throw validation("VERSION_NOT_READY", "Code version is not ready");
        }
        if (CodeApprovalStatus.REJECTED.equals(version.getApprovalStatus())
                || CodeApprovalStatus.REVOKED.equals(version.getApprovalStatus())) {
            throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
        }
        if (!Objects.equals(latestValidation.getId(), validationRun.getId())) {
            throw validation("VALIDATION_EVIDENCE_STALE", "Validation evidence is stale");
        }
        requireCurrentPassingEvidence(version, validationRun);
        requireCurrentDirectPassEvidence(version, validationRun, assessment);
        requireActualArtifact(version);
        if (systemConfigService.currentTrainingCodeReviewModeForUpdate()
                != TrainingCodeReviewMode.DIRECT_PASS) {
            throw validation(
                    "DIRECT_PASS_DISABLED",
                    "Direct-pass review mode is no longer enabled"
            );
        }

        CodeApprovalRecord latest = approvalRecordRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(versionId)
                .orElse(null);
        if (latest != null
                && (CodeApprovalStatus.REJECTED.equals(latest.getDecision())
                || CodeApprovalStatus.REVOKED.equals(latest.getDecision()))) {
            throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
        }
        if (CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())
                && exactDirectPassEvidence(
                        latest,
                        version,
                        validationRun,
                        assessment
                )) {
            return latest;
        }

        CodeApprovalRecord record = newRecord(
                version,
                CodeApprovalStatus.APPROVED,
                "Approved without code review by system configuration",
                validationRun,
                assessment,
                CodeApprovalDecisionSource.SYSTEM_CONFIG,
                null
        );
        record.setApprovalPolicyVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_APPROVAL_POLICY_VERSION
        );
        approvalRecordRepository.saveAndFlush(record);
        version.setApprovalStatus(CodeApprovalStatus.APPROVED);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.automaticDecision(
                assetId,
                versionId,
                assessment.getId(),
                "DIRECT_PASS_APPROVE",
                version.getArtifactSha256(),
                assessment.getRiskPolicyVersion(),
                CodeApprovalDecisionSource.SYSTEM_CONFIG
        );
        return record;
    }

    @Transactional
    public CodeApprovalRecord decideAutomatically(
            String versionId,
            String riskAssessmentId,
            boolean approve
    ) {
        String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeRiskAssessment assessment = riskAssessmentRepository
                .findByIdAndVersionIdForUpdate(riskAssessmentId, versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeValidationRun validationRun = validationRunRepository.findById(
                assessment.getValidationRunId()
        ).orElseThrow(() -> validation(
                "VALIDATION_EVIDENCE_MISSING", "Validation evidence is missing"
        ));
        if (!Objects.equals(asset.getId(), version.getAssetId())
                || !Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId())) {
            throw new CodeAssetAccessException();
        }
        requireCurrentPassingEvidence(version, validationRun);
        CodeRiskAssessment current = requireCurrentReviewableRisk(
                version, validationRun, true
        );
        if (!Objects.equals(current.getId(), assessment.getId())) {
            throw validation("RISK_EVIDENCE_STALE", "Risk assessment evidence is stale");
        }
        requireActualArtifact(version);
        if (!systemConfigService.currentTrainingCodeReviewModeForUpdate()
                .automaticDecisionsEnabled()) {
            throw validation(
                    "AUTO_REVIEW_DISABLED",
                    "Automatic code review is no longer enabled"
            );
        }

        String decision;
        String action;
        String reason;
        if (approve) {
            if (!CodeRiskLevel.LOW.equals(assessment.getRiskLevel())
                    || !CodeRiskDisposition.AUTO_APPROVE.equals(assessment.getDisposition())) {
                throw validation("AUTO_APPROVAL_NOT_ALLOWED", "Risk policy requires manual review");
            }
            if (CodeApprovalStatus.REJECTED.equals(version.getApprovalStatus())
                    || CodeApprovalStatus.REVOKED.equals(version.getApprovalStatus())) {
                throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
            }
            decision = CodeApprovalStatus.APPROVED;
            action = "AUTO_APPROVE";
            reason = "Automatically approved by current risk policy";
        } else {
            if (!CodeRiskDisposition.BLOCK.equals(assessment.getDisposition())) {
                throw validation("AUTO_REJECTION_NOT_ALLOWED", "Risk policy does not block this version");
            }
            if (CodeApprovalStatus.REVOKED.equals(version.getApprovalStatus())) {
                throw validation("APPROVAL_TERMINAL", "Code version approval is terminal");
            }
            decision = CodeApprovalStatus.REJECTED;
            action = "AUTO_REJECT";
            reason = "Rejected by current risk policy";
        }
        CodeApprovalRecord latest = approvalRecordRepository
                .findTopByVersionIdOrderByCreatedAtDescIdDesc(versionId)
                .orElse(null);
        if (latest != null
                && decision.equals(latest.getDecision())
                && CodeApprovalDecisionSource.AUTO_POLICY.equals(latest.getDecisionSource())
                && Objects.equals(latest.getRiskAssessmentId(), assessment.getId())) {
            return latest;
        }
        CodeApprovalRecord record = newRecord(
                version, decision, reason, validationRun, assessment,
                CodeApprovalDecisionSource.AUTO_POLICY, null
        );
        approvalRecordRepository.saveAndFlush(record);
        version.setApprovalStatus(decision);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        auditService.automaticDecision(
                assetId,
                versionId,
                assessment.getId(),
                action,
                version.getArtifactSha256(),
                assessment.getRiskPolicyVersion(),
                CodeApprovalDecisionSource.AUTO_POLICY
        );
        return record;
    }

    private static void requireCurrentDirectPassEvidence(
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeRiskAssessment assessment
    ) {
        if (!CodeRiskAssessmentStatus.COMPLETED.equals(assessment.getStatus())
                || !CodeRiskLevel.UNKNOWN.equals(assessment.getRiskLevel())
                || !CodeRiskDisposition.DIRECT_PASS.equals(assessment.getDisposition())
                || !TrainingCodeReviewPolicy.DIRECT_PASS_RISK_POLICY_VERSION.equals(
                        assessment.getRiskPolicyVersion())
                || !Objects.equals(assessment.getId(), version.getLatestRiskAssessmentId())
                || !Objects.equals(assessment.getValidationRunId(), validationRun.getId())
                || !Objects.equals(assessment.getArtifactSha256(),
                        version.getArtifactSha256())
                || !Objects.equals(assessment.getStatus(), version.getRiskStatus())
                || !Objects.equals(assessment.getRiskLevel(), version.getRiskLevel())
                || !Objects.equals(assessment.getDisposition(),
                        version.getReviewDisposition())
                || !Objects.equals(assessment.getRiskPolicyVersion(),
                        version.getRiskPolicyVersion())) {
            throw validation(
                    "DIRECT_PASS_EVIDENCE_STALE",
                    "Direct-pass approval evidence is stale"
            );
        }
    }

    private static boolean exactDirectPassEvidence(
            CodeApprovalRecord latest,
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeRiskAssessment assessment
    ) {
        return latest != null
                && CodeApprovalStatus.APPROVED.equals(latest.getDecision())
                && CodeApprovalDecisionSource.SYSTEM_CONFIG.equals(
                        latest.getDecisionSource())
                && Objects.equals(latest.getValidationRunId(), validationRun.getId())
                && Objects.equals(latest.getArtifactSha256(), version.getArtifactSha256())
                && Objects.equals(latest.getPolicyVersion(),
                        CodeArtifactAssembler.POLICY_VERSION)
                && Objects.equals(latest.getRiskAssessmentId(), assessment.getId())
                && Objects.equals(
                        latest.getApprovalPolicyVersion(),
                        TrainingCodeReviewPolicy.DIRECT_PASS_APPROVAL_POLICY_VERSION
                );
    }

    private static String sanitizeRequiredReason(String reason) {
        if (reason == null) {
            throw validation("APPROVAL_REASON_REQUIRED", "Approval reason is required");
        }
        String sanitized = reason.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isEmpty()) {
            throw validation("APPROVAL_REASON_REQUIRED", "Approval reason is required");
        }
        return sanitized.length() <= 1024 ? sanitized : sanitized.substring(0, 1024);
    }

    private static String sanitizeOptionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return sanitizeRequiredReason(reason);
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    public record ApprovalExpectation(
            String validationRunId,
            String riskAssessmentId,
            String artifactSha256,
            String policyVersion
    ) {
    }
}
