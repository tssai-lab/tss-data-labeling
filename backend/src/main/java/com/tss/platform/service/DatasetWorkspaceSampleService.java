package com.tss.platform.service;

import com.tss.platform.dto.DatasetAnnotationDto;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetWorkspaceSampleListItemDto;
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

import java.util.List;

@Service
public class DatasetWorkspaceSampleService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String VERSION_NOT_FOUND =
            "dataset workspace version not found or no permission";
    private static final String SAMPLE_NOT_FOUND =
            "dataset workspace sample not found or no permission";

    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final AuthContext authContext;

    public DatasetWorkspaceSampleService(
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
    public PageResponse<DatasetWorkspaceSampleListItemDto> listSamples(
            String draftVersionId,
            Integer page,
            Integer pageSize,
            Boolean includeDeleted
    ) {
        DatasetVersion draft = requireDraftVersion(draftVersionId, VERSION_NOT_FOUND);
        int resolvedPage = resolvePage(page);
        int resolvedPageSize = resolvePageSize(pageSize);
        Sort sort = Sort.by(
                Sort.Order.asc("sampleIndex"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id")
        );
        PageRequest pageable =
                PageRequest.of(resolvedPage - 1, resolvedPageSize, sort);
        Page<DatasetSample> result = Boolean.TRUE.equals(includeDeleted)
                ? sampleRepo.findByDatasetVersionId(draft.getId(), pageable)
                : sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                        draft.getId(),
                        pageable
                );

        PageResponse<DatasetWorkspaceSampleListItemDto> response =
                new PageResponse<>();
        response.setData(result.getContent().stream()
                .map(DatasetWorkspaceSampleService::toListItem)
                .toList());
        response.setTotal(result.getTotalElements());
        response.setPage(resolvedPage);
        response.setPageSize(resolvedPageSize);
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    @Transactional(readOnly = true)
    public DatasetSampleDetailDto getSample(String sampleId) {
        DatasetSample sample = requireWorkspaceSample(sampleId);
        DatasetSampleDetailDto dto = new DatasetSampleDetailDto();
        copySampleFields(sample, dto);
        dto.setData(loadData(sample).stream()
                .map(DatasetWorkspaceSampleService::toDataDto)
                .toList());
        dto.setAnnotations(loadAnnotations(sample).stream()
                .map(DatasetWorkspaceSampleService::toAnnotationDto)
                .toList());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<DatasetSampleDataDto> listSampleData(String sampleId) {
        DatasetSample sample = requireWorkspaceSample(sampleId);
        return loadData(sample).stream()
                .map(DatasetWorkspaceSampleService::toDataDto)
                .toList();
    }

    private DatasetSample requireWorkspaceSample(String sampleId) {
        if (sampleId == null || sampleId.isBlank()) {
            throw new IllegalArgumentException(SAMPLE_NOT_FOUND);
        }
        DatasetSample sample = sampleRepo.findByIdAndDeletedFalse(sampleId)
                .orElseThrow(() -> new IllegalArgumentException(SAMPLE_NOT_FOUND));
        requireDraftVersion(sample.getDatasetVersionId(), SAMPLE_NOT_FOUND);
        return sample;
    }

    private DatasetVersion requireDraftVersion(String versionId, String errorMessage) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> new IllegalArgumentException(errorMessage));
        if (!"DRAFT".equals(version.getStatus())) {
            throw new IllegalArgumentException(errorMessage);
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElseThrow(() -> new IllegalArgumentException(errorMessage));
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw new IllegalArgumentException(errorMessage);
        }
        return version;
    }

    private List<DatasetSampleData> loadData(DatasetSample sample) {
        return dataRepo.findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                sample.getId(),
                sample.getDatasetVersionId()
        );
    }

    private List<DatasetAnnotation> loadAnnotations(DatasetSample sample) {
        return annotationRepo.findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                sample.getId(),
                sample.getDatasetVersionId()
        );
    }

    private static DatasetWorkspaceSampleListItemDto toListItem(
            DatasetSample sample
    ) {
        DatasetWorkspaceSampleListItemDto dto =
                new DatasetWorkspaceSampleListItemDto();
        dto.setSampleId(sample.getId());
        dto.setDatasetVersionId(sample.getDatasetVersionId());
        dto.setExternalId(sample.getExternalId());
        dto.setSampleIndex(sample.getSampleIndex());
        dto.setTags(sample.getTags());
        dto.setMetadata(sample.getMetadata());
        dto.setCreatedAt(sample.getCreatedAt());
        dto.setDeleted(Boolean.TRUE.equals(sample.getDeleted()));
        dto.setDeletedAt(sample.getDeletedAt());
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

    private static int resolvePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private static int resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
