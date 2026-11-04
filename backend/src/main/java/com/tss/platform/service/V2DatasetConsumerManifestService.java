package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.v2.V2DatasetConsumerAnnotation;
import com.tss.platform.dto.v2.V2DatasetConsumerData;
import com.tss.platform.dto.v2.V2DatasetConsumerManifest;
import com.tss.platform.dto.v2.V2DatasetConsumerSample;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class V2DatasetConsumerManifestService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final AuthContext authContext;
    private SingleModalDatasetIndexer singleModalDatasetIndexer;

    public V2DatasetConsumerManifestService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            AuthContext authContext
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.authContext = authContext;
    }

    @Autowired(required = false)
    void setSingleModalDatasetIndexer(SingleModalDatasetIndexer singleModalDatasetIndexer) {
        this.singleModalDatasetIndexer = singleModalDatasetIndexer;
    }

    @Transactional(readOnly = true)
    public V2DatasetConsumerManifest get(
            String versionId,
            Integer page,
            Integer pageSize
    ) {
        DatasetVersion version = requireVersion(versionId);
        DatasetAsset asset = requireAsset(version);
        ensureConsumable(version);
        int resolvedPage = resolvePage(page);
        int resolvedPageSize = resolvePageSize(pageSize);
        Page<DatasetSample> samples = sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                version.getId(),
                PageRequest.of(
                        resolvedPage - 1,
                        resolvedPageSize,
                        Sort.by(
                                Sort.Order.asc("sampleIndex"),
                                Sort.Order.asc("createdAt"),
                                Sort.Order.asc("id")
                        )
                )
        );
        List<String> sampleIds = samples.getContent().stream()
                .map(DatasetSample::getId)
                .toList();
        Map<String, List<DatasetSampleData>> dataBySampleId = dataBySampleId(
                version.getId(),
                sampleIds
        );
        Map<String, List<DatasetAnnotation>> annotationsBySampleId =
                annotationsBySampleId(version.getId(), sampleIds);

        V2DatasetConsumerManifest manifest = new V2DatasetConsumerManifest();
        manifest.setDatasetVersionId(version.getId());
        manifest.setDatasetId(asset.getId());
        manifest.setType(asset.getType());
        manifest.setVersionLabel(displayVersion(version));
        manifest.setStatus(version.getStatus());
        manifest.setPage(resolvedPage);
        manifest.setPageSize(resolvedPageSize);
        manifest.setTotalSamples(samples.getTotalElements());
        manifest.setSamples(samples.getContent().stream()
                .map(sample -> toSample(
                        sample,
                        dataBySampleId.getOrDefault(sample.getId(), List.of()),
                        annotationsBySampleId.getOrDefault(sample.getId(), List.of())
                ))
                .toList());
        return manifest;
    }

    private void ensureConsumable(DatasetVersion version) {
        long existing = sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId());
        if (existing > 0) {
            return;
        }
        if (singleModalDatasetIndexer == null) {
            throw notConsumable("数据集版本没有可消费样本");
        }
        SingleModalDatasetIndexer.EnsureResult result =
                singleModalDatasetIndexer.ensureIndexed(version.getId());
        if (result.successful()
                && sampleRepo.countByDatasetVersionIdAndDeletedFalse(version.getId()) > 0) {
            return;
        }
        if (result.storageUnavailable()) {
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATASET_STORAGE_UNAVAILABLE",
                    "数据集存储暂时不可用，请稍后重试"
            );
        }
        throw notConsumable(result.message());
    }

    private V2BusinessException notConsumable(String message) {
        return new V2BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DATASET_NOT_CONSUMABLE",
                message == null || message.isBlank()
                        ? "数据集版本没有可消费样本"
                        : message
        );
    }

    private DatasetVersion requireVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw notFound();
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(this::notFound);
        if (!"READY".equals(version.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATASET_NOT_READY",
                    "数据集版本尚未发布，不能作为消费清单交付"
            );
        }
        return version;
    }

    private DatasetAsset requireAsset(DatasetVersion version) {
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        return asset;
    }

    private Map<String, List<DatasetSampleData>> dataBySampleId(
            String versionId,
            Collection<String> sampleIds
    ) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return dataRepo.findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscSeqAscIdAsc(
                        versionId,
                        sampleIds
                )
                .stream()
                .collect(Collectors.groupingBy(DatasetSampleData::getSampleId));
    }

    private Map<String, List<DatasetAnnotation>> annotationsBySampleId(
            String versionId,
            Collection<String> sampleIds
    ) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return annotationRepo
                .findByDatasetVersionIdAndSampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(
                        versionId,
                        sampleIds
                )
                .stream()
                .collect(Collectors.groupingBy(DatasetAnnotation::getSampleId));
    }

    private V2DatasetConsumerSample toSample(
            DatasetSample source,
            List<DatasetSampleData> data,
            List<DatasetAnnotation> annotations
    ) {
        V2DatasetConsumerSample target = new V2DatasetConsumerSample();
        target.setSampleId(source.getId());
        target.setExternalId(source.getExternalId());
        target.setSampleIndex(source.getSampleIndex());
        target.setTags(source.getTags());
        target.setMetadata(source.getMetadata());
        target.setData(data.stream().map(this::toData).toList());
        target.setAnnotations(annotations.stream().map(this::toAnnotation).toList());
        return target;
    }

    private V2DatasetConsumerData toData(DatasetSampleData source) {
        V2DatasetConsumerData target = new V2DatasetConsumerData();
        target.setSampleDataId(source.getId());
        target.setDataType(source.getDataType());
        target.setSensor(source.getSensor());
        target.setChannel(source.getChannel());
        target.setSeq(source.getSeq());
        target.setFormat(source.getFormat());
        target.setFileName(source.getFileName());
        target.setSizeBytes(source.getSizeBytes());
        target.setChecksum(source.getChecksum());
        target.setContentType(source.getContentType());
        target.setMetadata(source.getMetadata());
        target.setPreviewUrl("/api/dataset-sample-data/" + source.getId() + "/preview");
        target.setDownloadUrl("/api/dataset-sample-data/" + source.getId() + "/download");
        return target;
    }

    private V2DatasetConsumerAnnotation toAnnotation(DatasetAnnotation source) {
        V2DatasetConsumerAnnotation target = new V2DatasetConsumerAnnotation();
        target.setAnnotationId(source.getId());
        target.setSampleDataId(source.getSampleDataId());
        target.setAnnotationType(source.getAnnotationType());
        target.setFormat(source.getFormat());
        target.setFileName(source.getFileName());
        target.setSizeBytes(source.getSizeBytes());
        target.setChecksum(source.getChecksum());
        target.setContentType(source.getContentType());
        target.setMetadata(source.getMetadata());
        target.setDownloadUrl("/api/dataset-annotations/" + source.getId() + "/download");
        return target;
    }

    private static int resolvePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private static int resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static String displayVersion(DatasetVersion version) {
        if (version.getVersionLabel() != null && !version.getVersionLabel().isBlank()) {
            return version.getVersionLabel();
        }
        return version.getVersion();
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_NOT_FOUND",
                "数据集版本不存在或无权访问"
        );
    }
}
