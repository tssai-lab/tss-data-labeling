package com.tss.platform.service;

import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadServiceEnterpriseVersionTest {

    @Test
    void progressDoesNotLoadFullChunkEntities() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                mock(DatasetAssetRepository.class),
                mock(DatasetVersionRepository.class),
                mock(AuthContext.class),
                mock(MinioDeleteTaskService.class)
        );
        DatasetUploadSession session = uploadingSession(null, "new-corpus", "NLP", null, null);
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));

        DatasetUploadProgressDto progress = service.getProgress(session.getId());

        assertEquals(0, progress.getUploadedChunks());
        verify(chunkRepo, never()).findByUploadIdOrderByPartIndexAsc(session.getId());
    }

    @Test
    void progressIncludesExplicitStatusContractFields() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                mock(DatasetAssetRepository.class),
                versionRepo,
                mock(DatasetPackageRepository.class),
                mock(DatasetVersionPackageRepository.class),
                importJobRepo,
                mock(AuthContext.class),
                mock(MinioDeleteTaskService.class),
                mock(PlatformTransactionManager.class)
        );
        DatasetUploadSession session = uploadingSession(null, "new-corpus", "MULTIMODAL", null, null);
        session.setStatus("COMPLETED");
        session.setSampleGrouping("MANIFEST");
        session.setVersionId("dataset-ver-draft");
        session.setImportJobId("ijob-1");
        ImportJob job = new ImportJob();
        job.setId("ijob-1");
        job.setStatus("FAILED");
        DatasetVersion version = new DatasetVersion();
        version.setId(session.getVersionId());
        version.setStatus("DRAFT");
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(importJobRepo.findById("ijob-1")).thenReturn(Optional.of(job));
        when(versionRepo.findByIdAndDeletedFalse(session.getVersionId()))
                .thenReturn(Optional.of(version));

        DatasetUploadProgressDto progress = service.getProgress(session.getId());

        assertEquals("COMPLETED", progress.getUploadStatus());
        assertEquals("DRAFT", progress.getVersionStatus());
        assertEquals("ijob-1", progress.getImportJobId());
        assertEquals("FAILED", progress.getImportStatus());
        verify(chunkRepo, never()).summarizeProgressByUploadId(session.getId());
    }

    @Test
    void completeForNewAssetCreatesFirstReadyVersion() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        List<String> persistedStatuses = emulateVersionPersistence(versionRepo);
        emulateAssetPersistence(assetRepo);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetUploadSession session = uploadingSession(null, "new-corpus", "NLP", null, null);
        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1"))
                .thenReturn(List.of(uploadedChunk()));
        when(authContext.currentUserId()).thenReturn(7);
        when(minioClient.getObject(any())).thenReturn(textObject(session.getFileSize()));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId("dataset-upload-1");
        service.complete(request);

        ArgumentCaptor<DatasetAsset> assetCaptor = ArgumentCaptor.forClass(DatasetAsset.class);
        verify(assetRepo, times(2)).saveAndFlush(assetCaptor.capture());
        DatasetAsset asset = assetCaptor.getAllValues().get(1);

        ArgumentCaptor<DatasetVersion> versionCaptor = ArgumentCaptor.forClass(DatasetVersion.class);
        verify(versionRepo, times(2)).saveAndFlush(versionCaptor.capture());
        DatasetVersion version = versionCaptor.getAllValues().get(1);

        assertEquals(List.of("DRAFT", "READY"), persistedStatuses);
        assertEquals(1, version.getVersionNo());
        assertEquals("v1", version.getVersionLabel());
        assertEquals("READY", version.getStatus());
        assertEquals(asset.getId(), version.getAssetId());
        assertEquals(version.getId(), asset.getCurrentVersionId());
        assertTrue(version.getStoragePath().contains("/v1/corpus.txt"));
        assertTrue(version.getArtifactSha256().matches("[0-9a-f]{64}"));
        assertEquals("dataset.nlp.documents/v1", version.getArtifactSpecId());
        assertEquals(version.getArtifactSpecId(), session.getArtifactSpecId());
    }

    @Test
    void completeZipUploadPersistsCentralDirectoryFileCount() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        ZipCentralDirectoryReader zipReader = mock(ZipCentralDirectoryReader.class);
        emulateVersionPersistence(versionRepo);
        emulateAssetPersistence(assetRepo);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );
        service.setZipCentralDirectoryReader(zipReader);

        byte[] zip = ZipTestFixtures.zip(
                ZipTestFixtures.deflated("data/one.jsonl", "{}\n")
        );
        DatasetUploadSession session = uploadingSession(null, "new-corpus", "NLP", null, null);
        session.setFileName("corpus.zip");
        session.setFileSize((long) zip.length);
        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1"))
                .thenReturn(List.of(uploadedChunk()));
        when(authContext.currentUserId()).thenReturn(7);
        when(minioClient.getObject(any())).thenAnswer(invocation ->
                new GetObjectResponse(
                        new Headers.Builder().build(),
                        "datasets",
                        null,
                        "corpus.zip",
                        new ByteArrayInputStream(zip)
                )
        );
        when(zipReader.read(anyString(), eq((long) zip.length))).thenReturn(List.of(
                zipEntry("data/", true),
                zipEntry("data/one.jsonl", false),
                zipEntry("data/two.jsonl", false)
        ));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId("dataset-upload-1");
        service.complete(request);

        ArgumentCaptor<DatasetVersion> versionCaptor = ArgumentCaptor.forClass(DatasetVersion.class);
        verify(versionRepo, times(2)).saveAndFlush(versionCaptor.capture());
        assertEquals(2L, versionCaptor.getValue().getFileCount());
        assertEquals("dataset.nlp.documents/v1", versionCaptor.getValue().getArtifactSpecId());
        assertTrue(versionCaptor.getValue().getArtifactSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void initForExistingAssetCarriesTargetAssetAndEnterpriseVersionMetadata() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("pcb");
        asset.setType("CV");
        asset.setCvTaskType("OBJECT_DETECTION");
        asset.setAnnotationFormat("YOLO");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");

        DatasetVersion parent = new DatasetVersion();
        parent.setId("dataset-ver-1");
        parent.setAssetId("dataset-asset-1");
        parent.setVersionNo(1);
        parent.setVersionLabel("v1");
        parent.setStatus("READY");
        parent.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setAssetId("dataset-asset-1");
        req.setFileName("data.zip");
        req.setFileSize(1024L);
        req.setDatasetName("ignored-client-name");
        req.setVersion("legacy-v2");
        req.setDescription("cleaned images");
        req.setChangeLog("removed blurry samples");
        req.setType("CV");
        req.setCvTaskType("OBJECT_DETECTION");
        req.setAnnotationFormat("YOLO");

        when(authContext.currentUserId()).thenReturn(99);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findByIdAndDeletedFalse("dataset-ver-1")).thenReturn(Optional.of(parent));
        when(sessionRepo.findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc(any())).thenReturn(List.of());

        DatasetUploadProgressDto progress = service.init(req);

        assertEquals("dataset-asset-1", progress.getAssetId());
        assertEquals("legacy-v2", progress.getVersionLabel());
        assertEquals("cleaned images", progress.getDescription());
        assertEquals("removed blurry samples", progress.getChangeLog());
        assertEquals("dataset-ver-1", progress.getParentVersionId());

        ArgumentCaptor<DatasetUploadSession> sessionCaptor = ArgumentCaptor.forClass(DatasetUploadSession.class);
        verify(sessionRepo).save(sessionCaptor.capture());
        assertEquals(7, sessionCaptor.getValue().getOwnerUserId());
    }

    @Test
    void initForExistingAssetDefaultsToNextGeneratedVersionLabel() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("pcb");
        asset.setType("CV");
        asset.setCvTaskType("OBJECT_DETECTION");
        asset.setAnnotationFormat("YOLO");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");

        DatasetVersion parent = new DatasetVersion();
        parent.setId("dataset-ver-1");
        parent.setAssetId("dataset-asset-1");
        parent.setStatus("READY");

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setAssetId("dataset-asset-1");
        req.setFileName("data.zip");
        req.setFileSize(1024L);
        req.setDatasetName("ignored-client-name");
        req.setType("CV");
        req.setCvTaskType("OBJECT_DETECTION");
        req.setAnnotationFormat("YOLO");

        when(authContext.currentUserId()).thenReturn(7);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findByIdAndDeletedFalse("dataset-ver-1")).thenReturn(Optional.of(parent));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(1);
        when(sessionRepo.findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc(any())).thenReturn(List.of());

        DatasetUploadProgressDto progress = service.init(req);

        assertEquals(2, progress.getVersionNo());
        assertEquals("v2", progress.getVersionLabel());
    }

    @Test
    void initForMultimodalManifestPersistsStrictManifestFlag() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                mock(DatasetAssetRepository.class),
                mock(DatasetVersionRepository.class),
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setFileName("dataset.zip");
        req.setFileSize(1024L);
        req.setDatasetName("multimodal");
        req.setType("MULTIMODAL");
        req.setSampleGrouping("MANIFEST");
        req.setManifestPath("metadata/manifest.json");
        req.setStrictManifest(true);

        when(authContext.currentUserId()).thenReturn(7);
        when(sessionRepo.findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc(any())).thenReturn(List.of());

        service.init(req);

        ArgumentCaptor<DatasetUploadSession> sessionCaptor =
                ArgumentCaptor.forClass(DatasetUploadSession.class);
        verify(sessionRepo).save(sessionCaptor.capture());
        assertEquals("MANIFEST", sessionCaptor.getValue().getSampleGrouping());
        assertEquals("metadata/manifest.json", sessionCaptor.getValue().getManifestPath());
        assertTrue(sessionCaptor.getValue().getStrictManifest());
    }

    @Test
    void initRetryWithImplicitCurrentParentReusesExistingSession() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("pcb");
        asset.setType("CV");
        asset.setCvTaskType("OBJECT_DETECTION");
        asset.setAnnotationFormat("YOLO");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");

        DatasetVersion parent = new DatasetVersion();
        parent.setId("dataset-ver-1");
        parent.setAssetId("dataset-asset-1");
        parent.setStatus("READY");

        DatasetUploadSession existing = new DatasetUploadSession();
        existing.setId("dataset-upload-existing");
        existing.setFileName("data.zip");
        existing.setFileSize(1024L);
        existing.setDatasetName("pcb");
        existing.setVersion("v2");
        existing.setVersionLabel("v2");
        existing.setVersionNo(2);
        existing.setVersionLabelGenerated(true);
        existing.setType("CV");
        existing.setCvTaskType("OBJECT_DETECTION");
        existing.setAnnotationFormat("YOLO");
        existing.setAssetId("dataset-asset-1");
        existing.setParentVersionId("dataset-ver-1");
        existing.setOwnerUserId(7);
        existing.setStatus("UPLOADING");
        existing.setTotalChunks(1);
        existing.setChunkSize(5 * 1024 * 1024);

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setAssetId("dataset-asset-1");
        req.setFileName("data.zip");
        req.setFileSize(1024L);
        req.setFileFingerprint("sha256:abc");
        req.setType("CV");
        req.setCvTaskType("OBJECT_DETECTION");
        req.setAnnotationFormat("YOLO");

        when(authContext.currentUserId()).thenReturn(7);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findByIdAndDeletedFalse("dataset-ver-1")).thenReturn(Optional.of(parent));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(1);
        when(sessionRepo.findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
                "sha256:abc", List.of("UPLOADING", "FAILED"), 7
        )).thenReturn(Optional.of(existing));
        when(sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-existing")).thenReturn(List.of());

        DatasetUploadProgressDto progress = service.init(req);

        assertEquals("dataset-upload-existing", progress.getUploadId());
        verify(sessionRepo).save(existing);
    }

    @Test
    void initRetryDoesNotReuseExistingSessionWhenStrictManifestDiffers() {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                sessionRepo,
                chunkRepo,
                mock(DatasetAssetRepository.class),
                mock(DatasetVersionRepository.class),
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetUploadSession existing = new DatasetUploadSession();
        existing.setId("dataset-upload-existing");
        existing.setFileName("dataset.zip");
        existing.setFileSize(1024L);
        existing.setDatasetName("multimodal");
        existing.setVersion("v1");
        existing.setVersionLabel("v1");
        existing.setVersionNo(1);
        existing.setVersionLabelGenerated(true);
        existing.setType("MULTIMODAL");
        existing.setSampleGrouping("MANIFEST");
        existing.setManifestPath("manifest.json");
        existing.setStrictManifest(false);
        existing.setOwnerUserId(7);
        existing.setStatus("UPLOADING");
        existing.setTotalChunks(1);
        existing.setChunkSize(5 * 1024 * 1024);

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setFileName("dataset.zip");
        req.setFileSize(1024L);
        req.setFileFingerprint("sha256:abc");
        req.setDatasetName("multimodal");
        req.setType("MULTIMODAL");
        req.setSampleGrouping("MANIFEST");
        req.setStrictManifest(true);

        when(authContext.currentUserId()).thenReturn(7);
        when(sessionRepo.findFirstByFileFingerprintAndStatusInAndOwnerUserIdOrderByUpdatedAtDesc(
                "sha256:abc", List.of("UPLOADING", "FAILED"), 7
        )).thenReturn(Optional.of(existing));
        when(sessionRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc(any())).thenReturn(List.of());

        DatasetUploadProgressDto progress = service.init(req);

        assertTrue(progress.getUploadId().startsWith("dataset-upload-"));
        assertTrue(progress.getStrictManifest());
        ArgumentCaptor<DatasetUploadSession> sessionCaptor = ArgumentCaptor.forClass(DatasetUploadSession.class);
        verify(sessionRepo).save(sessionCaptor.capture());
        DatasetUploadSession saved = sessionCaptor.getValue();
        assertTrue(saved.getStrictManifest());
        assertTrue(saved.getId().startsWith("dataset-upload-"));
        assertTrue(!"dataset-upload-existing".equals(saved.getId()));
    }

    @Test
    void initForNewAssetRejectsParentVersionId() {
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                mock(DatasetUploadSessionRepository.class),
                mock(DatasetUploadChunkRepository.class),
                mock(DatasetAssetRepository.class),
                mock(DatasetVersionRepository.class),
                mock(AuthContext.class),
                mock(MinioDeleteTaskService.class)
        );

        DatasetUploadInitRequest req = new DatasetUploadInitRequest();
        req.setFileName("corpus.txt");
        req.setFileSize(100L);
        req.setDatasetName("new-corpus");
        req.setType("NLP");
        req.setParentVersionId("dataset-ver-other");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.init(req));

        assertTrue(error.getMessage().contains("parentVersionId"));
    }

    @Test
    void initRejectsParentVersionFromAnotherAsset() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                mock(DatasetUploadSessionRepository.class),
                mock(DatasetUploadChunkRepository.class),
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = datasetAsset("dataset-asset-1", "CV", "OBJECT_DETECTION", "YOLO");
        DatasetVersion parent = new DatasetVersion();
        parent.setId("dataset-ver-other");
        parent.setAssetId("dataset-asset-2");

        DatasetUploadInitRequest req = uploadInitRequest(
                "dataset-asset-1", "CV", "OBJECT_DETECTION", "YOLO"
        );
        req.setParentVersionId("dataset-ver-other");

        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findByIdAndDeletedFalse("dataset-ver-other")).thenReturn(Optional.of(parent));
        when(authContext.canAccessOwner(7)).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.init(req));

        assertTrue(error.getMessage().contains("must belong to target asset"));
    }

    @Test
    void folderUploadFailsWhenLockedAssetNoLongerExists() {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                mock(DatasetUploadSessionRepository.class),
                mock(DatasetUploadChunkRepository.class),
                assetRepo,
                mock(DatasetVersionRepository.class),
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("images");
        asset.setType("CV");
        asset.setCvTaskType("IMAGE_CLASSIFICATION");
        asset.setAnnotationFormat("NONE");
        asset.setOwnerUserId(7);

        MultipartFile file = mock(MultipartFile.class);
        when(authContext.canAccessOwner(7)).thenReturn(true);
        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadCvFolder(
                        "dataset-asset-1",
                        null,
                        null,
                        null,
                        "CV",
                        "IMAGE_CLASSIFICATION",
                        "NONE",
                        null,
                        null,
                        null,
                        null,
                        List.of(file),
                        List.of("images/a.png")
                )
        );

        assertTrue(error.getMessage().contains("dataset asset not found"));
    }

    @Test
    void completeForExistingAssetCreatesNextVersionAndUpdatesCurrentVersion() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        emulateVersionPersistence(versionRepo);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("nlp-corpus");
        asset.setType("NLP");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");

        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-1");
        session.setFileName("corpus.txt");
        session.setFileSize(100L);
        session.setChunkSize(5 * 1024 * 1024);
        session.setTotalChunks(1);
        session.setDatasetName("nlp-corpus");
        session.setVersionLabel(null);
        session.setVersion(null);
        session.setType("NLP");
        session.setStatus("UPLOADING");
        session.setAssetId("dataset-asset-1");
        session.setOwnerUserId(7);
        session.setParentVersionId("dataset-ver-1");
        session.setDescription("second version");

        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(authContext.currentUserId()).thenReturn(99);
        DatasetUploadChunk chunk = new DatasetUploadChunk();
        chunk.setUploadId("dataset-upload-1");
        chunk.setPartIndex(0);
        chunk.setObjectName("users/7/datasets/_uploads/dataset-upload-1/part-0");
        chunk.setSizeBytes(100L);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1")).thenReturn(List.of(chunk));
        when(assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(1);
        when(minioClient.getObject(any())).thenReturn(textObject(session.getFileSize()));

        DatasetUploadCompleteRequest completeRequest = new DatasetUploadCompleteRequest();
        completeRequest.setUploadId("dataset-upload-1");
        service.complete(completeRequest);

        ArgumentCaptor<DatasetVersion> versionCaptor = ArgumentCaptor.forClass(DatasetVersion.class);
        verify(versionRepo, times(2)).saveAndFlush(versionCaptor.capture());
        DatasetVersion savedVersion = versionCaptor.getValue();
        assertEquals("dataset-asset-1", savedVersion.getAssetId());
        assertEquals(2, savedVersion.getVersionNo());
        assertEquals("v2", savedVersion.getVersionLabel());
        assertEquals("READY", savedVersion.getStatus());
        assertEquals(7, savedVersion.getOwnerUserId());
        assertEquals(99, savedVersion.getCreatedBy());
        assertEquals("dataset-ver-1", savedVersion.getParentVersionId());
        assertTrue(savedVersion.getStoragePath().contains("/datasets/dataset-asset-1/v2/corpus.txt"));

        ArgumentCaptor<DatasetAsset> assetCaptor = ArgumentCaptor.forClass(DatasetAsset.class);
        verify(assetRepo).saveAndFlush(assetCaptor.capture());
        assertEquals(savedVersion.getId(), assetCaptor.getValue().getCurrentVersionId());
    }

    @Test
    void completeForExistingAssetRefreshesAutoGeneratedLabelWhenFinalVersionNoChanges() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        emulateVersionPersistence(versionRepo);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = new DatasetAsset();
        asset.setId("dataset-asset-1");
        asset.setName("nlp-corpus");
        asset.setType("NLP");
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-2");

        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-1");
        session.setFileName("corpus.txt");
        session.setFileSize(100L);
        session.setChunkSize(5 * 1024 * 1024);
        session.setTotalChunks(1);
        session.setDatasetName("nlp-corpus");
        session.setVersionLabel("v2");
        session.setVersion("v2");
        session.setVersionNo(2);
        session.setVersionLabelGenerated(true);
        session.setType("NLP");
        session.setStatus("UPLOADING");
        session.setAssetId("dataset-asset-1");
        session.setOwnerUserId(7);

        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        DatasetUploadChunk chunk = new DatasetUploadChunk();
        chunk.setUploadId("dataset-upload-1");
        chunk.setPartIndex(0);
        chunk.setObjectName("users/7/datasets/_uploads/dataset-upload-1/part-0");
        chunk.setSizeBytes(100L);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1")).thenReturn(List.of(chunk));
        when(assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(2);
        when(minioClient.getObject(any())).thenReturn(textObject(session.getFileSize()));

        DatasetUploadCompleteRequest completeRequest = new DatasetUploadCompleteRequest();
        completeRequest.setUploadId("dataset-upload-1");
        service.complete(completeRequest);

        ArgumentCaptor<DatasetVersion> versionCaptor = ArgumentCaptor.forClass(DatasetVersion.class);
        verify(versionRepo, times(2)).saveAndFlush(versionCaptor.capture());
        DatasetVersion savedVersion = versionCaptor.getValue();
        assertEquals(3, savedVersion.getVersionNo());
        assertEquals("v3", savedVersion.getVersionLabel());
        assertTrue(savedVersion.getStoragePath().contains("/datasets/dataset-asset-1/v3/corpus.txt"));
    }

    @Test
    void repeatedCompleteReturnsExistingResultWithoutCreatingAnotherVersion() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        MinioClient minioClient = mock(MinioClient.class);
        emulateVersionPersistence(versionRepo);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = datasetAsset("dataset-asset-1", "NLP", null, null);
        DatasetUploadSession session = uploadingSession(
                "dataset-asset-1", "nlp-corpus", "NLP", null, null
        );
        session.setParentVersionId("dataset-ver-1");

        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1"))
                .thenReturn(List.of(uploadedChunk()));
        when(assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(1);
        when(authContext.currentUserId()).thenReturn(7);
        when(minioClient.getObject(any())).thenReturn(textObject(session.getFileSize()));

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId("dataset-upload-1");
        var first = service.complete(request);
        var second = service.complete(request);

        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("versionNo"), second.get("versionNo"));
        assertEquals("dataset.nlp.documents/v1", first.get("artifactSpecId"));
        assertEquals(first.get("artifactSpecId"), second.get("artifactSpecId"));
        verify(versionRepo, times(2)).saveAndFlush(any(DatasetVersion.class));
        verify(minioClient, times(1)).composeObject(any());
    }

    @Test
    void completeRejectsDuplicateVersionLabelBeforeComposingObject() throws Exception {
        DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        DatasetUploadChunkRepository chunkRepo = mock(DatasetUploadChunkRepository.class);
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        MinioClient minioClient = mock(MinioClient.class);
        DatasetUploadService service = new DatasetUploadService(
                minioClient,
                minioConfig(),
                sessionRepo,
                chunkRepo,
                assetRepo,
                versionRepo,
                mock(AuthContext.class),
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = datasetAsset("dataset-asset-1", "NLP", null, null);
        DatasetUploadSession session = uploadingSession(
                "dataset-asset-1", "nlp-corpus", "NLP", null, null
        );
        session.setVersion("release");
        session.setVersionLabel("release");
        session.setVersionLabelGenerated(false);

        when(sessionRepo.findById("dataset-upload-1")).thenReturn(Optional.of(session));
        when(sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any())).thenReturn(1);
        when(chunkRepo.findByUploadIdOrderByPartIndexAsc("dataset-upload-1"))
                .thenReturn(List.of(uploadedChunk()));
        when(assetRepo.findByIdAndDeletedFalseForUpdate("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(versionRepo.findMaxVersionNoByAssetId("dataset-asset-1")).thenReturn(1);
        when(versionRepo.existsByAssetIdAndVersion("dataset-asset-1", "release")).thenReturn(true);

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId("dataset-upload-1");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.complete(request)
        );

        assertTrue(error.getMessage().contains("version label"));
        verify(minioClient, never()).composeObject(any());
        verify(versionRepo, never()).saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void initRejectsTypeMismatchForExistingAsset() {
        assertExistingAssetMetadataMismatch(
                "NLP", null, null, "type mismatch"
        );
    }

    @Test
    void initRejectsCvTaskTypeMismatchForExistingAsset() {
        assertExistingAssetMetadataMismatch(
                "CV", "IMAGE_CLASSIFICATION", "YOLO", "cvTaskType mismatch"
        );
    }

    @Test
    void initRejectsAnnotationFormatMismatchForExistingAsset() {
        assertExistingAssetMetadataMismatch(
                "CV", "OBJECT_DETECTION", "VOC", "annotationFormat mismatch"
        );
    }

    private void assertExistingAssetMetadataMismatch(
            String type,
            String cvTaskType,
            String annotationFormat,
            String expectedMessage
    ) {
        DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        AuthContext authContext = mock(AuthContext.class);
        DatasetUploadService service = new DatasetUploadService(
                mock(MinioClient.class),
                minioConfig(),
                mock(DatasetUploadSessionRepository.class),
                mock(DatasetUploadChunkRepository.class),
                assetRepo,
                mock(DatasetVersionRepository.class),
                authContext,
                mock(MinioDeleteTaskService.class)
        );

        DatasetAsset asset = datasetAsset("dataset-asset-1", "CV", "OBJECT_DETECTION", "YOLO");
        DatasetUploadInitRequest request =
                uploadInitRequest("dataset-asset-1", type, cvTaskType, annotationFormat);

        when(assetRepo.findByIdAndDeletedFalse("dataset-asset-1")).thenReturn(Optional.of(asset));
        when(authContext.canAccessOwner(7)).thenReturn(true);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.init(request)
        );

        assertTrue(error.getMessage().contains(expectedMessage));
    }

    private DatasetUploadInitRequest uploadInitRequest(
            String assetId,
            String type,
            String cvTaskType,
            String annotationFormat
    ) {
        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setAssetId(assetId);
        request.setFileName("data.zip");
        request.setFileSize(1024L);
        request.setType(type);
        request.setCvTaskType(cvTaskType);
        request.setAnnotationFormat(annotationFormat);
        return request;
    }

    private DatasetAsset datasetAsset(
            String id,
            String type,
            String cvTaskType,
            String annotationFormat
    ) {
        DatasetAsset asset = new DatasetAsset();
        asset.setId(id);
        asset.setName("dataset");
        asset.setType(type);
        asset.setCvTaskType(cvTaskType);
        asset.setAnnotationFormat(annotationFormat);
        asset.setOwnerUserId(7);
        asset.setCurrentVersionId("dataset-ver-1");
        return asset;
    }

    private DatasetUploadSession uploadingSession(
            String assetId,
            String datasetName,
            String type,
            String cvTaskType,
            String annotationFormat
    ) {
        DatasetUploadSession session = new DatasetUploadSession();
        session.setId("dataset-upload-1");
        session.setFileName("corpus.txt");
        session.setFileSize(100L);
        session.setChunkSize(5 * 1024 * 1024);
        session.setTotalChunks(1);
        session.setDatasetName(datasetName);
        session.setVersionLabelGenerated(true);
        session.setType(type);
        session.setCvTaskType(cvTaskType);
        session.setAnnotationFormat(annotationFormat);
        session.setStatus("UPLOADING");
        session.setAssetId(assetId);
        session.setOwnerUserId(7);
        return session;
    }

    private DatasetUploadChunk uploadedChunk() {
        DatasetUploadChunk chunk = new DatasetUploadChunk();
        chunk.setUploadId("dataset-upload-1");
        chunk.setPartIndex(0);
        chunk.setObjectName("users/7/datasets/_uploads/dataset-upload-1/part-0");
        chunk.setSizeBytes(100L);
        return chunk;
    }

    private ZipEntryInfo zipEntry(String path, boolean directory) {
        return new ZipEntryInfo(
                path,
                path,
                8,
                1L,
                1L,
                1L,
                0L,
                30L,
                false,
                directory
        );
    }

    private GetObjectResponse textObject(long size) {
        byte[] bytes = "a".repeat(Math.toIntExact(size)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new GetObjectResponse(
                new Headers.Builder().build(),
                "datasets",
                null,
                "corpus.txt",
                new ByteArrayInputStream(bytes)
        );
    }

    private List<String> emulateVersionPersistence(DatasetVersionRepository repository) {
        AtomicReference<DatasetVersion> stored = new AtomicReference<>();
        List<String> statuses = new ArrayList<>();
        when(repository.saveAndFlush(any(DatasetVersion.class))).thenAnswer(invocation -> {
            DatasetVersion version = invocation.getArgument(0);
            statuses.add(version.getStatus());
            stored.set(version);
            return version;
        });
        when(repository.findByIdAndDeletedFalseForUpdate(anyString())).thenAnswer(invocation -> {
            DatasetVersion version = stored.get();
            return version != null && invocation.getArgument(0).equals(version.getId())
                    ? Optional.of(version)
                    : Optional.empty();
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            DatasetVersion version = stored.get();
            return version != null && invocation.getArgument(0).equals(version.getId())
                    ? Optional.of(version)
                    : Optional.empty();
        });
        return statuses;
    }

    private void emulateAssetPersistence(DatasetAssetRepository repository) {
        AtomicReference<DatasetAsset> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any(DatasetAsset.class))).thenAnswer(invocation -> {
            DatasetAsset asset = invocation.getArgument(0);
            stored.set(asset);
            return asset;
        });
        when(repository.findByIdAndDeletedFalseForUpdate(anyString())).thenAnswer(invocation -> {
            DatasetAsset asset = stored.get();
            return asset != null && invocation.getArgument(0).equals(asset.getId())
                    ? Optional.of(asset)
                    : Optional.empty();
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            DatasetAsset asset = stored.get();
            return asset != null && invocation.getArgument(0).equals(asset.getId())
                    ? Optional.of(asset)
                    : Optional.empty();
        });
    }

    private MinioConfig minioConfig() {
        MinioConfig config = new MinioConfig();
        config.setBucket("datasets");
        return config;
    }
}
