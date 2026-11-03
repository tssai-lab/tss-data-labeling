package com.tss.platform.service;

import com.tss.platform.config.CodeRiskProperties;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeRiskFinding;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.model.CodeRiskAssessmentStatus;
import com.tss.platform.model.CodeRiskDisposition;
import com.tss.platform.model.CodeRiskLevel;
import com.tss.platform.model.TrainingCodeReviewMode;
import com.tss.platform.model.TrainingCodeReviewPolicy;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeRiskFindingRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent, bounded risk-scan orchestration. It never executes uploaded code.
 */
@Service
public class CodeRiskAssessmentService implements CodeRiskAssessmentRescanService {

    private static final Logger log = LoggerFactory.getLogger(CodeRiskAssessmentService.class);
    private static final String MANUAL_SCANNER_VERSION = "manual-review-only";
    private static final String VALIDATION_ERROR_CODE = "VALIDATION_NOT_PASSED";

    private final CodeRiskAssessmentRepository assessmentRepository;
    private final CodeRiskFindingRepository findingRepository;
    private final CodeVersionRepository versionRepository;
    private final CodeAssetRepository assetRepository;
    private final CodeValidationRunRepository validationRunRepository;
    private final CodeArtifactStorageService storageService;
    private final CodeZipArchiveService zipArchiveService;
    private final CodeStaticRiskScanner scanner;
    private final CodeRiskProperties properties;
    private final SystemConfigService systemConfigService;
    private final CodeApprovalService approvalService;
    private final CodeAssetAuditService auditService;
    private final AuthContext authContext;
    private final TransactionTemplate requiresNew;

    public CodeRiskAssessmentService(
            CodeRiskAssessmentRepository assessmentRepository,
            CodeRiskFindingRepository findingRepository,
            CodeVersionRepository versionRepository,
            CodeAssetRepository assetRepository,
            CodeValidationRunRepository validationRunRepository,
            CodeArtifactStorageService storageService,
            CodeZipArchiveService zipArchiveService,
            CodeStaticRiskScanner scanner,
            CodeRiskProperties properties,
            SystemConfigService systemConfigService,
            CodeApprovalService approvalService,
            CodeAssetAuditService auditService,
            AuthContext authContext,
            PlatformTransactionManager transactionManager
    ) {
        this.assessmentRepository = assessmentRepository;
        this.findingRepository = findingRepository;
        this.versionRepository = versionRepository;
        this.assetRepository = assetRepository;
        this.validationRunRepository = validationRunRepository;
        this.storageService = storageService;
        this.zipArchiveService = zipArchiveService;
        this.scanner = scanner;
        this.properties = properties;
        this.systemConfigService = systemConfigService;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.authContext = authContext;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Called by import/publish/revalidation after their immutable validation evidence is saved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CodeRiskAssessment enqueue(
            String versionId,
            String validationRunId,
            Integer requestedByUserId
    ) {
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                .orElseThrow(CodeAssetAccessException::new);
        CodeValidationRun validationRun = validationRunRepository.findById(validationRunId)
                .orElseThrow(() -> validation(
                        "VALIDATION_EVIDENCE_MISSING", "Validation evidence is missing"
                ));
        requireEvidence(version, validationRun);
        TrainingCodeReviewMode reviewMode =
                systemConfigService.currentTrainingCodeReviewMode();
        if (reviewMode == TrainingCodeReviewMode.DIRECT_PASS) {
            CodeRiskAssessment assessment = createDirectPassAssessment(
                    version,
                    validationRun,
                    requestedByUserId
            );
            approvalService.approveBySystemConfiguration(
                    version.getId(),
                    assessment.getId()
            );
            return assessment;
        }
        return createAssessment(
                version,
                validationRun,
                requestedByUserId,
                false,
                reviewMode == TrainingCodeReviewMode.MANUAL_ONLY
        );
    }

    @Override
    public CodeRiskAssessment rescan(String versionId) {
        approvalService.requireAdministratorAuthority();
        Integer requester;
        try {
            requester = authContext.currentUserId();
        } catch (RuntimeException exception) {
            throw new CodeApprovalForbiddenException();
        }
        CodeRiskAssessment assessment = requiresNew.execute(status -> {
            String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(versionId)
                    .orElseThrow(CodeAssetAccessException::new);
            assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                    .orElseThrow(CodeAssetAccessException::new);
            CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(versionId)
                    .orElseThrow(CodeAssetAccessException::new);
            CodeValidationRun validationRun = validationRunRepository
                    .findTopByVersionIdOrderByCreatedAtDescIdDesc(versionId)
                    .orElseThrow(() -> validation(
                            "VALIDATION_EVIDENCE_MISSING", "Validation evidence is missing"
                    ));
            requireEvidence(version, validationRun);
            CodeRiskAssessment latest = assessmentRepository
                    .findTopByVersionIdOrderByCreatedAtDescIdDesc(versionId)
                    .orElse(null);
            if (latest != null
                    && Objects.equals(latest.getValidationRunId(), validationRun.getId())
                    && Objects.equals(latest.getArtifactSha256(), version.getArtifactSha256())
                    && Objects.equals(latest.getRiskPolicyVersion(),
                            CodeStaticRiskScanner.RISK_POLICY_VERSION)
                    && (CodeRiskAssessmentStatus.QUEUED.equals(latest.getStatus())
                    || CodeRiskAssessmentStatus.RUNNING.equals(latest.getStatus()))) {
                return latest;
            }
            return createAssessment(version, validationRun, requester, true, false);
        });
        if (assessment == null) {
            throw new IllegalStateException("Risk assessment transaction failed");
        }
        return assessment;
    }

    public void processPendingBatch() {
        if (properties.getMode() == CodeRiskProperties.Mode.MANUAL_ONLY) {
            return;
        }
        recoverStaleRunning();
        List<CodeRiskAssessment> queued = assessmentRepository
                .findByStatusOrderByCreatedAtAscIdAsc(
                        CodeRiskAssessmentStatus.QUEUED,
                        PageRequest.of(0, properties.getBatchSize())
                );
        for (CodeRiskAssessment candidate : queued) {
            processOne(candidate.getId());
        }
        if (properties.getMode() == CodeRiskProperties.Mode.ENFORCE
                && systemConfigService.currentTrainingCodeReviewMode()
                .automaticDecisionsEnabled()) {
            reconcileAutomaticDecisions();
        }
    }

    private void processOne(String assessmentId) {
        ScanSnapshot snapshot = requiresNew.execute(status -> claim(assessmentId));
        if (snapshot == null) {
            return;
        }
        CodeRiskScanResult result;
        try {
            StoredCodeArtifact stored = storageService.read(snapshot.storagePath());
            requireStoredEvidence(snapshot, stored);
            result = scanner.scan(zipArchiveService.readEntries(
                    new ByteArrayInputStream(stored.bytes())
            ));
        } catch (CodeValidationException exception) {
            if (isEvidenceFailure(exception.getReasonCode())) {
                fail(snapshot, exception.getReasonCode());
                return;
            }
            result = new CodeRiskScanResult(
                    CodeStaticRiskScanner.SCANNER_VERSION,
                    CodeStaticRiskScanner.RISK_POLICY_VERSION,
                    CodeRiskLevel.HIGH,
                    CodeRiskDisposition.BLOCK,
                    safeReasonCode(exception.getReasonCode(), "RISK_ARTIFACT_INVALID"),
                    List.of(new CodeRiskScanFinding(
                            "ARTIFACT_VALIDATION_FAILED", "CRITICAL", "STRUCTURE",
                            null, null, null,
                            "Code artifact structure is invalid", true
                    ))
            );
        } catch (RuntimeException exception) {
            log.warn("Code risk scan failed: assessmentId={}, errorType={}",
                    assessmentId, exception.getClass().getSimpleName());
            fail(snapshot, "RISK_SCAN_ERROR");
            return;
        }
        complete(snapshot, result);
    }

    private CodeRiskAssessment createAssessment(
            CodeVersion version,
            CodeValidationRun validationRun,
            Integer requestedByUserId,
            boolean explicitRescan,
            boolean forceManualReview
    ) {
        Instant now = Instant.now();
        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("code-risk-" + compactUuid());
        assessment.setVersionId(version.getId());
        assessment.setValidationRunId(validationRun.getId());
        assessment.setArtifactSha256(version.getArtifactSha256());
        assessment.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        assessment.setRequestedByUserId(requestedByUserId);
        assessment.setCreatedAt(now);
        assessment.setFindingCount(0);

        if (!"PASSED".equals(validationRun.getStatus())) {
            assessment.setScannerVersion(CodeStaticRiskScanner.SCANNER_VERSION);
            assessment.setStatus(CodeRiskAssessmentStatus.ERROR);
            assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
            assessment.setDisposition(null);
            assessment.setErrorCode(VALIDATION_ERROR_CODE);
            assessment.setErrorMessage("Code validation did not pass");
            assessment.setCompletedAt(now);
        } else if (forceManualReview
                || properties.getMode() == CodeRiskProperties.Mode.MANUAL_ONLY) {
            assessment.setScannerVersion(MANUAL_SCANNER_VERSION);
            assessment.setStatus(CodeRiskAssessmentStatus.COMPLETED);
            assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
            assessment.setDisposition(CodeRiskDisposition.MANUAL_REVIEW);
            assessment.setCompletedAt(now);
        } else {
            assessment.setScannerVersion(CodeStaticRiskScanner.SCANNER_VERSION);
            assessment.setStatus(CodeRiskAssessmentStatus.QUEUED);
            assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
        }
        assessmentRepository.saveAndFlush(assessment);
        updateVersionSummary(version, assessment, now);
        auditService.riskAssessmentQueued(
                version.getAssetId(), version.getId(), assessment.getId(),
                validationRun.getId(), version.getArtifactSha256(),
                assessment.getRiskPolicyVersion()
        );
        if (CodeRiskAssessmentStatus.COMPLETED.equals(assessment.getStatus())) {
            auditService.riskAssessmentCompleted(
                    version.getAssetId(), version.getId(), assessment.getId(),
                    assessment.getRiskLevel(), assessment.getDisposition(),
                    assessment.getScannerVersion(), 0
            );
        } else if (CodeRiskAssessmentStatus.ERROR.equals(assessment.getStatus())) {
            auditService.riskAssessmentFailed(
                    version.getAssetId(), version.getId(), assessment.getId(),
                    assessment.getErrorCode()
            );
        }
        if (explicitRescan
                && properties.getMode() == CodeRiskProperties.Mode.MANUAL_ONLY
                && CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())) {
            version.setApprovalStatus(CodeApprovalStatus.PENDING);
            versionRepository.saveAndFlush(version);
        }
        return assessment;
    }

    private CodeRiskAssessment createDirectPassAssessment(
            CodeVersion version,
            CodeValidationRun validationRun,
            Integer requestedByUserId
    ) {
        Instant now = Instant.now();
        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("code-risk-" + compactUuid());
        assessment.setVersionId(version.getId());
        assessment.setValidationRunId(validationRun.getId());
        assessment.setArtifactSha256(version.getArtifactSha256());
        assessment.setRiskPolicyVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_RISK_POLICY_VERSION
        );
        assessment.setScannerVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_SCANNER_VERSION
        );
        assessment.setStatus(CodeRiskAssessmentStatus.COMPLETED);
        assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
        assessment.setDisposition(CodeRiskDisposition.DIRECT_PASS);
        assessment.setFindingCount(0);
        assessment.setRequestedByUserId(requestedByUserId);
        assessment.setCreatedAt(now);
        assessment.setCompletedAt(now);
        assessmentRepository.saveAndFlush(assessment);
        updateVersionSummary(version, assessment, now);
        auditService.riskAssessmentCompleted(
                version.getAssetId(),
                version.getId(),
                assessment.getId(),
                assessment.getRiskLevel(),
                assessment.getDisposition(),
                assessment.getScannerVersion(),
                0
        );
        return assessment;
    }

    private ScanSnapshot claim(String assessmentId) {
        CodeRiskAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElse(null);
        if (assessment == null || !CodeRiskAssessmentStatus.QUEUED.equals(
                assessment.getStatus())) {
            return null;
        }
        String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(
                assessment.getVersionId()
        ).orElse(null);
        if (assetId == null) {
            cancel(assessment, "CODE_VERSION_UNAVAILABLE");
            return null;
        }
        CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                .orElse(null);
        CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(
                assessment.getVersionId()
        ).orElse(null);
        CodeRiskAssessment locked = assessmentRepository.findByIdAndVersionIdForUpdate(
                assessmentId, assessment.getVersionId()
        ).orElse(null);
        if (asset == null || version == null || locked == null
                || !CodeRiskAssessmentStatus.QUEUED.equals(locked.getStatus())) {
            return null;
        }
        if (!Objects.equals(version.getLatestRiskAssessmentId(), locked.getId())) {
            cancel(locked, "RISK_ASSESSMENT_SUPERSEDED");
            return null;
        }
        CodeValidationRun validationRun = validationRunRepository.findById(
                locked.getValidationRunId()
        ).orElse(null);
        if (!validIdentity(asset, version)
                || validationRun == null
                || !validEvidence(version, validationRun, locked)) {
            failLocked(asset, version, locked, "RISK_EVIDENCE_STALE");
            return null;
        }
        locked.setStatus(CodeRiskAssessmentStatus.RUNNING);
        locked.setStartedAt(Instant.now());
        assessmentRepository.saveAndFlush(locked);
        version.setRiskStatus(CodeRiskAssessmentStatus.RUNNING);
        version.setUpdatedAt(Instant.now());
        versionRepository.saveAndFlush(version);
        return new ScanSnapshot(
                locked.getId(), version.getId(), asset.getId(), version.getOwnerUserId(),
                validationRun.getId(), version.getStoragePath(), version.getArtifactSha256(),
                version.getSizeBytes(), locked.getRiskPolicyVersion()
        );
    }

    private void complete(ScanSnapshot snapshot, CodeRiskScanResult result) {
        Boolean completed = requiresNew.execute(status -> {
            String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(snapshot.versionId())
                    .orElseThrow(CodeAssetAccessException::new);
            CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                    .orElseThrow(CodeAssetAccessException::new);
            CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(
                    snapshot.versionId()
            ).orElseThrow(CodeAssetAccessException::new);
            CodeRiskAssessment assessment = assessmentRepository.findByIdAndVersionIdForUpdate(
                    snapshot.assessmentId(), snapshot.versionId()
            ).orElseThrow(CodeAssetAccessException::new);
            if (!validIdentity(asset, version)
                    || !CodeRiskAssessmentStatus.RUNNING.equals(assessment.getStatus())
                    || !Objects.equals(version.getLatestRiskAssessmentId(), assessment.getId())
                    || !matchesSnapshot(version, assessment, snapshot)) {
                throw new CodeWorkspaceConflictException(
                        "RISK_SCAN_CONFLICT", "Risk assessment evidence changed"
                );
            }
            Instant now = Instant.now();
            List<CodeRiskFinding> findings = result.findings().stream()
                    .map(value -> toEntity(assessment.getId(), value, now))
                    .toList();
            findingRepository.saveAll(findings);
            assessment.setStatus(CodeRiskAssessmentStatus.COMPLETED);
            assessment.setRiskLevel(result.riskLevel());
            assessment.setDisposition(result.disposition());
            assessment.setScannerVersion(result.scannerVersion());
            assessment.setRiskPolicyVersion(result.riskPolicyVersion());
            assessment.setFindingCount(findings.size());
            assessment.setErrorCode(null);
            assessment.setErrorMessage(null);
            assessment.setCompletedAt(now);
            assessmentRepository.saveAndFlush(assessment);
            if (properties.getMode() == CodeRiskProperties.Mode.ENFORCE
                    && (CodeRiskDisposition.MANUAL_REVIEW.equals(result.disposition())
                    || CodeRiskDisposition.BLOCK.equals(result.disposition()))
                    && CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())
                    && systemConfigService.currentTrainingCodeReviewModeForUpdate()
                    .automaticDecisionsEnabled()) {
                // Fail closed in the same transaction that publishes the new
                // risk summary. The follow-up AUTO_POLICY rejection may be
                // retried, but no resolver can consume the old approval meanwhile.
                version.setApprovalStatus(CodeApprovalStatus.PENDING);
            }
            updateVersionSummary(version, assessment, now);
            auditService.riskAssessmentCompleted(
                    asset.getId(), version.getId(), assessment.getId(),
                    result.riskLevel(), result.disposition(), result.scannerVersion(),
                    findings.size()
            );
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(completed)
                && properties.getMode() == CodeRiskProperties.Mode.ENFORCE
                && systemConfigService.currentTrainingCodeReviewMode()
                .automaticDecisionsEnabled()) {
            applyAutomaticDecision(snapshot.versionId(), snapshot.assessmentId(), result);
        }
    }

    private void applyAutomaticDecision(
            String versionId,
            String assessmentId,
            CodeRiskScanResult result
    ) {
        try {
            if (CodeRiskDisposition.AUTO_APPROVE.equals(result.disposition())
                    && CodeRiskLevel.LOW.equals(result.riskLevel())) {
                approvalService.decideAutomatically(versionId, assessmentId, true);
            } else if (CodeRiskDisposition.BLOCK.equals(result.disposition())) {
                approvalService.decideAutomatically(versionId, assessmentId, false);
            }
        } catch (CodeValidationException | CodeAssetAccessException exception) {
            log.warn("Automatic code risk decision deferred: versionId={}, reasonCode={}",
                    versionId,
                    exception instanceof CodeValidationException validationException
                            ? validationException.getReasonCode() : "RESOURCE_UNAVAILABLE");
        }
    }

    private void reconcileAutomaticDecisions() {
        List<CodeRiskAssessment> completed = assessmentRepository
                .findByStatusOrderByCreatedAtDescIdDesc(
                        CodeRiskAssessmentStatus.COMPLETED,
                        PageRequest.of(0, properties.getBatchSize())
                );
        for (CodeRiskAssessment assessment : completed) {
            if (CodeRiskDisposition.AUTO_APPROVE.equals(assessment.getDisposition())
                    || CodeRiskDisposition.BLOCK.equals(assessment.getDisposition())) {
                applyAutomaticDecision(
                        assessment.getVersionId(), assessment.getId(),
                        new CodeRiskScanResult(
                                assessment.getScannerVersion(), assessment.getRiskPolicyVersion(),
                                assessment.getRiskLevel(), assessment.getDisposition(),
                                "RISK_RECONCILE", List.of()
                        )
                );
            }
        }
    }

    private void fail(ScanSnapshot snapshot, String reasonCode) {
        requiresNew.executeWithoutResult(status -> {
            String assetId = versionRepository.findAssetIdByIdAndDeletedFalse(snapshot.versionId())
                    .orElse(null);
            if (assetId == null) {
                return;
            }
            CodeAsset asset = assetRepository.findByIdAndDeletedFalseForUpdate(assetId)
                    .orElse(null);
            CodeVersion version = versionRepository.findByIdAndDeletedFalseForUpdate(
                    snapshot.versionId()
            ).orElse(null);
            CodeRiskAssessment assessment = assessmentRepository.findByIdAndVersionIdForUpdate(
                    snapshot.assessmentId(), snapshot.versionId()
            ).orElse(null);
            if (asset != null && version != null && assessment != null) {
                failLocked(asset, version, assessment, safeReasonCode(reasonCode, "RISK_SCAN_ERROR"));
            }
        });
    }

    private void failLocked(
            CodeAsset asset,
            CodeVersion version,
            CodeRiskAssessment assessment,
            String reasonCode
    ) {
        Instant now = Instant.now();
        assessment.setStatus(CodeRiskAssessmentStatus.ERROR);
        assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
        assessment.setDisposition(null);
        assessment.setErrorCode(reasonCode);
        assessment.setErrorMessage("Risk scan could not be completed");
        assessment.setCompletedAt(now);
        assessmentRepository.saveAndFlush(assessment);
        if (properties.getMode() == CodeRiskProperties.Mode.ENFORCE
                && CodeApprovalStatus.APPROVED.equals(version.getApprovalStatus())) {
            version.setApprovalStatus(CodeApprovalStatus.PENDING);
        }
        if (Objects.equals(version.getLatestRiskAssessmentId(), assessment.getId())) {
            updateVersionSummary(version, assessment, now);
        }
        auditService.riskAssessmentFailed(
                asset.getId(), version.getId(), assessment.getId(), reasonCode
        );
    }

    private void recoverStaleRunning() {
        List<CodeRiskAssessment> running = assessmentRepository
                .findByStatusOrderByCreatedAtAscIdAsc(CodeRiskAssessmentStatus.RUNNING);
        Instant cutoff = Instant.now().minusSeconds(properties.getStaleAfterSeconds());
        for (CodeRiskAssessment assessment : running) {
            if (assessment.getStartedAt() != null && assessment.getStartedAt().isBefore(cutoff)) {
                requiresNew.executeWithoutResult(status -> {
                    CodeRiskAssessment locked = assessmentRepository
                            .findByIdAndVersionIdForUpdate(
                                    assessment.getId(), assessment.getVersionId()
                            ).orElse(null);
                    if (locked != null
                            && CodeRiskAssessmentStatus.RUNNING.equals(locked.getStatus())
                            && locked.getStartedAt() != null
                            && locked.getStartedAt().isBefore(cutoff)) {
                        locked.setStatus(CodeRiskAssessmentStatus.QUEUED);
                        locked.setStartedAt(null);
                        assessmentRepository.saveAndFlush(locked);
                    }
                });
            }
        }
    }

    private void cancel(CodeRiskAssessment assessment, String reasonCode) {
        assessment.setStatus(CodeRiskAssessmentStatus.CANCELED);
        assessment.setErrorCode(reasonCode);
        assessment.setErrorMessage("Risk assessment was superseded");
        assessment.setCompletedAt(Instant.now());
        assessmentRepository.saveAndFlush(assessment);
    }

    private void updateVersionSummary(
            CodeVersion version,
            CodeRiskAssessment assessment,
            Instant now
    ) {
        version.setLatestRiskAssessmentId(assessment.getId());
        version.setRiskStatus(assessment.getStatus());
        version.setRiskLevel(assessment.getRiskLevel());
        version.setReviewDisposition(assessment.getDisposition());
        version.setRiskPolicyVersion(assessment.getRiskPolicyVersion());
        version.setUpdatedAt(now);
        versionRepository.saveAndFlush(version);
    }

    private static void requireEvidence(CodeVersion version, CodeValidationRun validationRun) {
        if (!Objects.equals(validationRun.getVersionId(), version.getId())
                || !Objects.equals(validationRun.getArtifactSha256(), version.getArtifactSha256())
                || !Objects.equals(validationRun.getPolicyVersion(),
                        version.getValidationPolicyVersion())) {
            throw validation("VALIDATION_EVIDENCE_STALE", "Validation evidence is stale");
        }
    }

    private static boolean validEvidence(
            CodeVersion version,
            CodeValidationRun validationRun,
            CodeRiskAssessment assessment
    ) {
        return "READY".equals(version.getStatus())
                && "PASSED".equals(version.getValidationStatus())
                && "PASSED".equals(validationRun.getStatus())
                && Objects.equals(validationRun.getVersionId(), version.getId())
                && Objects.equals(validationRun.getId(), assessment.getValidationRunId())
                && Objects.equals(validationRun.getArtifactSha256(), version.getArtifactSha256())
                && Objects.equals(validationRun.getArtifactSha256(), assessment.getArtifactSha256())
                && Objects.equals(validationRun.getPolicyVersion(),
                        version.getValidationPolicyVersion())
                && Objects.equals(assessment.getRiskPolicyVersion(),
                        CodeStaticRiskScanner.RISK_POLICY_VERSION);
    }

    private static boolean validIdentity(CodeAsset asset, CodeVersion version) {
        return asset != null
                && version != null
                && Objects.equals(asset.getId(), version.getAssetId())
                && Objects.equals(asset.getOwnerUserId(), version.getOwnerUserId());
    }

    private static void requireStoredEvidence(
            ScanSnapshot snapshot,
            StoredCodeArtifact stored
    ) {
        if (!validStoragePath(snapshot)) {
            throw validation("STORAGE_REFERENCE_INVALID", "Storage reference is invalid");
        }
        if (!Objects.equals(snapshot.storagePath(), stored.objectName())) {
            throw validation("STORAGE_REFERENCE_INVALID", "Storage reference is invalid");
        }
        if (!Objects.equals(snapshot.artifactSha256(), stored.artifactSha256())) {
            throw validation("ARTIFACT_SHA256_MISMATCH", "Artifact hash does not match");
        }
        if (snapshot.sizeBytes() == null || snapshot.sizeBytes() != stored.sizeBytes()) {
            throw validation("ARTIFACT_SIZE_MISMATCH", "Artifact size does not match");
        }
    }

    private static boolean validStoragePath(ScanSnapshot snapshot) {
        String prefix = "users/" + snapshot.ownerUserId()
                + "/codes/" + snapshot.assetId()
                + "/versions/" + snapshot.versionId() + "/";
        String leaf = snapshot.storagePath() != null
                && snapshot.storagePath().startsWith(prefix)
                ? snapshot.storagePath().substring(prefix.length()) : null;
        return leaf != null
                && !leaf.isBlank()
                && !leaf.contains("/")
                && !leaf.contains("\\")
                && !leaf.contains("?")
                && !leaf.contains("#")
                && !".".equals(leaf)
                && !"..".equals(leaf);
    }

    private static boolean matchesSnapshot(
            CodeVersion version,
            CodeRiskAssessment assessment,
            ScanSnapshot snapshot
    ) {
        return Objects.equals(version.getStoragePath(), snapshot.storagePath())
                && Objects.equals(version.getArtifactSha256(), snapshot.artifactSha256())
                && Objects.equals(version.getSizeBytes(), snapshot.sizeBytes())
                && Objects.equals(assessment.getValidationRunId(), snapshot.validationRunId())
                && Objects.equals(assessment.getArtifactSha256(), snapshot.artifactSha256())
                && Objects.equals(assessment.getRiskPolicyVersion(), snapshot.riskPolicyVersion());
    }

    private static CodeRiskFinding toEntity(
            String assessmentId,
            CodeRiskScanFinding finding,
            Instant now
    ) {
        CodeRiskFinding entity = new CodeRiskFinding();
        entity.setId("code-risk-finding-" + compactUuid());
        entity.setRiskAssessmentId(assessmentId);
        entity.setRuleId(finding.ruleId());
        entity.setSeverity(finding.severity());
        entity.setCategory(finding.category());
        entity.setFilePath(finding.filePath());
        entity.setLineStart(finding.lineStart());
        entity.setLineEnd(finding.lineEnd());
        entity.setDescription(finding.safeMessage());
        entity.setCreatedAt(now);
        return entity;
    }

    private static boolean isEvidenceFailure(String reasonCode) {
        return "STORAGE_REFERENCE_INVALID".equals(reasonCode)
                || "ARTIFACT_SHA256_MISMATCH".equals(reasonCode)
                || "ARTIFACT_SIZE_MISMATCH".equals(reasonCode);
    }

    private static String safeReasonCode(String reasonCode, String fallback) {
        return reasonCode != null && reasonCode.matches("[A-Z0-9_]+")
                ? reasonCode : fallback;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CodeValidationException validation(String code, String message) {
        return new CodeValidationException(code, message);
    }

    private record ScanSnapshot(
            String assessmentId,
            String versionId,
            String assetId,
            Integer ownerUserId,
            String validationRunId,
            String storagePath,
            String artifactSha256,
            Long sizeBytes,
            String riskPolicyVersion
    ) {
    }
}
