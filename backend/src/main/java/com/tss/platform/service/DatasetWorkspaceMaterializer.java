package com.tss.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.model.ZipEntryInfo;
import com.tss.platform.model.manifest.ManifestData;
import com.tss.platform.model.manifest.ManifestImportPlan;
import com.tss.platform.model.manifest.ManifestSample;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class DatasetWorkspaceMaterializer {

    static final int BATCH_SIZE = 500;

    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE =
            new TypeReference<>() {
            };
    private static final String READY = "READY";
    private static final String PRIMARY = "PRIMARY";

    private final DatasetPackageRepository packageRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final ZipCentralDirectoryReader zipReader;
    private final SingleModalImportPlanBuilder singleModalImportPlanBuilder;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private MinioService minioService;

    public DatasetWorkspaceMaterializer(
            DatasetPackageRepository packageRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ZipCentralDirectoryReader zipReader,
            SingleModalImportPlanBuilder singleModalImportPlanBuilder,
            EntityManager entityManager,
            ObjectMapper objectMapper
    ) {
        this.packageRepo = packageRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.zipReader = zipReader;
        this.singleModalImportPlanBuilder = singleModalImportPlanBuilder;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setMinioService(MinioService minioService) {
        this.minioService = minioService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void materialize(
            DatasetAsset asset,
            DatasetVersion parent,
            DatasetVersion draft
    ) {
        Instant now = Instant.now();
        GeneratedPrimary generatedPrimary = copyPackageRelations(
                asset,
                parent,
                draft,
                now
        );
        if (generatedPrimary != null) {
            if ("ZIP".equals(generatedPrimary.storageKind())) {
                materializeSingleModalZip(
                        asset,
                        parent,
                        draft,
                        generatedPrimary.packageId(),
                        now
                );
            } else {
                materializeSingleRaw(
                        asset,
                        parent,
                        draft,
                        generatedPrimary.packageId(),
                        now
                );
            }
            return;
        }

        Pageable pageable = PageRequest.of(0, BATCH_SIZE);
        while (true) {
            Slice<DatasetSample> parentSamples =
                    sampleRepo.findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                            parent.getId(),
                            pageable
                    );
            if (parentSamples.isEmpty()) {
                return;
            }

            copySampleBatch(parent, draft, parentSamples.getContent(), now);
            entityManager.flush();
            entityManager.clear();

            if (!parentSamples.hasNext()) {
                return;
            }
            pageable = parentSamples.nextPageable();
        }
    }

    private GeneratedPrimary copyPackageRelations(
            DatasetAsset asset,
            DatasetVersion parent,
            DatasetVersion draft,
            Instant now
    ) {
        List<DatasetVersionPackage> parentRelations =
                versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(parent.getId());
        if (parentRelations.isEmpty()) {
            if (parent.getStoragePath() == null || parent.getStoragePath().isBlank()) {
                throw new IllegalArgumentException(
                        "READY dataset version has no package relation or storagePath: "
                                + parent.getId()
                );
            }
            if (isZipBackedSingleModal(asset, parent)) {
                DatasetPackage primaryPackage = createPrimaryPackage(
                        asset,
                        parent,
                        now
                );
                DatasetVersionPackage relation = new DatasetVersionPackage();
                relation.setDatasetVersionId(draft.getId());
                relation.setPackageId(primaryPackage.getId());
                relation.setPackageRole(PRIMARY);
                relation.setPackageOrder(0);
                relation.setCreatedAt(now);
                versionPackageRepo.saveAll(List.of(relation));
                return new GeneratedPrimary(primaryPackage.getId(), "ZIP");
            }
            if (isSingleModal(asset)) {
                validateRawSourceIsUnambiguous(parent);
                DatasetPackage primaryPackage = createRawPrimaryPackage(
                        asset,
                        parent,
                        now
                );
                DatasetVersionPackage relation = new DatasetVersionPackage();
                relation.setDatasetVersionId(draft.getId());
                relation.setPackageId(primaryPackage.getId());
                relation.setPackageRole(PRIMARY);
                relation.setPackageOrder(0);
                relation.setCreatedAt(now);
                versionPackageRepo.save(relation);
                return new GeneratedPrimary(primaryPackage.getId(), "RAW");
            }
            return null;
        }

        List<DatasetVersionPackage> copiedRelations = parentRelations.stream()
                .map(source -> copyPackageRelation(source, draft.getId(), now))
                .toList();
        versionPackageRepo.saveAll(copiedRelations);
        return null;
    }

    private DatasetPackage createPrimaryPackage(
            DatasetAsset asset,
            DatasetVersion parent,
            Instant now
    ) {
        DatasetPackage existing = packageRepo
                .findByStoragePathAndDeletedFalse(parent.getStoragePath())
                .orElse(null);
        if (existing != null) {
            if (!asset.getId().equals(existing.getDatasetAssetId())) {
                throw sourceAmbiguous(parent);
            }
            return existing;
        }
        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setId(id("dataset-pkg"));
        datasetPackage.setDatasetAssetId(asset.getId());
        datasetPackage.setStoragePath(parent.getStoragePath());
        datasetPackage.setFileName(fileName(sourceName(parent)));
        datasetPackage.setSizeBytes(parent.getSizeBytes());
        datasetPackage.setChecksum(parent.getArtifactSha256());
        datasetPackage.setStatus(READY);
        datasetPackage.setStorageKind("ZIP");
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        return packageRepo.saveAndFlush(datasetPackage);
    }

    private DatasetPackage createRawPrimaryPackage(
            DatasetAsset asset,
            DatasetVersion parent,
            Instant now
    ) {
        if (minioService == null) {
            throw sourceAmbiguous(parent);
        }
        DatasetPackage existing = packageRepo
                .findByStoragePathAndDeletedFalse(parent.getStoragePath())
                .orElse(null);
        if (existing != null
                && (!asset.getId().equals(existing.getDatasetAssetId())
                || (!"RAW".equals(existing.getStorageKind())
                && !versionPackageRepo.findByPackageId(existing.getId()).isEmpty()))) {
            throw sourceAmbiguous(parent);
        }
        Long sizeBytes = parent.getSizeBytes();
        String checksum = validSha256(parent.getArtifactSha256())
                ? parent.getArtifactSha256().toLowerCase(Locale.ROOT)
                : null;
        try {
            if (sizeBytes == null || sizeBytes < 0) {
                sizeBytes = minioService.stat(parent.getStoragePath()).size();
            }
            if (checksum == null) {
                try (InputStream inputStream =
                             minioService.downloadStream(parent.getStoragePath())) {
                    checksum = sha256(inputStream);
                }
            }
        } catch (Exception exception) {
            throw sourceAmbiguous(parent);
        }
        DatasetPackage datasetPackage = existing == null
                ? new DatasetPackage()
                : existing;
        if (datasetPackage.getId() == null) {
            datasetPackage.setId(id("dataset-pkg"));
        }
        datasetPackage.setDatasetAssetId(asset.getId());
        datasetPackage.setStoragePath(parent.getStoragePath());
        datasetPackage.setFileName(fileName(sourceName(parent)));
        datasetPackage.setSizeBytes(sizeBytes);
        datasetPackage.setChecksum(checksum);
        datasetPackage.setStatus(READY);
        datasetPackage.setStorageKind("RAW");
        datasetPackage.setCreatedAt(now);
        datasetPackage.setDeleted(false);
        return packageRepo.saveAndFlush(datasetPackage);
    }

    private void validateRawSourceIsUnambiguous(DatasetVersion parent) {
        long sampleCount = sampleRepo
                .countByDatasetVersionIdAndDeletedFalse(parent.getId());
        long dataCount = dataRepo
                .countByDatasetVersionIdAndDeletedFalse(parent.getId());
        long annotationCount = annotationRepo
                .countByDatasetVersionIdAndDeletedFalse(parent.getId());
        boolean emptyMetadata = sampleCount == 0
                && dataCount == 0
                && annotationCount == 0;
        boolean uniqueMapping = sampleCount == 1
                && dataCount == 1
                && annotationCount == 0;
        if (!emptyMetadata && !uniqueMapping) {
            throw sourceAmbiguous(parent);
        }
    }

    private void materializeSingleRaw(
            DatasetAsset asset,
            DatasetVersion parent,
            DatasetVersion draft,
            String packageId,
            Instant now
    ) {
        DatasetPackage datasetPackage = packageRepo.findById(packageId)
                .orElseThrow(() -> sourceAmbiguous(parent));
        List<DatasetSample> parentSamples = sampleRepo
                .findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        parent.getId()
                );
        DatasetSample sample;
        DatasetSampleData data;
        if (parentSamples.isEmpty()) {
            sample = new DatasetSample();
            sample.setId(id("sample"));
            sample.setDatasetVersionId(draft.getId());
            sample.setCreatedByPackageId(packageId);
            sample.setExternalId(baseName(datasetPackage.getFileName()));
            sample.setSampleIndex(0);
            sample.setOwnerUserId(draft.getOwnerUserId());
            sample.setCreatedAt(now);
            sample.setUpdatedAt(now);
            sample.setDeleted(false);

            data = new DatasetSampleData();
            data.setId(id("data"));
            data.setSampleId(sample.getId());
            data.setDatasetVersionId(draft.getId());
            data.setDataType(dataType(asset));
            data.setSeq(0);
            data.setFormat(format(datasetPackage.getFileName()));
            data.setFileName(fileName(datasetPackage.getFileName()));
            data.setContentType(contentType(datasetPackage.getFileName()));
            data.setMetadata(Map.of());
            data.setCreatedAt(now);
            data.setUpdatedAt(now);
            data.setDeleted(false);
        } else {
            DatasetSample sourceSample = parentSamples.get(0);
            List<DatasetSampleData> sourceData = dataRepo
                    .findBySampleIdAndDatasetVersionIdAndDeletedFalseOrderBySeqAscIdAsc(
                            sourceSample.getId(),
                            parent.getId()
                    );
            if (sourceData.size() != 1) {
                throw sourceAmbiguous(parent);
            }
            sample = copySample(sourceSample, draft, now);
            sample.setCreatedByPackageId(packageId);
            data = copyData(sourceData.get(0), sample, draft, now);
            data.setDeleted(false);
            data.setDeletedAt(null);
        }
        data.setPackageId(packageId);
        data.setOriginalPath(fileName(datasetPackage.getFileName()));
        data.setFileName(data.getFileName() == null
                ? fileName(datasetPackage.getFileName())
                : data.getFileName());
        data.setFormat(data.getFormat() == null
                ? format(datasetPackage.getFileName())
                : data.getFormat());
        data.setContentType(data.getContentType() == null
                ? contentType(datasetPackage.getFileName())
                : data.getContentType());
        data.setSizeBytes(datasetPackage.getSizeBytes());
        data.setChecksum(datasetPackage.getChecksum());
        data.setZipEntryOffset(null);
        data.setZipDataOffset(null);
        data.setCompressedSize(null);
        data.setUncompressedSize(null);
        data.setCompressionMethod(null);
        data.setCrc32(null);
        sampleRepo.save(sample);
        dataRepo.save(data);
        entityManager.flush();
    }

    private boolean isZipBackedSingleModal(DatasetAsset asset, DatasetVersion parent) {
        if (asset == null
                || asset.getType() == null
                || asset.getType().isBlank()
                || parent.getSizeBytes() == null) {
            return false;
        }
        String taskType = DatasetTaskType.normalize(asset.getType());
        return !"MULTIMODAL".equals(taskType) && isZip(sourceName(parent));
    }

    private boolean isSingleModal(DatasetAsset asset) {
        return asset != null
                && asset.getType() != null
                && !asset.getType().isBlank()
                && !"MULTIMODAL".equals(DatasetTaskType.normalize(asset.getType()));
    }

    private String sourceName(DatasetVersion version) {
        return version.getFileName() == null || version.getFileName().isBlank()
                ? version.getStoragePath()
                : version.getFileName();
    }

    private boolean isZip(String path) {
        return path != null && path.toLowerCase().endsWith(".zip");
    }

    private void materializeSingleModalZip(
            DatasetAsset asset,
            DatasetVersion parent,
            DatasetVersion draft,
            String packageId,
            Instant now
    ) {
        List<ZipEntryInfo> entries;
        try {
            entries = zipReader.read(parent.getStoragePath(), parent.getSizeBytes());
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "READY single-modal ZIP could not be indexed: "
                            + exception.getMessage(),
                    exception
            );
        }
        ManifestImportPlan plan = singleModalImportPlanBuilder.build(
                DatasetTaskType.normalize(asset.getType()),
                entries,
                0
        );
        List<DatasetSample> samples = new ArrayList<>(plan.totalSamples());
        List<DatasetSampleData> dataItems = new ArrayList<>(plan.totalDataCount());
        for (ManifestSample manifestSample : plan.samples()) {
            DatasetSample sample = toSample(draft, packageId, manifestSample, now);
            samples.add(sample);
            for (ManifestData manifestData : manifestSample.data()) {
                dataItems.add(toSampleData(
                        draft,
                        sample,
                        packageId,
                        manifestData,
                        now
                ));
            }
        }
        sampleRepo.saveAll(samples);
        if (!dataItems.isEmpty()) {
            dataRepo.saveAll(dataItems);
        }
        entityManager.flush();
        entityManager.clear();
    }

    private void copySampleBatch(
            DatasetVersion parent,
            DatasetVersion draft,
            List<DatasetSample> parentSamples,
            Instant now
    ) {
        Map<String, DatasetSample> copiedSampleByParentId = new LinkedHashMap<>();
        List<DatasetSample> copiedSamples = new ArrayList<>(parentSamples.size());
        for (DatasetSample source : parentSamples) {
            DatasetSample copied = copySample(source, draft, now);
            copiedSamples.add(copied);
            copiedSampleByParentId.put(source.getId(), copied);
        }

        List<String> parentSampleIds = new ArrayList<>(copiedSampleByParentId.keySet());
        List<DatasetSampleData> parentData =
                dataRepo.findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        parent.getId(),
                        parentSampleIds
                );
        Map<String, DatasetSampleData> copiedDataByParentId = new LinkedHashMap<>();
        List<DatasetSampleData> copiedData = new ArrayList<>(parentData.size());
        for (DatasetSampleData source : parentData) {
            DatasetSample copiedSample = requireCopiedSample(
                    copiedSampleByParentId,
                    source.getSampleId()
            );
            DatasetSampleData copied = copyData(source, copiedSample, draft, now);
            copiedData.add(copied);
            copiedDataByParentId.put(source.getId(), copied);
        }

        List<DatasetAnnotation> parentAnnotations =
                annotationRepo
                        .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                                parent.getId(),
                                parentSampleIds
                        );
        List<DatasetAnnotation> copiedAnnotations =
                new ArrayList<>(parentAnnotations.size());
        for (DatasetAnnotation source : parentAnnotations) {
            DatasetSample copiedSample = requireCopiedSample(
                    copiedSampleByParentId,
                    source.getSampleId()
            );
            DatasetSampleData copiedReferencedData = null;
            if (source.getSampleDataId() != null) {
                copiedReferencedData = copiedDataByParentId.get(source.getSampleDataId());
                if (copiedReferencedData == null) {
                    throw new IllegalStateException(
                            "annotation references sample data outside copied parent batch: "
                                    + source.getSampleDataId()
                    );
                }
            }
            copiedAnnotations.add(
                    copyAnnotation(source, copiedSample, copiedReferencedData, draft, now)
            );
        }

        sampleRepo.saveAll(copiedSamples);
        if (!copiedData.isEmpty()) {
            dataRepo.saveAll(copiedData);
        }
        if (!copiedAnnotations.isEmpty()) {
            annotationRepo.saveAll(copiedAnnotations);
        }
    }

    private DatasetSample requireCopiedSample(
            Map<String, DatasetSample> copiedSampleByParentId,
            String parentSampleId
    ) {
        DatasetSample copied = copiedSampleByParentId.get(parentSampleId);
        if (copied == null) {
            throw new IllegalStateException(
                    "sample child references sample outside copied parent batch: "
                            + parentSampleId
            );
        }
        return copied;
    }

    private DatasetSample toSample(
            DatasetVersion draft,
            String packageId,
            ManifestSample source,
            Instant now
    ) {
        DatasetSample target = new DatasetSample();
        target.setId(id("sample"));
        target.setDatasetVersionId(draft.getId());
        target.setCreatedByPackageId(packageId);
        target.setExternalId(source.externalId());
        target.setSampleIndex(source.sampleIndex());
        target.setTags(source.tags());
        target.setMetadata(source.metadata());
        target.setOwnerUserId(draft.getOwnerUserId());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeleted(false);
        target.setDeletedAt(null);
        return target;
    }

    private DatasetVersionPackage copyPackageRelation(
            DatasetVersionPackage source,
            String draftVersionId,
            Instant now
    ) {
        DatasetVersionPackage target = new DatasetVersionPackage();
        target.setDatasetVersionId(draftVersionId);
        target.setPackageId(source.getPackageId());
        target.setPackageRole(source.getPackageRole());
        target.setPackageOrder(source.getPackageOrder());
        target.setCreatedAt(now);
        return target;
    }

    private DatasetSample copySample(
            DatasetSample source,
            DatasetVersion draft,
            Instant now
    ) {
        DatasetSample target = new DatasetSample();
        target.setId(id("sample"));
        target.setDatasetVersionId(draft.getId());
        target.setCreatedByPackageId(source.getCreatedByPackageId());
        target.setExternalId(source.getExternalId());
        target.setSampleIndex(source.getSampleIndex());
        target.setTags(copyJson(source.getTags()));
        target.setMetadata(copyJson(source.getMetadata()));
        target.setOwnerUserId(source.getOwnerUserId());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeleted(false);
        target.setDeletedAt(null);
        return target;
    }

    private DatasetSampleData copyData(
            DatasetSampleData source,
            DatasetSample copiedSample,
            DatasetVersion draft,
            Instant now
    ) {
        DatasetSampleData target = new DatasetSampleData();
        target.setId(id("data"));
        target.setSampleId(copiedSample.getId());
        target.setDatasetVersionId(draft.getId());
        target.setPackageId(source.getPackageId());
        target.setDataType(source.getDataType());
        target.setSensor(source.getSensor());
        target.setChannel(source.getChannel());
        target.setSeq(source.getSeq());
        target.setFormat(source.getFormat());
        target.setOriginalPath(source.getOriginalPath());
        target.setFileName(source.getFileName());
        target.setSizeBytes(source.getSizeBytes());
        target.setChecksum(source.getChecksum());
        target.setContentType(source.getContentType());
        target.setZipEntryOffset(source.getZipEntryOffset());
        target.setZipDataOffset(source.getZipDataOffset());
        target.setCompressedSize(source.getCompressedSize());
        target.setUncompressedSize(source.getUncompressedSize());
        target.setCompressionMethod(source.getCompressionMethod());
        target.setCrc32(source.getCrc32());
        target.setMetadata(copyJson(source.getMetadata()));
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeleted(false);
        target.setDeletedAt(null);
        return target;
    }

    private DatasetSampleData toSampleData(
            DatasetVersion draft,
            DatasetSample sample,
            String packageId,
            ManifestData source,
            Instant now
    ) {
        ZipEntryInfo entry = source.zipEntryInfo();
        DatasetSampleData target = new DatasetSampleData();
        target.setId(id("data"));
        target.setSampleId(sample.getId());
        target.setDatasetVersionId(draft.getId());
        target.setPackageId(packageId);
        target.setDataType(source.dataType());
        target.setSensor(source.sensor());
        target.setChannel(source.channel());
        target.setSeq(source.seq());
        target.setFormat(source.format());
        target.setOriginalPath(source.path());
        target.setFileName(source.fileName());
        target.setSizeBytes(entry.uncompressedSize());
        target.setContentType(source.contentType());
        target.setZipEntryOffset(entry.localHeaderOffset());
        target.setZipDataOffset(entry.zipDataOffset());
        target.setCompressedSize(entry.compressedSize());
        target.setUncompressedSize(entry.uncompressedSize());
        target.setCompressionMethod(compressionMethod(entry.method()));
        target.setCrc32(entry.crc32());
        target.setMetadata(source.metadata());
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        return target;
    }

    private static String compressionMethod(int method) {
        return switch (method) {
            case 0 -> "STORED";
            case 8 -> "DEFLATED";
            default -> throw new IllegalArgumentException(
                    "unsupported ZIP compression method: " + method
            );
        };
    }

    private DatasetAnnotation copyAnnotation(
            DatasetAnnotation source,
            DatasetSample copiedSample,
            DatasetSampleData copiedReferencedData,
            DatasetVersion draft,
            Instant now
    ) {
        DatasetAnnotation target = new DatasetAnnotation();
        target.setId(id("annotation"));
        target.setSampleId(copiedSample.getId());
        target.setSampleDataId(
                copiedReferencedData == null ? null : copiedReferencedData.getId()
        );
        target.setDatasetVersionId(draft.getId());
        target.setPackageId(source.getPackageId());
        target.setAnnotationType(source.getAnnotationType());
        target.setFormat(source.getFormat());
        target.setOriginalPath(source.getOriginalPath());
        target.setFileName(source.getFileName());
        target.setSizeBytes(source.getSizeBytes());
        target.setChecksum(source.getChecksum());
        target.setContentType(source.getContentType());
        target.setZipEntryOffset(source.getZipEntryOffset());
        target.setZipDataOffset(source.getZipDataOffset());
        target.setCompressedSize(source.getCompressedSize());
        target.setUncompressedSize(source.getUncompressedSize());
        target.setCompressionMethod(source.getCompressionMethod());
        target.setCrc32(source.getCrc32());
        target.setMetadata(copyJson(source.getMetadata()));
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeleted(false);
        target.setDeletedAt(null);
        return target;
    }

    private Map<String, Object> copyJson(Map<String, Object> source) {
        return source == null ? null : objectMapper.convertValue(source, JSON_MAP_TYPE);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    private static String sha256(InputStream inputStream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static IllegalArgumentException sourceAmbiguous(
            DatasetVersion parent
    ) {
        return new IllegalArgumentException(
                "DATASET_WORKSPACE_SOURCE_AMBIGUOUS: " + parent.getId()
        );
    }

    private static String fileName(String value) {
        if (value == null || value.isBlank()) {
            return "dataset.bin";
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String baseName(String value) {
        String name = fileName(value);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String format(String value) {
        String name = fileName(value);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "bin"
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String dataType(DatasetAsset asset) {
        String task = DatasetTaskType.normalize(asset.getType());
        return switch (task) {
            case "CV" -> "IMAGE";
            case "NLP" -> "TEXT";
            case "POINT_CLOUD", "ROBOT" -> "POINT_CLOUD";
            default -> "OTHER";
        };
    }

    private static String contentType(String value) {
        return switch (format(value)) {
            case "txt" -> "text/plain";
            case "json" -> "application/json";
            case "jsonl" -> "application/x-ndjson";
            case "xml" -> "application/xml";
            case "csv" -> "text/csv";
            case "yaml", "yml" -> "application/yaml";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "mp4" -> "video/mp4";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }

    private record GeneratedPrimary(String packageId, String storageKind) {
    }
}
