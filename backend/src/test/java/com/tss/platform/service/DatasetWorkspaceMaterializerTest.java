package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetWorkspaceMaterializerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void copiesPackageRelationsAndIndependentSampleGraph() {
        Fixture fixture = new Fixture();
        DatasetSample parentSample = parentSample();
        DatasetSampleData parentData = parentData(parentSample.getId());
        DatasetAnnotation parentAnnotation =
                parentAnnotation(parentSample.getId(), parentData.getId());
        DatasetVersionPackage primary = relation("pkg-primary", "PRIMARY", 0);
        DatasetVersionPackage append = relation("pkg-append", "APPEND", 1);

        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of(primary, append));
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        eq(fixture.parent.getId()),
                        any()
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(parentSample),
                        PageRequest.of(0, DatasetWorkspaceMaterializer.BATCH_SIZE),
                        false
                ));
        when(fixture.dataRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of(parentData));
        when(fixture.annotationRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of(parentAnnotation));

        fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft);

        ArgumentCaptor<List<DatasetVersionPackage>> relationCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.versionPackageRepo).saveAll(relationCaptor.capture());
        List<DatasetVersionPackage> copiedRelations = relationCaptor.getValue();
        assertEquals(2, copiedRelations.size());
        assertRelation(copiedRelations.get(0), fixture.draft.getId(), "pkg-primary", "PRIMARY", 0);
        assertRelation(copiedRelations.get(1), fixture.draft.getId(), "pkg-append", "APPEND", 1);

        ArgumentCaptor<List<DatasetSample>> sampleCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<DatasetSampleData>> dataCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<DatasetAnnotation>> annotationCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.sampleRepo).saveAll(sampleCaptor.capture());
        verify(fixture.dataRepo).saveAll(dataCaptor.capture());
        verify(fixture.annotationRepo).saveAll(annotationCaptor.capture());

        DatasetSample copiedSample = sampleCaptor.getValue().get(0);
        DatasetSampleData copiedData = dataCaptor.getValue().get(0);
        DatasetAnnotation copiedAnnotation = annotationCaptor.getValue().get(0);

        assertNotEquals(parentSample.getId(), copiedSample.getId());
        assertEquals(fixture.draft.getId(), copiedSample.getDatasetVersionId());
        assertEquals(parentSample.getExternalId(), copiedSample.getExternalId());
        assertEquals(parentSample.getSampleIndex(), copiedSample.getSampleIndex());
        assertEquals(parentSample.getCreatedByPackageId(), copiedSample.getCreatedByPackageId());
        assertFalse(Boolean.TRUE.equals(copiedSample.getDeleted()));
        assertNull(copiedSample.getDeletedAt());

        assertNotEquals(parentData.getId(), copiedData.getId());
        assertEquals(copiedSample.getId(), copiedData.getSampleId());
        assertEquals(fixture.draft.getId(), copiedData.getDatasetVersionId());
        assertDataFields(parentData, copiedData);

        assertNotEquals(parentAnnotation.getId(), copiedAnnotation.getId());
        assertEquals(copiedSample.getId(), copiedAnnotation.getSampleId());
        assertEquals(copiedData.getId(), copiedAnnotation.getSampleDataId());
        assertEquals(fixture.draft.getId(), copiedAnnotation.getDatasetVersionId());
        assertAnnotationFields(parentAnnotation, copiedAnnotation);

        assertNotSame(parentSample.getTags(), copiedSample.getTags());
        assertNotSame(parentSample.getMetadata(), copiedSample.getMetadata());
        Map<String, Object> copiedNested =
                (Map<String, Object>) copiedSample.getMetadata().get("nested");
        copiedNested.put("key", "draft");
        copiedSample.setDeleted(true);
        assertEquals(
                "parent",
                ((Map<String, Object>) parentSample.getMetadata().get("nested")).get("key")
        );
        assertFalse(Boolean.TRUE.equals(parentSample.getDeleted()));
        assertTrue(Boolean.TRUE.equals(copiedSample.getDeleted()));

        verify(fixture.entityManager).flush();
        verify(fixture.entityManager).clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void copiesTheSameNumberOfUndeletedParentSamples() {
        Fixture fixture = new Fixture();
        DatasetSample first = parentSample();
        DatasetSample second = parentSample();
        second.setId("parent-sample-2");
        second.setExternalId("scene-002");
        second.setSampleIndex(8);

        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        eq(fixture.parent.getId()),
                        any()
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(first, second),
                        PageRequest.of(0, DatasetWorkspaceMaterializer.BATCH_SIZE),
                        false
                ));
        when(fixture.dataRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of());
        when(fixture.annotationRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of());

        fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft);

        ArgumentCaptor<List<DatasetSample>> sampleCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.sampleRepo).saveAll(sampleCaptor.capture());
        assertEquals(2, sampleCaptor.getValue().size());
        assertEquals(
                List.of("scene-001", "scene-002"),
                sampleCaptor.getValue().stream().map(DatasetSample::getExternalId).toList()
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void keepsNullPackageIdsForLegacyStoragePathFallback() {
        Fixture fixture = new Fixture();
        DatasetSample parentSample = parentSample();
        parentSample.setCreatedByPackageId(null);
        DatasetSampleData parentData = parentData(parentSample.getId());
        parentData.setPackageId(null);

        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        eq(fixture.parent.getId()),
                        any()
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(parentSample),
                        PageRequest.of(0, DatasetWorkspaceMaterializer.BATCH_SIZE),
                        false
                ));
        when(fixture.dataRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of(parentData));
        when(fixture.annotationRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of());

        fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft);

        verify(fixture.packageRepo, never()).saveAndFlush(any());

        verify(fixture.versionPackageRepo, never()).saveAll(any());
        ArgumentCaptor<List<DatasetSample>> sampleCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<DatasetSampleData>> dataCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.sampleRepo).saveAll(sampleCaptor.capture());
        verify(fixture.dataRepo).saveAll(dataCaptor.capture());
        assertNull(sampleCaptor.getValue().get(0).getCreatedByPackageId());
        assertNull(dataCaptor.getValue().get(0).getPackageId());
        assertEquals(fixture.parent.getStoragePath(), fixture.draft.getStoragePath());
    }

    @Test
    void rejectsParentWithoutPackagesOrLegacyStoragePath() {
        Fixture fixture = new Fixture();
        fixture.parent.setStoragePath(null);
        fixture.draft.setStoragePath(null);
        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft)
        );

        assertEquals(
                "READY dataset version has no package relation or storagePath: ready-2",
                error.getMessage()
        );
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        any(),
                        any()
                );
    }

    @Test
    void rejectsSingleModalParentWithoutPackagesWhenSourceIsNotZip() {
        Fixture fixture = new Fixture();
        fixture.asset.setType("NLP");
        fixture.parent.setFileName("dataset.txt");
        fixture.parent.setStoragePath("users/7/datasets/asset-1/v2/dataset.txt");
        fixture.parent.setSizeBytes(1024L);
        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft)
        );

        assertEquals(
                "DATASET_WORKSPACE_SOURCE_AMBIGUOUS: ready-2",
                error.getMessage()
        );
        verify(fixture.sampleRepo, never())
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        any(),
                        any()
                );
    }

    @Test
    void rejectsAnnotationWhoseSampleDataWasNotCopied() {
        Fixture fixture = new Fixture();
        DatasetSample parentSample = parentSample();
        DatasetAnnotation parentAnnotation =
                parentAnnotation(parentSample.getId(), "parent-data-missing");

        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());
        when(fixture.sampleRepo
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        eq(fixture.parent.getId()),
                        any()
                ))
                .thenReturn(new SliceImpl<>(
                        List.of(parentSample),
                        PageRequest.of(0, DatasetWorkspaceMaterializer.BATCH_SIZE),
                        false
                ));
        when(fixture.dataRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of());
        when(fixture.annotationRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        eq(fixture.parent.getId()),
                        anyCollection()
                ))
                .thenReturn(List.of(parentAnnotation));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft)
        );

        assertEquals(
                "annotation references sample data outside copied parent batch: parent-data-missing",
                error.getMessage()
        );
        verify(fixture.annotationRepo, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void materializesZipBackedSingleModalParentIntoPrimaryPackageAndSamples()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.asset.setType("CV");
        fixture.parent.setFileName("dataset.zip");
        fixture.parent.setSizeBytes(1024L);
        when(fixture.versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                fixture.parent.getId()
        )).thenReturn(List.of());
        when(fixture.packageRepo.saveAndFlush(any())).thenAnswer(invocation ->
                invocation.getArgument(0)
        );
        when(fixture.zipReader.read(
                fixture.parent.getStoragePath(),
                fixture.parent.getSizeBytes()
        )).thenReturn(List.of(
                zipEntry("images/front.jpg"),
                zipEntry("labels/front.json")
        ));

        fixture.materializer.materialize(fixture.asset, fixture.parent, fixture.draft);

        ArgumentCaptor<DatasetPackage> packageCaptor =
                ArgumentCaptor.forClass(DatasetPackage.class);
        verify(fixture.packageRepo).saveAndFlush(packageCaptor.capture());
        DatasetPackage primaryPackage = packageCaptor.getValue();
        assertEquals(fixture.asset.getId(), primaryPackage.getDatasetAssetId());
        assertEquals(fixture.parent.getStoragePath(), primaryPackage.getStoragePath());
        assertEquals("dataset.zip", primaryPackage.getFileName());
        assertEquals(1024L, primaryPackage.getSizeBytes());
        assertEquals("READY", primaryPackage.getStatus());

        ArgumentCaptor<List<DatasetVersionPackage>> relationCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.versionPackageRepo).saveAll(relationCaptor.capture());
        assertEquals(1, relationCaptor.getValue().size());
        DatasetVersionPackage relation = relationCaptor.getValue().get(0);
        assertEquals(fixture.draft.getId(), relation.getDatasetVersionId());
        assertEquals(primaryPackage.getId(), relation.getPackageId());
        assertEquals("PRIMARY", relation.getPackageRole());
        assertEquals(0, relation.getPackageOrder());

        ArgumentCaptor<List<DatasetSample>> sampleCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<DatasetSampleData>> dataCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.sampleRepo).saveAll(sampleCaptor.capture());
        verify(fixture.dataRepo).saveAll(dataCaptor.capture());
        assertEquals(
                List.of("images/front.jpg", "labels/front.json"),
                sampleCaptor.getValue().stream().map(DatasetSample::getExternalId).toList()
        );
        assertEquals(
                List.of(primaryPackage.getId(), primaryPackage.getId()),
                sampleCaptor.getValue().stream()
                        .map(DatasetSample::getCreatedByPackageId)
                        .toList()
        );
        assertEquals(
                List.of("IMAGE", "TEXT"),
                dataCaptor.getValue().stream().map(DatasetSampleData::getDataType).toList()
        );
        assertEquals(
                List.of(primaryPackage.getId(), primaryPackage.getId()),
                dataCaptor.getValue().stream().map(DatasetSampleData::getPackageId).toList()
        );
        verify(fixture.annotationRepo, never()).saveAll(any());
    }

    private static void assertRelation(
            DatasetVersionPackage relation,
            String versionId,
            String packageId,
            String role,
            int order
    ) {
        assertEquals(versionId, relation.getDatasetVersionId());
        assertEquals(packageId, relation.getPackageId());
        assertEquals(role, relation.getPackageRole());
        assertEquals(order, relation.getPackageOrder());
    }

    private static void assertDataFields(
            DatasetSampleData parent,
            DatasetSampleData copied
    ) {
        assertEquals(parent.getDataType(), copied.getDataType());
        assertEquals(parent.getSensor(), copied.getSensor());
        assertEquals(parent.getChannel(), copied.getChannel());
        assertEquals(parent.getSeq(), copied.getSeq());
        assertEquals(parent.getFormat(), copied.getFormat());
        assertEquals(parent.getOriginalPath(), copied.getOriginalPath());
        assertEquals(parent.getFileName(), copied.getFileName());
        assertEquals(parent.getSizeBytes(), copied.getSizeBytes());
        assertEquals(parent.getChecksum(), copied.getChecksum());
        assertEquals(parent.getContentType(), copied.getContentType());
        assertEquals(parent.getPackageId(), copied.getPackageId());
        assertEquals(parent.getZipEntryOffset(), copied.getZipEntryOffset());
        assertEquals(parent.getZipDataOffset(), copied.getZipDataOffset());
        assertEquals(parent.getCompressedSize(), copied.getCompressedSize());
        assertEquals(parent.getUncompressedSize(), copied.getUncompressedSize());
        assertEquals(parent.getCompressionMethod(), copied.getCompressionMethod());
        assertEquals(parent.getCrc32(), copied.getCrc32());
        assertEquals(parent.getMetadata(), copied.getMetadata());
    }

    private static void assertAnnotationFields(
            DatasetAnnotation parent,
            DatasetAnnotation copied
    ) {
        assertEquals(parent.getAnnotationType(), copied.getAnnotationType());
        assertEquals(parent.getFormat(), copied.getFormat());
        assertEquals(parent.getOriginalPath(), copied.getOriginalPath());
        assertEquals(parent.getFileName(), copied.getFileName());
        assertEquals(parent.getSizeBytes(), copied.getSizeBytes());
        assertEquals(parent.getChecksum(), copied.getChecksum());
        assertEquals(parent.getContentType(), copied.getContentType());
        assertEquals(parent.getPackageId(), copied.getPackageId());
        assertEquals(parent.getZipEntryOffset(), copied.getZipEntryOffset());
        assertEquals(parent.getZipDataOffset(), copied.getZipDataOffset());
        assertEquals(parent.getCompressedSize(), copied.getCompressedSize());
        assertEquals(parent.getUncompressedSize(), copied.getUncompressedSize());
        assertEquals(parent.getCompressionMethod(), copied.getCompressionMethod());
        assertEquals(parent.getCrc32(), copied.getCrc32());
        assertEquals(parent.getMetadata(), copied.getMetadata());
    }

    private static DatasetSample parentSample() {
        DatasetSample sample = new DatasetSample();
        sample.setId("parent-sample-1");
        sample.setDatasetVersionId("ready-2");
        sample.setCreatedByPackageId("pkg-primary");
        sample.setExternalId("scene-001");
        sample.setSampleIndex(7);
        sample.setTags(new LinkedHashMap<>(Map.of("split", "train")));
        sample.setMetadata(new LinkedHashMap<>(Map.of(
                "nested",
                new LinkedHashMap<>(Map.of("key", "parent"))
        )));
        sample.setOwnerUserId(7);
        sample.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        sample.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        sample.setDeleted(false);
        return sample;
    }

    private static DatasetSampleData parentData(String sampleId) {
        DatasetSampleData data = new DatasetSampleData();
        data.setId("parent-data-1");
        data.setSampleId(sampleId);
        data.setDatasetVersionId("ready-2");
        data.setPackageId("pkg-primary");
        data.setDataType("VIDEO");
        data.setSensor("CAM_FRONT");
        data.setChannel("RGB");
        data.setSeq(3);
        data.setFormat("mp4");
        data.setOriginalPath("samples/scene-001/front.mp4");
        data.setFileName("front.mp4");
        data.setSizeBytes(1000L);
        data.setChecksum("sha256:data");
        data.setContentType("video/mp4");
        data.setZipEntryOffset(101L);
        data.setZipDataOffset(151L);
        data.setCompressedSize(800L);
        data.setUncompressedSize(1000L);
        data.setCompressionMethod("STORED");
        data.setCrc32(42L);
        data.setMetadata(new LinkedHashMap<>(Map.of("fps", 25)));
        data.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        data.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return data;
    }

    private static DatasetAnnotation parentAnnotation(String sampleId, String sampleDataId) {
        DatasetAnnotation annotation = new DatasetAnnotation();
        annotation.setId("parent-annotation-1");
        annotation.setSampleId(sampleId);
        annotation.setSampleDataId(sampleDataId);
        annotation.setDatasetVersionId("ready-2");
        annotation.setPackageId("pkg-append");
        annotation.setAnnotationType("BBOX");
        annotation.setFormat("COCO");
        annotation.setOriginalPath("annotations/scene-001.json");
        annotation.setFileName("scene-001.json");
        annotation.setSizeBytes(200L);
        annotation.setChecksum("sha256:annotation");
        annotation.setContentType("application/json");
        annotation.setZipEntryOffset(2001L);
        annotation.setZipDataOffset(2051L);
        annotation.setCompressedSize(150L);
        annotation.setUncompressedSize(200L);
        annotation.setCompressionMethod("DEFLATED");
        annotation.setCrc32(84L);
        annotation.setMetadata(new LinkedHashMap<>(Map.of("reviewed", true)));
        annotation.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return annotation;
    }

    private static DatasetVersionPackage relation(String packageId, String role, int order) {
        DatasetVersionPackage relation = new DatasetVersionPackage();
        relation.setDatasetVersionId("ready-2");
        relation.setPackageId(packageId);
        relation.setPackageRole(role);
        relation.setPackageOrder(order);
        relation.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return relation;
    }

    private static ZipEntryInfo zipEntry(String path) {
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
                false
        );
    }

    private static final class Fixture {
        private final DatasetPackageRepository packageRepo =
                mock(DatasetPackageRepository.class);
        private final DatasetVersionPackageRepository versionPackageRepo =
                mock(DatasetVersionPackageRepository.class);
        private final DatasetSampleRepository sampleRepo =
                mock(DatasetSampleRepository.class);
        private final DatasetSampleDataRepository dataRepo =
                mock(DatasetSampleDataRepository.class);
        private final DatasetAnnotationRepository annotationRepo =
                mock(DatasetAnnotationRepository.class);
        private final ZipCentralDirectoryReader zipReader =
                mock(ZipCentralDirectoryReader.class);
        private final EntityManager entityManager = mock(EntityManager.class);
        private final DatasetWorkspaceMaterializer materializer =
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
        private final DatasetAsset asset = asset();
        private final DatasetVersion parent = version("ready-2", "READY");
        private final DatasetVersion draft = version("draft-3", "DRAFT");

        private static DatasetAsset asset() {
            DatasetAsset asset = new DatasetAsset();
            asset.setId("asset-1");
            asset.setType("MULTIMODAL");
            asset.setOwnerUserId(7);
            asset.setDeleted(false);
            return asset;
        }

        private static DatasetVersion version(String id, String status) {
            DatasetVersion version = new DatasetVersion();
            version.setId(id);
            version.setAssetId("asset-1");
            version.setStatus(status);
            version.setStoragePath("users/7/datasets/asset-1/v2/data.zip");
            version.setOwnerUserId(7);
            version.setDeleted(false);
            return version;
        }
    }
}
