package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.MinioConfig;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
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
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetAppendPackageUploadTest {

    @Test
    void initializesAppendSessionForOwnedMultimodalDraft() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedDraft();
        AtomicReference<DatasetUploadSession> saved = new AtomicReference<>();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            DatasetUploadSession value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });

        DatasetPackageAppendInitRequest request = fixture.request();
        var result = fixture.service.initAppendPackage(fixture.version.getId(), request);

        assertEquals("APPEND_PACKAGE", saved.get().getUploadPurpose());
        assertEquals(fixture.version.getId(), saved.get().getVersionId());
        assertEquals(fixture.asset.getId(), saved.get().getAssetId());
        assertEquals("MANIFEST", saved.get().getSampleGrouping());
        assertEquals("manifest.json", saved.get().getManifestPath());
        assertEquals(6 * 1024 * 1024, result.getChunkSize());
        assertEquals(8534, result.getTotalChunks());
        verify(fixture.auditService).recordAppendInit(
                fixture.asset,
                fixture.version,
                saved.get()
        );
    }

    @Test
    void initializesAutoDirectoryAppendWithoutManifestPath() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedDraft();
        AtomicReference<DatasetUploadSession> saved = new AtomicReference<>();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            DatasetUploadSession value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setSampleGrouping("AUTO_DIRECTORY");
        request.setManifestPath(null);

        fixture.service.initAppendPackage(fixture.version.getId(), request);

        assertEquals("AUTO_DIRECTORY", saved.get().getSampleGrouping());
        assertNull(saved.get().getManifestPath());
    }

    @Test
    void initializesSingleModalAppendWithoutSampleGrouping() {
        Fixture fixture = new Fixture();
        fixture.asset.setType("CV");
        fixture.version.setCvTaskType("OBJECT_DETECTION");
        fixture.version.setAnnotationFormat("COCO");
        fixture.stubOwnedDraft();
        AtomicReference<DatasetUploadSession> saved = new AtomicReference<>();
        when(fixture.sessionRepo.save(any())).thenAnswer(invocation -> {
            DatasetUploadSession value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setSampleGrouping(null);
        request.setManifestPath(null);

        fixture.service.initAppendPackage(fixture.version.getId(), request);

        assertEquals("CV", saved.get().getType());
        assertEquals("OBJECT_DETECTION", saved.get().getCvTaskType());
        assertEquals("COCO", saved.get().getAnnotationFormat());
        assertNull(saved.get().getSampleGrouping());
        assertNull(saved.get().getManifestPath());
    }

    @Test
    void rejectsSingleModalAppendSampleGrouping() {
        Fixture fixture = new Fixture();
        fixture.asset.setType("CV");
        fixture.stubOwnedDraft();
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setSampleGrouping("AUTO_DIRECTORY");
        request.setManifestPath(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(fixture.version.getId(), request)
        );

        assertTrue(error.getMessage().contains("仅 MULTIMODAL 数据集支持 sampleGrouping"));
        verify(fixture.sessionRepo, never()).save(any());
    }

    @Test
    void rejectsManifestPathForAutoDirectoryAppend() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedDraft();
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setSampleGrouping("AUTO_DIRECTORY");
        request.setManifestPath("manifest.json");

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(fixture.version.getId(), request)
        );
        verify(fixture.sessionRepo, never()).save(any());
    }

    @Test
    void rejectsReadyVersionAndNonManifestAppend() {
        Fixture fixture = new Fixture();
        fixture.version.setStatus("READY");
        fixture.stubOwnedDraft();
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(
                        fixture.version.getId(),
                        fixture.request()
                )
        );

        fixture.version.setStatus("DRAFT");
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setSampleGrouping("OTHER");
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(fixture.version.getId(), request)
        );
        verify(fixture.sessionRepo, never()).save(any());
    }

    @Test
    void rejectsNonZipAndAnotherUsersDraft() {
        Fixture fixture = new Fixture();
        fixture.stubOwnedDraft();
        DatasetPackageAppendInitRequest request = fixture.request();
        request.setFileName("append.tar");
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(fixture.version.getId(), request)
        );

        request.setFileName("append.zip");
        when(fixture.authContext.canAccessOwner(fixture.asset.getOwnerUserId()))
                .thenReturn(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.service.initAppendPackage(fixture.version.getId(), request)
        );
        verify(fixture.sessionRepo, never()).save(any());
    }

    @Test
    void completesAppendWithPackageRelationAndPendingImportJob() throws Exception {
        assertCompletesAppend("MANIFEST", "manifest.json");
    }

    @Test
    void completesAutoDirectoryAppendWithoutManifestPath() throws Exception {
        assertCompletesAppend("AUTO_DIRECTORY", null);
    }

    private void assertCompletesAppend(
            String sampleGrouping,
            String manifestPath
    ) throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.appendSession();
        session.setSampleGrouping(sampleGrouping);
        session.setManifestPath(manifestPath);
        DatasetUploadChunk chunk = fixture.chunk(session);
        AtomicReference<DatasetPackage> savedPackage = new AtomicReference<>();
        AtomicReference<DatasetVersionPackage> savedRelation = new AtomicReference<>();
        AtomicReference<ImportJob> savedJob = new AtomicReference<>();

        fixture.stubOwnedDraft();
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.sessionRepo.updateStatusIfCurrent(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertTrue(
                            fixture.activeTransactions.get() > 0,
                            "append upload status claim must run in a transaction"
                    );
                    return 1;
                });
        when(fixture.sessionRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of(chunk));
        when(fixture.versionRepo.findByIdAndDeletedFalseForUpdate(fixture.version.getId()))
                .thenReturn(Optional.of(fixture.version));
        when(fixture.versionPackageRepo.findMaxPackageOrderByDatasetVersionId(
                fixture.version.getId()
        )).thenReturn(2);
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetPackage value = invocation.getArgument(0);
            savedPackage.set(value);
            return value;
        });
        when(fixture.versionPackageRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            DatasetVersionPackage value = invocation.getArgument(0);
            savedRelation.set(value);
            return value;
        });
        when(fixture.importJobRepo.saveAndFlush(any())).thenAnswer(invocation -> {
            ImportJob value = invocation.getArgument(0);
            savedJob.set(value);
            return value;
        });
        when(fixture.importJobRepo.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedJob.get())
        );
        when(fixture.versionPackageRepo.findByDatasetVersionIdAndPackageId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(savedRelation.get()));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(session.getFileSize());
        when(fixture.minioClient.statObject(any())).thenReturn(stat);

        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());
        Map<String, Object> result =
                fixture.service.completeAppendPackage(fixture.version.getId(), request);

        assertEquals("PENDING", savedPackage.get().getStatus());
        assertEquals(fixture.asset.getId(), savedPackage.get().getDatasetAssetId());
        assertEquals("append.zip", savedPackage.get().getFileName());
        assertEquals(manifestPath, savedPackage.get().getManifestPath());
        assertTrue(savedPackage.get().getStoragePath().contains(
                "/packages/append-upload-1/append.zip"
        ));
        assertEquals("APPEND", savedRelation.get().getPackageRole());
        assertEquals(3, savedRelation.get().getPackageOrder());
        assertEquals(savedPackage.get().getId(), savedJob.get().getPackageId());
        assertEquals(fixture.version.getId(), savedJob.get().getDatasetVersionId());
        assertEquals("PENDING", savedJob.get().getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertEquals("ready-1", fixture.asset.getCurrentVersionId());
        assertEquals("APPEND", result.get("packageRole"));
        assertEquals("DRAFT", result.get("versionStatus"));
        assertEquals("PENDING", result.get("importStatus"));
        assertEquals(savedPackage.get().getId(), result.get("packageId"));
        assertEquals(savedJob.get().getId(), result.get("importJobId"));
        verify(fixture.importJobLauncher).launch(savedJob.get().getId());
        verify(fixture.auditService).recordAppendCompleted(
                fixture.asset,
                fixture.version,
                session,
                savedPackage.get(),
                savedJob.get()
        );
    }

    @Test
    void completedAppendIsIdempotentAndProgressHidesPackageStorage() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.appendSession();
        session.setStatus("COMPLETED");
        session.setStoragePath(
                "users/7/datasets/asset-1/v2/packages/append-upload-1/append.zip"
        );
        session.setImportJobId("ijob-1");
        ImportJob job = new ImportJob();
        job.setId("ijob-1");
        job.setDatasetVersionId(fixture.version.getId());
        job.setPackageId("package-1");
        job.setStatus("PENDING");
        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId(fixture.version.getId());
        relation.setPackageId("package-1");
        relation.setPackageRole("APPEND");
        relation.setPackageOrder(1);
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(fixture.importJobRepo.findById(job.getId())).thenReturn(Optional.of(job));
        when(fixture.versionPackageRepo.findByDatasetVersionIdAndPackageId(
                fixture.version.getId(),
                job.getPackageId()
        )).thenReturn(Optional.of(relation));
        fixture.stubOwnedDraft();
        when(fixture.chunkRepo.findByUploadIdOrderByPartIndexAsc(session.getId()))
                .thenReturn(List.of());
        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        Map<String, Object> result =
                fixture.service.completeAppendPackage(fixture.version.getId(), request);
        var progress = fixture.service.getProgress(session.getId());

        assertEquals("package-1", result.get("packageId"));
        assertFalse(new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(progress)
                .contains("storagePath"));
        verify(fixture.minioClient, never()).composeObject(any());
        verify(fixture.importJobLauncher).launch(job.getId());
    }

    @Test
    void oldCompleteRejectsAppendSession() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadSession session = fixture.appendSession();
        when(fixture.sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(session.getId());

        assertThrows(IllegalArgumentException.class, () -> fixture.service.complete(request));

        verify(fixture.minioClient, never()).composeObject(any());
    }

    private static final class Fixture {
        private final MinioClient minioClient = mock(MinioClient.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final ImportJobLauncher importJobLauncher = mock(ImportJobLauncher.class);
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        private final AtomicInteger activeTransactions = new AtomicInteger();
        private final DatasetAsset asset = asset();
        private final DatasetVersion version = version();
        private final DatasetUploadService service;

        private Fixture() {
            MinioConfig config = new MinioConfig();
            config.setBucket("datasets");
            when(authContext.currentUserId()).thenReturn(7);
            when(authContext.canAccessOwner(any())).thenReturn(true);
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
                    transactionManager,
                    auditService
            );
            service.setImportJobLauncher(importJobLauncher);
        }

        private void stubOwnedDraft() {
            when(versionRepo.findByIdAndDeletedFalse(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalse(asset.getId()))
                    .thenReturn(Optional.of(asset));
        }

        private DatasetPackageAppendInitRequest request() {
            DatasetPackageAppendInitRequest request = new DatasetPackageAppendInitRequest();
            request.setFileName("append.zip");
            request.setFileSize(50L * 1024 * 1024 * 1024);
            request.setSampleGrouping("MANIFEST");
            request.setManifestPath("manifest.json");
            return request;
        }

        private DatasetUploadSession appendSession() {
            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("append-upload-1");
            session.setUploadPurpose("APPEND_PACKAGE");
            session.setFileName("append.zip");
            session.setFileSize(1024L);
            session.setChunkSize(5 * 1024 * 1024);
            session.setTotalChunks(1);
            session.setDatasetName(asset.getName());
            session.setVersion(version.getVersion());
            session.setVersionLabel(version.getVersionLabel());
            session.setVersionNo(version.getVersionNo());
            session.setType("MULTIMODAL");
            session.setSampleGrouping("MANIFEST");
            session.setManifestPath("manifest.json");
            session.setStatus("UPLOADING");
            session.setAssetId(asset.getId());
            session.setVersionId(version.getId());
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
            chunk.setObjectName("users/7/datasets/_uploads/append-upload-1/part-0");
            chunk.setSizeBytes(session.getFileSize());
            return chunk;
        }

        private static DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setName("multimodal");
            value.setType("MULTIMODAL");
            value.setOwnerUserId(7);
            value.setCurrentVersionId("ready-1");
            value.setDeleted(false);
            return value;
        }

        private static DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("draft-2");
            value.setAssetId("asset-1");
            value.setVersion("v2");
            value.setVersionLabel("v2");
            value.setVersionNo(2);
            value.setStatus("DRAFT");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }
    }
}
