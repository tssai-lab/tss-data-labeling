package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.training.plan.TrainingPlanDefinition;
import com.tss.platform.training.plan.TrainingPlanRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeAssetImportServiceTest {

    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeValidationRunRepository validationRepository =
            mock(CodeValidationRunRepository.class);
    private final CodeArtifactStorageService storageService =
            mock(CodeArtifactStorageService.class);
    private final MinioDeleteTaskService deleteTaskService = mock(MinioDeleteTaskService.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final CodeRiskAssessmentService riskAssessmentService =
            mock(CodeRiskAssessmentService.class);
    private final TrainingPlanRegistry trainingPlanRegistry = mock(TrainingPlanRegistry.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final CodeFilePolicy filePolicy = new CodeFilePolicy();
    private final CodeZipArchiveService zipService = new CodeZipArchiveService();
    private final AtomicReference<String> uploadedObject = new AtomicReference<>();
    private final AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();

    private CodeAssetImportService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(authContext.currentUserId()).thenReturn(7);
        when(assetRepository.saveAndFlush(any(CodeAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRepository.saveAndFlush(any(CodeValidationRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doAnswer(invocation -> {
            uploadedObject.set(invocation.getArgument(0));
            byte[] bytes = invocation.getArgument(1);
            uploadedBytes.set(Arrays.copyOf(bytes, bytes.length));
            return null;
        }).when(storageService).upload(anyString(), any(byte[].class));
        when(storageService.read(anyString())).thenAnswer(invocation -> stored(
                invocation.getArgument(0), uploadedBytes.get()
        ));
        when(trainingPlanRegistry.requireEnabled(anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new TrainingPlanDefinition(
                        "tss.training.plan/v1", "test-plan", "v1", "Test", null, true, null,
                        List.of(), new TrainingPlanDefinition.Execution("python", "train.py", List.of()),
                        null, List.of(), List.of(), null, null
                ));

        CodeArtifactAssembler assembler = new CodeArtifactAssembler(
                storageService, zipService, new CodePathPolicy(), filePolicy, trainingPlanRegistry
        );
        service = new CodeAssetImportService(
                assetRepository,
                versionRepository,
                validationRepository,
                zipService,
                assembler,
                storageService,
                deleteTaskService,
                auditService,
                riskAssessmentService,
                trainingPlanRegistry,
                authContext,
                transactionManager
        );
    }

    @Test
    void importsStrictZipAsDeterministicReadyPassedPendingVersion() {
        LinkedHashMap<String, byte[]> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("train.py", bytes("print('ok')\n"));
        reverseOrder.put("config.yaml", bytes("epochs: 2\n"));
        byte[] source = nonDeterministicZip(reverseOrder);
        byte[] expected = zipService.writeDeterministic(Map.of(
                "train.py", bytes("print('ok')\n"),
                "config.yaml", bytes("epochs: 2\n")
        ));
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", source
        );

        CodeAssetImportResult result = service.importAsset(file, command());

        assertTrue(result.assetId().matches("code-asset-[0-9a-f]{32}"));
        assertTrue(result.versionId().matches("code-version-[0-9a-f]{32}"));
        assertEquals("v1", result.version());
        assertEquals("source.zip", result.fileName());
        assertEquals("READY", result.status());
        assertEquals("PASSED", result.validationStatus());
        assertEquals(CodeApprovalStatus.PENDING, result.approvalStatus());
        assertEquals(CodeArtifactAssembler.POLICY_VERSION, result.validationPolicyVersion());
        assertEquals(filePolicy.sha256(uploadedBytes.get()), result.artifactSha256());
        assertArrayEquals(expected, uploadedBytes.get());
        assertFalse(Arrays.equals(source, uploadedBytes.get()));
        assertFalse(Arrays.stream(CodeAssetImportResult.class.getRecordComponents())
                .anyMatch(component -> "storagePath".equals(component.getName())));

        ArgumentCaptor<CodeAsset> assetCaptor = ArgumentCaptor.forClass(CodeAsset.class);
        verify(assetRepository).saveAndFlush(assetCaptor.capture());
        assertEquals(7, assetCaptor.getValue().getOwnerUserId());
        assertEquals("train.py", assetCaptor.getValue().getEntryScript());

        ArgumentCaptor<CodeVersion> versionCaptor = ArgumentCaptor.forClass(CodeVersion.class);
        verify(versionRepository).saveAndFlush(versionCaptor.capture());
        CodeVersion version = versionCaptor.getValue();
        assertEquals(result.assetId(), version.getAssetId());
        assertEquals(result.versionId(), version.getId());
        assertEquals(result.artifactSha256(), version.getArtifactSha256());
        assertEquals((long) uploadedBytes.get().length, version.getSizeBytes());
        assertTrue(version.getStoragePath().matches(
                "users/7/codes/" + result.assetId() + "/versions/"
                        + result.versionId() + "/[0-9a-f]{32}\\.zip"
        ));
        assertEquals(uploadedObject.get(), version.getStoragePath());

        ArgumentCaptor<CodeValidationRun> validationCaptor =
                ArgumentCaptor.forClass(CodeValidationRun.class);
        verify(validationRepository).saveAndFlush(validationCaptor.capture());
        assertEquals(result.versionId(), validationCaptor.getValue().getVersionId());
        assertEquals(result.artifactSha256(), validationCaptor.getValue().getArtifactSha256());
        assertEquals("PASSED", validationCaptor.getValue().getStatus());
        assertNotNull(validationCaptor.getValue().getCompletedAt());
        verify(riskAssessmentService).enqueue(
                result.versionId(), validationCaptor.getValue().getId(), 7
        );
        verify(auditService).imported(
                result.assetId(), result.versionId(), 2L, result.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION
        );
        var transactionDefinition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(transactionDefinition.capture());
        assertEquals(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionDefinition.getValue().getPropagationBehavior()
        );
        var order = inOrder(
                storageService,
                transactionManager,
                assetRepository,
                versionRepository,
                validationRepository,
                auditService
        );
        order.verify(storageService).upload(anyString(), any(byte[].class));
        order.verify(storageService).read(anyString());
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(assetRepository).saveAndFlush(any(CodeAsset.class));
        order.verify(versionRepository).saveAndFlush(any(CodeVersion.class));
        order.verify(validationRepository).saveAndFlush(any(CodeValidationRun.class));
        order.verify(auditService).imported(
                result.assetId(), result.versionId(), 2L, result.artifactSha256(),
                CodeArtifactAssembler.POLICY_VERSION
        );
        order.verify(transactionManager).commit(transactionStatus);
        verify(deleteTaskService, never()).enqueueDefaultBucketDeleteImmediately(
                anyString(), anyString(), anyString(), any()
        );
    }

    @Test
    void rejectsUnsafeArchiveBeforeUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "source.zip", "application/zip", bytes("not-a-zip")
        );

        assertThrows(CodeValidationException.class,
                () -> service.importAsset(file, command()));

        verify(storageService, never()).upload(anyString(), any(byte[].class));
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNameIsRejectedBeforeArchiveReadOrObjectUpload() {
        when(assetRepository.existsActiveNormalizedName(
                7,
                "Training asset",
                null
        )).thenReturn(true);

        AssetNameConflictException error = assertThrows(
                AssetNameConflictException.class,
                () -> service.importAsset(validFile(), command())
        );

        assertEquals("code", error.getAssetType());
        verify(assetRepository).existsActiveNormalizedName(
                7,
                "Training asset",
                null
        );
        verify(storageService, never()).upload(anyString(), any(byte[].class));
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentNameConflictAfterUploadQueuesCompensationAndStaysConflict() {
        when(assetRepository.saveAndFlush(any(CodeAsset.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key violates unique constraint "
                                + "uk_code_asset_owner_normalized_name"
                ));

        AssetNameConflictException error = assertThrows(
                AssetNameConflictException.class,
                () -> service.importAsset(validFile(), command())
        );

        assertEquals("code", error.getAssetType());
        assertExactCleanup();
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void importsWindowsZipPathAfterSafeNormalization() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "aliased.zip",
                "application/zip",
                nonDeterministicZip(Map.of(
                        "src\\train.py", bytes("print('aliased')\n")
                ))
        );
        CodeAssetImportCommand aliasedEntry = new CodeAssetImportCommand(
                "Training asset",
                "v1",
                "image_text_consistency_fusion_logreg",
                "TRAINING",
                "python:3.11",
                "src/train.py",
                "NLP",
                null
        );

        CodeAssetImportResult result = service.importAsset(file, aliasedEntry);

        assertEquals("PASSED", result.validationStatus());
        Map<String, byte[]> normalized = zipService.readEntries(
                new java.io.ByteArrayInputStream(uploadedBytes.get())
        );
        assertEquals(java.util.Set.of("src/train.py"), normalized.keySet());
        assertArrayEquals(bytes("print('aliased')\n"), normalized.get("src/train.py"));
    }

    @Test
    void changedStoredRereadIsRejectedAndQueuesExactObjectCleanup() {
        MockMultipartFile file = validFile();
        byte[] changed = zipService.writeDeterministic(Map.of(
                "train.py", bytes("print('changed')\n")
        ));
        org.mockito.Mockito.doAnswer(invocation -> stored(
                invocation.getArgument(0), changed
        )).when(storageService).read(anyString());

        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> service.importAsset(file, command())
        );

        assertEquals("STORED_ARTIFACT_CHANGED", error.getReasonCode());
        assertFalse(error.getMessage().contains("users/"));
        assertExactCleanup();
        verify(assetRepository, never()).saveAndFlush(any());
    }

    @Test
    void commitTimeFailureQueuesExactObjectAndReturnsSanitizedFailure() {
        doThrow(new IllegalStateException("jdbc://secret-db"))
                .when(transactionManager).commit(transactionStatus);

        CodeAssetImportException error = assertThrows(
                CodeAssetImportException.class,
                () -> service.importAsset(validFile(), command())
        );

        assertFalse(error.getMessage().contains("secret-db"));
        assertEquals(null, error.getCause());
        assertExactCleanup();
    }

    @Test
    void cleanupQueueFailureFallsBackToDeletingExactObject() {
        doThrow(new IllegalStateException("cleanup queue unavailable"))
                .when(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                        anyString(), anyString(), anyString(), any()
                );
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                CodeAssetImportException.class,
                () -> service.importAsset(validFile(), command())
        );

        verify(storageService).delete(uploadedObject.get());
    }

    @Test
    void legacyMappingAloneRetainsDeprecatedStoragePath() {
        String profile = "image_text_consistency_fusion_logreg";
        String requiredEntry = trainingPlanRegistry.requireEnabled(profile, null)
                .execution()
                .entrypoint();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "legacy.zip",
                "application/zip",
                zipService.writeDeterministic(Map.of(
                        requiredEntry, bytes("print('legacy')\n")
                ))
        );

        var result = service.importLegacy(
                file, "Legacy asset", "v1", profile, "remark"
        );

        assertEquals(uploadedObject.get(), result.getStoragePath());
        assertEquals("READY", result.getStatus());
        assertEquals(CodeApprovalStatus.PENDING, result.getApprovalStatus());
        assertEquals(profile, result.getTrainingProfile());
    }

    private void assertExactCleanup() {
        ArgumentCaptor<String> objectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                objectCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK
                ),
                sourceIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(7)
        );
        assertEquals(uploadedObject.get(), objectCaptor.getValue());
        String[] segments = objectCaptor.getValue().split("/");
        assertEquals(7, segments.length);
        assertEquals("users", segments[0]);
        assertEquals("7", segments[1]);
        assertEquals("codes", segments[2]);
        assertTrue(segments[3].matches("code-asset-[0-9a-f]{32}"));
        assertEquals("versions", segments[4]);
        assertEquals(sourceIdCaptor.getValue(), segments[5]);
        assertTrue(segments[5].matches("code-version-[0-9a-f]{32}"));
        assertTrue(segments[6].matches("[0-9a-f]{32}\\.zip"));
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile(
                "file",
                "source.zip",
                "application/zip",
                zipService.writeDeterministic(Map.of(
                        "train.py", bytes("print('ok')\n")
                ))
        );
    }

    private CodeAssetImportCommand command() {
        return new CodeAssetImportCommand(
                "Training asset",
                "v1",
                "image_text_consistency_fusion_logreg",
                "TRAINING",
                "python:3.11",
                "train.py",
                "NLP",
                "remark"
        );
    }

    private StoredCodeArtifact stored(String objectName, byte[] bytes) {
        return new StoredCodeArtifact(
                objectName,
                bytes,
                filePolicy.sha256(bytes),
                bytes.length
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nonDeterministicZip(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(file.getKey());
                    entry.setTime(Instant.now().toEpochMilli());
                    zip.putNextEntry(entry);
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
