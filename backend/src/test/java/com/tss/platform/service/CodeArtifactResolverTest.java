package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalDecisionSource;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.model.CodeRiskDisposition;
import com.tss.platform.model.CodeRiskLevel;
import com.tss.platform.model.TrainingCodeReviewPolicy;
import com.tss.platform.repository.CodeApprovalRecordRepository;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeRiskAssessmentRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeArtifactResolverTest {

    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeValidationRunRepository validationRepository =
            mock(CodeValidationRunRepository.class);
    private final CodeApprovalRecordRepository approvalRepository =
            mock(CodeApprovalRecordRepository.class);
    private final CodeRiskAssessmentRepository riskAssessmentRepository =
            mock(CodeRiskAssessmentRepository.class);
    private final CodeArtifactStorageService storageService =
            mock(CodeArtifactStorageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CodeArtifactResolver resolver;
    private CodeAsset asset;
    private CodeVersion version;
    private CodeValidationRun validation;
    private CodeApprovalRecord approval;

    @BeforeEach
    void setUp() {
        resolver = new CodeArtifactResolver(
                versionRepository,
                assetRepository,
                validationRepository,
                approvalRepository,
                riskAssessmentRepository,
                storageService
        );
        asset = asset();
        version = version();
        validation = validation();
        approval = approval();
        when(versionRepository.findByIdAndDeletedFalseForUpdate("version-1"))
                .thenReturn(Optional.of(version));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset));
        when(validationRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.of(validation));
        when(approvalRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.of(approval));
        when(storageService.read(version.getStoragePath())).thenReturn(stored(
                version.getStoragePath(), version.getArtifactSha256(), 3
        ));
    }

    @Test
    void nullMissingAndCrossOwnerAreNonEnumerating() {
        assertThrows(CodeAssetAccessException.class, () -> resolver.resolve("version-1", null));
        verify(versionRepository, never()).findByIdAndDeletedFalseForUpdate(any());

        when(versionRepository.findByIdAndDeletedFalseForUpdate("missing"))
                .thenReturn(Optional.empty());
        assertThrows(CodeAssetAccessException.class, () -> resolver.resolve("missing", 7));

        assertThrows(CodeAssetAccessException.class, () -> resolver.resolve("version-1", 8));
        verify(validationRepository, never())
                .findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1");
    }

    @Test
    void locksVersionBeforeReadingAssetSoResolveCompetesWithRevoke() {
        resolver.resolve("version-1", 7);

        var order = inOrder(versionRepository, assetRepository);
        order.verify(versionRepository).findByIdAndDeletedFalseForUpdate("version-1");
        order.verify(assetRepository).findByIdAndDeletedFalse("asset-1");
    }

    @Test
    void successfulResolveReadsAndVerifiesTheActualStoredArtifact() {
        resolver.resolve("version-1", 7);

        verify(storageService).read(version.getStoragePath());
    }

    @Test
    void resolverUsesWritableTransactionForPostgresPessimisticLock() throws Exception {
        Transactional transactional = CodeArtifactResolver.class
                .getMethod("resolve", String.class, Integer.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional != null);
        assertFalse(transactional.readOnly());
    }

    @Test
    void codeVersionCarriesImmutableConsumerMetadataSnapshot() throws Exception {
        for (String field : new String[]{
                "purpose", "runtime", "entryScript", "trainingType", "trainingProfile"
        }) {
            assertTrue(CodeVersion.class.getDeclaredField(field) != null, field);
        }
    }

    @Test
    void gatesLifecycleHashAndVersionValidationFields() {
        version.setStatus("DEPRECATED");
        assertReason("VERSION_NOT_READY");

        version.setStatus("READY");
        version.setArtifactSha256("A".repeat(64));
        assertReason("ARTIFACT_EVIDENCE_INVALID");

        version.setArtifactSha256("a".repeat(64));
        version.setValidationStatus("FAILED");
        assertReason("VALIDATION_NOT_CURRENT");

        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion("OLD");
        assertReason("VALIDATION_NOT_CURRENT");
    }

    @Test
    void rejectsMissingFailedOrStaleLatestValidationEvidence() {
        when(validationRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.empty());
        assertReason("VALIDATION_EVIDENCE_MISSING");

        when(validationRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.of(validation));
        validation.setStatus("FAILED");
        assertReason("VALIDATION_EVIDENCE_STALE");

        validation.setStatus("PASSED");
        validation.setArtifactSha256("b".repeat(64));
        assertReason("VALIDATION_EVIDENCE_STALE");

        validation.setArtifactSha256(version.getArtifactSha256());
        validation.setPolicyVersion("OLD");
        assertReason("VALIDATION_EVIDENCE_STALE");
    }

    @Test
    void rejectsAllNonApprovedStatesAndStaleLatestApprovalEvidence() {
        for (String status : new String[]{
                CodeApprovalStatus.PENDING,
                CodeApprovalStatus.REJECTED,
                CodeApprovalStatus.REVOKED
        }) {
            version.setApprovalStatus(status);
            assertReason("APPROVAL_REQUIRED");
        }
        version.setApprovalStatus(CodeApprovalStatus.APPROVED);
        when(approvalRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.empty());
        assertReason("APPROVAL_EVIDENCE_MISSING");

        when(approvalRepository.findTopByVersionIdOrderByCreatedAtDescIdDesc("version-1"))
                .thenReturn(Optional.of(approval));
        approval.setDecision(CodeApprovalStatus.REVOKED);
        assertReason("APPROVAL_EVIDENCE_STALE");

        approval.setDecision(CodeApprovalStatus.APPROVED);
        approval.setValidationRunId("stale-run");
        assertReason("APPROVAL_EVIDENCE_STALE");

        approval.setValidationRunId(validation.getId());
        approval.setArtifactSha256("b".repeat(64));
        assertReason("APPROVAL_EVIDENCE_STALE");
    }

    @Test
    void requiresExactInternalStoragePrefixAndSafeLeaf() {
        for (String path : new String[]{
                null,
                "",
                "users/8/codes/asset-1/versions/version-1/a.zip",
                "users/7/codes/asset-other/versions/version-1/a.zip",
                "users/7/codes/asset-1/versions/version-other/a.zip",
                "users/7/codes/asset-1/versions/version-1/",
                "users/7/codes/asset-1/versions/version-1/../secret.zip",
                "users/7/codes/asset-1/versions/version-1/a.zip?token=secret",
                "users/7/codes/asset-1/versions/version-1/a\\b.zip"
        }) {
            version.setStoragePath(path);
            assertReason("STORAGE_REFERENCE_INVALID");
        }
    }

    @Test
    void newApprovalEvidenceMustBindCurrentCompletedRiskAssessment() {
        CodeRiskAssessment risk = new CodeRiskAssessment();
        risk.setId("risk-1");
        risk.setVersionId("version-1");
        risk.setValidationRunId("validation-1");
        risk.setArtifactSha256(version.getArtifactSha256());
        risk.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        risk.setScannerVersion(CodeStaticRiskScanner.SCANNER_VERSION);
        risk.setStatus("COMPLETED");
        risk.setRiskLevel("LOW");
        risk.setDisposition("AUTO_APPROVE");
        risk.setFindingCount(0);
        version.setLatestRiskAssessmentId("risk-1");
        version.setRiskStatus("COMPLETED");
        version.setRiskLevel("LOW");
        version.setReviewDisposition("AUTO_APPROVE");
        version.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        approval.setRiskAssessmentId("risk-1");
        approval.setApprovalPolicyVersion(CodeApprovalService.APPROVAL_POLICY_VERSION);
        when(riskAssessmentRepository.findById("risk-1")).thenReturn(Optional.of(risk));

        ResolvedCodeArtifact resolved = resolver.resolve("version-1", 7);

        assertEquals("risk-1", resolved.riskAssessmentId());
        assertEquals("LOW", resolved.riskLevel());

        // A later SHADOW assessment may update the query summary, but it must
        // not invalidate the immutable evidence bound to an existing approval.
        version.setLatestRiskAssessmentId("risk-shadow");
        version.setRiskStatus("RUNNING");
        version.setRiskLevel("UNKNOWN");
        version.setReviewDisposition(null);
        assertEquals("risk-1", resolver.resolve("version-1", 7).riskAssessmentId());

        risk.setRiskPolicyVersion("code-risk-policy-v1");
        assertReason("RISK_EVIDENCE_STALE");

        risk.setRiskPolicyVersion(CodeStaticRiskScanner.RISK_POLICY_VERSION);
        risk.setDisposition("BLOCK");
        assertReason("RISK_EVIDENCE_STALE");
    }

    @Test
    void directPassApprovalRequiresExplicitSystemConfigurationEvidence() {
        CodeRiskAssessment directPass = new CodeRiskAssessment();
        directPass.setId("risk-direct");
        directPass.setVersionId("version-1");
        directPass.setValidationRunId("validation-1");
        directPass.setArtifactSha256(version.getArtifactSha256());
        directPass.setRiskPolicyVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_RISK_POLICY_VERSION
        );
        directPass.setScannerVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_SCANNER_VERSION
        );
        directPass.setStatus("COMPLETED");
        directPass.setRiskLevel(CodeRiskLevel.UNKNOWN);
        directPass.setDisposition(CodeRiskDisposition.DIRECT_PASS);
        directPass.setFindingCount(0);
        approval.setDecisionSource(CodeApprovalDecisionSource.SYSTEM_CONFIG);
        approval.setRiskAssessmentId("risk-direct");
        approval.setApprovalPolicyVersion(
                TrainingCodeReviewPolicy.DIRECT_PASS_APPROVAL_POLICY_VERSION
        );
        when(riskAssessmentRepository.findById("risk-direct"))
                .thenReturn(Optional.of(directPass));

        ResolvedCodeArtifact resolved = resolver.resolve("version-1", 7);

        assertEquals(CodeApprovalDecisionSource.SYSTEM_CONFIG,
                resolved.approvalSource());
        assertEquals("risk-direct", resolved.riskAssessmentId());
        assertEquals(CodeRiskLevel.UNKNOWN, resolved.riskLevel());

        directPass.setDisposition(CodeRiskDisposition.MANUAL_REVIEW);
        assertReason("DIRECT_PASS_EVIDENCE_STALE");
    }

    @Test
    void rejectsMissingOrNegativeDatabaseSizeEvidenceWithoutReadingStorage() {
        version.setSizeBytes(null);
        assertReason("ARTIFACT_EVIDENCE_INVALID");

        version.setSizeBytes(-1L);
        assertReason("ARTIFACT_EVIDENCE_INVALID");

        verify(storageService, never()).read(any());
    }

    @Test
    void storageReadFailureIsFailClosed() {
        when(storageService.read(version.getStoragePath()))
                .thenThrow(new CodeArtifactStorageException());

        assertReason("STORAGE_READ_FAILED");
    }

    @Test
    void rejectsStoredObjectNameMismatch() {
        when(storageService.read(version.getStoragePath())).thenReturn(stored(
                "users/7/codes/asset-1/versions/version-1/other.zip",
                version.getArtifactSha256(),
                3
        ));

        assertReason("STORAGE_REFERENCE_INVALID");
    }

    @Test
    void rejectsActualStoredShaMismatch() {
        when(storageService.read(version.getStoragePath())).thenReturn(stored(
                version.getStoragePath(), "b".repeat(64), 3
        ));

        assertReason("ARTIFACT_SHA256_MISMATCH");
    }

    @Test
    void rejectsActualStoredSizeMismatch() {
        when(storageService.read(version.getStoragePath())).thenReturn(stored(
                version.getStoragePath(), version.getArtifactSha256(), 4
        ));

        assertReason("ARTIFACT_SIZE_MISMATCH");
    }

    @Test
    void returnsStableInternalResultAndAllowlistedManifestWithoutLeaks() throws Exception {
        ResolvedCodeArtifact resolved = resolver.resolve("version-1", 7);

        assertEquals("asset-1", resolved.assetId());
        assertEquals("version-1", resolved.versionId());
        assertEquals("TRAINING", resolved.purpose());
        assertEquals("python:3.11", resolved.runtime());
        assertEquals("train.py", resolved.entryScript());
        assertEquals("a".repeat(64), resolved.artifactSha256());
        assertEquals("validation-1", resolved.validationRunId());
        assertEquals("approval-1", resolved.approvalRecordId());
        assertTrue(resolved.storagePath().startsWith(
                "users/7/codes/asset-1/versions/version-1/"
        ));

        JsonNode manifest = objectMapper.valueToTree(resolved.toConsumerManifest());
        assertFalse(manifest.has("storagePath"));
        assertFalse(manifest.has("ownerUserId"));
        Iterator<Map.Entry<String, JsonNode>> fields = manifest.fields();
        while (fields.hasNext()) {
            String key = fields.next().getKey().toLowerCase();
            assertFalse(key.contains("storage"));
            assertFalse(key.contains("path"));
            assertFalse(key.contains("object"));
            assertFalse(key.contains("bucket"));
            assertFalse(key.contains("url"));
            assertFalse(key.contains("owneruserid"));
        }
        String json = objectMapper.writeValueAsString(manifest).toLowerCase();
        assertFalse(json.contains("users/7/"));
        assertFalse(json.contains("token="));
        assertFalse(json.contains(".zip"));
    }

    @Test
    void assetMetadataChangesDoNotAlterPublishedVersionManifest() {
        asset.setPurpose("MUTATED_PURPOSE");
        asset.setRuntime("mutated-runtime");
        asset.setEntryScript("mutated.py");
        asset.setTrainingType("MUTATED_TYPE");
        asset.setTrainingProfile("mutated-profile");

        ResolvedCodeArtifact resolved = resolver.resolve("version-1", 7);

        assertEquals("TRAINING", resolved.purpose());
        assertEquals("python:3.11", resolved.runtime());
        assertEquals("train.py", resolved.entryScript());
        assertEquals("NLP", resolved.trainingType());
        assertEquals("profile-1", resolved.trainingProfile());
    }

    private void assertReason(String expected) {
        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> resolver.resolve("version-1", 7)
        );
        assertEquals(expected, error.getReasonCode());
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setOwnerUserId(7);
        asset.setPurpose("TRAINING");
        asset.setRuntime("python:3.11");
        asset.setEntryScript("train.py");
        asset.setTrainingType("NLP");
        asset.setTrainingProfile("profile-1");
        asset.setDeleted(false);
        return asset;
    }

    private static CodeVersion version() {
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setStatus("READY");
        version.setArtifactSha256("a".repeat(64));
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setApprovalStatus(CodeApprovalStatus.APPROVED);
        version.setStoragePath("users/7/codes/asset-1/versions/version-1/artifact.zip");
        version.setSizeBytes(3L);
        version.setPurpose("TRAINING");
        version.setRuntime("python:3.11");
        version.setEntryScript("train.py");
        version.setTrainingType("NLP");
        version.setTrainingProfile("profile-1");
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
        return run;
    }

    private static CodeApprovalRecord approval() {
        CodeApprovalRecord record = new CodeApprovalRecord();
        record.setId("approval-1");
        record.setVersionId("version-1");
        record.setArtifactSha256("a".repeat(64));
        record.setValidationRunId("validation-1");
        record.setPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        record.setDecision(CodeApprovalStatus.APPROVED);
        return record;
    }

    private static StoredCodeArtifact stored(String objectName, String sha256, int size) {
        return new StoredCodeArtifact(objectName, new byte[size], sha256, size);
    }
}
