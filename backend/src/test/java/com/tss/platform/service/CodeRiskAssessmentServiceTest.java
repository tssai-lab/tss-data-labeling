package com.tss.platform.service;

import com.tss.platform.config.CodeRiskProperties;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeRiskAssessmentServiceTest {

    private final CodeRiskAssessmentRepository assessmentRepository =
            mock(CodeRiskAssessmentRepository.class);
    private final CodeRiskFindingRepository findingRepository =
            mock(CodeRiskFindingRepository.class);
    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeValidationRunRepository validationRepository =
            mock(CodeValidationRunRepository.class);
    private final CodeArtifactStorageService storageService =
            mock(CodeArtifactStorageService.class);
    private final CodeApprovalService approvalService = mock(CodeApprovalService.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final CodeRiskProperties properties = new CodeRiskProperties();
    private final CodeZipArchiveService zipService = new CodeZipArchiveService();
    private final CodeFilePolicy filePolicy = new CodeFilePolicy();
    private final CodeStaticRiskScanner scanner = new CodeStaticRiskScanner(
            new com.fasterxml.jackson.databind.ObjectMapper()
    );

    private CodeRiskAssessmentService service;
    private CodeAsset asset;
    private CodeVersion version;
    private CodeValidationRun validation;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(assessmentRepository.saveAndFlush(any(CodeRiskAssessment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(findingRepository.saveAll(any())).thenAnswer(
                invocation -> invocation.getArgument(0)
        );
        when(assessmentRepository.findByStatusOrderByCreatedAtAscIdAsc(
                eq(CodeRiskAssessmentStatus.RUNNING)
        )).thenReturn(List.of());
        when(assessmentRepository.findByStatusOrderByCreatedAtDescIdDesc(
                eq(CodeRiskAssessmentStatus.COMPLETED), any(Pageable.class)
        )).thenReturn(List.of());
        when(systemConfigService.currentTrainingCodeReviewMode())
                .thenReturn(TrainingCodeReviewMode.STANDARD_REVIEW);
        when(systemConfigService.currentTrainingCodeReviewModeForUpdate())
                .thenReturn(TrainingCodeReviewMode.STANDARD_REVIEW);
        asset = asset();
        version = version();
        validation = validation();
        service = new CodeRiskAssessmentService(
                assessmentRepository,
                findingRepository,
                versionRepository,
                assetRepository,
                validationRepository,
                storageService,
                zipService,
                scanner,
                properties,
                systemConfigService,
                approvalService,
                auditService,
                authContext,
                transactionManager
        );
    }

    @Test
    void dynamicManualOnlyCreatesCompletedManualReviewEvidenceWithoutAutoDecision() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        when(systemConfigService.currentTrainingCodeReviewMode())
                .thenReturn(TrainingCodeReviewMode.MANUAL_ONLY);
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));
        when(validationRepository.findById("validation-1"))
                .thenReturn(Optional.of(validation));

        CodeRiskAssessment assessment = service.enqueue("version-1", "validation-1", 7);

        assertEquals(CodeRiskAssessmentStatus.COMPLETED, assessment.getStatus());
        assertEquals(CodeRiskLevel.UNKNOWN, assessment.getRiskLevel());
        assertEquals(CodeRiskDisposition.MANUAL_REVIEW, assessment.getDisposition());
        assertEquals(assessment.getId(), version.getLatestRiskAssessmentId());
        assertEquals(CodeRiskAssessmentStatus.COMPLETED, version.getRiskStatus());
        verify(auditService).riskAssessmentCompleted(
                "asset-1", "version-1", assessment.getId(),
                CodeRiskLevel.UNKNOWN, CodeRiskDisposition.MANUAL_REVIEW,
                "manual-review-only", 0
        );
        verify(approvalService, never()).approveBySystemConfiguration(any(), any());
        verify(approvalService, never()).decideAutomatically(any(), any(), any(Boolean.class));
    }

    @Test
    void directPassSkipsScannerAndApprovesOnlyAfterValidationEvidenceExists() {
        when(systemConfigService.currentTrainingCodeReviewMode())
                .thenReturn(TrainingCodeReviewMode.DIRECT_PASS);
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));
        when(validationRepository.findById("validation-1"))
                .thenReturn(Optional.of(validation));

        CodeRiskAssessment assessment = service.enqueue("version-1", "validation-1", 7);

        assertEquals(CodeRiskAssessmentStatus.COMPLETED, assessment.getStatus());
        assertEquals(CodeRiskLevel.UNKNOWN, assessment.getRiskLevel());
        assertEquals(CodeRiskDisposition.DIRECT_PASS, assessment.getDisposition());
        assertEquals(
                TrainingCodeReviewPolicy.DIRECT_PASS_RISK_POLICY_VERSION,
                assessment.getRiskPolicyVersion()
        );
        assertEquals(
                TrainingCodeReviewPolicy.DIRECT_PASS_SCANNER_VERSION,
                assessment.getScannerVersion()
        );
        verify(approvalService).approveBySystemConfiguration(
                "version-1",
                assessment.getId()
        );
        verify(auditService).riskAssessmentCompleted(
                "asset-1",
                "version-1",
                assessment.getId(),
                CodeRiskLevel.UNKNOWN,
                CodeRiskDisposition.DIRECT_PASS,
                TrainingCodeReviewPolicy.DIRECT_PASS_SCANNER_VERSION,
                0
        );
    }

    @Test
    void adminRescanChecksAuthorityBeforeResourceLookup() {
        org.mockito.Mockito.doThrow(new CodeApprovalForbiddenException())
                .when(approvalService).requireAdministratorAuthority();

        assertThrows(CodeApprovalForbiddenException.class, () -> service.rescan("hidden"));

        verify(versionRepository, never()).findAssetIdByIdAndDeletedFalse(any());
    }

    @Test
    void enforceCleanScanAutoApprovesOnlyAfterActualShaAndLengthMatch() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        byte[] archive = zipService.writeDeterministic(Map.of(
                "train.py", "def train(x):\n    return x + 1\n"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        configureArtifact(archive);
        CodeRiskAssessment queued = queuedAssessment();
        configureQueuedProcessing(queued);
        when(storageService.read(version.getStoragePath())).thenReturn(new StoredCodeArtifact(
                version.getStoragePath(), archive, version.getArtifactSha256(), archive.length
        ));

        service.processPendingBatch();

        assertEquals(CodeRiskAssessmentStatus.COMPLETED, queued.getStatus());
        assertEquals(CodeRiskLevel.LOW, queued.getRiskLevel());
        assertEquals(CodeRiskDisposition.AUTO_APPROVE, queued.getDisposition());
        assertEquals(0, queued.getFindingCount());
        verify(approvalService).decideAutomatically("version-1", "risk-1", true);
    }

    @Test
    void shaMismatchFailsClosedAndNeverCallsAutomaticDecision() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        byte[] archive = zipService.writeDeterministic(Map.of(
                "train.py", "print('ok')\n".getBytes(StandardCharsets.UTF_8)
        ));
        configureArtifact(archive);
        CodeRiskAssessment queued = queuedAssessment();
        configureQueuedProcessing(queued);
        when(storageService.read(version.getStoragePath())).thenReturn(new StoredCodeArtifact(
                version.getStoragePath(), archive, "b".repeat(64), archive.length
        ));

        service.processPendingBatch();

        assertEquals(CodeRiskAssessmentStatus.ERROR, queued.getStatus());
        assertEquals(CodeRiskLevel.UNKNOWN, queued.getRiskLevel());
        assertEquals("ARTIFACT_SHA256_MISMATCH", queued.getErrorCode());
        verify(approvalService, never()).decideAutomatically(any(), any(), eq(true));
        verify(approvalService, never()).decideAutomatically(any(), any(), eq(false));
    }

    @Test
    void queuedScanCanFinishButCannotDecideAfterAutomaticReviewIsDisabled() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        when(systemConfigService.currentTrainingCodeReviewMode())
                .thenReturn(TrainingCodeReviewMode.MANUAL_ONLY);
        byte[] archive = zipService.writeDeterministic(Map.of(
                "train.py", "def train(x):\n    return x + 1\n"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        configureArtifact(archive);
        CodeRiskAssessment queued = queuedAssessment();
        configureQueuedProcessing(queued);
        when(storageService.read(version.getStoragePath())).thenReturn(new StoredCodeArtifact(
                version.getStoragePath(), archive, version.getArtifactSha256(), archive.length
        ));

        service.processPendingBatch();

        assertEquals(CodeRiskAssessmentStatus.COMPLETED, queued.getStatus());
        assertEquals(CodeRiskDisposition.AUTO_APPROVE, queued.getDisposition());
        assertEquals("PENDING", version.getApprovalStatus());
        verify(approvalService, never()).decideAutomatically(any(), any(), any(Boolean.class));
    }

    @Test
    void disablingAutomaticReviewDoesNotRevokeAnExistingApprovalWhenScanFinishes() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        when(systemConfigService.currentTrainingCodeReviewMode())
                .thenReturn(TrainingCodeReviewMode.MANUAL_ONLY);
        when(systemConfigService.currentTrainingCodeReviewModeForUpdate())
                .thenReturn(TrainingCodeReviewMode.MANUAL_ONLY);
        version.setApprovalStatus("APPROVED");
        byte[] archive = zipService.writeDeterministic(Map.of(
                "train.py", ("KEY = '''-----BEGIN PRIVATE KEY-----\n"
                        + "sensitive\n-----END PRIVATE KEY-----'''\n")
                        .getBytes(StandardCharsets.UTF_8)
        ));
        configureArtifact(archive);
        CodeRiskAssessment queued = queuedAssessment();
        configureQueuedProcessing(queued);
        when(storageService.read(version.getStoragePath())).thenReturn(new StoredCodeArtifact(
                version.getStoragePath(), archive, version.getArtifactSha256(), archive.length
        ));

        service.processPendingBatch();

        assertEquals(CodeRiskDisposition.BLOCK, queued.getDisposition());
        assertEquals("APPROVED", version.getApprovalStatus());
        verify(approvalService, never()).decideAutomatically(any(), any(), any(Boolean.class));
    }

    @Test
    void enforceBlockInvalidatesOldApprovalBeforeAutomaticRejection() {
        properties.setMode(CodeRiskProperties.Mode.ENFORCE);
        version.setApprovalStatus("APPROVED");
        byte[] archive = zipService.writeDeterministic(Map.of(
                "train.py", ("KEY = '''-----BEGIN PRIVATE KEY-----\\n"
                        + "sensitive\\n-----END PRIVATE KEY-----'''\\n")
                        .getBytes(StandardCharsets.UTF_8)
        ));
        configureArtifact(archive);
        CodeRiskAssessment queued = queuedAssessment();
        configureQueuedProcessing(queued);
        when(storageService.read(version.getStoragePath())).thenReturn(new StoredCodeArtifact(
                version.getStoragePath(), archive, version.getArtifactSha256(), archive.length
        ));

        service.processPendingBatch();

        assertEquals(CodeRiskDisposition.BLOCK, queued.getDisposition());
        assertEquals("PENDING", version.getApprovalStatus());
        verify(approvalService).decideAutomatically("version-1", "risk-1", false);
    }

    private void configureQueuedProcessing(CodeRiskAssessment queued) {
        version.setLatestRiskAssessmentId(queued.getId());
        version.setRiskStatus(CodeRiskAssessmentStatus.QUEUED);
        version.setRiskLevel(CodeRiskLevel.UNKNOWN);
        version.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        when(assessmentRepository.findByStatusOrderByCreatedAtAscIdAsc(
                eq(CodeRiskAssessmentStatus.QUEUED), any(Pageable.class)
        )).thenReturn(List.of(queued));
        when(assessmentRepository.findById("risk-1")).thenReturn(Optional.of(queued));
        when(versionRepository.findAssetIdByIdAndDeletedFalse("version-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));
        when(assessmentRepository.findByIdAndVersionIdForUpdate("risk-1", "version-1"))
                .thenReturn(Optional.of(queued));
        when(validationRepository.findById("validation-1"))
                .thenReturn(Optional.of(validation));
    }

    private void configureArtifact(byte[] bytes) {
        version.setArtifactSha256(filePolicy.sha256(bytes));
        version.setSizeBytes((long) bytes.length);
        validation.setArtifactSha256(version.getArtifactSha256());
    }

    private CodeRiskAssessment queuedAssessment() {
        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("risk-1");
        assessment.setVersionId("version-1");
        assessment.setValidationRunId("validation-1");
        assessment.setArtifactSha256(version.getArtifactSha256());
        assessment.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        assessment.setScannerVersion(CodeStaticRiskScanner.SCANNER_VERSION);
        assessment.setStatus(CodeRiskAssessmentStatus.QUEUED);
        assessment.setRiskLevel(CodeRiskLevel.UNKNOWN);
        assessment.setFindingCount(0);
        assessment.setCreatedAt(Instant.now());
        return assessment;
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeVersion version() {
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setStatus("READY");
        version.setApprovalStatus("PENDING");
        version.setStoragePath("users/7/codes/asset-1/versions/version-1/artifact.zip");
        version.setArtifactSha256("a".repeat(64));
        version.setSizeBytes(1L);
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setDeleted(false);
        return version;
    }

    private static CodeValidationRun validation() {
        CodeValidationRun run = new CodeValidationRun();
        run.setId("validation-1");
        run.setVersionId("version-1");
        run.setArtifactSha256("a".repeat(64));
        run.setPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        run.setStatus("PASSED");
        run.setCreatedAt(Instant.now());
        run.setCompletedAt(Instant.now());
        return run;
    }
}
