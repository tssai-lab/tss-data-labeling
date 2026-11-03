package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadManifestCompletionTest {

    @Test
    void completesManifestUploadAsDraftWithPendingImportJob() throws Exception {
        assertCompletesMultimodalUpload("MANIFEST", "manifest.json");
    }

    @Test
    void completesAutoDirectoryUploadWithoutManifestPath() throws Exception {
        assertCompletesMultimodalUpload("AUTO_DIRECTORY", null);
    }

    private void assertCompletesMultimodalUpload(
            String sampleGrouping,
            String manifestPath
    ) throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.multimodalSession();
        session.setSampleGrouping(sampleGrouping);
        session.setManifestPath(manifestPath);
        DatasetUploadChunk chunk = fixture.chunk(session);
        AtomicReference<DatasetVersion> savedVersion = new AtomicReference<>();
        AtomicReference<DatasetAsset> savedAsset = new AtomicReference<>();
        AtomicReference<DatasetPackage> savedPackage = new AtomicReference<>();
        AtomicReference<DatasetVersionPackage> savedVersionPackage = new AtomicReference<>();
        AtomicReference<ImportJob> savedJob = new AtomicReference<>();

        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.sessionRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId())).thenReturn(List.of(chunk));
        when(fixture.assetRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetAsset value = invocation.getArgument(0);
            savedAsset.set(value);
            return value;
        });
        when(fixture.versionRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersion value = invocation.getArgument(0);
            savedVersion.set(value);
            return value;
        });
        when(fixture.versionRepo.findByIdAndDeletedFalse(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedVersion.get()));
        when(fixture.versionRepo.findMaxVersionNoByAssetId(any())).thenReturn(0);
        when(fixture.versionRepo.existsByAssetIdAndVersion(any(), any())).thenReturn(false);
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetPackage value = invocation.getArgument(0);
            savedPackage.set(value);
            return value;
        });
        when(fixture.versionPackageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersionPackage value = invocation.getArgument(0);
            savedVersionPackage.set(value);
            return value;
        });
        when(fixture.importJobRepo.findByDatasetVersionIdAndPackageId(any(), any()))
                .thenReturn(Optional.empty());
        when(fixture.importJobRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            ImportJob value = invocation.getArgument(0);
            savedJob.set(value);
            return value;
        });
        when(fixture.importJobRepo.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedJob.get()));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(session.getFileSize());
        when(fixture.minioClient.statObject(any())).thenReturn(stat);

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());
        Map<String, Object> result = fixture.service.complete(request);

        assertEquals("DRAFT", savedVersion.get().getStatus());
        assertNull(savedVersion.get().getPublishedAt());
        assertNull(savedAsset.get().getCurrentVersionId());
        assertEquals(savedVersion.get().getAssetId(), savedPackage.get().getDatasetAssetId());
        assertEquals(savedVersion.get().getStoragePath(), savedPackage.get().getStoragePath());
        assertEquals(manifestPath, savedPackage.get().getManifestPath());
        assertEquals("READY", savedPackage.get().getStatus());
        assertEquals(savedVersion.get().getId(), savedVersionPackage.get().getDatasetVersionId());
        assertEquals(savedPackage.get().getId(), savedVersionPackage.get().getPackageId());
        assertEquals("PRIMARY", savedVersionPackage.get().getPackageRole());
        assertEquals(0, savedVersionPackage.get().getPackageOrder());
        assertEquals("PENDING", savedJob.get().getStatus());
        assertEquals(savedPackage.get().getId(), savedJob.get().getPackageId());
        assertEquals(savedJob.get().getId(), session.getImportJobId());
        assertEquals("COMPLETED", session.getStatus());
        assertEquals("DRAFT", result.get("versionStatus"));
        assertEquals("PENDING", result.get("importStatus"));
        assertEquals(savedJob.get().getId(), result.get("importJobId"));
        verify(fixture.importJobLauncher).launch(savedJob.get().getId());
        verify(fixture.minioClient, never()).getObject(any());
    }

    @Test
    void rejectsNonReadyParentVersionDuringInit() {
        Fixture fixture = new Fixture();
        DatasetAsset asset = new DatasetAsset();
        asset.setId("asset-1");
        asset.setName("existing");
        asset.setType("MULTIMODAL");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        DatasetVersion parent = new DatasetVersion();
        parent.setId("version-parent");
        parent.setAssetId(asset.getId());
        parent.setStatus("DRAFT");
        parent.setDeleted(false);

        when(fixture.assetRepo.findByIdAndDeletedFalse(asset.getId())).thenReturn(Optional.of(asset));
        when(fixture.versionRepo.findByIdAndDeletedFalse(parent.getId())).thenReturn(Optional.of(parent));

        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setAssetId(asset.getId());
        request.setFileName("dataset.zip");
        request.setFileSize(1024L);
        request.setType("MULTIMODAL");
        request.setSampleGrouping("MANIFEST");
        request.setParentVersionId(parent.getId());

        assertThrows(IllegalArgumentException.class, () -> fixture.service.init(request));
    }

    @Test
    void completeManifestUploadRejectsExistingAssetWithActiveDraftBeforeCreatingVersion() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.multimodalSession();
        session.setAssetId("dataset-asset-1");
        session.setParentVersionId("dataset-ver-ready");
        DatasetUploadChunk chunk = fixture.chunk(session);
        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setType("MULTIMODAL");
        asset.setOwnerUserId(7);
        DatasetVersion activeDraft = new DatasetVersion();
        activeDraft.setId("dataset-ver-draft");
        activeDraft.setAssetId("dataset-asset-1");
        activeDraft.setStatus("DRAFT");
        activeDraft.setDeleted(false);

        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId())).thenReturn(List.of(chunk));
        when(fixture.assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(fixture.versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                "dataset-asset-1",
                "DRAFT"
        )).thenReturn(Optional.of(activeDraft));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.complete(request)
        );

        assertTrue(error.getMessage().contains("active DRAFT"), error.getMessage());
        verify(fixture.versionRepo, never()).saveAndFlush(any(DatasetVersion.class));
        verify(fixture.minioClient, never()).composeObject(any());
    }

    @Test
    void completeManifestUploadMapsActiveDraftUniqueViolationBeforeComposingObject() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.multimodalSession();
        session.setAssetId("dataset-asset-1");
        session.setParentVersionId("dataset-ver-ready");
        DatasetUploadChunk chunk = fixture.chunk(session);
        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setType("MULTIMODAL");
        asset.setOwnerUserId(7);

        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId())).thenReturn(List.of(chunk));
        when(fixture.assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(fixture.versionRepo.saveAndFlush(any(DatasetVersion.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key",
                        new org.hibernate.exception.ConstraintViolationException(
                                "duplicate",
                                null,
                                "uk_dataset_version_one_active_draft"
                        )
                ));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.complete(request)
        );

        assertTrue(error.getMessage().contains("active DRAFT"), error.getMessage());
        verify(fixture.minioClient, never()).composeObject(any());
    }

    @Test
    void initStoresDefaultManifestAndDynamicChunkSize() {
        Fixture fixture = new Fixture();
        AtomicReference<DatasetUploadSession> savedSession = new AtomicReference<>();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            DatasetUploadSession value = invocation.getArgument(0);
            savedSession.set(value);
            return value;
        });

        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setDatasetName("large multimodal");
        request.setFileName("dataset.zip");
        request.setFileSize(50L * 1024 * 1024 * 1024);
        request.setType("MULTIMODAL");
        request.setSampleGrouping("MANIFEST");

        var progress = fixture.service.init(request);

        assertEquals(6 * 1024 * 1024, progress.getChunkSize());
        assertEquals(8534, progress.getTotalChunks());
        assertEquals("MANIFEST", savedSession.get().getSampleGrouping());
        assertEquals("manifest.json", savedSession.get().getManifestPath());
    }

    @Test
    void initStoresAutoDirectoryWithoutManifestPath() {
        Fixture fixture = new Fixture();
        AtomicReference<DatasetUploadSession> savedSession = new AtomicReference<>();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            DatasetUploadSession value = invocation.getArgument(0);
            savedSession.set(value);
            return value;
        });

        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setDatasetName("auto multimodal");
        request.setFileName("dataset.zip");
        request.setFileSize(1024L);
        request.setType("MULTIMODAL");
        request.setSampleGrouping("AUTO_DIRECTORY");

        fixture.service.init(request);

        assertEquals("AUTO_DIRECTORY", savedSession.get().getSampleGrouping());
        assertNull(savedSession.get().getManifestPath());
    }

    @Test
    void repeatedCompleteReturnsExistingIdsWithoutComposingAgain() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.multimodalSession();
        session.setStatus("COMPLETED");
        session.setAssetId("asset-1");
        session.setVersionId("version-1");
        session.setImportJobId("ijob-1");
        ImportJob job = new ImportJob();
        job.setId("ijob-1");
        job.setStatus("PENDING");
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.importJobRepo.findById(job.getId())).thenReturn(Optional.of(job));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());
        Map<String, Object> result = fixture.service.complete(request);

        assertEquals("version-1", result.get("datasetVersionId"));
        assertEquals("ijob-1", result.get("importJobId"));
        verify(fixture.importJobLauncher).launch(job.getId());
        verify(fixture.minioClient, never()).composeObject(any());
    }

    @Test
    void composeFailurePersistsRetryableFailureAndDeletesDraftAndEmptyAsset() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.multimodalSession();
        DatasetUploadChunk chunk = fixture.chunk(session);
        AtomicReference<DatasetVersion> savedVersion = new AtomicReference<>();
        AtomicReference<DatasetAsset> savedAsset = new AtomicReference<>();
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.sessionRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId())).thenReturn(List.of(chunk));
        when(fixture.assetRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetAsset value = invocation.getArgument(0);
            savedAsset.set(value);
            return value;
        });
        when(fixture.versionRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersion value = invocation.getArgument(0);
            savedVersion.set(value);
            return value;
        });
        when(fixture.versionRepo.findMaxVersionNoByAssetId(any())).thenReturn(0);
        when(fixture.versionRepo.existsByAssetIdAndVersion(any(), any())).thenReturn(false);
        when(fixture.versionRepo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedVersion.get()));
        when(fixture.assetRepo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedAsset.get()));
        doThrow(new RuntimeException("compose failed")).when(fixture.minioClient).composeObject(any());

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        DatasetUploadCompletionException error = assertThrows(
                DatasetUploadCompletionException.class,
                () -> fixture.service.complete(request)
        );
        assertEquals("DATASET_UPLOAD_STORAGE_FAILED", error.getReasonCode());
        assertEquals("FAILED", session.getStatus());
        assertEquals(
                "DATASET_UPLOAD_STORAGE_FAILED",
                session.getCompletionErrorCode()
        );
        assertNull(session.getVersionId());
        assertNull(session.getAssetId());
        verify(fixture.versionRepo).delete(savedVersion.get());
        verify(fixture.assetRepo).delete(savedAsset.get());
    }

    private static final class Fixture {
        private final MinioClient minioClient = mock(MinioClient.class);
        private final DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final MinioDeleteTaskService deleteTaskService = mock(MinioDeleteTaskService.class);
        private final ImportJobLauncher importJobLauncher = mock(ImportJobLauncher.class);
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        private final DatasetUploadService service;

        private Fixture() {
            MinioConfig config = new MinioConfig();
            config.setBucket("datasets");
            when(authContext.currentUserId()).thenReturn(7);
            when(authContext.canAccessOwner(any())).thenReturn(true);
            when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            service = new DatasetUploadService(
                    minioClient,
                    config,
                    sessionRepo,
                    chunkRepo,
                    assetRepo,
                    versionRepo,
                    packageRepo,
                    versionPackageRepo,
                    importJobRepo,
                    authContext,
                    deleteTaskService,
                    transactionManager
            );
            service.setImportJobLauncher(importJobLauncher);
        }

        private DatasetUploadSession multimodalSession() {
            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("upload-1");
            session.setFileName("dataset.zip");
            session.setFileSize(1024L);
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setDatasetName("multimodal");
            session.setVersion("v1");
            session.setVersionLabel("v1");
            session.setVersionNo(1);
            session.setVersionLabelGenerated(false);
            session.setType("MULTIMODAL");
            session.setSampleGrouping("MANIFEST");
            session.setManifestPath("manifest.json");
            session.setStatus("UPLOADING");
            session.setOwnerUserId(7);
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            return session;
        }

        private DatasetUploadChunk chunk(DatasetUploadSession session) {
            DatasetUploadChunk chunk = new DatasetUploadChunk();
            chunk.setId("chunk-1");
            chunk.setUploadId(session.getId());
            chunk.setPartIndex(0);
            chunk.setObjectName("users/7/datasets/_uploads/upload-1/part-0");
            chunk.setSizeBytes(session.getFileSize());
            return chunk;
        }
    }
}
