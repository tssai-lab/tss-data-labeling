package com.tss.platform.service;

import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatasetVersionFileCountServiceTest {

    private final DatasetSampleDataRepository dataRepo =
            mock(DatasetSampleDataRepository.class);
    private final DatasetAnnotationRepository annotationRepo =
            mock(DatasetAnnotationRepository.class);
    private final ZipCentralDirectoryReader zipReader =
            mock(ZipCentralDirectoryReader.class);
    private final DatasetVersionRepository versionRepo =
            mock(DatasetVersionRepository.class);
    private final DatasetVersionFileCountService service =
            new DatasetVersionFileCountService(dataRepo, annotationRepo, zipReader, versionRepo);

    @Test
    void returnsPersistedFileCountWithoutRecomputing() {
        DatasetVersion version = version("dataset.zip", "users/7/datasets/a/v1/dataset.zip");
        version.setFileCount(42L);

        Long count = service.countCurrentVersionFiles(asset("CV"), version);

        assertEquals(42L, count);
        verifyNoInteractions(dataRepo, annotationRepo, zipReader, versionRepo);
    }

    @Test
    void countsMultimodalDataAndAnnotationFiles() {
        DatasetAsset asset = asset("MULTIMODAL");
        DatasetVersion version = version("dataset.zip", "users/7/datasets/a/v1/dataset.zip");
        when(dataRepo.countByDatasetVersionId(version.getId())).thenReturn(3L);
        when(annotationRepo.countByDatasetVersionId(version.getId())).thenReturn(2L);

        Long count = service.countCurrentVersionFiles(asset, version);

        assertEquals(5L, count);
        verify(versionRepo).updateFileCountIfAbsent(version.getId(), 5L);
        verify(versionRepo, never()).saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void countsSingleModalMetadataBackedVersionFromSamples() {
        DatasetAsset asset = asset("CV");
        DatasetVersion version = version("dataset.zip", "users/7/datasets/a/v2/dataset.zip");
        when(dataRepo.countByDatasetVersionId(version.getId())).thenReturn(4L);
        when(annotationRepo.countByDatasetVersionId(version.getId())).thenReturn(1L);

        Long count = service.countCurrentVersionFiles(asset, version);

        assertEquals(5L, count);
    }

    @Test
    void countsNonArchiveVersionAsOneFile() {
        DatasetAsset asset = asset("NLP");
        DatasetVersion version = version("dataset.jsonl", "users/7/datasets/a/v1/dataset.jsonl");

        Long count = service.countCurrentVersionFiles(asset, version);

        assertEquals(1L, count);
    }

    @Test
    void countsNonDirectoryZipEntries() throws Exception {
        DatasetAsset asset = asset("CV");
        DatasetVersion version = version("dataset.zip", "users/7/datasets/a/v1/dataset.zip");
        version.setSizeBytes(1024L);
        when(zipReader.read(version.getStoragePath(), version.getSizeBytes()))
                .thenReturn(List.of(
                        zipEntry("images/a.jpg", false),
                        zipEntry("images/", true),
                        zipEntry("labels/a.txt", false)
                ));

        Long count = service.countCurrentVersionFiles(asset, version);

        assertEquals(2L, count);
        verify(versionRepo).updateFileCountIfAbsent(version.getId(), 2L);
        verify(versionRepo, never()).saveAndFlush(any(DatasetVersion.class));
    }

    @Test
    void returnsNullWithoutPersistingWhenZipCountFails() throws Exception {
        DatasetAsset asset = asset("CV");
        DatasetVersion version = version("dataset.zip", "users/7/datasets/a/v1/dataset.zip");
        version.setSizeBytes(1024L);
        when(zipReader.read(version.getStoragePath(), version.getSizeBytes()))
                .thenThrow(new IllegalArgumentException("bad zip"));

        Long count = service.countCurrentVersionFiles(asset, version);

        assertNull(count);
        assertNull(version.getFileCount());
        verify(versionRepo, never()).saveAndFlush(any(DatasetVersion.class));
        verify(versionRepo, never()).updateFileCountIfAbsent(anyString(), anyLong());
    }

    @Test
    void returnsNullWhenCurrentVersionIsMissing() {
        assertNull(service.countCurrentVersionFiles(asset("CV"), null));
    }

    @Test
    void returnsNullWhenCurrentVersionStorageIsMissing() {
        assertNull(service.countCurrentVersionFiles(asset("CV"), version("dataset.zip", null)));
    }

    private static DatasetAsset asset(String type) {
        DatasetAsset asset = new DatasetAsset();
        asset.setId("asset-1");
        asset.setType(type);
        return asset;
    }

    private static DatasetVersion version(String fileName, String storagePath) {
        DatasetVersion version = new DatasetVersion();
        version.setId("version-1");
        version.setFileName(fileName);
        version.setStoragePath(storagePath);
        version.setSizeBytes(1L);
        return version;
    }

    private static ZipEntryInfo zipEntry(String path, boolean directory) {
        return new ZipEntryInfo(
                path,
                path,
                8,
                1L,
                1L,
                1L,
                0L,
                0L,
                false,
                directory
        );
    }
}
