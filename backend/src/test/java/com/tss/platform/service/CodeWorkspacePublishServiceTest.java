package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.model.CodeApprovalStatus;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeValidationRunRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeWorkspacePublishServiceTest {

    private final CodeWorkspaceRepository workspaceRepository =
            mock(CodeWorkspaceRepository.class);
    private final CodeWorkspaceFileDeltaRepository deltaRepository =
            mock(CodeWorkspaceFileDeltaRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeValidationRunRepository validationRunRepository =
            mock(CodeValidationRunRepository.class);
    private final CodeArtifactStorageService storageService =
            mock(CodeArtifactStorageService.class);
    private final MinioDeleteTaskService deleteTaskService = mock(MinioDeleteTaskService.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final CodeRiskAssessmentService riskAssessmentService =
            mock(CodeRiskAssessmentService.class);
    private final AuthContext authContext = mock(AuthContext.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private final CodeFilePolicy filePolicy = new CodeFilePolicy();
    private final CodeZipArchiveService zipService = new CodeZipArchiveService();
    private final CodeArtifactAssembler assembler = new CodeArtifactAssembler(
            storageService, zipService, new CodePathPolicy(), filePolicy,
            mock(com.tss.platform.training.plan.TrainingPlanRegistry.class)
    );
    private final AtomicReference<String> uploadedObject = new AtomicReference<>();
    private final AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();

    private CodeWorkspacePublishService service;
    private CodeAsset asset;
    private CodeWorkspace workspace;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(7);
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.saveAndFlush(any(CodeValidationRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceRepository.saveAndFlush(any(CodeWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            uploadedObject.set(invocation.getArgument(0));
            byte[] bytes = invocation.getArgument(1);
            uploadedBytes.set(Arrays.copyOf(bytes, bytes.length));
            return null;
        }).when(storageService).upload(anyString(), any(byte[].class));
        when(storageService.read(anyString())).thenAnswer(invocation -> {
            String objectName = invocation.getArgument(0);
            byte[] bytes = uploadedBytes.get();
            if (bytes == null) {
                throw new CodeArtifactStorageException();
            }
            return stored(objectName, bytes);
        });

        service = new CodeWorkspacePublishService(
                workspaceRepository,
                deltaRepository,
                assetRepository,
                versionRepository,
                validationRunRepository,
                assembler,
                storageService,
                deleteTaskService,
                auditService,
                riskAssessmentService,
                authContext,
                transactionManager
        );
        asset = asset();
        workspace = workspace(3L, null);
        stubSnapshot(asset, workspace);
    }

    @Test
    void publishesNoBaseAsDeterministicImmutableVersionAndClosesWorkspaceOnce() {
        CodeWorkspaceFileDelta train = upsert("train.py", "print('ok')\n");
        CodeWorkspaceFileDelta config = upsert("config.yaml", "epochs: 2\n");
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(config, train));

        CodeVersion published = service.publish("workspace-1", 3L, " v1.0 ");

        assertEquals("v1.0", published.getVersion());
        assertTrue(published.getId().matches("code-version-[0-9a-f]{32}"));
        String expectedPrefix = "users/7/codes/asset-1/versions/"
                + published.getId() + "/";
        assertTrue(published.getStoragePath().matches(
                java.util.regex.Pattern.quote(expectedPrefix) + "[0-9a-f]{32}\\.zip"
        ));
        assertEquals(uploadedObject.get(), published.getStoragePath());
        assertEquals(filePolicy.sha256(uploadedBytes.get()), published.getArtifactSha256());
        assertEquals((long) uploadedBytes.get().length, published.getSizeBytes());
        assertEquals("READY", published.getStatus());
        assertEquals("PASSED", published.getValidationStatus());
        assertEquals(CodeApprovalStatus.PENDING, published.getApprovalStatus());
        assertEquals(CodeArtifactAssembler.POLICY_VERSION,
                published.getValidationPolicyVersion());
        assertEquals("TRAINING", published.getPurpose());
        assertEquals("python:3.11", published.getRuntime());
        assertEquals("train.py", published.getEntryScript());
        assertEquals("NLP", published.getTrainingType());
        assertEquals("profile-1", published.getTrainingProfile());
        assertEquals(7, published.getOwnerUserId());

        byte[] expected = zipService.writeDeterministic(Map.of(
                "config.yaml", bytes("epochs: 2\n"),
                "train.py", bytes("print('ok')\n")
        ));
        assertArrayEquals(expected, uploadedBytes.get());
        assertEquals(Map.of(
                        "config.yaml", "epochs: 2\n",
                        "train.py", "print('ok')\n"
                ), textEntries(uploadedBytes.get()));

        assertEquals(CodeWorkspace.STATUS_PUBLISHED, workspace.getStatus());
        assertEquals(4L, workspace.getRevision());
        assertEquals(published.getId(), workspace.getClosedVersionId());
        assertNotNull(workspace.getClosedAt());
        verify(workspaceRepository).saveAndFlush(workspace);
        verify(transactionManager, times(2)).commit(transactionStatus);
        ArgumentCaptor<TransactionDefinition> transactionCaptor =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(transactionCaptor.capture());
        assertTrue(transactionCaptor.getAllValues().stream().allMatch(definition ->
                definition.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(deleteTaskService, never()).enqueueDefaultBucketDeleteImmediately(
                anyString(), anyString(), anyString(), any()
        );

        ArgumentCaptor<CodeValidationRun> runCaptor =
                ArgumentCaptor.forClass(CodeValidationRun.class);
        verify(validationRunRepository).saveAndFlush(runCaptor.capture());
        assertEquals(published.getId(), runCaptor.getValue().getVersionId());
        assertEquals(published.getArtifactSha256(), runCaptor.getValue().getArtifactSha256());
        assertEquals("PASSED", runCaptor.getValue().getStatus());
        verify(riskAssessmentService).enqueue(
                published.getId(), runCaptor.getValue().getId(), 7
        );
        verify(auditService).published(
                "asset-1", published.getId(), "workspace-1", 4L, 2L,
                published.getArtifactSha256(), CodeArtifactAssembler.POLICY_VERSION
        );

        var order = inOrder(workspaceRepository, assetRepository);
        order.verify(workspaceRepository).findAssetIdByIdAndDeletedFalse("workspace-1");
        order.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        order.verify(workspaceRepository).findByIdAndDeletedFalseForUpdate("workspace-1");
    }

    @Test
    void administratorPublishKeepsOriginalOwnerNamespaceAndRecordsAdministratorRequester() {
        when(authContext.isAdmin()).thenReturn(true);
        when(authContext.currentUserId()).thenReturn(99);
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(upsert("train.py", "print('admin update')\n")));

        CodeVersion published = service.publish("workspace-1", 3L, "admin-v1");

        assertEquals(7, published.getOwnerUserId());
        assertTrue(published.getStoragePath().startsWith(
                "users/7/codes/asset-1/versions/" + published.getId() + "/"
        ));
        org.mockito.ArgumentCaptor<CodeValidationRun> runCaptor =
                org.mockito.ArgumentCaptor.forClass(CodeValidationRun.class);
        verify(validationRunRepository).saveAndFlush(runCaptor.capture());
        assertEquals(99, runCaptor.getValue().getRequestedByUserId());
        verify(riskAssessmentService).enqueue(
                published.getId(), runCaptor.getValue().getId(), 7
        );
    }

    @Test
    void snapshotDefensivelyCopiesDeltaBytesBeforeStorageIo() {
        CodeWorkspaceFileDelta train = upsert("train.py", "print('snapshot')\n");
        byte[] original = Arrays.copyOf(train.getContentBytes(), train.getContentBytes().length);
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(train));
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 1) {
                train.getContentBytes()[0] = 'X';
            }
            return null;
        }).when(transactionManager).commit(transactionStatus);

        CodeVersion published = service.publish("workspace-1", 3L, "snapshot-v1");

        assertEquals("print('snapshot')\n", textEntries(uploadedBytes.get()).get("train.py"));
        assertEquals(filePolicy.sha256(original),
                filePolicy.sha256(textEntriesBytes(uploadedBytes.get()).get("train.py")));
        assertEquals(CodeWorkspace.STATUS_PUBLISHED, workspace.getStatus());
        assertEquals(published.getId(), workspace.getClosedVersionId());
    }

    @Test
    void changedStoredRereadIsRejectedAndQueuesExactObjectCleanup() {
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(upsert("train.py", "print('expected')\n")));
        byte[] changed = zipService.writeDeterministic(Map.of(
                "train.py", bytes("print('changed')\n")
        ));
        doAnswer(invocation -> stored(invocation.getArgument(0), changed))
                .when(storageService).read(anyString());

        CodeValidationException error = assertThrows(
                CodeValidationException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );

        assertEquals("STORED_ARTIFACT_CHANGED", error.getReasonCode());
        assertFalse(error.getMessage().contains("users/"));
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
        assertEquals(CodeWorkspace.STATUS_OPEN, workspace.getStatus());
        assertEquals(3L, workspace.getRevision());
    }

    @Test
    void ownerRevisionAndInvalidArchiveFailuresNeverUpload() {
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(upsert("notes.txt", "no entry\n")));

        when(authContext.canAccessOwner(7)).thenReturn(false);
        assertThrows(CodeAssetAccessException.class,
                () -> service.publish("workspace-1", 3L, "v1"));

        when(authContext.canAccessOwner(7)).thenReturn(true);
        CodeWorkspaceConflictException stale = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 2L, "v1")
        );
        assertEquals("WORKSPACE_REVISION_CONFLICT", stale.getReasonCode());

        CodeValidationException missingEntry = assertThrows(
                CodeValidationException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );
        assertEquals("ENTRY_SCRIPT_MISSING", missingEntry.getReasonCode());
        verify(storageService, never()).upload(anyString(), any(byte[].class));
        verify(deleteTaskService, never()).enqueueDefaultBucketDeleteImmediately(
                anyString(), anyString(), anyString(), any()
        );
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void baseLifecycleChangeAfterSnapshotLosesPublishRaceAndCleansUpload() {
        CodeVersion base = baseVersion();
        workspace.setBaseVersionId(base.getId());
        byte[] baseBytes = zipService.writeDeterministic(Map.of(
                "train.py", bytes("print('base')\n")
        ));
        base.setArtifactSha256(filePolicy.sha256(baseBytes));
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("base-v1", "asset-1"))
                .thenReturn(Optional.of(base));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("base-v1"))
                .thenReturn(Optional.of(base));
        when(authContext.canAccessObjectName(base.getStoragePath(), 7)).thenReturn(true);
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            String object = invocation.getArgument(0);
            if (base.getStoragePath().equals(object)) {
                return stored(object, baseBytes);
            }
            return stored(object, uploadedBytes.get());
        }).when(storageService).read(anyString());
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 1) {
                base.setStatus("DEPRECATED");
            }
            return null;
        }).when(transactionManager).commit(transactionStatus);

        CodeWorkspaceConflictException conflict = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v2")
        );

        assertEquals("BASE_VERSION_CHANGED", conflict.getReasonCode());
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void mergesBaseUpsertAndDeleteIntoCompleteZipWithoutMutatingOldVersion() {
        CodeVersion base = baseVersion();
        workspace.setBaseVersionId(base.getId());
        byte[] baseBytes = zipService.writeDeterministic(Map.of(
                "config.yaml", bytes("epochs: 1\n"),
                "remove.txt", bytes("remove me\n"),
                "train.py", bytes("print('base')\n")
        ));
        String originalBaseSha = filePolicy.sha256(baseBytes);
        String originalBasePath = base.getStoragePath();
        base.setArtifactSha256(originalBaseSha);
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("base-v1", "asset-1"))
                .thenReturn(Optional.of(base));
        when(versionRepository.findByIdAndDeletedFalseForUpdate("base-v1"))
                .thenReturn(Optional.of(base));
        when(authContext.canAccessObjectName(base.getStoragePath(), 7)).thenReturn(true);
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(
                        delete("remove.txt"),
                        upsert("train.py", "print('published')\n")
                ));
        doAnswer(invocation -> {
            String object = invocation.getArgument(0);
            return base.getStoragePath().equals(object)
                    ? stored(object, baseBytes)
                    : stored(object, uploadedBytes.get());
        }).when(storageService).read(anyString());

        CodeVersion published = service.publish("workspace-1", 3L, "v2");

        byte[] expected = zipService.writeDeterministic(Map.of(
                "config.yaml", bytes("epochs: 1\n"),
                "train.py", bytes("print('published')\n")
        ));
        assertArrayEquals(expected, uploadedBytes.get());
        assertEquals(Map.of(
                "config.yaml", "epochs: 1\n",
                "train.py", "print('published')\n"
        ), textEntries(uploadedBytes.get()));
        assertEquals(originalBaseSha, base.getArtifactSha256());
        assertEquals(originalBasePath, base.getStoragePath());
        assertEquals("READY", base.getStatus());
        assertEquals("base", base.getVersion());
        assertFalse(base.getId().equals(published.getId()));
        verify(versionRepository).saveAndFlush(published);
        var lockOrder = inOrder(workspaceRepository, assetRepository, versionRepository);
        lockOrder.verify(workspaceRepository).findAssetIdByIdAndDeletedFalse("workspace-1");
        lockOrder.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        lockOrder.verify(workspaceRepository).findByIdAndDeletedFalseForUpdate("workspace-1");
        lockOrder.verify(versionRepository)
                .findByIdAndAssetIdAndDeletedFalse("base-v1", "asset-1");
        lockOrder.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        lockOrder.verify(workspaceRepository).findByIdAndDeletedFalseForUpdate("workspace-1");
        lockOrder.verify(versionRepository).findByIdAndDeletedFalseForUpdate("base-v1");
    }

    @Test
    void workspaceRevisionChangeDuringStorageIoRejectsAndCleansUploadedObject() {
        prepareValidSingleFile();
        onFirstCommit(() -> workspace.setRevision(4L));

        CodeWorkspaceConflictException conflict = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );

        assertEquals("WORKSPACE_REVISION_CONFLICT", conflict.getReasonCode());
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void workspaceBaseChangeDuringStorageIoRejectsAndCleansUploadedObject() {
        prepareValidSingleFile();
        onFirstCommit(() -> workspace.setBaseVersionId("another-base"));

        CodeWorkspaceConflictException changedBase = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );
        assertEquals("WORKSPACE_PUBLISH_CONFLICT", changedBase.getReasonCode());
        assertExactCleanupForUploadedObject();
    }

    @Test
    void workspaceCloseDuringStorageIoRejectsAndCleansUploadedObject() {
        prepareValidSingleFile();
        onFirstCommit(() -> workspace.setStatus(CodeWorkspace.STATUS_ABANDONED));

        CodeWorkspaceConflictException closed = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );

        assertEquals("WORKSPACE_READ_ONLY", closed.getReasonCode());
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void assetRowVersionOrMetadataChangeDuringStorageIoRejectsAndCleansUploadedObject() {
        prepareValidSingleFile();
        onFirstCommit(() -> {
            asset.setRowVersion(12L);
            asset.setEntryScript("changed.py");
        });

        CodeWorkspaceConflictException changedAsset = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );
        assertEquals("WORKSPACE_PUBLISH_CONFLICT", changedAsset.getReasonCode());
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateLabelBeforeMaterializationNeverUploadsAndFinalRaceCleansLoser() {
        prepareValidSingleFile();
        when(versionRepository.existsByAssetIdAndVersion("asset-1", "v1"))
                .thenReturn(true);

        CodeWorkspaceConflictException initial = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );
        assertEquals("VERSION_LABEL_CONFLICT", initial.getReasonCode());
        verify(storageService, never()).upload(anyString(), any(byte[].class));

        when(versionRepository.existsByAssetIdAndVersion("asset-1", "v2"))
                .thenReturn(false, true);
        CodeWorkspaceConflictException loser = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.publish("workspace-1", 3L, "v2")
        );
        assertEquals("VERSION_LABEL_CONFLICT", loser.getReasonCode());
        assertExactCleanupForUploadedObject();
        verify(versionRepository, never()).saveAndFlush(any());
        assertEquals(CodeWorkspace.STATUS_OPEN, workspace.getStatus());
        assertEquals(3L, workspace.getRevision());
    }

    @Test
    void flushFailureAfterUploadIsCaughtOutsideTransactionAndQueuesExactCleanup() {
        prepareValidSingleFile();
        when(versionRepository.saveAndFlush(any(CodeVersion.class)))
                .thenThrow(new RuntimeException("database details users/7/private"));

        CodeWorkspacePublishException error = assertThrows(
                CodeWorkspacePublishException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );

        assertFalse(error.getMessage().contains("users/7"));
        assertExactCleanupForUploadedObject();
        verify(validationRunRepository, never()).saveAndFlush(any());
        verify(workspaceRepository, never()).saveAndFlush(any());
    }

    @Test
    void commitTimeFailureAfterFinalCallbackStillQueuesExactCleanup() {
        prepareValidSingleFile();
        doNothing().doThrow(new RuntimeException("commit failed users/7/private"))
                .when(transactionManager).commit(transactionStatus);

        CodeWorkspacePublishException error = assertThrows(
                CodeWorkspacePublishException.class,
                () -> service.publish("workspace-1", 3L, "v1")
        );

        assertFalse(error.getMessage().contains("users/7"));
        verify(versionRepository).saveAndFlush(any(CodeVersion.class));
        verify(validationRunRepository).saveAndFlush(any(CodeValidationRun.class));
        verify(workspaceRepository).saveAndFlush(workspace);
        assertExactCleanupForUploadedObject();
    }

    @Test
    void cleanupQueueFailureFallsBackToDeletingTheExactUploadedObject() {
        prepareValidSingleFile();
        byte[] changed = zipService.writeDeterministic(Map.of(
                "train.py", bytes("print('changed')\n")
        ));
        doAnswer(invocation -> stored(invocation.getArgument(0), changed))
                .when(storageService).read(anyString());
        doThrow(new RuntimeException("queue unavailable"))
                .when(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                        anyString(), anyString(), anyString(), any()
                );

        assertThrows(CodeValidationException.class,
                () -> service.publish("workspace-1", 3L, "v1"));

        verify(storageService).delete(uploadedObject.get());
    }

    @Test
    void invalidLabelsFailBeforeRepositoryOrStorageWork() {
        for (String invalid : List.of(" ", "bad label", "../v1", "v/1", "x".repeat(65))) {
            CodeValidationException error = assertThrows(
                    CodeValidationException.class,
                    () -> service.publish("workspace-1", 3L, invalid)
            );
            assertEquals("VERSION_LABEL_INVALID", error.getReasonCode());
        }
        verify(workspaceRepository, never()).findAssetIdByIdAndDeletedFalse(anyString());
        verify(storageService, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void materializedArtifactDoesNotExposeMutableFileArrays() {
        MaterializedCodeArtifact materialized = assembler.materialize(
                asset,
                null,
                List.of(upsert("train.py", "print('immutable')\n"))
        );

        byte[] exposed = materialized.files().get("train.py");
        exposed[0] = 'X';

        assertEquals("print('immutable')\n", new String(
                materialized.files().get("train.py"), StandardCharsets.UTF_8
        ));
    }

    private void prepareValidSingleFile() {
        when(deltaRepository.findByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(upsert("train.py", "print('ok')\n")));
    }

    private void onFirstCommit(Runnable action) {
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.incrementAndGet() == 1) {
                action.run();
            }
            return null;
        }).when(transactionManager).commit(transactionStatus);
    }

    private void stubSnapshot(CodeAsset ownerAsset, CodeWorkspace ownerWorkspace) {
        when(workspaceRepository.findAssetIdByIdAndDeletedFalse("workspace-1"))
                .thenReturn(Optional.of("asset-1"));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(ownerAsset));
        when(workspaceRepository.findByIdAndDeletedFalseForUpdate("workspace-1"))
                .thenReturn(Optional.of(ownerWorkspace));
        when(versionRepository.existsByAssetIdAndVersion(anyString(), anyString()))
                .thenReturn(false);
    }

    private void assertExactCleanupForUploadedObject() {
        ArgumentCaptor<String> objectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(deleteTaskService).enqueueDefaultBucketDeleteImmediately(
                objectCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        MinioDeleteTaskService.SOURCE_CODE_ARTIFACT_ROLLBACK),
                sourceIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(7)
        );
        assertEquals(uploadedObject.get(), objectCaptor.getValue());
        String[] segments = objectCaptor.getValue().split("/");
        assertEquals(7, segments.length);
        assertEquals("users", segments[0]);
        assertEquals("7", segments[1]);
        assertEquals("codes", segments[2]);
        assertEquals("asset-1", segments[3]);
        assertEquals("versions", segments[4]);
        assertEquals(sourceIdCaptor.getValue(), segments[5]);
        assertTrue(segments[5].matches("code-version-[0-9a-f]{32}"));
        assertTrue(segments[6].matches("[0-9a-f]{32}\\.zip"));
    }

    private Map<String, String> textEntries(byte[] archive) {
        return textEntriesBytes(archive).entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new String(entry.getValue(), StandardCharsets.UTF_8)
                )
        );
    }

    private Map<String, byte[]> textEntriesBytes(byte[] archive) {
        return zipService.readEntries(new ByteArrayInputStream(archive));
    }

    private StoredCodeArtifact stored(String objectName, byte[] bytes) {
        return new StoredCodeArtifact(
                objectName,
                bytes,
                filePolicy.sha256(bytes),
                bytes.length
        );
    }

    private CodeWorkspaceFileDelta upsert(String path, String text) {
        byte[] content = bytes(text);
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-" + path.replace('/', '-'));
        delta.setWorkspaceId("workspace-1");
        delta.setPath(path);
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_UPSERT);
        delta.setContentBytes(content);
        delta.setContentHash(filePolicy.sha256(content));
        delta.setSizeBytes((long) content.length);
        delta.setCreatedAt(Instant.now());
        delta.setUpdatedAt(Instant.now());
        return delta;
    }

    private static CodeWorkspaceFileDelta delete(String path) {
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-delete-" + path.replace('/', '-'));
        delta.setWorkspaceId("workspace-1");
        delta.setPath(path);
        delta.setOperation(CodeWorkspaceFileDelta.OPERATION_DELETE);
        delta.setCreatedAt(Instant.now());
        delta.setUpdatedAt(Instant.now());
        return delta;
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setName("asset");
        asset.setPurpose("TRAINING");
        asset.setRuntime("python:3.11");
        asset.setEntryScript("train.py");
        asset.setTrainingType("NLP");
        asset.setTrainingProfile("profile-1");
        asset.setOwnerUserId(7);
        asset.setRowVersion(11L);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeWorkspace workspace(long revision, String baseVersionId) {
        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId("workspace-1");
        workspace.setAssetId("asset-1");
        workspace.setBaseVersionId(baseVersionId);
        workspace.setOwnerUserId(7);
        workspace.setStatus(CodeWorkspace.STATUS_OPEN);
        workspace.setRevision(revision);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace.setDeleted(false);
        return workspace;
    }

    private static CodeVersion baseVersion() {
        CodeVersion version = new CodeVersion();
        version.setId("base-v1");
        version.setAssetId("asset-1");
        version.setVersion("base");
        version.setStoragePath("users/7/codes/asset-1/versions/base-v1/base.zip");
        version.setStatus("READY");
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion(CodeArtifactAssembler.POLICY_VERSION);
        version.setApprovalStatus(CodeApprovalStatus.APPROVED);
        version.setOwnerUserId(7);
        version.setDeleted(false);
        return version;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
