package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadFailureLifecycleTest {

    @Test
    void invalidUtf8BecomesFailedAndSameUploadCanCompleteAfterContentReplacement()
            throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.stubInitialUpload();
        byte[] invalid = new byte[]{(byte) 0xc3};
        byte[] valid = new byte[]{'a'};
        session.setFileSize(1L);
        fixture.chunk.setSizeBytes(1L);
        fixture.stubObjectContents(invalid, valid);

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        DatasetUploadCompletionException failure = assertThrows(
                DatasetUploadCompletionException.class,
                () -> fixture.service.complete(request)
        );

        assertEquals("INVALID_DATASET_CONTENT", failure.getReasonCode());
        assertEquals("FAILED", session.getStatus());
        assertEquals("INVALID_DATASET_CONTENT", session.getCompletionErrorCode());
        assertEquals("VALIDATION", session.getCompletionErrorDetails().get("stage"));
        assertTrue(session.getCompletionFailedAt() != null);
        assertNull(session.getVersionId());
        verify(fixture.chunkRepo, never()).deleteByUploadId(session.getId());

        Map<String, Object> completed = fixture.service.complete(request);

        assertEquals("COMPLETED", session.getStatus());
        assertEquals("COMPLETED", completed.get("status"));
        assertNull(session.getCompletionErrorCode());
        assertNull(session.getCompletionErrorMessage());
        assertNull(session.getCompletionErrorDetails());
        assertNull(session.getCompletionFailedAt());
        verify(fixture.sessionRepo).updateStatusIfCurrent(
                eq(session.getId()),
                eq(session.getOwnerUserId()),
                eq("FAILED"),
                eq("COMPLETING"),
                any()
        );
    }

    @Test
    void storageAndFinalizationFailuresDoNotExposeTechnicalCause() throws Exception {
        Fixture storageFixture = new Fixture();
        DatasetUploadSession storageSession = storageFixture.stubInitialUpload();
        when(storageFixture.minioClient.composeObject(any()))
                .thenThrow(new RuntimeException(
                        "users/7/datasets/private-object database password"
                ));

        DatasetUploadCompletionException storageFailure = assertThrows(
                DatasetUploadCompletionException.class,
                () -> storageFixture.service.complete(request(storageSession))
        );

        assertEquals("DATASET_UPLOAD_STORAGE_FAILED", storageFailure.getReasonCode());
        assertFalse(storageFailure.getMessage().contains("users/"));
        assertFalse(storageSession.getCompletionErrorMessage().contains("password"));

        Fixture finalizationFixture = new Fixture();
        DatasetUploadSession finalizationSession = finalizationFixture.stubInitialUpload();
        finalizationFixture.stubObjectContents(new byte[]{'a'});
        AtomicInteger saves = new AtomicInteger();
        when(finalizationFixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenAnswer(invocation -> {
                    DatasetVersion version = invocation.getArgument(0);
                    finalizationFixture.storedVersion.set(version);
                    if (saves.incrementAndGet() > 1) {
                        throw new RuntimeException(
                                "SQL users/7/datasets/private-object"
                        );
                    }
                    return version;
                });

        DatasetUploadCompletionException finalizationFailure = assertThrows(
                DatasetUploadCompletionException.class,
                () -> finalizationFixture.service.complete(
                        request(finalizationSession)
                )
        );

        assertEquals(
                "DATASET_UPLOAD_FINALIZATION_FAILED",
                finalizationFailure.getReasonCode()
        );
        assertEquals("FINALIZATION", finalizationFailure.getDetails().get("stage"));
        assertFalse(finalizationFailure.getMessage().contains("SQL"));
    }

    @Test
    void replacingChunkOnFailedInitialUploadClearsFailureAndKeepsUploadId()
            throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.stubInitialUpload();
        session.setStatus("FAILED");
        session.setFileSize(3L);
        session.setCompletionErrorCode("INVALID_DATASET_CONTENT");
        session.setCompletionErrorMessage("数据集内容格式无效，请检查文件后重试");
        session.setCompletionErrorDetails(Map.of("stage", "VALIDATION"));
        session.setCompletionFailedAt(java.time.Instant.now());
        fixture.chunk.setSizeBytes(3L);
        when(fixture.sessionRepo.findByIdForUpdate(session.getId()))
                .thenReturn(Optional.of(session));
        when(fixture.chunkRepo.findByUploadIdAndPartIndex(session.getId(), 0))
                .thenReturn(Optional.of(fixture.chunk));
        when(fixture.chunkRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.findPartIndexesByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(0));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(3L);
        when(stat.etag()).thenReturn("replacement-etag");
        when(fixture.minioClient.statObject(any())).thenReturn(stat);

        var result = fixture.service.saveChunk(
                session.getId(),
                0,
                new MockMultipartFile("file", new byte[]{1, 2, 3})
        );

        assertEquals(session.getId(), result.getUploadId());
        assertEquals("UPLOADING", session.getStatus());
        assertNull(session.getCompletionErrorCode());
        assertNull(session.getCompletionFailedAt());
        assertFalse(fixture.chunk.getObjectName().endsWith("old-part"));
    }

    @Test
    void initWithSameFingerprintResumesFailedSession() {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.stubInitialUpload();
        session.setFileFingerprint("sha256:failed");
        session.setVersion("v1");
        session.setVersionLabel("v1");
        session.setVersionNo(null);
        session.setStatus("FAILED");
        session.setCompletionErrorCode("INVALID_DATASET_CONTENT");
        session.setCompletionErrorMessage("数据集内容格式无效，请检查文件后重试");
        session.setCompletionErrorDetails(Map.of("stage", "VALIDATION"));
        session.setCompletionFailedAt(java.time.Instant.now());
        DatasetAsset asset = new DatasetAsset();
        asset.setId(session.getAssetId());
        asset.setName(session.getDatasetName());
        asset.setType(session.getType());
        asset.setOwnerUserId(session.getOwnerUserId());
        when(fixture.assetRepo.findByIdAndDeletedFalse(asset.getId()))
                .thenReturn(Optional.of(asset));
        when(fixture.authContext.canAccessOwner(7)).thenReturn(true);
        when(fixture.sessionRepo
                .findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
                        "sha256:failed",
                        List.of("UPLOADING", "FAILED"),
                        7
                ))
                .thenReturn(Optional.of(session));
        when(fixture.sessionRepo.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setAssetId(asset.getId());
        request.setFileName(session.getFileName());
        request.setFileSize(session.getFileSize());
        request.setFileFingerprint(session.getFileFingerprint());
        request.setType(session.getType());

        var resumed = fixture.service.init(request);

        assertEquals(session.getId(), resumed.getUploadId());
        assertEquals("FAILED", resumed.getStatus());
    }

    @Test
    void missingChunksRemainUploadingWithoutPersistedFailure() {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.stubInitialUpload();
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.complete(request(session))
        );

        assertEquals("UPLOADING", session.getStatus());
        assertNull(session.getCompletionErrorCode());
        verify(fixture.sessionRepo, never()).updateStatusIfCurrent(
                any(), any(), any(), any(), any()
        );
    }

    private DatasetUploadCompleteRequest request(DatasetUploadSession session) {
        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());
        return request;
    }

    private static final class Fixture {
        private final MinioClient minioClient = mock(MinioClient.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final AtomicReference<DatasetVersion> storedVersion =
                new AtomicReference<>();
        private final DatasetUploadChunk chunk = new DatasetUploadChunk();
        private final DatasetUploadService service;

        private Fixture() {
            MinioConfig config = new MinioConfig();
            config.setBucket("datasets");
            service = new DatasetUploadService(
                    minioClient,
                    config,
                    sessionRepo,
                    chunkRepo,
                    assetRepo,
                    versionRepo,
                    authContext,
                    deleteTaskService
            );
        }

        private DatasetUploadSession stubInitialUpload() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("dataset-asset-1");
            asset.setName("Corpus");
            asset.setType("NLP");
            asset.setOwnerUserId(7);
            asset.setDeleted(false);

            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("dataset-upload-1");
            session.setUploadPurpose("INITIAL_DATASET");
            session.setFileName("corpus.txt");
            session.setFileSize(1L);
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setDatasetName("Corpus");
            session.setVersionLabelGenerated(true);
            session.setType("NLP");
            session.setStatus("UPLOADING");
            session.setAssetId(asset.getId());
            session.setOwnerUserId(7);

            chunk.setId("dataset-chunk-1");
            chunk.setUploadId(session.getId());
            chunk.setPartIndex(0);
            chunk.setObjectName("users/7/datasets/_uploads/dataset-upload-1/old-part");
            chunk.setSizeBytes(session.getFileSize());

            when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
            when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                    .thenReturn(1);
            when(sessionRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                    .thenReturn(List.of(chunk));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId()))
                    .thenReturn(Optional.of(asset));
            when(assetRepo.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(versionRepo.findMaxVersionNoByAssetId(asset.getId())).thenReturn(0);
            when(versionRepo.saveAndFlush(any(DatasetVersion.class)))
                    .thenAnswer(invocation -> {
                        DatasetVersion version = invocation.getArgument(0);
                        storedVersion.set(version);
                        return version;
                    });
            when(versionRepo.findByIdAndDeletedFalseForUpdate(anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(storedVersion.get()));
            when(versionRepo.findById(anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(storedVersion.get()));
            when(authContext.currentUserId()).thenReturn(7);
            return session;
        }

        private void stubObjectContents(byte[]... contents) throws Exception {
            AtomicInteger reads = new AtomicInteger();
            when(minioClient.getObject(any())).thenAnswer(invocation -> {
                int index = Math.min(reads.getAndIncrement(), contents.length - 1);
                byte[] content = contents[index];
                return new GetObjectResponse(
                        new Headers.Builder().build(),
                        "datasets",
                        null,
                        "corpus.txt",
                        new ByteArrayInputStream(content)
                );
            });
        }
    }
}
