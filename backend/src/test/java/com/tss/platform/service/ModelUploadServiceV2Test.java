package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.ModelUploadProgressDto;
import com.tss.platform.dto.UploadCompleteRequest;
import com.tss.platform.dto.v2.V2ModelUploadDto;
import com.tss.platform.dto.v2.V2ModelUploadInitRequest;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelUploadChunk;
import com.tss.platform.entity.ModelUploadSession;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelUploadChunkRepository;
import com.tss.platform.repository.ModelUploadSessionRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.StatObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUploadServiceV2Test {

    @Test
    void progressDoesNotLoadFullChunkEntities() {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));

        V2ModelUploadDto result = fixture.service.getProgressV2(session.getId());

        assertEquals(0, result.getUploadedChunks());
        verify(fixture.chunkRepo, org.mockito.Mockito.never())
                .findByUploadIdOrderByPartIndexAsc(session.getId());
    }

    @Test
    void initPersistsFileAndBusinessMetadata() {
        Fixture fixture = new Fixture();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        V2ModelUploadDto result = fixture.service.initV2(fixture.request());

        ArgumentCaptor<ModelUploadSession> captor =
                ArgumentCaptor.forClass(ModelUploadSession.class);
        verify(fixture.sessionRepo).save(captor.capture());
        ModelUploadSession session = captor.getValue();
        assertEquals("model.zip", session.getFileName());
        assertEquals("Detector", session.getModelName());
        assertEquals("v1", session.getModelVersion());
        assertEquals("CV", session.getTaskType());
        assertEquals("detector model", session.getRemark());
        assertNull(session.getTargetAssetId());
        assertEquals(session.getId(), result.getUploadId());
        assertEquals("Detector", result.getModelName());
    }

    @Test
    void initAcceptsOtherAsAnUnclassifiedStorageCategory() {
        Fixture fixture = new Fixture();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        V2ModelUploadInitRequest request = fixture.request();
        request.setTaskType(" other ");

        V2ModelUploadDto result = fixture.service.initV2(request);

        ArgumentCaptor<ModelUploadSession> captor =
                ArgumentCaptor.forClass(ModelUploadSession.class);
        verify(fixture.sessionRepo).save(captor.capture());
        assertEquals("OTHER", captor.getValue().getTaskType());
        assertNull(captor.getValue().getArtifactSpecId());
        assertEquals("OTHER", result.getTaskType());
        assertNull(result.getArtifactSpecId());
    }

    @Test
    void fingerprintResumeRequiresMatchingBusinessMetadata() {
        Fixture fixture = new Fixture();
        ModelUploadSession existing = fixture.session();
        existing.setModelVersion("v0");
        when(fixture.sessionRepo
                .findFirstByFileFingerprintAndStatusAndOwnerUserIdOrderByUpdatedAtDesc(
                        "sha256:abc",
                        "UPLOADING",
                        7
                ))
                .thenReturn(Optional.of(existing));
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        V2ModelUploadDto result = fixture.service.initV2(fixture.request());

        assertNotEquals(existing.getId(), result.getUploadId());
        assertEquals("v1", result.getModelVersion());
    }

    @Test
    void completeUsesBusinessMetadataStoredInSession() throws Exception {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        ModelUploadChunk chunk = new ModelUploadChunk();
        chunk.setUploadId(session.getId());
        chunk.setPartIndex(0);
        chunk.setObjectName("users/7/models/_uploads/upload-1/part-0");
        chunk.setSizeBytes(session.getFileSize());
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.sessionRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(chunk));
        when(fixture.assetRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.versionRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        fixture.stubCompletedObject();

        V2ModelUploadDto result = fixture.service.completeV2(session.getId());

        ArgumentCaptor<ModelAsset> assetCaptor = ArgumentCaptor.forClass(ModelAsset.class);
        verify(fixture.assetRepo, times(2)).saveAndFlush(assetCaptor.capture());
        ModelAsset savedAsset = assetCaptor.getAllValues().get(1);
        assertEquals("Detector", savedAsset.getName());
        assertEquals("CV", savedAsset.getType());
        ArgumentCaptor<ModelVersion> versionCaptor =
                ArgumentCaptor.forClass(ModelVersion.class);
        verify(fixture.versionRepo).saveAndFlush(versionCaptor.capture());
        assertEquals("v1", versionCaptor.getValue().getVersion());
        assertEquals("READY", versionCaptor.getValue().getStatus());
        assertEquals("detector model", versionCaptor.getValue().getChangeLog());
        assertEquals("git abc123", versionCaptor.getValue().getCommitInfo());
        assertEquals(fixture.sha256(zipBytes()), versionCaptor.getValue().getArtifactSha256());
        assertEquals("model.cv.yolo-weight/v1", versionCaptor.getValue().getArtifactSpecId());
        assertEquals(versionCaptor.getValue().getArtifactSpecId(), session.getArtifactSpecId());
        assertEquals(7, versionCaptor.getValue().getCreatedBy());
        assertEquals(
                versionCaptor.getValue().getCreatedAt(),
                versionCaptor.getValue().getPublishedAt()
        );
        assertEquals("COMPLETED", result.getStatus());
        assertTrue(result.getModelId().startsWith("model-ver-"));
        assertEquals(fixture.sha256(zipBytes()), result.getArtifactSha256());
        assertEquals("model.cv.yolo-weight/v1", result.getArtifactSpecId());
        assertEquals(true, result.getIsCurrent());
        assertEquals(
                "model.cv.yolo-weight/v1",
                fixture.service.getProgressV2(session.getId()).getArtifactSpecId()
        );
        UploadCompleteRequest legacyRequest = new UploadCompleteRequest();
        legacyRequest.setUploadId(session.getId());
        legacyRequest.setModelName(session.getModelName());
        legacyRequest.setVersion(session.getModelVersion());
        legacyRequest.setType(session.getTaskType());
        legacyRequest.setRemark(session.getRemark());
        legacyRequest.setCommitInfo(session.getCommitInfo());
        legacyRequest.setHyperParams(session.getHyperParams());
        Map<String, Object> legacyResult = fixture.service.complete(legacyRequest);
        assertTrue(!legacyResult.containsKey("storagePath"));
        String json = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(result);
        assertTrue(!json.contains("storagePath"));
        assertTrue(!json.contains("ownerUserId"));
    }

    @Test
    void saveChunkUploadsObjectOutsideDatabaseTransaction() throws Exception {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.chunkRepo.findByUploadIdAndPartIndex(session.getId(), 0))
                .thenReturn(Optional.empty());
        when(fixture.chunkRepo.save(any())).thenAnswer(invocation -> {
            assertTrue(
                    fixture.activeTransactions.get() > 0,
                    "chunk metadata must be persisted in a database transaction"
            );
            return invocation.getArgument(0);
        });
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            assertTrue(
                    fixture.activeTransactions.get() > 0,
                    "session metadata must be persisted in a database transaction"
            );
            return invocation.getArgument(0);
        });
        when(fixture.minioClient.putObject(any())).thenAnswer(invocation -> {
            assertEquals(
                    0,
                    fixture.activeTransactions.get(),
                    "chunk object upload must run outside database transactions"
            );
            return mock(ObjectWriteResponse.class);
        });
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.etag()).thenReturn("etag-1");
        when(fixture.minioClient.statObject(any())).thenAnswer(invocation -> {
            assertEquals(
                    0,
                    fixture.activeTransactions.get(),
                    "chunk object stat must run outside database transactions"
            );
            return stat;
        });
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "part-0",
                "application/octet-stream",
                new byte[128]
        );

        ModelUploadProgressDto result =
                fixture.service.saveChunk(session.getId(), 0, file);

        assertEquals(session.getId(), result.getUploadId());
        verify(fixture.chunkRepo).save(any());
        verify(fixture.sessionRepo).save(any());
    }

    @Test
    void completeComposesAndValidatesModelObjectOutsideDatabaseTransaction() throws Exception {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        ModelUploadChunk chunk = new ModelUploadChunk();
        chunk.setUploadId(session.getId());
        chunk.setPartIndex(0);
        chunk.setObjectName("users/7/models/_uploads/upload-1/part-0");
        chunk.setSizeBytes(session.getFileSize());
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertTrue(
                            fixture.activeTransactions.get() > 0,
                            "completion status claim must run in a database transaction"
                    );
                    return 1;
                });
        when(fixture.sessionRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            assertTrue(
                    fixture.activeTransactions.get() > 0,
                    "completed session must be persisted in a database transaction"
            );
            return invocation.getArgument(0);
        });
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(chunk));
        when(fixture.assetRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            assertTrue(
                    fixture.activeTransactions.get() > 0,
                    "model asset must be persisted in a database transaction"
            );
            return invocation.getArgument(0);
        });
        when(fixture.versionRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            assertTrue(
                    fixture.activeTransactions.get() > 0,
                    "model version must be persisted in a database transaction"
            );
            return invocation.getArgument(0);
        });
        when(fixture.minioClient.composeObject(any())).thenAnswer(invocation -> {
            assertEquals(
                    0,
                    fixture.activeTransactions.get(),
                    "model object compose must run outside database transactions"
            );
            return mock(ObjectWriteResponse.class);
        });
        byte[] zip = zipBytes();
        when(fixture.minioClient.getObject(any())).thenAnswer(invocation -> {
            assertEquals(
                    0,
                    fixture.activeTransactions.get(),
                    "model object validation must run outside database transactions"
            );
            return new GetObjectResponse(
                    new Headers.Builder().build(),
                    "models",
                    null,
                    "model.zip",
                    new ByteArrayInputStream(zip)
            );
        });
        StatObjectResponse completedStat = mock(StatObjectResponse.class);
        when(completedStat.size()).thenReturn((long) zip.length);
        when(fixture.minioClient.statObject(any())).thenAnswer(invocation -> {
            assertEquals(
                    0,
                    fixture.activeTransactions.get(),
                    "model object stat must run outside database transactions"
            );
            return completedStat;
        });

        V2ModelUploadDto result = fixture.service.completeV2(session.getId());

        assertEquals("COMPLETED", result.getStatus());
    }

    @Test
    void duplicateVersionReturnsConflictStatus() {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        session.setTargetAssetId("asset-1");
        ModelAsset asset = new ModelAsset();
        asset.setId("asset-1");
        asset.setName("Detector");
        asset.setType("CV");
        asset.setRemark("detector model");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        ModelUploadChunk chunk = new ModelUploadChunk();
        chunk.setUploadId(session.getId());
        chunk.setPartIndex(0);
        chunk.setObjectName("part-0");
        chunk.setSizeBytes(session.getFileSize());
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                .thenReturn(Optional.of(asset));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(chunk));
        ModelVersion existing = new ModelVersion();
        existing.setStatus("READY");
        when(fixture.versionRepo.findByAssetIdAndVersionAndDeletedFalse(asset.getId(), "v1"))
                .thenReturn(Optional.of(existing));

        com.tss.platform.controller.v2.V2BusinessException error = assertThrows(
                com.tss.platform.controller.v2.V2BusinessException.class,
                () -> fixture.service.completeV2(session.getId())
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatus());
        assertEquals("MODEL_VERSION_CONFLICT", error.getErrorCode());
    }

    @Test
    void rejectsASecondVersionThatChangesTheVerifiedModelContract() throws Exception {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        session.setTargetAssetId("asset-1");
        ModelAsset asset = new ModelAsset();
        asset.setId("asset-1");
        asset.setName("Detector");
        asset.setType("CV");
        asset.setRemark("detector model");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        ModelUploadChunk chunk = new ModelUploadChunk();
        chunk.setUploadId(session.getId());
        chunk.setPartIndex(0);
        chunk.setObjectName("part-0");
        chunk.setSizeBytes(session.getFileSize());
        ModelVersion existing = new ModelVersion();
        existing.setArtifactSpecId("model.cv.hf-image/v1");
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                .thenReturn(Optional.of(asset));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(chunk));
        when(fixture.versionRepo
                .findFirstByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByCreatedAtDesc(
                        asset.getId()
                )).thenReturn(Optional.of(existing));
        fixture.stubCompletedObject();

        com.tss.platform.controller.v2.V2BusinessException error = assertThrows(
                com.tss.platform.controller.v2.V2BusinessException.class,
                () -> fixture.service.completeV2(session.getId())
        );

        assertEquals("MODEL_UPLOAD_FAILED", error.getErrorCode());
        assertEquals(
                "model artifact specification does not match existing asset versions",
                error.getDetails().get("reason")
        );
        verify(fixture.versionRepo, org.mockito.Mockito.never()).saveAndFlush(any());
        assertNull(session.getArtifactSpecId());
    }

    @Test
    void inaccessibleTargetAssetReturnsSanitizedNotFoundError() {
        Fixture fixture = new Fixture();
        V2ModelUploadInitRequest request = fixture.request();
        request.setTargetAssetId("asset-secret");
        when(fixture.assetRepo.findByIdAndDeletedFalse("asset-secret"))
                .thenReturn(Optional.empty());

        com.tss.platform.controller.v2.V2BusinessException error = assertThrows(
                com.tss.platform.controller.v2.V2BusinessException.class,
                () -> fixture.service.initV2(request)
        );

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("MODEL_ASSET_NOT_FOUND", error.getErrorCode());
        assertEquals("模型资产不存在或无权访问", error.getMessage());
    }

    @Test
    void unknownUploadOnCompleteReturnsSanitizedNotFoundError() {
        Fixture fixture = new Fixture();
        when(fixture.sessionRepo.findById("upload-secret")).thenReturn(Optional.empty());

        com.tss.platform.controller.v2.V2BusinessException error = assertThrows(
                com.tss.platform.controller.v2.V2BusinessException.class,
                () -> fixture.service.completeV2("upload-secret")
        );

        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("MODEL_UPLOAD_NOT_FOUND", error.getErrorCode());
        assertEquals("模型上传任务不存在或无权访问", error.getMessage());
    }

    @Test
    void genericV2FailureIncludesOriginalValidationReasonInDetails() {
        Fixture fixture = new Fixture();
        ModelUploadSession session = fixture.session();
        session.setRemark(null);
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));

        com.tss.platform.controller.v2.V2BusinessException error = assertThrows(
                com.tss.platform.controller.v2.V2BusinessException.class,
                () -> fixture.service.completeV2(session.getId())
        );

        assertEquals("MODEL_UPLOAD_FAILED", error.getErrorCode());
        assertEquals("remark 不能为空", error.getDetails().get("reason"));
    }

    private static byte[] zipBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("yolo11n.pt");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static final class Fixture {
        private final MinioClient minioClient = mock(MinioClient.class);
        private final ModelUploadSessionRepository sessionRepo =
                mock(ModelUploadSessionRepository.class);
        private final ModelUploadChunkRepository chunkRepo =
                mock(ModelUploadChunkRepository.class);
        private final ModelAssetRepository assetRepo = mock(ModelAssetRepository.class);
        private final ModelVersionRepository versionRepo = mock(ModelVersionRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final ModelUploadService service;

        private Fixture() {
            MinioConfig config = new MinioConfig();
            config.setBucket("models");
            when(authContext.currentUserId()).thenReturn(7);
            when(authContext.canAccessOwner(7)).thenReturn(true);
            when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
                activeTransactions.incrementAndGet();
                return new SimpleTransactionStatus();
            });
            doAnswer(invocation -> {
                activeTransactions.decrementAndGet();
                return null;
            }).when(transactionManager).commit(any());
            doAnswer(invocation -> {
                activeTransactions.decrementAndGet();
                return null;
            }).when(transactionManager).rollback(any());
            service = new ModelUploadService(
                    minioClient,
                    config,
                    sessionRepo,
                    chunkRepo,
                    assetRepo,
                    versionRepo,
                    authContext,
                    deleteTaskService,
                    transactionManager
            );
        }

        private V2ModelUploadInitRequest request() {
            V2ModelUploadInitRequest request = new V2ModelUploadInitRequest();
            request.setFileName("model.zip");
            request.setFileSize(128L);
            request.setFileFingerprint("sha256:abc");
            request.setModelName("Detector");
            request.setModelVersion("v1");
            request.setTaskType("CV");
            request.setRemark("detector model");
            request.setCommitInfo("git abc123");
            return request;
        }

        private ModelUploadSession session() {
            ModelUploadSession session = new ModelUploadSession();
            session.setId("upload-1");
            session.setFileName("model.zip");
            session.setFileSize((long) zipBytesUnchecked().length);
            session.setFileFingerprint("sha256:abc");
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setStatus("UPLOADING");
            session.setModelName("Detector");
            session.setModelVersion("v1");
            session.setTaskType("CV");
            session.setRemark("detector model");
            session.setCommitInfo("git abc123");
            session.setOwnerUserId(7);
            session.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            session.setUpdatedAt(session.getCreatedAt());
            return session;
        }

        private void stubCompletedObject() throws Exception {
            byte[] zip = zipBytes();
            when(minioClient.getObject(any())).thenAnswer(invocation -> new GetObjectResponse(
                    new Headers.Builder().build(),
                    "models",
                    null,
                    "model.zip",
                    new ByteArrayInputStream(zip)
            ));
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn((long) zip.length);
            when(minioClient.statObject(any())).thenReturn(stat);
        }

        private String sha256(byte[] bytes) throws Exception {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        }
    }

    private static byte[] zipBytesUnchecked() {
        try {
            return zipBytes();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
