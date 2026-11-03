package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.config.MinioConfig;
import com.tss.platform.controller.DatasetController;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetSampleListItemDto;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestAnnotation;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalDatasetLifecycleAcceptanceTest {

    @ParameterizedTest(name = "{0} lifecycle")
    @ValueSource(strings = {"MANIFEST", "AUTO_DIRECTORY"})
    @SuppressWarnings("unchecked")
    void completesReadyDraftAppendMutationPublishAndFileReadLifecycle(
            String sampleGrouping
    ) throws Exception {
        Fixture fixture = new Fixture(sampleGrouping);

        fixture.importJobService.execute(Fixture.INITIAL_JOB_ID);

        DatasetVersion v1 = fixture.versions.get(Fixture.V1_ID);
        assertEquals("READY", v1.getStatus());
        assertEquals(Fixture.V1_ID, fixture.asset.getCurrentVersionId());
        assertEquals(2, fixture.activeSamples(Fixture.V1_ID).size());

        DatasetWorkspaceDraftDto draftResult =
                fixture.workspaceService.createDraft(Fixture.V1_ID);
        String v2Id = draftResult.getDraftVersionId();
        DatasetVersion v2 = fixture.versions.get(v2Id);
        assertEquals("DRAFT", v2.getStatus());
        assertEquals(Fixture.V1_ID, v2.getParentVersionId());
        assertEquals(Fixture.V1_ID, fixture.asset.getCurrentVersionId());
        assertEquals(2, fixture.activeSamples(v2Id).size());
        assertEquals(1, fixture.packages.size());

        DatasetSample inheritedFirst = fixture.sample(v2Id, "scene-001");
        DatasetSample inheritedSecond = fixture.sample(v2Id, "scene-002");
        assertNotEquals(
                fixture.sample(Fixture.V1_ID, "scene-001").getId(),
                inheritedFirst.getId()
        );

        DatasetPackageAppendInitRequest appendRequest =
                new DatasetPackageAppendInitRequest();
        appendRequest.setFileName("append.zip");
        appendRequest.setFileSize(1024L);
        appendRequest.setSampleGrouping(sampleGrouping);
        if ("MANIFEST".equals(sampleGrouping)) {
            appendRequest.setManifestPath("manifest.json");
        }
        DatasetUploadProgressDto appendInit =
                fixture.uploadService.initAppendPackage(v2Id, appendRequest);
        fixture.addUploadedChunk(appendInit.getUploadId(), appendRequest.getFileSize());

        DatasetUploadCompleteRequest completeRequest =
                new DatasetUploadCompleteRequest();
        completeRequest.setUploadId(appendInit.getUploadId());
        Map<String, Object> appendComplete =
                fixture.uploadService.completeAppendPackage(v2Id, completeRequest);
        String appendPackageId = String.valueOf(appendComplete.get("packageId"));
        String appendJobId = String.valueOf(appendComplete.get("importJobId"));
        assertEquals("PENDING", fixture.packages.get(appendPackageId).getStatus());

        fixture.importJobService.execute(appendJobId);

        DatasetSample appended = fixture.sample(v2Id, "scene-003");
        assertEquals("SUCCESS", fixture.jobs.get(appendJobId).getStatus());
        assertEquals("READY", fixture.packages.get(appendPackageId).getStatus());
        assertEquals(appendPackageId, appended.getCreatedByPackageId());
        assertEquals(3, fixture.activeSamples(v2Id).size());
        assertEquals("DRAFT", v2.getStatus());

        fixture.mutationService.deleteSample(inheritedFirst.getId());
        assertTrue(inheritedFirst.getDeleted());
        fixture.mutationService.restoreSample(inheritedFirst.getId());
        assertFalse(inheritedFirst.getDeleted());
        fixture.mutationService.deleteSample(appended.getId());
        assertTrue(appended.getDeleted());

        DatasetWorkspacePublishDto publishResult =
                fixture.publishService.publish(v2Id);

        assertEquals("READY", publishResult.getStatus());
        assertEquals(v2Id, fixture.asset.getCurrentVersionId());
        assertEquals("READY", v2.getStatus());
        assertEquals("READY", v1.getStatus());
        assertEquals(2, fixture.activeSamples(Fixture.V1_ID).size());
        assertEquals(2, fixture.activeSamples(v2Id).size());
        assertTrue(fixture.sample(v2Id, "scene-003").getDeleted());

        PageResponse<DatasetSampleListItemDto> readySamples =
                fixture.sampleService.listSamples(v2Id, 1, 20);
        assertEquals(2, readySamples.getTotal());
        assertEquals(
                Set.of("scene-001", "scene-002"),
                readySamples.getData().stream()
                        .map(DatasetSampleListItemDto::getExternalId)
                        .collect(Collectors.toSet())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.workspaceSampleService.listSamples(v2Id, 1, 20, false)
        );

        DatasetSampleData imageData =
                fixture.data(inheritedSecond.getId(), "IMAGE");
        byte[] imageBytes = "IMAGE".getBytes(StandardCharsets.UTF_8);
        SampleFileService.SampleFileStream preview =
                fixture.fileService.openDataPreview(imageData.getId());
        SampleFileService.SampleFileStream download =
                fixture.fileService.openDataDownload(imageData.getId());
        assertArrayEquals(imageBytes, preview.inputStream().readAllBytes());
        assertArrayEquals(imageBytes, download.inputStream().readAllBytes());

        DatasetSampleData videoData =
                fixture.data(inheritedFirst.getId(), "VIDEO");
        SampleFileService.SampleFileStream videoRange =
                fixture.fileService.openDataPreview(videoData.getId(), "bytes=2-5");
        assertTrue(videoRange.partial());
        assertEquals(2L, videoRange.rangeStart());
        assertEquals(5L, videoRange.rangeEnd());
        assertArrayEquals(
                "DEO1".getBytes(StandardCharsets.UTF_8),
                videoRange.inputStream().readAllBytes()
        );

        v2.setFileCount(null);
        assertNull(v2.getFileCount());
        ApiResponse<Map<String, Object>> listResponse =
                fixture.datasetController.list(null, null, 1, null, 20);
        List<Map<String, Object>> listData =
                (List<Map<String, Object>>) listResponse.getData().get("data");
        assertEquals(1, listData.size());
        assertEquals(v2Id, listData.get(0).get("currentVersionId"));
        assertEquals("READY", listData.get(0).get("versionStatus"));
        assertNull(listData.get(0).get("latestDraftVersionId"));
        assertNull(listData.get(0).get("importJobId"));
        long expectedFileCount = fixture.metadataFileCount(v2Id);
        assertTrue(expectedFileCount > 0);
        assertEquals(expectedFileCount, listData.get(0).get("currentVersionFileCount"));
        assertEquals(expectedFileCount, listData.get(0).get("fileCount"));

        DatasetWorkspaceDraftDto conflictDraft =
                fixture.workspaceService.createDraft(v2Id);
        String conflictDraftId = conflictDraft.getDraftVersionId();
        int inheritedCount = fixture.activeSamples(conflictDraftId).size();
        DatasetPackageAppendInitRequest conflictRequest =
                new DatasetPackageAppendInitRequest();
        conflictRequest.setFileName("conflict.zip");
        conflictRequest.setFileSize(1024L);
        conflictRequest.setSampleGrouping(sampleGrouping);
        if ("MANIFEST".equals(sampleGrouping)) {
            conflictRequest.setManifestPath("manifest.json");
        }
        DatasetUploadProgressDto conflictInit =
                fixture.uploadService.initAppendPackage(
                        conflictDraftId,
                        conflictRequest
                );
        fixture.addUploadedChunk(
                conflictInit.getUploadId(),
                conflictRequest.getFileSize()
        );
        DatasetUploadCompleteRequest conflictCompleteRequest =
                new DatasetUploadCompleteRequest();
        conflictCompleteRequest.setUploadId(conflictInit.getUploadId());
        Map<String, Object> conflictComplete =
                fixture.uploadService.completeAppendPackage(
                        conflictDraftId,
                        conflictCompleteRequest
                );
        String conflictJobId =
                String.valueOf(conflictComplete.get("importJobId"));
        String conflictPackageId =
                String.valueOf(conflictComplete.get("packageId"));
        fixture.stubConflictPlan(sampleGrouping);

        fixture.importJobService.execute(conflictJobId);

        assertEquals("FAILED", fixture.jobs.get(conflictJobId).getStatus());
        assertEquals(
                inheritedCount,
                fixture.activeSamples(conflictDraftId).size()
        );
        assertEquals(
                "FAILED",
                fixture.packages.get(conflictPackageId).getStatus()
        );
    }

    private static final class Fixture {
        private static final String ASSET_ID = "asset-1";
        private static final String V1_ID = "version-1";
        private static final String PRIMARY_PACKAGE_ID = "package-primary";
        private static final String INITIAL_JOB_ID = "job-primary";
        private static final String PRIMARY_PATH =
                "users/7/datasets/asset-1/v1/primary.zip";

        private final DatasetAssetRepository assetRepo =
                mock(DatasetAssetRepository.class);
        private final DatasetVersionRepository versionRepo =
                mock(DatasetVersionRepository.class);
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final DatasetUploadChunkRepository chunkRepo =
                mock(DatasetUploadChunkRepository.class);
        private final ImportJobRepository jobRepo =
                mock(ImportJobRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final AuthContext authContext = mock(AuthContext.class);
        private final EntityManager entityManager = mock(EntityManager.class);
        private final MinioClient minioClient = mock(MinioClient.class);
        private final MinioService minioService = mock(MinioService.class);
        private final MinioDeleteTaskService deleteTaskService =
                mock(MinioDeleteTaskService.class);
        private final ImportJobLauncher importJobLauncher =
                mock(ImportJobLauncher.class);
        private final ZipCentralDirectoryReader zipReader =
                mock(ZipCentralDirectoryReader.class);
        private final ManifestZipReader manifestReader =
                mock(ManifestZipReader.class);
        private final ManifestParser manifestParser = mock(ManifestParser.class);
        private final AutoDirectoryManifestBuilder autoDirectoryManifestBuilder =
                mock(AutoDirectoryManifestBuilder.class);
        private final PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);

        private final Map<String, DatasetVersion> versions = new LinkedHashMap<>();
        private final Map<String, DatasetPackage> packages = new LinkedHashMap<>();
        private final List<DatasetVersionPackage> relations = new ArrayList<>();
        private final Map<String, DatasetUploadSession> sessions =
                new LinkedHashMap<>();
        private final List<DatasetUploadChunk> chunks = new ArrayList<>();
        private final Map<String, ImportJob> jobs = new LinkedHashMap<>();
        private final Map<String, DatasetSample> samples = new LinkedHashMap<>();
        private final Map<String, DatasetSampleData> dataItems =
                new LinkedHashMap<>();
        private final Map<String, DatasetAnnotation> annotations =
                new LinkedHashMap<>();
        private final Map<String, List<StoredSegment>> storedSegments =
                new HashMap<>();

        private final DatasetAsset asset = asset();
        private final DatasetWorkspaceService workspaceService;
        private final DatasetUploadService uploadService;
        private final ImportJobService importJobService;
        private final DatasetWorkspaceSampleMutationService mutationService;
        private final DatasetWorkspacePublishService publishService;
        private final SampleService sampleService;
        private final DatasetWorkspaceSampleService workspaceSampleService;
        private final SampleFileService fileService;
        private final DatasetController datasetController;

        private Fixture(String sampleGrouping) throws Exception {
            seedInitialUpload(sampleGrouping);
            stubInfrastructure();

            DatasetWorkspaceMaterializer materializer =
                    new DatasetWorkspaceMaterializer(
                            packageRepo,
                            versionPackageRepo,
                            sampleRepo,
                            dataRepo,
                            annotationRepo,
                            zipReader,
                            new SingleModalImportPlanBuilder(),
                            entityManager,
                            new ObjectMapper()
                    );
            workspaceService = new DatasetWorkspaceService(
                    versionRepo,
                    assetRepo,
                    authContext,
                    new DatasetVersionLifecycleService(versionRepo),
                    materializer
            );

            MinioConfig minioConfig = new MinioConfig();
            minioConfig.setBucket("datasets");
            uploadService = new DatasetUploadService(
                    minioClient,
                    minioConfig,
                    sessionRepo,
                    chunkRepo,
                    assetRepo,
                    versionRepo,
                    packageRepo,
                    versionPackageRepo,
                    jobRepo,
                    authContext,
                    deleteTaskService,
                    transactionManager
            );
            uploadService.setImportJobLauncher(importJobLauncher);

            importJobService = new ImportJobService(
                    jobRepo,
                    versionRepo,
                    assetRepo,
                    packageRepo,
                    versionPackageRepo,
                    sessionRepo,
                    sampleRepo,
                    dataRepo,
                    annotationRepo,
                    minioService,
                    zipReader,
                    manifestReader,
                    manifestParser,
                    autoDirectoryManifestBuilder,
                    new SingleModalImportPlanBuilder(),
                    transactionManager
            );
            mutationService = new DatasetWorkspaceSampleMutationService(
                    sampleRepo,
                    versionRepo,
                    assetRepo,
                    authContext
            );
            publishService = new DatasetWorkspacePublishService(
                    versionRepo,
                    assetRepo,
                    jobRepo,
                    versionPackageRepo,
                    packageRepo,
                    sampleRepo,
                    dataRepo,
                    annotationRepo,
                    authContext
            );
            sampleService = new SampleService(
                    sampleRepo,
                    dataRepo,
                    annotationRepo,
                    versionRepo,
                    assetRepo,
                    authContext
            );
            workspaceSampleService = new DatasetWorkspaceSampleService(
                    sampleRepo,
                    dataRepo,
                    annotationRepo,
                    versionRepo,
                    assetRepo,
                    authContext
            );
            fileService = new SampleFileService(
                    dataRepo,
                    sampleRepo,
                    annotationRepo,
                    versionRepo,
                    assetRepo,
                    packageRepo,
                    versionPackageRepo,
                    minioService,
                    authContext
            );
            datasetController = new DatasetController(
                    assetRepo,
                    versionRepo,
                    jobRepo,
                    authContext,
                    new DatasetVersionFileCountService(
                            dataRepo,
                            annotationRepo,
                            zipReader,
                            versionRepo
                    )
            );
        }

        private void seedInitialUpload(String sampleGrouping) {
            DatasetVersion version = new DatasetVersion();
            version.setId(V1_ID);
            version.setAssetId(ASSET_ID);
            version.setVersion("v1");
            version.setVersionLabel("v1");
            version.setVersionNo(1);
            version.setStatus("DRAFT");
            version.setStoragePath(PRIMARY_PATH);
            version.setFileName("primary.zip");
            version.setSizeBytes(4096L);
            version.setOwnerUserId(7);
            version.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            version.setDeleted(false);
            versions.put(version.getId(), version);

            DatasetPackage primary = new DatasetPackage();
            primary.setId(PRIMARY_PACKAGE_ID);
            primary.setDatasetAssetId(ASSET_ID);
            primary.setStoragePath(PRIMARY_PATH);
            primary.setFileName("primary.zip");
            primary.setSizeBytes(4096L);
            primary.setManifestPath(
                    "MANIFEST".equals(sampleGrouping) ? "manifest.json" : null
            );
            primary.setStatus("READY");
            primary.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            primary.setDeleted(false);
            packages.put(primary.getId(), primary);

            DatasetVersionPackage relation = new DatasetVersionPackage();
            relation.setDatasetVersionId(V1_ID);
            relation.setPackageId(PRIMARY_PACKAGE_ID);
            relation.setPackageRole("PRIMARY");
            relation.setPackageOrder(0);
            relation.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            relations.add(relation);

            ImportJob job = new ImportJob();
            job.setId(INITIAL_JOB_ID);
            job.setDatasetVersionId(V1_ID);
            job.setPackageId(PRIMARY_PACKAGE_ID);
            job.setStatus("PENDING");
            job.setProgress(0);
            job.setImportedSamples(0);
            job.setOwnerUserId(7);
            job.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            job.setUpdatedAt(job.getCreatedAt());
            jobs.put(job.getId(), job);

            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("upload-primary");
            session.setUploadPurpose("PRIMARY");
            session.setVersionId(V1_ID);
            session.setAssetId(ASSET_ID);
            session.setImportJobId(INITIAL_JOB_ID);
            session.setType("MULTIMODAL");
            session.setSampleGrouping(sampleGrouping);
            session.setManifestPath(
                    "MANIFEST".equals(sampleGrouping) ? "manifest.json" : null
            );
            session.setStatus("COMPLETED");
            session.setOwnerUserId(7);
            sessions.put(session.getId(), session);

            storedSegments.put(PRIMARY_PATH, List.of(
                    new StoredSegment(
                            1000L,
                            "VIDEO12".getBytes(StandardCharsets.UTF_8)
                    ),
                    new StoredSegment(
                            2000L,
                            "IMAGE".getBytes(StandardCharsets.UTF_8)
                    ),
                    new StoredSegment(
                            2100L,
                            "{}".getBytes(StandardCharsets.UTF_8)
                    )
            ));
        }

        private void stubInfrastructure() throws Exception {
            when(authContext.currentUserId()).thenReturn(7);
            when(authContext.isAdmin()).thenReturn(false);
            when(authContext.canAccessOwner(any())).thenAnswer(invocation ->
                    Integer.valueOf(7).equals(invocation.getArgument(0))
            );
            when(transactionManager.getTransaction(any()))
                    .thenAnswer(invocation -> new SimpleTransactionStatus());

            stubAssetRepository();
            stubVersionRepository();
            stubPackageRepositories();
            stubSessionRepositories();
            stubJobRepository();
            stubSampleRepositories();
            stubObjectStorage();
        }

        private void stubAssetRepository() {
            when(assetRepo.findByIdAndDeletedFalse(anyString()))
                    .thenAnswer(invocation -> activeAsset(invocation.getArgument(0)));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(anyString()))
                    .thenAnswer(invocation -> activeAsset(invocation.getArgument(0)));
            when(assetRepo.findByOwnerUserIdAndDeletedFalse(anyInt()))
                    .thenAnswer(invocation ->
                            Integer.valueOf(7).equals(invocation.getArgument(0))
                                    ? List.of(asset)
                                    : List.of()
                    );
            when(assetRepo.searchCatalogForOwner(
                    anyInt(),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    any(Pageable.class)
            )).thenAnswer(invocation -> {
                List<DatasetAsset> assets =
                        Integer.valueOf(7).equals(invocation.getArgument(0))
                                ? List.of(asset)
                                : List.of();
                return new PageImpl<>(
                        assets,
                        invocation.getArgument(3),
                        assets.size()
                );
            });
            when(assetRepo.save(any(DatasetAsset.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(assetRepo.saveAndFlush(any(DatasetAsset.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private void stubVersionRepository() {
            when(versionRepo.findById(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(versions.get(invocation.getArgument(0)))
            );
            when(versionRepo.findByIdAndDeletedFalse(anyString()))
                    .thenAnswer(invocation ->
                            activeVersion(invocation.getArgument(0))
                    );
            when(versionRepo.findByIdAndDeletedFalseForUpdate(anyString()))
                    .thenAnswer(invocation ->
                            activeVersion(invocation.getArgument(0))
                    );
            when(versionRepo.findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> versions.values().stream()
                    .filter(version -> invocation.getArgument(0).equals(version.getAssetId()))
                    .filter(version -> invocation.getArgument(1).equals(version.getStatus()))
                    .filter(version -> !Boolean.TRUE.equals(version.getDeleted()))
                    .max(Comparator.comparing(
                            DatasetVersion::getVersionNo,
                            Comparator.nullsFirst(Comparator.naturalOrder())
                    )));
            when(versionRepo.findMaxVersionNoByAssetId(anyString()))
                    .thenAnswer(invocation -> versions.values().stream()
                            .filter(version ->
                                    invocation.getArgument(0).equals(version.getAssetId())
                            )
                            .map(DatasetVersion::getVersionNo)
                            .filter(value -> value != null)
                            .max(Integer::compareTo)
                            .orElse(0));
            when(versionRepo.existsByAssetIdAndVersion(anyString(), anyString()))
                    .thenAnswer(invocation -> versions.values().stream().anyMatch(version ->
                            invocation.getArgument(0).equals(version.getAssetId())
                                    && invocation.getArgument(1).equals(version.getVersion())
                    ));
            when(versionRepo.findByAssetIdAndVersion(anyString(), anyString()))
                    .thenAnswer(invocation -> versions.values().stream()
                            .filter(version -> invocation.getArgument(0)
                                    .equals(version.getAssetId()))
                            .filter(version -> invocation.getArgument(1)
                                    .equals(version.getVersion()))
                            .findFirst());
            when(versionRepo.findByAssetIdInAndDeletedFalse(anyCollection()))
                    .thenAnswer(invocation -> {
                        Collection<String> assetIds = invocation.getArgument(0);
                        return versions.values().stream()
                                .filter(version -> assetIds.contains(version.getAssetId()))
                                .filter(version ->
                                        !Boolean.TRUE.equals(version.getDeleted())
                                )
                                .toList();
                    });
            when(versionRepo.save(any(DatasetVersion.class)))
                    .thenAnswer(invocation -> saveVersion(invocation.getArgument(0)));
            when(versionRepo.saveAndFlush(any(DatasetVersion.class)))
                    .thenAnswer(invocation -> saveVersion(invocation.getArgument(0)));
            when(versionRepo.updateFileCountIfAbsent(anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        DatasetVersion version = versions.get(invocation.getArgument(0));
                        if (version == null
                                || version.getFileCount() != null
                                || Boolean.TRUE.equals(version.getDeleted())) {
                            return 0;
                        }
                        return 1;
                    });
        }

        private void stubPackageRepositories() {
            when(packageRepo.findByIdAndDeletedFalse(anyString()))
                    .thenAnswer(invocation -> activePackage(invocation.getArgument(0)));
            when(packageRepo.findAllById(any())).thenAnswer(invocation -> {
                Iterable<String> ids = invocation.getArgument(0);
                List<DatasetPackage> result = new ArrayList<>();
                for (String id : ids) {
                    DatasetPackage datasetPackage = packages.get(id);
                    if (datasetPackage != null) {
                        result.add(datasetPackage);
                    }
                }
                return result;
            });
            when(packageRepo.saveAndFlush(any(DatasetPackage.class)))
                    .thenAnswer(invocation -> {
                        DatasetPackage value = invocation.getArgument(0);
                        packages.put(value.getId(), value);
                        return value;
                    });

            when(versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                    anyString()
            )).thenAnswer(invocation -> packageRelations(invocation.getArgument(0)));
            when(versionPackageRepo.findByDatasetVersionIdAndPackageId(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> relations.stream()
                    .filter(relation ->
                            invocation.getArgument(0).equals(
                                    relation.getDatasetVersionId()
                            )
                    )
                    .filter(relation ->
                            invocation.getArgument(1).equals(relation.getPackageId())
                    )
                    .findFirst());
            when(versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> relations.stream().anyMatch(relation ->
                    invocation.getArgument(0).equals(relation.getDatasetVersionId())
                            && invocation.getArgument(1).equals(relation.getPackageId())
            ));
            when(versionPackageRepo.findMaxPackageOrderByDatasetVersionId(
                    anyString()
            )).thenAnswer(invocation -> packageRelations(invocation.getArgument(0))
                    .stream()
                    .map(DatasetVersionPackage::getPackageOrder)
                    .max(Integer::compareTo)
                    .orElse(-1));
            when(versionPackageRepo.saveAll(any())).thenAnswer(invocation ->
                    saveRelations(invocation.getArgument(0))
            );
            when(versionPackageRepo.saveAndFlush(any(DatasetVersionPackage.class)))
                    .thenAnswer(invocation -> {
                        DatasetVersionPackage value = invocation.getArgument(0);
                        relations.add(value);
                        return value;
                    });
        }

        private void stubSessionRepositories() {
            when(sessionRepo.findById(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(sessions.get(invocation.getArgument(0)))
            );
            when(sessionRepo.findByImportJobId(anyString())).thenAnswer(invocation ->
                    sessions.values().stream()
                            .filter(session -> invocation.getArgument(0)
                                    .equals(session.getImportJobId()))
                            .findFirst()
            );
            when(sessionRepo.save(any(DatasetUploadSession.class)))
                    .thenAnswer(invocation -> saveSession(invocation.getArgument(0)));
            when(sessionRepo.saveAndFlush(any(DatasetUploadSession.class)))
                    .thenAnswer(invocation -> saveSession(invocation.getArgument(0)));
            when(sessionRepo.updateStatusIfCurrent(
                    anyString(),
                    anyInt(),
                    anyString(),
                    anyString(),
                    any(Instant.class)
            )).thenAnswer(invocation -> {
                DatasetUploadSession session =
                        sessions.get(invocation.getArgument(0));
                if (session == null
                        || !invocation.getArgument(1).equals(session.getOwnerUserId())
                        || !invocation.getArgument(2).equals(session.getStatus())) {
                    return 0;
                }
                session.setStatus(invocation.getArgument(3));
                session.setUpdatedAt(invocation.getArgument(4));
                return 1;
            });

            when(chunkRepo.findByUploadIdOrderByPartIndexAsc(anyString()))
                    .thenAnswer(invocation -> chunks.stream()
                            .filter(chunk -> invocation.getArgument(0)
                                    .equals(chunk.getUploadId()))
                            .sorted(Comparator.comparing(
                                    DatasetUploadChunk::getPartIndex
                            ))
                            .toList());
            when(chunkRepo.findByUploadIdAndPartIndex(anyString(), anyInt()))
                    .thenAnswer(invocation -> chunks.stream()
                            .filter(chunk -> invocation.getArgument(0)
                                    .equals(chunk.getUploadId()))
                            .filter(chunk -> invocation.getArgument(1)
                                    .equals(chunk.getPartIndex()))
                            .findFirst());
            when(chunkRepo.save(any(DatasetUploadChunk.class)))
                    .thenAnswer(invocation -> {
                        DatasetUploadChunk value = invocation.getArgument(0);
                        chunks.removeIf(chunk -> chunk.getId().equals(value.getId()));
                        chunks.add(value);
                        return value;
                    });
        }

        private void stubJobRepository() {
            when(jobRepo.findById(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(jobs.get(invocation.getArgument(0)))
            );
            when(jobRepo.findByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> jobs.values().stream()
                            .filter(job -> invocation.getArgument(0)
                                    .equals(job.getDatasetVersionId()))
                            .toList());
            when(jobRepo.findByDatasetVersionIdIn(anyCollection()))
                    .thenAnswer(invocation -> {
                        Collection<String> versionIds = invocation.getArgument(0);
                        return jobs.values().stream()
                                .filter(job ->
                                        versionIds.contains(job.getDatasetVersionId())
                                )
                                .toList();
                    });
            when(jobRepo.findByDatasetVersionIdAndPackageId(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> jobs.values().stream()
                    .filter(job -> invocation.getArgument(0)
                            .equals(job.getDatasetVersionId()))
                    .filter(job -> invocation.getArgument(1)
                            .equals(job.getPackageId()))
                    .findFirst());
            when(jobRepo.saveAndFlush(any(ImportJob.class)))
                    .thenAnswer(invocation -> {
                        ImportJob value = invocation.getArgument(0);
                        jobs.put(value.getId(), value);
                        return value;
                    });
            when(jobRepo.claimPending(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    any(Instant.class)
            )).thenAnswer(invocation -> {
                ImportJob job = jobs.get(invocation.getArgument(0));
                if (job == null
                        || !invocation.getArgument(1).equals(job.getStatus())
                        || job.getExecutorId() != null) {
                    return 0;
                }
                job.setStatus(invocation.getArgument(2));
                job.setExecutorId(invocation.getArgument(3));
                job.setStartedAt(invocation.getArgument(4));
                job.setHeartbeatAt(invocation.getArgument(4));
                return 1;
            });
            when(jobRepo.completeSuccessIfOwned(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyInt(),
                    any(Instant.class),
                    any(Instant.class)
            )).thenAnswer(invocation -> {
                ImportJob job = jobs.get(invocation.getArgument(0));
                if (job == null
                        || !invocation.getArgument(1).equals(job.getExecutorId())
                        || !invocation.getArgument(2).equals(job.getStatus())) {
                    return 0;
                }
                job.setStatus("SUCCESS");
                job.setProgress(100);
                job.setTotalSamples(invocation.getArgument(3));
                job.setImportedSamples(invocation.getArgument(3));
                job.setFinishedAt(invocation.getArgument(4));
                job.setUpdatedAt(invocation.getArgument(5));
                return 1;
            });
            when(jobRepo.markFailedIfOwned(
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    anyString(),
                    any(),
                    any(Instant.class)
            )).thenAnswer(invocation -> {
                ImportJob job = jobs.get(invocation.getArgument(0));
                if (job == null
                        || !invocation.getArgument(1).equals(job.getExecutorId())
                        || !invocation.getArgument(2).equals(job.getStatus())) {
                    return 0;
                }
                job.setStatus("FAILED");
                job.setErrorMessage(invocation.getArgument(3));
                job.setErrorCode(invocation.getArgument(4));
                job.setErrorDetailsJson(invocation.getArgument(5));
                job.setFinishedAt(invocation.getArgument(6));
                return 1;
            });
        }

        private void stubSampleRepositories() {
            when(sampleRepo.saveAll(any())).thenAnswer(invocation ->
                    saveSamples(invocation.getArgument(0))
            );
            when(sampleRepo.saveAllAndFlush(any())).thenAnswer(invocation ->
                    saveSamples(invocation.getArgument(0))
            );
            when(sampleRepo.saveAndFlush(any(DatasetSample.class)))
                    .thenAnswer(invocation -> {
                        DatasetSample value = invocation.getArgument(0);
                        samples.put(value.getId(), value);
                        return value;
                    });
            when(sampleRepo.findByIdAndDeletedFalse(anyString()))
                    .thenAnswer(invocation -> activeSample(invocation.getArgument(0)));
            when(sampleRepo.findByIdForUpdate(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(samples.get(invocation.getArgument(0)))
            );
            when(sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                    anyString(),
                    any(Pageable.class)
            )).thenAnswer(invocation -> page(
                    activeSamples(invocation.getArgument(0)),
                    invocation.getArgument(1)
            ));
            when(sampleRepo.findByDatasetVersionId(
                    anyString(),
                    any(Pageable.class)
            )).thenAnswer(invocation -> page(
                    versionSamples(invocation.getArgument(0)),
                    invocation.getArgument(1)
            ));
            when(sampleRepo
                    .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                            anyString(),
                            any(Pageable.class)
                    )).thenAnswer(invocation -> slice(
                    activeSamples(invocation.getArgument(0)),
                    invocation.getArgument(1)
            ));
            when(sampleRepo.findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(
                    anyString(),
                    anyCollection()
            )).thenAnswer(invocation -> {
                Collection<String> externalIds = invocation.getArgument(1);
                return activeSamples(invocation.getArgument(0)).stream()
                        .filter(sample -> externalIds.contains(sample.getExternalId()))
                        .toList();
            });
            when(sampleRepo.findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(
                    anyString(),
                    anyCollection()
            )).thenAnswer(invocation -> {
                Collection<Integer> indexes = invocation.getArgument(1);
                return activeSamples(invocation.getArgument(0)).stream()
                        .filter(sample -> indexes.contains(sample.getSampleIndex()))
                        .toList();
            });
            when(sampleRepo.countByDatasetVersionIdAndDeletedFalse(anyString()))
                    .thenAnswer(invocation ->
                            (long) activeSamples(invocation.getArgument(0)).size()
                    );
            when(sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdIsNull(
                    anyString()
            )).thenAnswer(invocation -> versionSamples(invocation.getArgument(0))
                    .stream()
                    .filter(sample -> sample.getCreatedByPackageId() == null)
                    .count());
            when(sampleRepo.findDuplicateExternalIdsByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> duplicates(
                            activeSamples(invocation.getArgument(0)),
                            DatasetSample::getExternalId
                    ));
            when(sampleRepo.findDuplicateSampleIndexesByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> duplicates(
                            activeSamples(invocation.getArgument(0)),
                            DatasetSample::getSampleIndex
                    ));
            when(sampleRepo.findDistinctCreatedByPackageIdsByDatasetVersionId(
                    anyString()
            )).thenAnswer(invocation -> versionSamples(invocation.getArgument(0))
                    .stream()
                    .map(DatasetSample::getCreatedByPackageId)
                    .filter(value -> value != null)
                    .distinct()
                    .toList());
            when(sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                    anyString()
            )).thenAnswer(invocation -> activeSamples(invocation.getArgument(0))
                    .stream()
                    .map(DatasetSample::getSampleIndex)
                    .max(Integer::compareTo)
                    .orElse(-1));

            when(dataRepo.saveAll(any())).thenAnswer(invocation ->
                    saveData(invocation.getArgument(0))
            );
            when(dataRepo.saveAllAndFlush(any())).thenAnswer(invocation ->
                    saveData(invocation.getArgument(0))
            );
            when(dataRepo.findById(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(dataItems.get(invocation.getArgument(0)))
            );
            when(dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> dataItems.values().stream()
                    .filter(data -> invocation.getArgument(0).equals(data.getSampleId()))
                    .filter(data -> invocation.getArgument(1)
                            .equals(data.getDatasetVersionId()))
                    .sorted(Comparator.comparing(DatasetSampleData::getSeq)
                            .thenComparing(DatasetSampleData::getId))
                    .toList());
            when(dataRepo
                    .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                            anyString(),
                            anyCollection()
                    )).thenAnswer(invocation -> {
                Collection<String> sampleIds = invocation.getArgument(1);
                return dataItems.values().stream()
                        .filter(data -> invocation.getArgument(0)
                                .equals(data.getDatasetVersionId()))
                        .filter(data -> sampleIds.contains(data.getSampleId()))
                        .sorted(Comparator.comparing(DatasetSampleData::getSampleId)
                                .thenComparing(DatasetSampleData::getSeq)
                                .thenComparing(DatasetSampleData::getId))
                        .toList();
            });
            when(dataRepo.countByDatasetVersionIdAndPackageIdIsNull(anyString()))
                    .thenAnswer(invocation -> dataItems.values().stream()
                            .filter(data -> invocation.getArgument(0)
                                    .equals(data.getDatasetVersionId()))
                            .filter(data -> data.getPackageId() == null)
                            .count());
            when(dataRepo.countByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> dataItems.values().stream()
                            .filter(data -> invocation.getArgument(0)
                                    .equals(data.getDatasetVersionId()))
                            .count());
            when(dataRepo.findDistinctPackageIdsByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> dataItems.values().stream()
                            .filter(data -> invocation.getArgument(0)
                                    .equals(data.getDatasetVersionId()))
                            .map(DatasetSampleData::getPackageId)
                            .filter(value -> value != null)
                            .distinct()
                            .toList());

            when(annotationRepo.saveAll(any())).thenAnswer(invocation ->
                    saveAnnotations(invocation.getArgument(0))
            );
            when(annotationRepo.saveAllAndFlush(any())).thenAnswer(invocation ->
                    saveAnnotations(invocation.getArgument(0))
            );
            when(annotationRepo.findById(anyString())).thenAnswer(invocation ->
                    Optional.ofNullable(annotations.get(invocation.getArgument(0)))
            );
            when(annotationRepo.findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                    anyString(),
                    anyString()
            )).thenAnswer(invocation -> annotations.values().stream()
                    .filter(annotation -> invocation.getArgument(0)
                            .equals(annotation.getSampleId()))
                    .filter(annotation -> invocation.getArgument(1)
                            .equals(annotation.getDatasetVersionId()))
                    .sorted(Comparator.comparing(DatasetAnnotation::getCreatedAt)
                            .thenComparing(DatasetAnnotation::getId))
                    .toList());
            when(annotationRepo
                    .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                            anyString(),
                            anyCollection()
                    )).thenAnswer(invocation -> {
                Collection<String> sampleIds = invocation.getArgument(1);
                return annotations.values().stream()
                        .filter(annotation -> invocation.getArgument(0)
                                .equals(annotation.getDatasetVersionId()))
                        .filter(annotation ->
                                sampleIds.contains(annotation.getSampleId())
                        )
                        .sorted(Comparator.comparing(DatasetAnnotation::getSampleId)
                                .thenComparing(DatasetAnnotation::getCreatedAt)
                                .thenComparing(DatasetAnnotation::getId))
                        .toList();
            });
            when(annotationRepo.countByDatasetVersionIdAndPackageIdIsNull(
                    anyString()
            )).thenAnswer(invocation -> annotations.values().stream()
                    .filter(annotation -> invocation.getArgument(0)
                            .equals(annotation.getDatasetVersionId()))
                    .filter(annotation -> annotation.getPackageId() == null)
                    .count());
            when(annotationRepo.countByDatasetVersionId(anyString()))
                    .thenAnswer(invocation -> annotations.values().stream()
                            .filter(annotation -> invocation.getArgument(0)
                                    .equals(annotation.getDatasetVersionId()))
                            .count());
            when(annotationRepo.findDistinctPackageIdsByDatasetVersionId(
                    anyString()
            )).thenAnswer(invocation -> annotations.values().stream()
                    .filter(annotation -> invocation.getArgument(0)
                            .equals(annotation.getDatasetVersionId()))
                    .map(DatasetAnnotation::getPackageId)
                    .filter(value -> value != null)
                    .distinct()
                    .toList());
        }

        private void stubObjectStorage() throws Exception {
            when(minioClient.statObject(any())).thenAnswer(invocation -> {
                StatObjectResponse response = mock(StatObjectResponse.class);
                when(response.size()).thenReturn(1024L);
                return response;
            });
            when(minioService.stat(anyString())).thenAnswer(invocation -> {
                String objectName = invocation.getArgument(0);
                DatasetPackage datasetPackage = packages.values().stream()
                        .filter(value -> objectName.equals(value.getStoragePath()))
                        .findFirst()
                        .orElseThrow();
                StatObjectResponse response = mock(StatObjectResponse.class);
                when(response.size()).thenReturn(datasetPackage.getSizeBytes());
                return response;
            });
            when(zipReader.read(anyString(), anyLong())).thenReturn(List.of());
            when(manifestReader.readManifest(anyString(), anyLong(), anyString()))
                    .thenReturn("{}");
            when(manifestParser.parse(
                    anyString(),
                    any(),
                    anyString(),
                    anyInt(),
                    anyBoolean()
            ))
                    .thenAnswer(invocation -> {
                        int sampleIndex = invocation.getArgument(3);
                        return sampleIndex == 0
                                ? initialPlan()
                                : appendPlan(sampleIndex);
                    });
            when(autoDirectoryManifestBuilder.build(any(), anyInt()))
                    .thenAnswer(invocation -> {
                        int sampleIndex = invocation.getArgument(1);
                        return sampleIndex == 0
                                ? initialPlan()
                                : appendPlan(sampleIndex);
                    });
            when(minioService.downloadRange(anyString(), anyLong(), anyLong()))
                    .thenAnswer(invocation -> new ByteArrayInputStream(readRange(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2)
                    )));
        }

        private void stubConflictPlan(String sampleGrouping) {
            if ("AUTO_DIRECTORY".equals(sampleGrouping)) {
                when(autoDirectoryManifestBuilder.build(any(), anyInt()))
                        .thenAnswer(invocation ->
                                conflictPlan(invocation.getArgument(1))
                        );
                return;
            }
            when(manifestParser.parse(
                    anyString(),
                    any(),
                    anyString(),
                    anyInt(),
                    anyBoolean()
            )).thenAnswer(invocation ->
                    conflictPlan(invocation.getArgument(3))
            );
        }

        private void addUploadedChunk(String uploadId, long sizeBytes) {
            DatasetUploadChunk chunk = new DatasetUploadChunk();
            chunk.setId("chunk-" + uploadId);
            chunk.setUploadId(uploadId);
            chunk.setPartIndex(0);
            chunk.setObjectName(
                    "users/7/datasets/_uploads/" + uploadId + "/part-0"
            );
            chunk.setSizeBytes(sizeBytes);
            chunk.setCreatedAt(Instant.now());
            chunks.add(chunk);
        }

        private DatasetSample sample(String versionId, String externalId) {
            return versionSamples(versionId).stream()
                    .filter(sample -> externalId.equals(sample.getExternalId()))
                    .findFirst()
                    .orElseThrow();
        }

        private DatasetSampleData data(String sampleId, String dataType) {
            return dataItems.values().stream()
                    .filter(data -> sampleId.equals(data.getSampleId()))
                    .filter(data -> dataType.equals(data.getDataType()))
                    .findFirst()
                    .orElseThrow();
        }

        private List<DatasetSample> activeSamples(String versionId) {
            return versionSamples(versionId).stream()
                    .filter(sample -> !Boolean.TRUE.equals(sample.getDeleted()))
                    .toList();
        }

        private List<DatasetSample> versionSamples(String versionId) {
            return samples.values().stream()
                    .filter(sample -> versionId.equals(sample.getDatasetVersionId()))
                    .sorted(Comparator.comparing(DatasetSample::getSampleIndex)
                            .thenComparing(DatasetSample::getId))
                    .toList();
        }

        private long metadataFileCount(String versionId) {
            long dataCount = dataItems.values().stream()
                    .filter(data -> versionId.equals(data.getDatasetVersionId()))
                    .count();
            long annotationCount = annotations.values().stream()
                    .filter(annotation -> versionId.equals(annotation.getDatasetVersionId()))
                    .count();
            return dataCount + annotationCount;
        }

        private Optional<DatasetAsset> activeAsset(String id) {
            return ASSET_ID.equals(id) && !Boolean.TRUE.equals(asset.getDeleted())
                    ? Optional.of(asset)
                    : Optional.empty();
        }

        private Optional<DatasetVersion> activeVersion(String id) {
            DatasetVersion version = versions.get(id);
            return version == null || Boolean.TRUE.equals(version.getDeleted())
                    ? Optional.empty()
                    : Optional.of(version);
        }

        private Optional<DatasetPackage> activePackage(String id) {
            DatasetPackage datasetPackage = packages.get(id);
            return datasetPackage == null
                    || Boolean.TRUE.equals(datasetPackage.getDeleted())
                    ? Optional.empty()
                    : Optional.of(datasetPackage);
        }

        private Optional<DatasetSample> activeSample(String id) {
            DatasetSample sample = samples.get(id);
            return sample == null || Boolean.TRUE.equals(sample.getDeleted())
                    ? Optional.empty()
                    : Optional.of(sample);
        }

        private DatasetVersion saveVersion(DatasetVersion version) {
            versions.put(version.getId(), version);
            return version;
        }

        private DatasetUploadSession saveSession(DatasetUploadSession session) {
            sessions.put(session.getId(), session);
            return session;
        }

        private List<DatasetVersionPackage> saveRelations(
                Iterable<DatasetVersionPackage> values
        ) {
            List<DatasetVersionPackage> saved = toList(values);
            relations.addAll(saved);
            return saved;
        }

        private List<DatasetSample> saveSamples(Iterable<DatasetSample> values) {
            List<DatasetSample> saved = toList(values);
            saved.forEach(value -> samples.put(value.getId(), value));
            return saved;
        }

        private List<DatasetSampleData> saveData(
                Iterable<DatasetSampleData> values
        ) {
            List<DatasetSampleData> saved = toList(values);
            saved.forEach(value -> dataItems.put(value.getId(), value));
            return saved;
        }

        private List<DatasetAnnotation> saveAnnotations(
                Iterable<DatasetAnnotation> values
        ) {
            List<DatasetAnnotation> saved = toList(values);
            saved.forEach(value -> annotations.put(value.getId(), value));
            return saved;
        }

        private List<DatasetVersionPackage> packageRelations(String versionId) {
            return relations.stream()
                    .filter(relation ->
                            versionId.equals(relation.getDatasetVersionId())
                    )
                    .sorted(Comparator.comparing(
                            DatasetVersionPackage::getPackageOrder
                    ))
                    .toList();
        }

        private PageImpl<DatasetSample> page(
                List<DatasetSample> values,
                Pageable pageable
        ) {
            int from = Math.min((int) pageable.getOffset(), values.size());
            int to = Math.min(from + pageable.getPageSize(), values.size());
            return new PageImpl<>(values.subList(from, to), pageable, values.size());
        }

        private SliceImpl<DatasetSample> slice(
                List<DatasetSample> values,
                Pageable pageable
        ) {
            int from = Math.min((int) pageable.getOffset(), values.size());
            int to = Math.min(from + pageable.getPageSize(), values.size());
            return new SliceImpl<>(
                    values.subList(from, to),
                    pageable,
                    to < values.size()
            );
        }

        private <T, K> List<K> duplicates(
                List<T> values,
                Function<T, K> keyExtractor
        ) {
            Set<K> seen = new HashSet<>();
            Set<K> duplicates = new HashSet<>();
            for (T value : values) {
                K key = keyExtractor.apply(value);
                if (!seen.add(key)) {
                    duplicates.add(key);
                }
            }
            return List.copyOf(duplicates);
        }

        private byte[] readRange(String objectName, long offset, long length) {
            for (StoredSegment segment :
                    storedSegments.getOrDefault(objectName, List.of())) {
                long relative = offset - segment.offset();
                if (relative >= 0
                        && relative + length <= segment.bytes().length) {
                    byte[] result = new byte[(int) length];
                    System.arraycopy(
                            segment.bytes(),
                            (int) relative,
                            result,
                            0,
                            (int) length
                    );
                    return result;
                }
            }
            throw new IllegalArgumentException(
                    "missing stored range: " + objectName + "@" + offset + "+" + length
            );
        }

        private static ManifestImportPlan initialPlan() {
            ManifestData video = data(
                    "samples/scene-001/front.mp4",
                    "VIDEO",
                    "front.mp4",
                    "video/mp4",
                    1000L,
                    7
            );
            ManifestData image = data(
                    "samples/scene-002/front.png",
                    "IMAGE",
                    "front.png",
                    "image/png",
                    2000L,
                    5
            );
            ManifestAnnotation annotation = new ManifestAnnotation(
                    "annotations/scene-002.json",
                    "BBOX",
                    "json",
                    image.path(),
                    "scene-002.json",
                    "application/json",
                    Map.of(),
                    entry("annotations/scene-002.json", 2100L, 2)
            );
            List<ManifestSample> samples = List.of(
                    new ManifestSample(
                            "scene-001",
                            0,
                            Map.of(),
                            Map.of("source", "primary"),
                            List.of(video),
                            List.of()
                    ),
                    new ManifestSample(
                            "scene-002",
                            1,
                            Map.of(),
                            Map.of("source", "primary"),
                            List.of(image),
                            List.of(annotation)
                    )
            );
            return new ManifestImportPlan("1.0", samples, 2, 2, 1, List.of());
        }

        private static ManifestImportPlan appendPlan(int sampleIndex) {
            ManifestData image = data(
                    "samples/scene-003/front.png",
                    "IMAGE",
                    "front.png",
                    "image/png",
                    3000L,
                    6
            );
            ManifestSample sample = new ManifestSample(
                    "scene-003",
                    sampleIndex,
                    Map.of(),
                    Map.of("source", "append"),
                    List.of(image),
                    List.of()
            );
            return new ManifestImportPlan(
                    "1.0",
                    List.of(sample),
                    1,
                    1,
                    0,
                    List.of()
            );
        }

        private static ManifestImportPlan conflictPlan(int sampleIndex) {
            ManifestData image = data(
                    "scene-001/image/conflict.png",
                    "IMAGE",
                    "conflict.png",
                    "image/png",
                    4000L,
                    4
            );
            ManifestSample sample = new ManifestSample(
                    "scene-001",
                    sampleIndex,
                    Map.of(),
                    Map.of(),
                    List.of(image),
                    List.of()
            );
            return new ManifestImportPlan(
                    "1.0",
                    List.of(sample),
                    1,
                    1,
                    0,
                    List.of()
            );
        }

        private static ManifestData data(
                String path,
                String dataType,
                String fileName,
                String contentType,
                long offset,
                int size
        ) {
            return new ManifestData(
                    path,
                    dataType,
                    "CAM_FRONT",
                    "RGB",
                    0,
                    fileName.substring(fileName.lastIndexOf('.') + 1),
                    fileName,
                    contentType,
                    Map.of(),
                    entry(path, offset, size)
            );
        }

        private static ZipEntryInfo entry(String path, long offset, int size) {
            return new ZipEntryInfo(
                    path,
                    path,
                    0,
                    size,
                    size,
                    1L,
                    offset - 30,
                    offset,
                    false,
                    false
            );
        }

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId(ASSET_ID);
            asset.setName("multimodal-lifecycle");
            asset.setType("MULTIMODAL");
            asset.setOwnerUserId(7);
            asset.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
            asset.setDeleted(false);
            return asset;
        }

        private static <T> List<T> toList(Iterable<T> values) {
            List<T> result = new ArrayList<>();
            values.forEach(result::add);
            return result;
        }
    }

    private record StoredSegment(long offset, byte[] bytes) {
    }
}
