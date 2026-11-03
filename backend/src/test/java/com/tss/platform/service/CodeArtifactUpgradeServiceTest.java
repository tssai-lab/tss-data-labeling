package com.tss.platform.service;

import com.tss.platform.dto.v2.V2CodeArtifactUpgradeResult;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CodeArtifactUpgradeServiceTest {

    private static final String VERSION_ID = "version-1";
    private static final String ASSET_ID = "asset-1";
    private static final int OWNER_ID = 7;
    private static final String LEGACY_PATH =
            "users/7/codes/asset-1/v1/code.zip";

    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeArtifactStorageService storageService =
            mock(CodeArtifactStorageService.class);
    private final MinioDeleteTaskService deleteTaskService =
            mock(MinioDeleteTaskService.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final CodeApprovalService approvalService = mock(CodeApprovalService.class);
    private final CodeValidationService validationService = mock(CodeValidationService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final CodeFilePolicy filePolicy = new CodeFilePolicy();

    private CodeArtifactUpgradeService service;
    private CodeAsset asset;
    private CodeVersion version;
    private byte[] bytes;
    private String sha256;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        service = new CodeArtifactUpgradeService(
                versionRepository,
                assetRepository,
                storageService,
                deleteTaskService,
                auditService,
                approvalService,
                validationService,
                filePolicy,
                transactionManager
        );
        asset = asset();
        version = legacyVersion();
        bytes = "legacy-zip-bytes".getBytes(StandardCharsets.UTF_8);
        sha256 = filePolicy.sha256(bytes);

        when(versionRepository.findByIdAndDeletedFalse(VERSION_ID))
                .thenReturn(Optional.of(version));
        when(assetRepository.findByIdAndDeletedFalse(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(assetRepository.findByIdAndDeletedFalseForUpdate(ASSET_ID))
                .thenReturn(Optional.of(asset));
        when(versionRepository.findByIdAndDeletedFalseForUpdate(VERSION_ID))
                .thenReturn(Optional.of(version));
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storageService.read(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            return stored(objectName, bytes);
        });
        when(validationService.validateVersion(VERSION_ID)).thenReturn(passed(sha256));
    }

    @Test
    void administratorGateRunsBeforeAnyResourceLookupAndPreventsEnumeration() {
        doThrow(new CodeApprovalForbiddenException())
                .when(approvalService).requireAdministratorAuthority();

        assertThrows(CodeApprovalForbiddenException.class,
                () -> service.upgrade(VERSION_ID));

        verify(approvalService).requireAdministratorAuthority();
        verifyNoInteractions(versionRepository, assetRepository, storageService,
                deleteTaskService, auditService, validationService);
    }

    @Test
    void migratesExactLegacyObjectThenFinalizesPendingVersionAndValidates() {
        V2CodeArtifactUpgradeResult result = service.upgrade(VERSION_ID);

        ArgumentCaptor<String> objectName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).upload(objectName.capture(), uploaded.capture());
        String canonical = objectName.getValue();
        assertTrue(canonical.matches(
                "users/7/codes/asset-1/versions/version-1/[0-9a-f]{32}\\.zip"));
        assertTrue(Arrays.equals(bytes, uploaded.getValue()));
        verify(storageService).read(canonical);

        assertEquals(canonical, version.getStoragePath());
        assertEquals(sha256, version.getArtifactSha256());
        assertEquals((long) bytes.length, version.getSizeBytes());
        assertEquals("NOT_RUN", version.getValidationStatus());
        assertNull(version.getValidationPolicyVersion());
        assertEquals(CodeApprovalStatus.PENDING, version.getApprovalStatus());
        verify(auditService).artifactUpgraded(ASSET_ID, VERSION_ID, sha256);
        verify(deleteTaskService).enqueueDefaultBucketDelete(
                LEGACY_PATH,
                MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_UPGRADE,
                VERSION_ID,
                OWNER_ID
        );
        verify(validationService).validateVersion(VERSION_ID);
        verify(approvalService).requireAdministratorAuthority();
        verifyNoMoreInteractions(approvalService);

        assertEquals(VERSION_ID, result.versionId());
        assertEquals(sha256, result.artifactSha256());
        assertEquals((long) bytes.length, result.sizeBytes());
        assertEquals(CodeApprovalStatus.PENDING, result.approvalStatus());
        assertTrue(result.upgraded());
        assertEquals("PASSED", result.validation().status());
    }

    @Test
    void canonicalRetryReadsAndValidatesWithoutCopyAuditOrOldDelete() {
        String canonical = configureCanonicalVersion();

        V2CodeArtifactUpgradeResult result = service.upgrade(VERSION_ID);

        assertFalse(result.upgraded());
        verify(storageService).read(canonical);
        verify(storageService, never()).upload(anyString(), any());
        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(), anyString(), anyString(), any());
        verify(auditService, never()).artifactUpgraded(anyString(), anyString(), anyString());
        verify(validationService).validateVersion(VERSION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "users/7/codes/asset-1/v2/code.zip",
            "users/7/codes/asset-1/v1/other.zip"
    })
    void rejectsSafeButNonHistoricalLegacySegmentsWithoutStorageSideEffects(
            String wrongLegacyPath
    ) {
        version.setStoragePath(wrongLegacyPath);

        assertThrows(CodeValidationException.class,
                () -> service.upgrade(VERSION_ID));

        verifyNoInteractions(storageService, deleteTaskService, auditService, validationService);
    }

    @Test
    void acceptsHistoricalSanitizeSegmentPathDerivedFromFrozenDatabaseValues() {
        version.setVersion(" V/One? ");
        version.setFileName(" Model:*?.ZIP ");
        version.setStoragePath(
                "users/7/codes/asset-1/v_one_/model___.zip"
        );

        V2CodeArtifactUpgradeResult result = service.upgrade(VERSION_ID);

        assertTrue(result.upgraded());
        verify(storageService).read(version.getStoragePath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"METADATA", "APPROVAL"})
    void canonicalRetryLocksAndRejectsChangedSnapshotBeforeValidation(String change) {
        configureCanonicalVersion();
        CodeVersion locked = CodeValidationService.copyVersion(version);
        if ("METADATA".equals(change)) {
            locked.setRuntime("changed-runtime");
        } else {
            locked.setApprovalStatus(CodeApprovalStatus.REJECTED);
        }
        when(versionRepository.findByIdAndDeletedFalseForUpdate(VERSION_ID))
                .thenReturn(Optional.of(locked));

        assertThrows(CodeWorkspaceConflictException.class,
                () -> service.upgrade(VERSION_ID));

        InOrder locks = inOrder(assetRepository, versionRepository);
        locks.verify(assetRepository).findByIdAndDeletedFalse(ASSET_ID);
        locks.verify(assetRepository).findByIdAndDeletedFalseForUpdate(ASSET_ID);
        locks.verify(versionRepository).findByIdAndDeletedFalseForUpdate(VERSION_ID);
        verify(storageService, never()).upload(anyString(), any());
        verify(auditService, never()).artifactUpgraded(anyString(), anyString(), anyString());
        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(), anyString(), anyString(), any());
        verify(validationService, never()).validateVersion(anyString());
    }

    @Test
    void canonicalRetryRejectsApprovalChangeAfterPassingValidation() {
        configureCanonicalVersion();
        when(validationService.validateVersion(VERSION_ID)).thenAnswer(invocation -> {
            version.setApprovalStatus(CodeApprovalStatus.REJECTED);
            return passed(sha256);
        });

        assertThrows(CodeWorkspaceConflictException.class,
                () -> service.upgrade(VERSION_ID));

        verify(storageService, never()).upload(anyString(), any());
        verify(auditService, never()).artifactUpgraded(anyString(), anyString(), anyString());
        verify(deleteTaskService, never()).enqueueDefaultBucketDelete(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void legacyUpgradeRejectsFrozenMetadataChangeAfterPassingValidation() {
        when(validationService.validateVersion(VERSION_ID)).thenAnswer(invocation -> {
            version.setRuntime("changed-runtime");
            return passed(sha256);
        });

        assertThrows(CodeWorkspaceConflictException.class,
                () -> service.upgrade(VERSION_ID));
    }

    @Test
    void rejectsTerminalApprovalInvalidLegacyPathAndIdentityMismatchBeforeStorage() {
        version.setApprovalStatus(CodeApprovalStatus.REJECTED);
        assertThrows(CodeWorkspaceConflictException.class,
                () -> service.upgrade(VERSION_ID));
        verifyNoInteractions(storageService);

        version.setApprovalStatus(CodeApprovalStatus.PENDING);
        version.setStoragePath("users/7/codes/asset-1/v1/nested/code.zip");
        assertThrows(CodeValidationException.class,
                () -> service.upgrade(VERSION_ID));
        verifyNoInteractions(storageService);

        version.setStoragePath(LEGACY_PATH);
        asset.setOwnerUserId(8);
        assertThrows(CodeAssetAccessException.class,
                () -> service.upgrade(VERSION_ID));
        verifyNoInteractions(storageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBJECT_NAME", "BYTES", "SHA", "SIZE"})
    void rejectsEveryDestinationEvidenceMismatchAndCompensates(String mismatch) {
        when(storageService.read(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            if (LEGACY_PATH.equals(objectName)) {
                return stored(objectName, bytes);
            }
            return switch (mismatch) {
                case "OBJECT_NAME" -> stored(objectName + "-wrong", bytes);
                case "BYTES" -> stored(objectName,
                        "changed-zip-byte".getBytes(StandardCharsets.UTF_8));
                case "SHA" -> new StoredCodeArtifact(
                        objectName, bytes, "b".repeat(64), bytes.length);
                case "SIZE" -> stored(objectName,
                        "legacy-zip-bytes-longer".getBytes(StandardCharsets.UTF_8));
                default -> throw new AssertionError(mismatch);
            };
        });

        assertThrows(CodeValidationException.class,
                () -> service.upgrade(VERSION_ID));

        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        verify(storageService).upload(destination.capture(), any());
        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                destination.getValue(),
                MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK,
                VERSION_ID,
                OWNER_ID
        );
        verify(versionRepository, never()).saveAndFlush(any());
        verify(validationService, never()).validateVersion(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBJECT_NAME", "SHA", "EMPTY"})
    void rejectsInvalidSourceEvidenceBeforeUploadOrCleanup(String mismatch) {
        when(storageService.read(LEGACY_PATH)).thenReturn(switch (mismatch) {
            case "OBJECT_NAME" -> stored(LEGACY_PATH + "-wrong", bytes);
            case "SHA" -> new StoredCodeArtifact(
                    LEGACY_PATH, bytes, "b".repeat(64), bytes.length);
            case "EMPTY" -> stored(LEGACY_PATH, new byte[0]);
            default -> throw new AssertionError(mismatch);
        });

        assertThrows(CodeValidationException.class,
                () -> service.upgrade(VERSION_ID));

        verify(storageService, never()).upload(anyString(), any());
        verifyNoInteractions(deleteTaskService, auditService, validationService);
    }

    @Test
    void finalizeCasFailureCompensatesNewObjectAndLocksAssetBeforeVersion() {
        CodeVersion changed = legacyVersion();
        changed.setValidationStatus("FAILED");
        when(versionRepository.findByIdAndDeletedFalseForUpdate(VERSION_ID))
                .thenReturn(Optional.of(changed));

        assertThrows(CodeWorkspaceConflictException.class,
                () -> service.upgrade(VERSION_ID));

        InOrder locks = inOrder(assetRepository, versionRepository);
        locks.verify(assetRepository).findByIdAndDeletedFalse(ASSET_ID);
        locks.verify(assetRepository).findByIdAndDeletedFalseForUpdate(ASSET_ID);
        locks.verify(versionRepository).findByIdAndDeletedFalseForUpdate(VERSION_ID);
        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                anyString(),
                eq(MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK),
                eq(VERSION_ID),
                eq(OWNER_ID)
        );
        verify(validationService, never()).validateVersion(anyString());
    }

    @Test
    void auditFailureCompensatesNewObject() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).artifactUpgraded(ASSET_ID, VERSION_ID, sha256);

        assertThrows(IllegalStateException.class,
                () -> service.upgrade(VERSION_ID));

        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                anyString(),
                eq(MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK),
                eq(VERSION_ID),
                eq(OWNER_ID)
        );
        verify(validationService, never()).validateVersion(anyString());
    }

    @Test
    void oldObjectDeleteRegistrationFailureCompensatesNewObject() {
        doThrow(new IllegalStateException("delete task unavailable"))
                .when(deleteTaskService).enqueueDefaultBucketDelete(
                        LEGACY_PATH,
                        MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_UPGRADE,
                        VERSION_ID,
                        OWNER_ID
                );

        assertThrows(IllegalStateException.class,
                () -> service.upgrade(VERSION_ID));

        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                anyString(),
                eq(MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK),
                eq(VERSION_ID),
                eq(OWNER_ID)
        );
        verify(validationService, never()).validateVersion(anyString());
    }

    @Test
    void compensationFallsBackToDirectExactDeleteWhenQueueingFails() {
        when(storageService.read(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            return LEGACY_PATH.equals(objectName)
                    ? stored(objectName, bytes)
                    : stored(objectName + "-wrong", bytes);
        });
        doThrow(new IllegalStateException("queue unavailable"))
                .when(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                        anyString(), anyString(), anyString(), any());

        assertThrows(CodeValidationException.class,
                () -> service.upgrade(VERSION_ID));

        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        verify(storageService).upload(destination.capture(), any());
        verify(storageService).delete(destination.getValue());
    }

    @Test
    void failedValidationLeavesPendingVersionAndNeverApproves() {
        when(validationService.validateVersion(VERSION_ID)).thenReturn(
                new CodeValidationResult(
                        CodeArtifactAssembler.POLICY_VERSION,
                        sha256,
                        "FAILED",
                        "ENTRY_SCRIPT_MISSING",
                        "Code artifact validation failed",
                        1
                )
        );

        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> service.upgrade(VERSION_ID)
        );

        assertEquals("ENTRY_SCRIPT_MISSING", error.getReasonCode());
        assertEquals(CodeApprovalStatus.PENDING, version.getApprovalStatus());
        verify(approvalService).requireAdministratorAuthority();
        verifyNoMoreInteractions(approvalService);
    }

    @Test
    void storageValidationFailureMapsToServiceUnavailableAndNeverApproves() {
        when(validationService.validateVersion(VERSION_ID)).thenReturn(
                new CodeValidationResult(
                        CodeArtifactAssembler.POLICY_VERSION,
                        sha256,
                        "FAILED",
                        "STORAGE_READ_FAILED",
                        "Code artifact storage could not be read",
                        0
                )
        );

        assertThrows(CodeArtifactStorageException.class,
                () -> service.upgrade(VERSION_ID));

        assertEquals(CodeApprovalStatus.PENDING, version.getApprovalStatus());
        verify(approvalService).requireAdministratorAuthority();
        verifyNoMoreInteractions(approvalService);
    }

    private StoredCodeArtifact stored(String objectName, byte[] content) {
        return new StoredCodeArtifact(
                objectName,
                content,
                filePolicy.sha256(content),
                content.length
        );
    }

    private String configureCanonicalVersion() {
        String canonical =
                "users/7/codes/asset-1/versions/version-1/existing.zip";
        version.setStoragePath(canonical);
        version.setArtifactSha256(sha256);
        version.setSizeBytes((long) bytes.length);
        return canonical;
    }

    private static CodeValidationResult passed(String artifactSha256) {
        return new CodeValidationResult(
                CodeArtifactAssembler.POLICY_VERSION,
                artifactSha256,
                "PASSED",
                null,
                "Code artifact validation passed",
                1
        );
    }

    private static CodeAsset asset() {
        CodeAsset value = new CodeAsset();
        value.setId(ASSET_ID);
        value.setOwnerUserId(OWNER_ID);
        value.setRowVersion(0L);
        value.setDeleted(false);
        return value;
    }

    private static CodeVersion legacyVersion() {
        CodeVersion value = new CodeVersion();
        value.setId(VERSION_ID);
        value.setAssetId(ASSET_ID);
        value.setVersion("v1");
        value.setFileName("code.zip");
        value.setStoragePath(LEGACY_PATH);
        value.setSizeBytes(100L);
        value.setStatus("READY");
        value.setApprovalStatus(CodeApprovalStatus.PENDING);
        value.setArtifactSha256(null);
        value.setValidationStatus("NOT_RUN");
        value.setValidationPolicyVersion(null);
        value.setOwnerUserId(OWNER_ID);
        value.setCreatedAt(Instant.EPOCH);
        value.setUpdatedAt(Instant.EPOCH);
        value.setDeleted(false);
        return value;
    }
}
