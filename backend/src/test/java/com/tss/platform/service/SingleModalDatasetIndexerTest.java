package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleModalDatasetIndexerTest {

    @Test
    void indexesRawNlpObjectAsOneStoredSample() throws Exception {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope("document.txt", 12L);
        fixture.stubSize(12L);

        long count = fixture.indexer.indexNewVersion(scope.asset, scope.version);

        assertEquals(1L, count);
        ArgumentCaptor<List<DatasetSample>> samples = listCaptor();
        ArgumentCaptor<List<DatasetSampleData>> data = listCaptor();
        verify(fixture.sampleRepo).saveAllAndFlush(samples.capture());
        verify(fixture.dataRepo).saveAllAndFlush(data.capture());
        assertEquals(1, samples.getValue().size());
        DatasetSampleData indexed = data.getValue().get(0);
        assertNull(indexed.getPackageId());
        assertEquals(0L, indexed.getZipDataOffset());
        assertEquals(12L, indexed.getCompressedSize());
        assertEquals("STORED", indexed.getCompressionMethod());
    }

    @Test
    void indexesZipThroughPrimaryPackageAndCentralDirectory() throws Exception {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope("dataset.zip", 200L);
        fixture.stubSize(200L);
        ZipEntryInfo entry = new ZipEntryInfo(
                "texts/a.txt", "texts/a.txt", 8, 10, 12, 99, 20, 40,
                false, false
        );
        when(fixture.zipReader.read(scope.version.getStoragePath(), 200L))
                .thenReturn(List.of(entry));
        when(fixture.packageRepo.findByStoragePathAndDeletedFalse(
                scope.version.getStoragePath()
        )).thenReturn(Optional.empty());
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        long count = fixture.indexer.indexNewVersion(scope.asset, scope.version);

        assertEquals(1L, count);
        ArgumentCaptor<DatasetPackage> datasetPackage =
                ArgumentCaptor.forClass(DatasetPackage.class);
        verify(fixture.packageRepo).saveAndFlush(datasetPackage.capture());
        ArgumentCaptor<DatasetVersionPackage> relation =
                ArgumentCaptor.forClass(DatasetVersionPackage.class);
        verify(fixture.versionPackageRepo).saveAndFlush(relation.capture());
        assertEquals("PRIMARY", relation.getValue().getPackageRole());
        ArgumentCaptor<List<DatasetSampleData>> data = listCaptor();
        verify(fixture.dataRepo).saveAllAndFlush(data.capture());
        assertEquals(datasetPackage.getValue().getId(), data.getValue().get(0).getPackageId());
        assertEquals(40L, data.getValue().get(0).getZipDataOffset());
        assertEquals("DEFLATED", data.getValue().get(0).getCompressionMethod());
    }

    @Test
    void missingHistoricalObjectDemotesReadyVersionAndClearsCurrent() throws Exception {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope("document.txt", 12L);
        fixture.stubLocked(scope);
        when(fixture.minioService.stat(scope.version.getStoragePath()))
                .thenThrow(new IllegalArgumentException("NoSuchKey"));

        SingleModalDatasetIndexer.EnsureResult result =
                fixture.indexer.ensureIndexed(scope.version.getId());

        assertFalse(result.successful());
        assertFalse(result.storageUnavailable());
        assertEquals("DRAFT", scope.version.getStatus());
        assertNull(scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
        verify(fixture.assetRepo).saveAndFlush(scope.asset);
    }

    @Test
    void transientHistoricalStorageFailureKeepsReadyStateAndCurrentPointer() throws Exception {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope("document.txt", 12L);
        fixture.stubLocked(scope);
        when(fixture.minioService.stat(scope.version.getStoragePath()))
                .thenThrow(new IllegalStateException("connection reset"));

        SingleModalDatasetIndexer.EnsureResult result =
                fixture.indexer.ensureIndexed(scope.version.getId());

        assertTrue(result.storageUnavailable());
        assertEquals("READY", scope.version.getStatus());
        assertEquals(scope.version.getId(), scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo, never()).saveAndFlush(any());
        verify(fixture.assetRepo, never()).saveAndFlush(any());
    }

    @Test
    void malformedHistoricalZipDemotesReadyVersionAndClearsCurrent() throws Exception {
        Fixture fixture = new Fixture();
        Scope scope = fixture.scope("dataset.zip", 200L);
        fixture.stubLocked(scope);
        fixture.stubSize(200L);
        when(fixture.zipReader.read(scope.version.getStoragePath(), 200L))
                .thenThrow(new IllegalArgumentException("Invalid ZIP central directory header"));

        SingleModalDatasetIndexer.EnsureResult result =
                fixture.indexer.ensureIndexed(scope.version.getId());

        assertFalse(result.successful());
        assertFalse(result.storageUnavailable());
        assertEquals("DRAFT", scope.version.getStatus());
        assertNull(scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
        verify(fixture.assetRepo).saveAndFlush(scope.asset);
    }

    @Test
    void malformedHistoricalJsonDemotesReadyVersionAndClearsCurrent() throws Exception {
        Fixture fixture = new Fixture();
        byte[] content = "{\"text\":".getBytes(StandardCharsets.UTF_8);
        Scope scope = fixture.scope("broken.json", content.length);
        fixture.stubLocked(scope);
        fixture.stubSize(content.length);
        when(fixture.minioService.downloadStream(scope.version.getStoragePath()))
                .thenReturn(new ByteArrayInputStream(content));

        SingleModalDatasetIndexer.EnsureResult result =
                fixture.indexer.ensureIndexed(scope.version.getId());

        assertFalse(result.successful());
        assertFalse(result.storageUnavailable());
        assertEquals("DRAFT", scope.version.getStatus());
        assertNull(scope.asset.getCurrentVersionId());
        verify(fixture.versionRepo).saveAndFlush(scope.version);
        verify(fixture.assetRepo).saveAndFlush(scope.asset);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private record Scope(DatasetAsset asset, DatasetVersion version) {
    }

    private static final class Fixture {
        private final DatasetVersionRepository versionRepo = mock(DatasetVersionRepository.class);
        private final DatasetAssetRepository assetRepo = mock(DatasetAssetRepository.class);
        private final DatasetPackageRepository packageRepo = mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetSampleRepository sampleRepo = mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final ZipCentralDirectoryReader zipReader = mock(ZipCentralDirectoryReader.class);
        private final MinioService minioService = mock(MinioService.class);
        private final SingleModalDatasetIndexer indexer = new SingleModalDatasetIndexer(
                versionRepo,
                assetRepo,
                packageRepo,
                versionPackageRepo,
                sampleRepo,
                dataRepo,
                zipReader,
                new SingleModalImportPlanBuilder(),
                minioService
        );

        private Fixture() {
            when(sampleRepo.countByDatasetVersionIdAndDeletedFalse(any())).thenReturn(0L);
            when(sampleRepo.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(dataRepo.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        private Scope scope(String fileName, long size) {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setType("NLP");
            DatasetVersion version = new DatasetVersion();
            version.setId("version-1");
            version.setAssetId(asset.getId());
            version.setFileName(fileName);
            version.setStoragePath("users/7/datasets/asset-1/v1/" + fileName);
            version.setSizeBytes(size);
            version.setStatus("READY");
            version.setOwnerUserId(7);
            asset.setCurrentVersionId(version.getId());
            return new Scope(asset, version);
        }

        private void stubSize(long size) throws Exception {
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn(size);
            when(minioService.stat(any())).thenReturn(stat);
        }

        private void stubLocked(Scope scope) {
            when(versionRepo.findByIdAndDeletedFalseForUpdate(scope.version.getId()))
                    .thenReturn(Optional.of(scope.version));
            when(assetRepo.findByIdAndDeletedFalseForUpdate(scope.asset.getId()))
                    .thenReturn(Optional.of(scope.asset));
        }
    }
}
