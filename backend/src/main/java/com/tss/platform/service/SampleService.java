package com.tss.platform.service;

import com.tss.platform.dto.DatasetAnnotationDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdAnnotationDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdDataDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdSampleDto;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetSampleListItemDto;
import com.tss.platform.dto.PageResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SampleService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String VERSION_NOT_FOUND =
            "dataset version not found or no permission";
    private static final String SAMPLE_NOT_FOUND =
            "dataset sample not found or no permission";

    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;

    public SampleService(
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            AuthContext authContext
    ) {
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public PageResponse<DatasetSampleListItemDto> listSamples(
            String versionId,
            Integer page,
            Integer pageSize
    ) {
        DatasetVersion version = requireReadyVersion(versionId, VERSION_NOT_FOUND);
        int resolvedPage = resolvePage(page);
        int resolvedPageSize = resolvePageSize(pageSize);
        Sort sort = Sort.by(
                Sort.Order.asc("sampleIndex"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id")
        );
        Page<DatasetSample> result = sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                version.getId(),
                PageRequest.of(resolvedPage - 1, resolvedPageSize, sort)
        );

        PageResponse<DatasetSampleListItemDto> response = new PageResponse<>();
        response.setData(result.getContent().stream().map(SampleService::toListItem).toList());
        response.setTotal(result.getTotalElements());
        response.setPage(resolvedPage);
        response.setPageSize(resolvedPageSize);
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<DatasetMultimodalExternalIdSampleDto> findMultimodalByExternalId(
            String externalId,
            Collection<String> datasetVersionIds,
            Integer page,
            Integer pageSize
    ) {
        String resolvedExternalId = normalizeExternalId(externalId);
        List<String> resolvedVersionIds = normalizeDatasetVersionIds(datasetVersionIds);
        requireReadyVersions(resolvedVersionIds);

        int resolvedPage = resolvePage(page);
        int resolvedPageSize = resolvePageSize(pageSize);
        Sort sort = Sort.by(
                Sort.Order.asc("datasetVersionId"),
                Sort.Order.asc("sampleIndex"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id")
        );
        Page<DatasetSample> samples =
                sampleRepo.findByDatasetVersionIdInAndExternalIdAndDeletedFalse(
                        resolvedVersionIds,
                        resolvedExternalId,
                        PageRequest.of(resolvedPage - 1, resolvedPageSize, sort)
                );

        List<String> sampleIds = samples.getContent().stream()
                .map(DatasetSample::getId)
                .toList();
        Map<String, List<DatasetSampleData>> dataBySampleId =
                dataBySampleId(sampleIds);
        Map<String, List<DatasetAnnotation>> annotationsBySampleId =
                annotationsBySampleId(sampleIds);

        PageResponse<DatasetMultimodalExternalIdSampleDto> response = new PageResponse<>();
        response.setData(samples.getContent().stream()
                .map(sample -> toMultimodalExternalIdSample(
                        sample,
                        dataBySampleId.getOrDefault(sample.getId(), List.of()),
                        annotationsBySampleId.getOrDefault(sample.getId(), List.of())
                ))
                .toList());
        response.setTotal(samples.getTotalElements());
        response.setPage(resolvedPage);
        response.setPageSize(resolvedPageSize);
        response.setTotalPages(samples.getTotalPages());
        return response;
    }

    @Transactional(readOnly = true)
    public DatasetSampleDetailDto getSample(String sampleId) {
        DatasetSample sample = requireAuthorizedSample(sampleId);
        List<DatasetSampleDataDto> data = loadData(sample).stream()
                .map(SampleService::toDataDto)
                .toList();
        List<DatasetAnnotationDto> annotations = loadAnnotations(sample).stream()
                .map(SampleService::toAnnotationDto)
                .toList();

        DatasetSampleDetailDto dto = new DatasetSampleDetailDto();
        copySampleFields(sample, dto);
        dto.setData(data);
        dto.setAnnotations(annotations);
        return dto;
    }

    @Transactional(readOnly = true)
    public List<DatasetSampleDataDto> listSampleData(String sampleId) {
        DatasetSample sample = requireAuthorizedSample(sampleId);
        return loadData(sample).stream().map(SampleService::toDataDto).toList();
    }

    private DatasetSample requireAuthorizedSample(String sampleId) {
        if (sampleId == null || sampleId.isBlank()) {
            throw new IllegalArgumentException(SAMPLE_NOT_FOUND);
        }
        DatasetSample sample = sampleRepo.findByIdAndDeletedFalse(sampleId)
                .orElseThrow(() -> new IllegalArgumentException(SAMPLE_NOT_FOUND));
        requireReadyVersion(sample.getDatasetVersionId(), SAMPLE_NOT_FOUND);
        return sample;
    }

    private DatasetVersion requireReadyVersion(String versionId, String errorMessage) {
        if (versionId == null || versionId.isBlank()) {
            throw hiddenDatasetVersionException(errorMessage);
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> hiddenDatasetVersionException(errorMessage));
        if (!"READY".equals(version.getStatus())) {
            throw hiddenDatasetVersionException(errorMessage);
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> hiddenDatasetVersionException(errorMessage));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw hiddenDatasetVersionException(errorMessage);
        }
        return version;
    }

    private void requireReadyVersions(List<String> versionIds) {
        List<DatasetVersion> versions = versionRepo.findByIdInAndDeletedFalse(versionIds);
        if (versions.size() != versionIds.size()) {
            throw new DatasetVersionAccessException(VERSION_NOT_FOUND);
        }
        Map<String, DatasetVersion> versionById = versions.stream()
                .collect(Collectors.toMap(DatasetVersion::getId, Function.identity()));
        for (String versionId : versionIds) {
            DatasetVersion version = versionById.get(versionId);
            if (version == null || !"READY".equals(version.getStatus())) {
                throw new DatasetVersionAccessException(VERSION_NOT_FOUND);
            }
            DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                    .orElseThrow(() -> new DatasetVersionAccessException(VERSION_NOT_FOUND));
            if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
                throw new DatasetVersionAccessException(VERSION_NOT_FOUND);
            }
        }
    }

    private List<DatasetSampleData> loadData(DatasetSample sample) {
        return dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                sample.getId(),
                sample.getDatasetVersionId()
        );
    }

    private Map<String, List<DatasetSampleData>> dataBySampleId(
            Collection<String> sampleIds
    ) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return dataRepo.findBySampleIdInOrderBySampleIdAscSeqAscIdAsc(sampleIds)
                .stream()
                .collect(Collectors.groupingBy(DatasetSampleData::getSampleId));
    }

    private Map<String, List<DatasetAnnotation>> annotationsBySampleId(
            Collection<String> sampleIds
    ) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return annotationRepo
                .findBySampleIdInOrderBySampleIdAscCreatedAtAscIdAsc(sampleIds)
                .stream()
                .collect(Collectors.groupingBy(DatasetAnnotation::getSampleId));
    }

    private List<DatasetAnnotation> loadAnnotations(DatasetSample sample) {
        return annotationRepo.findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                sample.getId(),
                sample.getDatasetVersionId()
        );
    }

    private static DatasetSampleListItemDto toListItem(DatasetSample sample) {
        DatasetSampleListItemDto dto = new DatasetSampleListItemDto();
        dto.setSampleId(sample.getId());
        dto.setDatasetVersionId(sample.getDatasetVersionId());
        dto.setExternalId(sample.getExternalId());
        dto.setSampleIndex(sample.getSampleIndex());
        dto.setTags(sample.getTags());
        dto.setMetadata(sample.getMetadata());
        dto.setCreatedAt(sample.getCreatedAt());
        return dto;
    }

    private static void copySampleFields(
            DatasetSample sample,
            DatasetSampleDetailDto dto
    ) {
        dto.setSampleId(sample.getId());
        dto.setDatasetVersionId(sample.getDatasetVersionId());
        dto.setExternalId(sample.getExternalId());
        dto.setSampleIndex(sample.getSampleIndex());
        dto.setTags(sample.getTags());
        dto.setMetadata(sample.getMetadata());
        dto.setCreatedAt(sample.getCreatedAt());
    }

    private static DatasetSampleDataDto toDataDto(DatasetSampleData data) {
        DatasetSampleDataDto dto = new DatasetSampleDataDto();
        dto.setSampleDataId(data.getId());
        dto.setDataType(data.getDataType());
        dto.setSensor(data.getSensor());
        dto.setChannel(data.getChannel());
        dto.setSeq(data.getSeq());
        dto.setFormat(data.getFormat());
        dto.setFileName(data.getFileName());
        dto.setSizeBytes(data.getSizeBytes());
        dto.setChecksum(data.getChecksum());
        dto.setContentType(data.getContentType());
        dto.setMetadata(data.getMetadata());
        dto.setCreatedAt(data.getCreatedAt());
        return dto;
    }

    private static DatasetAnnotationDto toAnnotationDto(DatasetAnnotation annotation) {
        DatasetAnnotationDto dto = new DatasetAnnotationDto();
        dto.setAnnotationId(annotation.getId());
        dto.setSampleDataId(annotation.getSampleDataId());
        dto.setAnnotationType(annotation.getAnnotationType());
        dto.setFormat(annotation.getFormat());
        dto.setFileName(annotation.getFileName());
        dto.setSizeBytes(annotation.getSizeBytes());
        dto.setChecksum(annotation.getChecksum());
        dto.setContentType(annotation.getContentType());
        dto.setMetadata(annotation.getMetadata());
        dto.setCreatedAt(annotation.getCreatedAt());
        return dto;
    }

    private static DatasetMultimodalExternalIdSampleDto toMultimodalExternalIdSample(
            DatasetSample sample,
            List<DatasetSampleData> data,
            List<DatasetAnnotation> annotations
    ) {
        DatasetMultimodalExternalIdSampleDto dto =
                new DatasetMultimodalExternalIdSampleDto();
        dto.setDatasetVersionId(sample.getDatasetVersionId());
        dto.setSampleId(sample.getId());
        dto.setExternalId(sample.getExternalId());
        dto.setSampleIndex(sample.getSampleIndex());
        dto.setData(data.stream()
                .map(SampleService::toMultimodalExternalIdData)
                .toList());
        dto.setAnnotations(annotations.stream()
                .map(SampleService::toMultimodalExternalIdAnnotation)
                .toList());
        return dto;
    }

    private static DatasetMultimodalExternalIdDataDto toMultimodalExternalIdData(
            DatasetSampleData data
    ) {
        DatasetMultimodalExternalIdDataDto dto =
                new DatasetMultimodalExternalIdDataDto();
        dto.setSampleDataId(data.getId());
        dto.setDataType(data.getDataType());
        dto.setSensor(data.getSensor());
        dto.setChannel(data.getChannel());
        dto.setSeq(data.getSeq());
        dto.setFormat(data.getFormat());
        dto.setFileName(data.getFileName());
        dto.setSizeBytes(data.getSizeBytes());
        dto.setChecksum(data.getChecksum());
        dto.setContentType(data.getContentType());
        dto.setPreviewUrl("/api/dataset-sample-data/" + data.getId() + "/preview");
        dto.setDownloadUrl("/api/dataset-sample-data/" + data.getId() + "/download");
        return dto;
    }

    private static DatasetMultimodalExternalIdAnnotationDto toMultimodalExternalIdAnnotation(
            DatasetAnnotation annotation
    ) {
        DatasetMultimodalExternalIdAnnotationDto dto =
                new DatasetMultimodalExternalIdAnnotationDto();
        dto.setAnnotationId(annotation.getId());
        dto.setSampleDataId(annotation.getSampleDataId());
        dto.setAnnotationType(annotation.getAnnotationType());
        dto.setFormat(annotation.getFormat());
        dto.setFileName(annotation.getFileName());
        dto.setSizeBytes(annotation.getSizeBytes());
        dto.setChecksum(annotation.getChecksum());
        dto.setContentType(annotation.getContentType());
        dto.setDownloadUrl("/api/dataset-annotations/" + annotation.getId() + "/download");
        return dto;
    }

    private static String normalizeExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required");
        }
        return externalId.trim();
    }

    private static List<String> normalizeDatasetVersionIds(
            Collection<String> datasetVersionIds
    ) {
        if (datasetVersionIds == null || datasetVersionIds.isEmpty()) {
            throw new IllegalArgumentException("datasetVersionIds are required");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : datasetVersionIds) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String versionId = part.trim();
                if (!versionId.isBlank()) {
                    normalized.add(versionId);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("datasetVersionIds are required");
        }
        return List.copyOf(normalized);
    }

    private static IllegalArgumentException hiddenDatasetVersionException(
            String errorMessage
    ) {
        if (VERSION_NOT_FOUND.equals(errorMessage)) {
            return new DatasetVersionAccessException(errorMessage);
        }
        return new IllegalArgumentException(errorMessage);
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

    public static class DatasetVersionAccessException extends IllegalArgumentException {

        public DatasetVersionAccessException(String message) {
            super(message);
        }
    }
}
