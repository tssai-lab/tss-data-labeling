package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.entity.ImportJobSampleFailure;
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
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.repository.ImportJobSampleFailureRepository;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportJobServiceTest {

    @Test
    void importsPlanAndPublishesReadyVersion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals(100, fixture.job.getProgress());
        assertEquals(1, fixture.job.getTotalSamples());
        assertEquals(1, fixture.job.getImportedSamples());
        assertNotNull(fixture.job.getFinishedAt());
        assertEquals("READY", fixture.version.getStatus());
        assertEquals("dataset.multimodal.manifest/v1", fixture.version.getArtifactSpecId());
        assertEquals(fixture.version.getArtifactSpecId(), fixture.session.getArtifactSpecId());
        assertNotNull(fixture.version.getPublishedAt());
        assertEquals(fixture.version.getId(), fixture.asset.getCurrentVersionId());
        verify(fixture.auditService).recordImportSucceeded(
                fixture.asset,
                fixture.version,
                fixture.job,
                false,
                1,
                1,
                1
        );

        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        DatasetSampleData data = captureSaved(fixture.dataRepo, DatasetSampleData.class);
        DatasetAnnotation annotation = captureSaved(fixture.annotationRepo, DatasetAnnotation.class);

        assertEquals(fixture.version.getId(), sample.getDatasetVersionId());
        assertEquals(sample.getId(), data.getSampleId());
        assertEquals(sample.getDatasetVersionId(), data.getDatasetVersionId());
        assertEquals("VIDEO", data.getDataType());
        assertEquals("mp4", data.getFormat());
        assertEquals("video/mp4", data.getContentType());
        assertEquals(12.5, data.getMetadata().get("duration_sec"));
        assertEquals(100L, data.getSizeBytes());
        assertEquals(10L, data.getZipEntryOffset());
        assertEquals(40L, data.getZipDataOffset());
        assertEquals(80L, data.getCompressedSize());
        assertEquals(100L, data.getUncompressedSize());
        assertEquals("DEFLATED", data.getCompressionMethod());
        assertEquals(123L, data.getCrc32());

        assertEquals(sample.getId(), annotation.getSampleId());
        assertEquals(sample.getDatasetVersionId(), annotation.getDatasetVersionId());
        assertEquals(data.getId(), annotation.getSampleDataId());
        assertEquals(20L, annotation.getSizeBytes());
        assertEquals("STORED", annotation.getCompressionMethod());
    }

    @Test
    void importsPackageBackedPlanFromPackageStorageAndPersistsPackageIds() throws Exception {
        Fixture fixture = new Fixture();
        fixture.job.setPackageId(fixture.datasetPackage.getId());
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        DatasetSampleData data = captureSaved(fixture.dataRepo, DatasetSampleData.class);
        DatasetAnnotation annotation = captureSaved(fixture.annotationRepo, DatasetAnnotation.class);

        assertEquals(fixture.datasetPackage.getId(), sample.getCreatedByPackageId());
        assertEquals(fixture.datasetPackage.getId(), data.getPackageId());
        assertEquals(fixture.datasetPackage.getId(), annotation.getPackageId());
        verify(fixture.minioService).stat(fixture.datasetPackage.getStoragePath());
        verify(fixture.zipReader).read(
                fixture.datasetPackage.getStoragePath(),
                fixture.datasetPackage.getSizeBytes()
        );
        verify(fixture.manifestReader).readManifest(
                fixture.datasetPackage.getStoragePath(),
                fixture.datasetPackage.getSizeBytes(),
                fixture.datasetPackage.getManifestPath()
        );
    }

    @Test
    void initialAutoDirectoryImportBuildsPlanWithoutReadingManifest() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setSampleGrouping("AUTO_DIRECTORY");
        fixture.session.setManifestPath(null);
        fixture.stubContext();
        when(fixture.autoBuilder.build(any(), eq(0))).thenReturn(fixture.plan(0));

        fixture.service.execute(fixture.job.getId());

        verify(fixture.autoBuilder).build(any(), eq(0));
        verify(fixture.manifestReader, never()).readManifest(any(), anyLong(), any());
        verify(fixture.parser, never()).parse(any(), any(), any());
        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals("READY", fixture.version.getStatus());
        assertEquals("dataset.multimodal.directory/v1", fixture.version.getArtifactSpecId());
        assertEquals(fixture.version.getArtifactSpecId(), fixture.session.getArtifactSpecId());
    }

    @Test
    void rejectsASecondVersionThatChangesTheVerifiedMultimodalContract() throws Exception {
        Fixture fixture = new Fixture();
        DatasetVersion existing = new DatasetVersion();
        existing.setArtifactSpecId("dataset.multimodal.directory/v1");
        when(fixture.versionRepo
                .findTopByAssetIdAndArtifactSpecIdIsNotNullAndDeletedFalseOrderByVersionNoDesc(
                        fixture.asset.getId()
                )).thenReturn(Optional.of(existing));
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertNull(fixture.version.getArtifactSpecId());
        assertNull(fixture.session.getArtifactSpecId());
        verify(fixture.sampleRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void appendAutoDirectoryStartsAfterCurrentMaximumSampleIndex() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.session.setSampleGrouping("AUTO_DIRECTORY");
        fixture.session.setManifestPath(null);
        fixture.datasetPackage.setManifestPath(null);
        when(fixture.sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                fixture.version.getId()
        )).thenReturn(4);
        fixture.stubContext();
        when(fixture.autoBuilder.build(any(), eq(5))).thenReturn(fixture.plan(5));

        fixture.service.execute(fixture.job.getId());

        verify(fixture.autoBuilder).build(any(), eq(5));
        verify(fixture.manifestReader, never()).readManifest(any(), anyLong(), any());
        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        assertEquals(5, sample.getSampleIndex());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertEquals("READY", fixture.datasetPackage.getStatus());
    }

    @Test
    void manifestImportDoesNotUseAutoDirectoryBuilder() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        verify(fixture.autoBuilder, never()).build(any(), any(Integer.class));
        verify(fixture.manifestReader).readManifest(
                fixture.version.getStoragePath(),
                fixture.version.getSizeBytes(),
                fixture.session.getManifestPath()
        );
    }

    @Test
    void manifestImportPassesStrictManifestFlagToParser() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setStrictManifest(true);
        fixture.stubContext();
        when(fixture.parser.parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                eq(0),
                eq(true)
        )).thenReturn(fixture.plan(0));

        fixture.service.execute(fixture.job.getId());

        verify(fixture.parser).parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                eq(0),
                eq(true)
        );
        assertEquals("SUCCESS", fixture.job.getStatus());
    }

    @Test
    void appendsPackageSamplesWithoutPublishingDraftOrChangingCurrentVersion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.asset.setCurrentVersionId("ready-1");
        when(fixture.sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                fixture.version.getId()
        )).thenReturn(4);
        when(fixture.dataRepo.countByDatasetVersionId(fixture.version.getId()))
                .thenReturn(1L);
        when(fixture.annotationRepo.countByDatasetVersionId(fixture.version.getId()))
                .thenReturn(1L);
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        DatasetSampleData data = captureSaved(fixture.dataRepo, DatasetSampleData.class);
        DatasetAnnotation annotation =
                captureSaved(fixture.annotationRepo, DatasetAnnotation.class);
        assertEquals(5, sample.getSampleIndex());
        assertEquals(fixture.datasetPackage.getId(), sample.getCreatedByPackageId());
        assertEquals(fixture.datasetPackage.getId(), data.getPackageId());
        assertEquals(fixture.datasetPackage.getId(), annotation.getPackageId());
        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals("READY", fixture.datasetPackage.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertNull(fixture.version.getPublishedAt());
        assertEquals("ready-1", fixture.asset.getCurrentVersionId());
        ArgumentCaptor<DatasetVersion> versionCaptor =
                ArgumentCaptor.forClass(DatasetVersion.class);
        verify(fixture.versionRepo).saveAndFlush(versionCaptor.capture());
        assertEquals(fixture.version.getId(), versionCaptor.getValue().getId());
        assertEquals(2L, versionCaptor.getValue().getFileCount());
        verify(fixture.assetRepo, never()).saveAndFlush(fixture.asset);
    }

    @Test
    void successfulAppendSupersedesOlderFailedAppendWithoutPersistedRows() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.asset.setCurrentVersionId("ready-1");

        ImportJob oldFailedJob = new ImportJob();
        oldFailedJob.setId("ijob-old-failed");
        oldFailedJob.setDatasetVersionId(fixture.version.getId());
        oldFailedJob.setPackageId("package-old-failed");
        oldFailedJob.setStatus("FAILED");
        oldFailedJob.setErrorCode("DUPLICATE_SAMPLE");
        oldFailedJob.setErrorMessage("上传内容包含已存在的样本");

        DatasetPackage oldFailedPackage = new DatasetPackage();
        oldFailedPackage.setId(oldFailedJob.getPackageId());
        oldFailedPackage.setDatasetAssetId(fixture.asset.getId());
        oldFailedPackage.setStoragePath("users/7/datasets/asset-1/v2/old-failed.zip");
        oldFailedPackage.setFileName("old-failed.zip");
        oldFailedPackage.setSizeBytes(100L);
        oldFailedPackage.setStatus("FAILED");
        oldFailedPackage.setCreatedAt(Instant.parse("2026-06-01T00:01:00Z"));
        oldFailedPackage.setDeleted(false);

        DatasetVersionPackage oldFailedRelation = new DatasetVersionPackage();
        oldFailedRelation.setDatasetVersionId(fixture.version.getId());
        oldFailedRelation.setPackageId(oldFailedPackage.getId());
        oldFailedRelation.setPackageRole("APPEND");
        oldFailedRelation.setPackageOrder(1);

        when(fixture.jobRepo.findByDatasetVersionId(fixture.version.getId()))
                .thenReturn(List.of(oldFailedJob, fixture.job));
        when(fixture.versionPackageRepo.findByDatasetVersionIdAndPackageId(
                fixture.version.getId(),
                oldFailedPackage.getId()
        )).thenReturn(Optional.of(oldFailedRelation));
        when(fixture.packageRepo.findByIdAndDeletedFalse(oldFailedPackage.getId()))
                .thenReturn(Optional.of(oldFailedPackage));
        when(fixture.sampleRepo.countByDatasetVersionIdAndCreatedByPackageIdAndDeletedFalse(
                fixture.version.getId(),
                oldFailedPackage.getId()
        )).thenReturn(0L);
        when(fixture.sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                fixture.version.getId()
        )).thenReturn(4);
        when(fixture.dataRepo.countByDatasetVersionId(fixture.version.getId()))
                .thenReturn(1L);
        when(fixture.annotationRepo.countByDatasetVersionId(fixture.version.getId()))
                .thenReturn(1L);
        fixture.stubSuccessfulImport();

        fixture.service.execute(fixture.job.getId());

        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals("SUPERSEDED", oldFailedJob.getStatus());
        assertNull(oldFailedJob.getErrorCode());
        assertNull(oldFailedJob.getErrorMessage());
        assertEquals("SUPERSEDED", oldFailedPackage.getStatus());
    }

    @Test
    void appendsSingleModalPackageFromZipEntriesWithoutSampleGrouping() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.asset.setType("CV");
        fixture.session.setType("CV");
        fixture.session.setSampleGrouping(null);
        fixture.session.setManifestPath(null);
        fixture.datasetPackage.setManifestPath(null);
        fixture.asset.setCurrentVersionId("ready-1");
        when(fixture.sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                fixture.version.getId()
        )).thenReturn(9);
        fixture.stubContext();
        when(fixture.zipReader.read(
                fixture.datasetPackage.getStoragePath(),
                fixture.datasetPackage.getSizeBytes()
        )).thenReturn(List.of(fixture.zipEntry("images/front.jpg")));

        fixture.service.execute(fixture.job.getId());

        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        DatasetSampleData data = captureSaved(fixture.dataRepo, DatasetSampleData.class);
        assertEquals("images/front.jpg", sample.getExternalId());
        assertEquals(10, sample.getSampleIndex());
        assertEquals(fixture.datasetPackage.getId(), sample.getCreatedByPackageId());
        assertEquals("IMAGE", data.getDataType());
        assertEquals("front.jpg", data.getFileName());
        assertEquals(fixture.datasetPackage.getId(), data.getPackageId());
        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals("READY", fixture.datasetPackage.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertEquals("ready-1", fixture.asset.getCurrentVersionId());
        verify(fixture.manifestReader, never()).readManifest(any(), anyLong(), any());
        verify(fixture.autoBuilder, never()).build(any(), any(Integer.class));
        verify(fixture.annotationRepo).saveAllAndFlush(List.of());
    }

    @Test
    void appendExternalIdConflictFailsPackageWithoutWritingRows() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.stubSuccessfulImport();
        DatasetSample existing = new DatasetSample();
        existing.setId("existing-sample");
        existing.setDatasetVersionId(fixture.version.getId());
        existing.setExternalId("scene-1");
        existing.setSampleIndex(0);
        existing.setDeleted(false);
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(
                        eq(fixture.version.getId()),
                        any()
                ))
                .thenReturn(List.of(existing));

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("DUPLICATE_SAMPLE", fixture.job.getErrorCode());
        assertEquals("上传内容包含已存在的样本", fixture.job.getErrorMessage());
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"field\":\"external_id\""
        ));
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"externalId\":\"scene-1\""
        ));
        assertEquals("FAILED", fixture.datasetPackage.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        verify(fixture.sampleRepo, never()).saveAllAndFlush(any());
        verify(fixture.dataRepo, never()).saveAllAndFlush(any());
        verify(fixture.annotationRepo, never()).saveAllAndFlush(any());
        verify(fixture.auditService).recordImportFailed(
                fixture.asset,
                fixture.version,
                fixture.job,
                "APPEND",
                "DUPLICATE_SAMPLE"
        );
    }

    @Test
    void appendSampleIndexConflictFailsWithoutWritingRows() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.stubSuccessfulImport();
        DatasetSample existing = new DatasetSample();
        existing.setId("existing-sample");
        existing.setDatasetVersionId(fixture.version.getId());
        existing.setExternalId("existing-scene");
        existing.setSampleIndex(0);
        existing.setDeleted(false);
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(
                        eq(fixture.version.getId()),
                        any()
                ))
                .thenReturn(List.of(existing));

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("DUPLICATE_SAMPLE", fixture.job.getErrorCode());
        assertEquals("上传内容包含已存在的样本", fixture.job.getErrorMessage());
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"field\":\"sample_index\""
        ));
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"sampleIndex\":0"
        ));
        assertEquals("FAILED", fixture.datasetPackage.getStatus());
        verify(fixture.sampleRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void primaryPackageImportBecomesPartialWhenOneSampleTransactionFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.job.setPackageId(fixture.datasetPackage.getId());
        fixture.versionPackage.setPackageRole("PRIMARY");
        fixture.datasetPackage.setStatus("READY");
        fixture.stubContext();
        when(fixture.parser.parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                eq(0),
                eq(false)
        )).thenReturn(fixture.twoSamplePlan(0));
        AtomicInteger dataWrites = new AtomicInteger();
        when(fixture.dataRepo.saveAllAndFlush(any())).thenAnswer(invocation -> {
            if (dataWrites.incrementAndGet() == 2) {
                throw new IllegalArgumentException("sample data is invalid");
            }
            return invocation.getArgument(0);
        });
        when(fixture.jobRepo.markPartialIfOwned(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            fixture.job.setStatus("PARTIAL");
            fixture.job.setProgress(invocation.getArgument(3));
            fixture.job.setTotalSamples(invocation.getArgument(4));
            fixture.job.setImportedSamples(invocation.getArgument(5));
            fixture.job.setErrorCode(invocation.getArgument(7));
            fixture.job.setErrorMessage(invocation.getArgument(6));
            fixture.job.setFinishedAt(invocation.getArgument(9));
            return 1;
        });
        when(fixture.failureRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.execute(fixture.job.getId());

        assertEquals("PARTIAL", fixture.job.getStatus());
        assertEquals(50, fixture.job.getProgress());
        assertEquals(2, fixture.job.getTotalSamples());
        assertEquals(1, fixture.job.getImportedSamples());
        assertEquals("PARTIAL", fixture.datasetPackage.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        ArgumentCaptor<ImportJobSampleFailure> failureCaptor =
                ArgumentCaptor.forClass(ImportJobSampleFailure.class);
        verify(fixture.failureRepo).saveAndFlush(failureCaptor.capture());
        assertEquals(fixture.job.getId(), failureCaptor.getValue().getImportJobId());
        assertEquals(fixture.version.getId(), failureCaptor.getValue().getDatasetVersionId());
        assertEquals(fixture.datasetPackage.getId(), failureCaptor.getValue().getPackageId());
        assertEquals("scene-2", failureCaptor.getValue().getExternalId());
        assertEquals(1, failureCaptor.getValue().getSampleIndex());
        assertEquals("FAILED", failureCaptor.getValue().getStatus());
        verify(fixture.auditService).recordImportPartial(
                fixture.asset,
                fixture.version,
                fixture.job,
                "PRIMARY",
                1,
                1
        );
    }

    @Test
    void appendPackageImportBecomesPartialWhenOneSampleTransactionFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.stubContext();
        when(fixture.parser.parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                any(Integer.class),
                eq(false)
        )).thenReturn(fixture.twoSamplePlan(0));
        AtomicInteger dataWrites = new AtomicInteger();
        when(fixture.dataRepo.saveAllAndFlush(any())).thenAnswer(invocation -> {
            if (dataWrites.incrementAndGet() == 2) {
                throw new IllegalArgumentException("sample data is invalid");
            }
            return invocation.getArgument(0);
        });
        when(fixture.jobRepo.markPartialIfOwned(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            fixture.job.setStatus("PARTIAL");
            fixture.job.setProgress(invocation.getArgument(3));
            fixture.job.setTotalSamples(invocation.getArgument(4));
            fixture.job.setImportedSamples(invocation.getArgument(5));
            return 1;
        });
        when(fixture.failureRepo.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.execute(fixture.job.getId());

        assertEquals("PARTIAL", fixture.job.getStatus());
        assertEquals("PARTIAL", fixture.datasetPackage.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        verify(fixture.auditService).recordImportPartial(
                fixture.asset,
                fixture.version,
                fixture.job,
                "APPEND",
                1,
                1
        );
    }

    @Test
    void incrementalRetryUsesFailureExternalIdAndOriginalSampleIndex() throws Exception {
        Fixture fixture = new Fixture();
        fixture.asAppendPackage();
        fixture.job.setImportedSamples(1);
        fixture.job.setTotalSamples(2);
        ImportJobSampleFailure failure = new ImportJobSampleFailure();
        failure.setId("failure-1");
        failure.setImportJobId(fixture.job.getId());
        failure.setDatasetVersionId(fixture.version.getId());
        failure.setPackageId(fixture.datasetPackage.getId());
        failure.setExternalId("scene-1");
        failure.setSampleIndex(2);
        failure.setStatus("RETRYING");
        when(fixture.failureRepo.findByImportJobIdAndStatusOrderBySampleIndexAsc(
                fixture.job.getId(),
                "RETRYING"
        )).thenReturn(List.of(failure));
        when(fixture.failureRepo.markResolved(any(), any())).thenReturn(1);
        when(fixture.sampleRepo.findMaxSampleIndexByDatasetVersionIdAndDeletedFalse(
                fixture.version.getId()
        )).thenReturn(9);
        fixture.stubContext();
        when(fixture.parser.parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                eq(10),
                eq(false)
        )).thenReturn(fixture.plan(10));

        fixture.service.execute(fixture.job.getId());

        DatasetSample sample = captureSaved(fixture.sampleRepo, DatasetSample.class);
        assertEquals("scene-1", sample.getExternalId());
        assertEquals(2, sample.getSampleIndex());
        assertEquals("SUCCESS", fixture.job.getStatus());
        verify(fixture.failureRepo).markResolved(eq("failure-1"), any(Instant.class));
    }

    @Test
    void doesNotReplaceNewerCurrentVersion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();
        DatasetVersion newer = new DatasetVersion();
        newer.setId("version-newer");
        newer.setAssetId(fixture.asset.getId());
        newer.setVersionNo(3);
        newer.setStatus("READY");
        newer.setDeleted(false);
        fixture.asset.setCurrentVersionId(newer.getId());
        when(fixture.versionRepo.findByIdAndDeletedFalse(newer.getId())).thenReturn(Optional.of(newer));

        fixture.service.execute(fixture.job.getId());

        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals(newer.getId(), fixture.asset.getCurrentVersionId());
    }

    @Test
    void replacesOlderCurrentVersionAfterCandidateBecomesReady() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();
        DatasetVersion older = new DatasetVersion();
        older.setId("version-older");
        older.setAssetId(fixture.asset.getId());
        older.setVersionNo(1);
        older.setStatus("READY");
        older.setDeleted(false);
        fixture.asset.setCurrentVersionId(older.getId());
        when(fixture.versionRepo.findByIdAndDeletedFalse(older.getId())).thenReturn(Optional.of(older));

        fixture.service.execute(fixture.job.getId());

        assertEquals("SUCCESS", fixture.job.getStatus());
        assertEquals(fixture.version.getId(), fixture.asset.getCurrentVersionId());
    }

    @Test
    void marksFailedAndKeepsDraftWhenManifestPathIsMissingFromZip() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubContext();
        fixture.asset.setCurrentVersionId("version-ready");
        when(fixture.manifestReader.readManifest(
                fixture.version.getStoragePath(),
                fixture.version.getSizeBytes(),
                fixture.session.getManifestPath()
        )).thenReturn("""
                {
                  "version":"1.0",
                  "samples":[{
                    "external_id":"scene-1",
                    "data":[{"path":"missing.png","data_type":"IMAGE"}]
                  }]
                }
                """);
        ImportJobService service = fixture.serviceWithParser(new ManifestParser(new ObjectMapper()));

        service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("INVALID_MANIFEST", fixture.job.getErrorCode());
        assertEquals("Manifest 内容无效，请检查后重试", fixture.job.getErrorMessage());
        assertFalse(fixture.job.getErrorMessage().contains("missing.png"));
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"field\":\"samples[0].data[0].path\""
        ));
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"externalId\":\"scene-1\""
        ));
        assertTrue(fixture.job.getErrorDetailsJson().contains(
                "\"path\":\"missing.png\""
        ));
        assertEquals("DRAFT", fixture.version.getStatus());
        assertNull(fixture.version.getPublishedAt());
        assertEquals("version-ready", fixture.asset.getCurrentVersionId());
        verify(fixture.sampleRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void rollsBackAtomicWriteAndMarksFailedWhenAnnotationSaveFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();
        doThrow(new RuntimeException("annotation insert failed"))
                .when(fixture.annotationRepo)
                .saveAllAndFlush(any());

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("DRAFT", fixture.version.getStatus());
        assertNull(fixture.asset.getCurrentVersionId());
        assertTrue(fixture.transactionManager.rollbackCount > 0);
    }

    @Test
    void storesGenericStructuredFailureWithoutLeakingTechnicalMessage() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubContext();
        when(fixture.minioService.stat(any()))
                .thenThrow(new RuntimeException("MinIO bucket=models object=secret.zip"));

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("IMPORT_FAILED", fixture.job.getErrorCode());
        assertEquals("数据导入失败，请检查上传内容后重试", fixture.job.getErrorMessage());
        assertNull(fixture.job.getErrorDetailsJson());
    }

    @Test
    void storesManifestFailureCodeAndDetails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubContext();
        when(fixture.minioService.stat(any()))
                .thenThrow(new ManifestValidationException(
                        "DUPLICATE_SAMPLE",
                        "样本 scene-1 已存在",
                        Map.of("sampleName", "scene-1")
                ));

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("DUPLICATE_SAMPLE", fixture.job.getErrorCode());
        assertEquals("样本 scene-1 已存在", fixture.job.getErrorMessage());
        assertTrue(fixture.job.getErrorDetailsJson().contains("\"sampleName\":\"scene-1\""));
    }

    @Test
    void strictManifestUndeclaredEntryFailsImportJobWithStructuredError() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session.setStrictManifest(true);
        fixture.stubContext();
        when(fixture.parser.parse(
                any(),
                any(),
                eq(fixture.session.getManifestPath()),
                eq(0),
                eq(true)
        )).thenThrow(new ManifestValidationException(
                "INVALID_MANIFEST_UNDECLARED_ENTRY",
                "manifest 未声明 ZIP 文件: README.txt",
                Map.of("path", "README.txt", "undeclaredEntries", List.of("README.txt"))
        ));

        fixture.service.execute(fixture.job.getId());

        assertEquals("FAILED", fixture.job.getStatus());
        assertEquals("INVALID_MANIFEST_UNDECLARED_ENTRY", fixture.job.getErrorCode());
        assertEquals("manifest 未声明 ZIP 文件: README.txt", fixture.job.getErrorMessage());
        assertTrue(fixture.job.getErrorDetailsJson().contains("\"path\":\"README.txt\""));
        verify(fixture.sampleRepo, never()).saveAllAndFlush(any());
    }

    @Test
    void rejectsStartingNonPendingJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.job.setStatus("RUNNING");
        when(fixture.jobRepo.findByIdForUpdate(fixture.job.getId())).thenReturn(Optional.of(fixture.job));

        assertThrows(IllegalArgumentException.class, () -> fixture.service.execute(fixture.job.getId()));

        verify(fixture.zipReader, never()).read(any(), anyLong());
    }

    @Test
    void executorMismatchRollsBackSuccessAndCannotMarkFailed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.stubSuccessfulImport();
        when(fixture.jobRepo.completeSuccessIfOwned(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(0);
        when(fixture.jobRepo.markFailedIfOwned(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(0);

        fixture.service.execute(fixture.job.getId());

        assertTrue(fixture.transactionManager.rollbackCount > 0);
        verify(fixture.versionRepo, never()).saveAndFlush(fixture.version);
    }

    @SuppressWarnings("unchecked")
    private static <T> T captureSaved(Object repository, Class<T> type) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        if (repository instanceof DatasetSampleRepository sampleRepository) {
            verify(sampleRepository).saveAllAndFlush((Iterable<DatasetSample>) captor.capture());
        } else if (repository instanceof DatasetSampleDataRepository dataRepository) {
            verify(dataRepository).saveAllAndFlush((Iterable<DatasetSampleData>) captor.capture());
        } else if (repository instanceof DatasetAnnotationRepository annotationRepository) {
            verify(annotationRepository).saveAllAndFlush((Iterable<DatasetAnnotation>) captor.capture());
        } else {
            throw new IllegalArgumentException("unsupported repository: " + type.getName());
        }
        return captor.getValue().get(0);
    }

    private static final class Fixture {
        private final ImportJobRepository jobRepo = mock(ImportJobRepository.class);
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetUploadSessionRepository sessionRepo = mock(DatasetUploadSessionRepository.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo = mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo = mock(DatasetAnnotationRepository.class);
        private final ImportJobSampleFailureRepository failureRepo =
                mock(ImportJobSampleFailureRepository.class);
        private final MinioService minioService = mock(MinioService.class);
        private final ZipCentralDirectoryReader zipReader = mock(ZipCentralDirectoryReader.class);
        private final ManifestZipReader manifestReader = mock(ManifestZipReader.class);
        private final ManifestParser parser = mock(ManifestParser.class);
        private final AutoDirectoryManifestBuilder autoBuilder =
                mock(AutoDirectoryManifestBuilder.class);
        private final SingleModalImportPlanBuilder singleModalBuilder =
                new SingleModalImportPlanBuilder();
        private final DatasetWorkspaceAuditService auditService =
                mock(DatasetWorkspaceAuditService.class);
        private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        private final ImportJob job = job();
        private final DatasetVersion version = version();
        private final DatasetAsset asset = asset();
        private final DatasetPackage datasetPackage = datasetPackage();
        private final DatasetUploadSession session = session();
        private final DatasetVersionPackage versionPackage = versionPackage();
        private final ImportJobService service = new ImportJobService(
                jobRepo,
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                sessionRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                failureRepo,
                minioService,
                zipReader,
                manifestReader,
                parser,
                autoBuilder,
                singleModalBuilder,
                transactionManager,
                auditService
        );

        private void stubContext() throws Exception {
            when(jobRepo.claimPending(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                job.setStatus(invocation.getArgument(2));
                job.setExecutorId(invocation.getArgument(3));
                job.setStartedAt(invocation.getArgument(4));
                job.setHeartbeatAt(invocation.getArgument(4));
                return 1;
            });
            when(jobRepo.findById(job.getId())).thenReturn(Optional.of(job));
            when(jobRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(versionRepo.findByIdAndDeletedFalse(version.getId())).thenReturn(Optional.of(version));
            when(versionRepo.findByIdAndDeletedFalseForUpdate(version.getId()))
                    .thenReturn(Optional.of(version));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId())).thenReturn(Optional.of(asset));
            when(sessionRepo.findByImportJobId(job.getId())).thenReturn(Optional.of(session));
            when(packageRepo.findByIdAndDeletedFalse(datasetPackage.getId()))
                    .thenReturn(Optional.of(datasetPackage));
            when(versionPackageRepo.existsByDatasetVersionIdAndPackageId(
                    version.getId(),
                    datasetPackage.getId()
            )).thenReturn(true);
            when(versionPackageRepo.findByDatasetVersionIdAndPackageId(
                    version.getId(),
                    datasetPackage.getId()
            )).thenReturn(Optional.of(versionPackage));
            when(packageRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(sampleRepo
                    .findByDatasetVersionIdAndDeletedFalseAndExternalIdIn(any(), any()))
                    .thenReturn(List.of());
            when(sampleRepo
                    .findByDatasetVersionIdAndDeletedFalseAndSampleIndexIn(any(), any()))
                    .thenReturn(List.of());
            when(sampleRepo.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(dataRepo.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(annotationRepo.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(jobRepo.completeSuccessIfOwned(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            )).thenAnswer(invocation -> {
                job.setStatus("SUCCESS");
                job.setProgress(100);
                job.setTotalSamples(invocation.getArgument(3));
                job.setImportedSamples(invocation.getArgument(3));
                job.setFinishedAt(invocation.getArgument(4));
                return 1;
            });
            when(jobRepo.markFailedIfOwned(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
            )).thenAnswer(invocation -> {
                job.setStatus("FAILED");
                job.setErrorMessage(invocation.getArgument(3));
                job.setErrorCode(invocation.getArgument(4));
                job.setErrorDetailsJson(invocation.getArgument(5));
                job.setFinishedAt(invocation.getArgument(6));
                return 1;
            });
            String objectName = job.getPackageId() == null
                    ? version.getStoragePath()
                    : datasetPackage.getStoragePath();
            long objectSize = job.getPackageId() == null
                    ? version.getSizeBytes()
                    : datasetPackage.getSizeBytes();
            String manifestPath = job.getPackageId() == null
                    ? session.getManifestPath()
                    : datasetPackage.getManifestPath();
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn(objectSize);
            when(minioService.stat(objectName)).thenReturn(stat);
            when(zipReader.read(objectName, objectSize)).thenReturn(zipEntries());
            when(manifestReader.readManifest(
                    objectName,
                    objectSize,
                    manifestPath
            )).thenReturn("{\"version\":\"1.0\"}");
        }

        private void stubSuccessfulImport() throws Exception {
            stubContext();
            if ("APPEND".equals(versionPackage.getPackageRole())) {
                when(parser.parse(
                        any(),
                        any(),
                        eq(session.getManifestPath()),
                        any(Integer.class),
                        eq(false)
                )).thenAnswer(invocation -> {
                    assertEquals("RUNNING", job.getStatus());
                    assertNotNull(job.getStartedAt());
                    int start = invocation.getArgument(3);
                    return plan(start);
                });
            } else {
                when(parser.parse(
                        any(),
                        any(),
                        eq(session.getManifestPath()),
                        eq(0),
                        eq(false)
                ))
                        .thenAnswer(invocation -> {
                            assertEquals("RUNNING", job.getStatus());
                            assertNotNull(job.getStartedAt());
                            return plan(0);
                        });
            }
        }

        private void asAppendPackage() {
            job.setPackageId(datasetPackage.getId());
            datasetPackage.setStatus("PENDING");
            versionPackage.setPackageRole("APPEND");
            session.setUploadPurpose("APPEND_PACKAGE");
        }

        private ImportJobService serviceWithParser(ManifestParser selectedParser) {
            return new ImportJobService(
                    jobRepo,
                    versionRepo,
                    assetRepo,
                    packageRepo,
                    versionPackageRepo,
                    sessionRepo,
                sampleRepo,
                dataRepo,
                annotationRepo,
                failureRepo,
                minioService,
                zipReader,
                manifestReader,
                    selectedParser,
                    autoBuilder,
                    singleModalBuilder,
                    transactionManager,
                    auditService
            );
        }

        private ManifestImportPlan plan(int sampleIndex) {
            ZipEntryInfo videoEntry = zipEntries().get(0);
            ZipEntryInfo annotationEntry = zipEntries().get(1);
            ManifestData data = new ManifestData(
                    "videos/front.mp4",
                    "VIDEO",
                    "CAM_FRONT",
                    "RGB",
                    0,
                    "mp4",
                    "front.mp4",
                    "video/mp4",
                    Map.of("duration_sec", 12.5),
                    videoEntry
            );
            ManifestAnnotation annotation = new ManifestAnnotation(
                    "labels/front.json",
                    "BBOX",
                    "COCO",
                    data.path(),
                    "front.json",
                    "application/json",
                    Map.of("reviewed", true),
                    annotationEntry
            );
            ManifestSample sample = new ManifestSample(
                    "scene-1",
                    sampleIndex,
                    Map.of("weather", "sunny"),
                    Map.of("split", "train"),
                    List.of(data),
                    List.of(annotation)
            );
            return new ManifestImportPlan("1.0", List.of(sample), 1, 1, 1, List.of());
        }

        private ManifestImportPlan twoSamplePlan(int sampleIndex) {
            ManifestImportPlan first = plan(sampleIndex);
            ManifestSample source = first.samples().get(0);
            ManifestSample second = new ManifestSample(
                    "scene-2",
                    sampleIndex + 1,
                    source.tags(),
                    source.metadata(),
                    source.data(),
                    source.annotations()
            );
            return new ManifestImportPlan(
                    "1.0",
                    List.of(source, second),
                    2,
                    2,
                    2,
                    List.of()
            );
        }

        private List<ZipEntryInfo> zipEntries() {
            return List.of(
                    new ZipEntryInfo(
                            "videos/front.mp4",
                            "videos/front.mp4",
                            8,
                            80,
                            100,
                            123,
                            10,
                            40,
                            false,
                            false
                    ),
                    new ZipEntryInfo(
                            "labels/front.json",
                            "labels/front.json",
                            0,
                            20,
                            20,
                            456,
                            200,
                            240,
                            false,
                            false
                    )
            );
        }

        private ZipEntryInfo zipEntry(String path) {
            return new ZipEntryInfo(
                    path,
                    path,
                    8,
                    80,
                    100,
                    123,
                    10,
                    40,
                    false,
                    false
            );
        }

        private ImportJob job() {
            ImportJob value = new ImportJob();
            value.setId("ijob-1");
            value.setDatasetVersionId("version-2");
            value.setStatus("PENDING");
            value.setProgress(0);
            value.setImportedSamples(0);
            value.setOwnerUserId(7);
            value.setCreatedAt(Instant.now());
            return value;
        }

        private DatasetVersion version() {
            DatasetVersion value = new DatasetVersion();
            value.setId("version-2");
            value.setAssetId("asset-1");
            value.setVersion("v2");
            value.setVersionNo(2);
            value.setStoragePath("users/7/datasets/asset-1/v2/dataset.zip");
            value.setSizeBytes(4096L);
            value.setStatus("DRAFT");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }

        private DatasetAsset asset() {
            DatasetAsset value = new DatasetAsset();
            value.setId("asset-1");
            value.setName("multimodal");
            value.setType("MULTIMODAL");
            value.setOwnerUserId(7);
            value.setDeleted(false);
            return value;
        }

        private DatasetPackage datasetPackage() {
            DatasetPackage value = new DatasetPackage();
            value.setId("dataset-pkg-1");
            value.setDatasetAssetId("asset-1");
            value.setStoragePath("users/7/datasets/asset-1/v2/package-primary.zip");
            value.setFileName("package-primary.zip");
            value.setSizeBytes(4096L);
            value.setManifestPath("manifest.json");
            value.setStatus("READY");
            value.setCreatedAt(Instant.now());
            value.setDeleted(false);
            return value;
        }

        private DatasetUploadSession session() {
            DatasetUploadSession value = new DatasetUploadSession();
            value.setId("upload-1");
            value.setImportJobId("ijob-1");
            value.setVersionId("version-2");
            value.setType("MULTIMODAL");
            value.setManifestPath("manifest.json");
            value.setSampleGrouping("MANIFEST");
            value.setStatus("COMPLETED");
            return value;
        }

        private DatasetVersionPackage versionPackage() {
            DatasetVersionPackage value = new DatasetVersionPackage();
            value.setDatasetVersionId(version.getId());
            value.setPackageId(datasetPackage.getId());
            value.setPackageRole("PRIMARY");
            value.setPackageOrder(0);
            value.setCreatedAt(Instant.now());
            return value;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private int rollbackCount;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbackCount++;
        }
    }
}
