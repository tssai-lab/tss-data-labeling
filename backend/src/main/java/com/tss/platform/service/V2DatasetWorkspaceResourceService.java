package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetAnnotationResource;
import com.tss.platform.dto.v2.V2DatasetContentUpdateRequest;
import com.tss.platform.dto.v2.V2DatasetDataResource;
import com.tss.platform.dto.v2.V2DatasetInlineAnnotationCreateRequest;
import com.tss.platform.dto.v2.V2DatasetInlineDataCreateRequest;
import com.tss.platform.dto.v2.V2DatasetMutationResult;
import com.tss.platform.dto.v2.V2DatasetSampleCreateRequest;
import com.tss.platform.dto.v2.V2DatasetSampleDetail;
import com.tss.platform.dto.v2.V2DatasetSampleListItem;
import com.tss.platform.dto.v2.V2DatasetWorkspaceMutationRequest;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class V2DatasetWorkspaceResourceService {

    private static final DatasetWorkspaceResourcePolicy FIELD_POLICY = new DatasetWorkspaceResourcePolicy("INVALID_REQUEST");

    private static final Set<String> SAMPLE_PATCH_FIELDS =
            Set.of("expectedWorkspaceRevision", "tags", "metadata");
    private static final Set<String> DATA_PATCH_FIELDS = Set.of(
            "expectedWorkspaceRevision",
            "dataType",
            "sensor",
            "channel",
            "seq",
            "format",
            "fileName",
            "contentType",
            "metadata"
    );
    private static final Set<String> ANNOTATION_PATCH_FIELDS = Set.of(
            "expectedWorkspaceRevision",
            "sampleDataId",
            "annotationType",
            "format",
            "fileName",
            "contentType",
            "metadata"
    );

    private final DatasetWorkspaceCommandService commandService;
    private final DatasetWorkspaceTextFilePolicy textFilePolicy;
    private final DatasetWorkspaceRawStorageService rawStorageService;
    private final DatasetWorkspaceAuditService auditService;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final ObjectMapper objectMapper;

    public V2DatasetWorkspaceResourceService(
            DatasetWorkspaceCommandService commandService,
            DatasetWorkspaceTextFilePolicy textFilePolicy,
            DatasetWorkspaceRawStorageService rawStorageService,
            DatasetWorkspaceAuditService auditService,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            ObjectMapper objectMapper
    ) {
        this.commandService = commandService;
        this.textFilePolicy = textFilePolicy;
        this.rawStorageService = rawStorageService;
        this.auditService = auditService;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<V2DatasetSampleListItem> listSamples(
            String workspaceId,
            int page,
            int pageSize,
            boolean includeDeleted
    ) {
        commandService.requireReadable(workspaceId);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);
        Pageable pageable = PageRequest.of(
                safePage - 1,
                safePageSize,
                Sort.by(
                        Sort.Order.asc("sampleIndex"),
                        Sort.Order.asc("id")
                )
        );
        Page<DatasetSample> result = includeDeleted
                ? sampleRepo.findByDatasetVersionId(workspaceId, pageable)
                : sampleRepo.findByDatasetVersionIdAndDeletedFalse(
                        workspaceId,
                        pageable
                );
        PageResponse<V2DatasetSampleListItem> response = new PageResponse<>();
        response.setData(result.getContent().stream().map(DatasetWorkspaceResourceMapper::toSampleItem).toList());
        response.setTotal(result.getTotalElements());
        response.setPage(safePage);
        response.setPageSize(safePageSize);
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    @Transactional(readOnly = true)
    public V2DatasetSampleDetail getSample(
            String workspaceId,
            String sampleId
    ) {
        commandService.requireReadable(workspaceId);
        DatasetSample sample = requireSample(workspaceId, sampleId, false);
        return toSampleDetail(sample);
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetSampleDetail> createSample(
            String workspaceId,
            V2DatasetSampleCreateRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        String externalId = requiredText(
                request.externalId(),
                "externalId 不能为空",
                255
        );
        if (sampleRepo.existsByDatasetVersionIdAndExternalId(
                workspaceId,
                externalId
        )) {
            throw conflict(
                    "SAMPLE_EXTERNAL_ID_CONFLICT",
                    "externalId 在工作区内已存在"
            );
        }
        Instant now = Instant.now();
        DatasetSample sample = new DatasetSample();
        sample.setId("dss-" + compactUuid());
        sample.setDatasetVersionId(workspaceId);
        sample.setExternalId(externalId);
        Integer maxIndex = sampleRepo.findMaxSampleIndexByDatasetVersionId(workspaceId);
        sample.setSampleIndex((maxIndex == null ? -1 : maxIndex) + 1);
        sample.setTags(copyMap(request.tags()));
        sample.setMetadata(copyMap(request.metadata()));
        sample.setOwnerUserId(access.asset().getOwnerUserId());
        sample.setCreatedAt(now);
        sample.setUpdatedAt(now);
        sample.setDeleted(false);
        try {
            sample = sampleRepo.saveAndFlush(sample);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "SAMPLE_IDENTITY_CONFLICT",
                    "样本 externalId 或 sampleIndex 已冲突，请刷新后重试"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "SAMPLE_CREATED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getId(),
                revision,
                null
        );
        return mutation(workspaceId, revision, toSampleDetail(sample));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetSampleDetail> patchSample(
            String workspaceId,
            String sampleId,
            JsonNode patch
    ) {
        requirePatchObject(patch, SAMPLE_PATCH_FIELDS);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        expectedRevision(patch)
                );
        DatasetSample sample = requireSample(workspaceId, sampleId, true);
        requireNotDeleted(sample.getDeleted(), "DATASET_SAMPLE", sampleId);
        if (patch.has("tags")) {
            sample.setTags(mergePatchMap(sample.getTags(), patch.get("tags")));
        }
        if (patch.has("metadata")) {
            sample.setMetadata(mergePatchMap(
                    sample.getMetadata(),
                    patch.get("metadata")
            ));
        }
        sample.setUpdatedAt(Instant.now());
        sample = sampleRepo.save(sample);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "SAMPLE_UPDATED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getId(),
                revision,
                null
        );
        return mutation(workspaceId, revision, toSampleDetail(sample));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetSampleDetail> deleteSample(
            String workspaceId,
            String sampleId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        DatasetSample sample = requireSample(workspaceId, sampleId, true);
        if (Boolean.TRUE.equals(sample.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    toSampleDetail(sample)
            );
        }
        Instant now = Instant.now();
        sample.setDeleted(true);
        sample.setDeletedAt(now);
        sample.setUpdatedAt(now);
        sample = sampleRepo.save(sample);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "SAMPLE_DELETED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getId(),
                revision,
                null
        );
        return mutation(workspaceId, revision, toSampleDetail(sample));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetSampleDetail> restoreSample(
            String workspaceId,
            String sampleId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        DatasetSample sample = requireSample(workspaceId, sampleId, true);
        if (!Boolean.TRUE.equals(sample.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    toSampleDetail(sample)
            );
        }
        sample.setDeleted(false);
        sample.setDeletedAt(null);
        sample.setUpdatedAt(Instant.now());
        try {
            sample = sampleRepo.saveAndFlush(sample);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "SAMPLE_RESTORE_CONFLICT",
                    "样本恢复后将产生 externalId 或 sampleIndex 冲突"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "SAMPLE_RESTORED",
                "DATASET_SAMPLE",
                sample.getId(),
                null,
                sample.getId(),
                revision,
                null
        );
        return mutation(workspaceId, revision, toSampleDetail(sample));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetDataResource> createInlineData(
            String workspaceId,
            String sampleId,
            V2DatasetInlineDataCreateRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        DatasetSample sample = requireActiveSample(workspaceId, sampleId);
        String dataType = dataType(request.dataType());
        int seq = nonNegative(request.seq(), "seq");
        DatasetWorkspaceTextFilePolicy.ValidatedText text =
                textFilePolicy.validate(
                        request.content(),
                        request.fileName(),
                        request.format(),
                        request.contentType()
                );
        DatasetPackage datasetPackage = rawStorageService.storeText(
                access.asset(),
                access.workspace(),
                text
        );
        Instant now = Instant.now();
        DatasetSampleData data = new DatasetSampleData();
        data.setId("dsd-" + compactUuid());
        data.setSampleId(sample.getId());
        data.setDatasetVersionId(workspaceId);
        data.setPackageId(datasetPackage.getId());
        data.setDataType(dataType);
        data.setSensor(optionalText(request.sensor(), 64));
        data.setChannel(optionalText(request.channel(), 32));
        data.setSeq(seq);
        data.setFormat(text.format());
        data.setOriginalPath(text.fileName());
        data.setFileName(text.fileName());
        data.setSizeBytes((long) text.bytes().length);
        data.setChecksum(text.sha256());
        data.setContentType(text.contentType());
        data.setMetadata(copyMap(request.metadata()));
        data.setCreatedAt(now);
        data.setUpdatedAt(now);
        data.setDeleted(false);
        clearZipIndex(data);
        try {
            data = dataRepo.saveAndFlush(data);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "DATA_RESOURCE_IDENTITY_CONFLICT",
                    "同一样本的数据类型、传感器、通道和序号组合已存在"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "DATA_RESOURCE_CREATED",
                "DATA",
                data.getId(),
                datasetPackage.getId(),
                sampleId,
                revision,
                data.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toData(data));
    }

    @Transactional(readOnly = true)
    public V2DatasetDataResource getData(
            String workspaceId,
            String sampleId,
            String dataId
    ) {
        commandService.requireReadable(workspaceId);
        requireSample(workspaceId, sampleId, false);
        return DatasetWorkspaceResourceMapper.toData(readData(workspaceId, sampleId, dataId));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetDataResource> patchData(
            String workspaceId,
            String sampleId,
            String dataId,
            JsonNode patch
    ) {
        requirePatchObject(patch, DATA_PATCH_FIELDS);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        expectedRevision(patch)
                );
        requireActiveSample(workspaceId, sampleId);
        DatasetSampleData data = requireData(workspaceId, sampleId, dataId);
        requireNotDeleted(data.getDeleted(), "DATA", dataId);
        if (patch.has("dataType")) {
            data.setDataType(dataType(textValue(patch.get("dataType"), "dataType")));
        }
        if (patch.has("sensor")) {
            data.setSensor(optionalTextNode(patch.get("sensor"), 64, "sensor"));
        }
        if (patch.has("channel")) {
            data.setChannel(optionalTextNode(patch.get("channel"), 32, "channel"));
        }
        if (patch.has("seq")) {
            JsonNode node = patch.get("seq");
            if (node == null || !node.isIntegralNumber()) {
                throw invalid("seq 必须是非负整数");
            }
            data.setSeq(nonNegative(node.intValue(), "seq"));
        }
        if (patch.has("fileName")) {
            data.setFileName(textValue(patch.get("fileName"), "fileName"));
        }
        if (patch.has("format")) {
            data.setFormat(textValue(patch.get("format"), "format"));
        }
        if (patch.has("contentType")) {
            data.setContentType(textValue(patch.get("contentType"), "contentType"));
        }
        if (patch.has("metadata")) {
            data.setMetadata(mergePatchMap(data.getMetadata(), patch.get("metadata")));
        }
        DatasetWorkspaceTextFilePolicy.Descriptor descriptor =
                textFilePolicy.validateDescriptor(
                        data.getFileName(),
                        data.getFormat(),
                        data.getContentType()
                );
        data.setFileName(descriptor.fileName());
        data.setFormat(descriptor.format());
        data.setContentType(descriptor.contentType());
        data.setUpdatedAt(Instant.now());
        try {
            data = dataRepo.saveAndFlush(data);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "DATA_RESOURCE_IDENTITY_CONFLICT",
                    "同一样本的数据类型、传感器、通道和序号组合已存在"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "DATA_RESOURCE_UPDATED",
                "DATA",
                data.getId(),
                data.getPackageId(),
                sampleId,
                revision,
                data.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toData(data));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetDataResource> replaceDataContent(
            String workspaceId,
            String sampleId,
            String dataId,
            V2DatasetContentUpdateRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        requireActiveSample(workspaceId, sampleId);
        DatasetSampleData data = requireData(workspaceId, sampleId, dataId);
        requireNotDeleted(data.getDeleted(), "DATA", dataId);
        DatasetWorkspaceTextFilePolicy.ValidatedText text =
                textFilePolicy.validate(
                        request.content(),
                        fallback(request.fileName(), data.getFileName()),
                        fallback(request.format(), data.getFormat()),
                        fallback(request.contentType(), data.getContentType())
                );
        String oldPackageId = data.getPackageId();
        DatasetPackage datasetPackage = rawStorageService.storeText(
                access.asset(),
                access.workspace(),
                text
        );
        data.setPackageId(datasetPackage.getId());
        data.setOriginalPath(text.fileName());
        data.setFileName(text.fileName());
        data.setFormat(text.format());
        data.setContentType(text.contentType());
        data.setSizeBytes((long) text.bytes().length);
        data.setChecksum(text.sha256());
        data.setUpdatedAt(Instant.now());
        clearZipIndex(data);
        data = dataRepo.saveAndFlush(data);
        rawStorageService.releaseIfUnreferenced(
                access.workspace(),
                oldPackageId,
                access.asset().getOwnerUserId()
        );
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "DATA_CONTENT_REPLACED",
                "DATA",
                data.getId(),
                datasetPackage.getId(),
                sampleId,
                revision,
                data.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toData(data));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetDataResource> deleteData(
            String workspaceId,
            String sampleId,
            String dataId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        requireSample(workspaceId, sampleId, false);
        DatasetSampleData data = requireData(workspaceId, sampleId, dataId);
        if (Boolean.TRUE.equals(data.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    DatasetWorkspaceResourceMapper.toData(data)
            );
        }
        if (annotationRepo.countByDatasetVersionIdAndSampleDataIdAndDeletedFalse(
                workspaceId,
                dataId
        ) > 0) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "RESOURCE_IN_USE",
                    "数据组件仍被有效标注引用，请先删除标注或重新关联",
                    Map.of(
                            "resourceType", "DATA",
                            "resourceId", dataId
                    )
            );
        }
        Instant now = Instant.now();
        data.setDeleted(true);
        data.setDeletedAt(now);
        data.setUpdatedAt(now);
        data = dataRepo.save(data);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "DATA_RESOURCE_DELETED",
                "DATA",
                data.getId(),
                data.getPackageId(),
                sampleId,
                revision,
                data.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toData(data));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetDataResource> restoreData(
            String workspaceId,
            String sampleId,
            String dataId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        requireActiveSample(workspaceId, sampleId);
        DatasetSampleData data = requireData(workspaceId, sampleId, dataId);
        if (!Boolean.TRUE.equals(data.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    DatasetWorkspaceResourceMapper.toData(data)
            );
        }
        data.setDeleted(false);
        data.setDeletedAt(null);
        data.setUpdatedAt(Instant.now());
        try {
            data = dataRepo.saveAndFlush(data);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "DATA_RESOURCE_RESTORE_CONFLICT",
                    "恢复后将产生数据组件唯一性冲突"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "DATA_RESOURCE_RESTORED",
                "DATA",
                data.getId(),
                data.getPackageId(),
                sampleId,
                revision,
                data.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toData(data));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetAnnotationResource>
    createInlineAnnotation(
            String workspaceId,
            String sampleId,
            V2DatasetInlineAnnotationCreateRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        DatasetSample sample = requireActiveSample(workspaceId, sampleId);
        String sampleDataId = optionalText(request.sampleDataId(), 64);
        validateAnnotationTarget(workspaceId, sampleId, sampleDataId);
        String annotationType = requiredText(
                request.annotationType(),
                "annotationType 不能为空",
                64
        );
        DatasetWorkspaceTextFilePolicy.ValidatedText text =
                textFilePolicy.validate(
                        request.content(),
                        request.fileName(),
                        request.format(),
                        request.contentType()
                );
        DatasetPackage datasetPackage = rawStorageService.storeText(
                access.asset(),
                access.workspace(),
                text
        );
        Instant now = Instant.now();
        DatasetAnnotation annotation = new DatasetAnnotation();
        annotation.setId("dsa-" + compactUuid());
        annotation.setSampleId(sample.getId());
        annotation.setSampleDataId(sampleDataId);
        annotation.setDatasetVersionId(workspaceId);
        annotation.setPackageId(datasetPackage.getId());
        annotation.setAnnotationType(annotationType);
        annotation.setFormat(text.format());
        annotation.setOriginalPath(text.fileName());
        annotation.setFileName(text.fileName());
        annotation.setSizeBytes((long) text.bytes().length);
        annotation.setChecksum(text.sha256());
        annotation.setContentType(text.contentType());
        annotation.setMetadata(copyMap(request.metadata()));
        annotation.setCreatedAt(now);
        annotation.setUpdatedAt(now);
        annotation.setDeleted(false);
        clearZipIndex(annotation);
        annotation = annotationRepo.save(annotation);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "ANNOTATION_RESOURCE_CREATED",
                "ANNOTATION",
                annotation.getId(),
                datasetPackage.getId(),
                sampleId,
                revision,
                annotation.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toAnnotation(annotation));
    }

    @Transactional(readOnly = true)
    public V2DatasetAnnotationResource getAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        commandService.requireReadable(workspaceId);
        requireSample(workspaceId, sampleId, false);
        return DatasetWorkspaceResourceMapper.toAnnotation(readAnnotation(
                workspaceId,
                sampleId,
                annotationId
        ));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetAnnotationResource> patchAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId,
            JsonNode patch
    ) {
        requirePatchObject(patch, ANNOTATION_PATCH_FIELDS);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        expectedRevision(patch)
                );
        requireActiveSample(workspaceId, sampleId);
        DatasetAnnotation annotation = requireAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        requireNotDeleted(annotation.getDeleted(), "ANNOTATION", annotationId);
        if (patch.has("sampleDataId")) {
            annotation.setSampleDataId(optionalTextNode(
                    patch.get("sampleDataId"),
                    64,
                    "sampleDataId"
            ));
        }
        validateAnnotationTarget(
                workspaceId,
                sampleId,
                annotation.getSampleDataId()
        );
        if (patch.has("annotationType")) {
            annotation.setAnnotationType(requiredText(
                    textValue(patch.get("annotationType"), "annotationType"),
                    "annotationType 不能为空",
                    64
            ));
        }
        if (patch.has("fileName")) {
            annotation.setFileName(textValue(patch.get("fileName"), "fileName"));
        }
        if (patch.has("format")) {
            annotation.setFormat(textValue(patch.get("format"), "format"));
        }
        if (patch.has("contentType")) {
            annotation.setContentType(textValue(
                    patch.get("contentType"),
                    "contentType"
            ));
        }
        if (patch.has("metadata")) {
            annotation.setMetadata(mergePatchMap(
                    annotation.getMetadata(),
                    patch.get("metadata")
            ));
        }
        DatasetWorkspaceTextFilePolicy.Descriptor descriptor =
                textFilePolicy.validateDescriptor(
                        annotation.getFileName(),
                        annotation.getFormat(),
                        annotation.getContentType()
                );
        annotation.setFileName(descriptor.fileName());
        annotation.setFormat(descriptor.format());
        annotation.setContentType(descriptor.contentType());
        annotation.setUpdatedAt(Instant.now());
        annotation = annotationRepo.save(annotation);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "ANNOTATION_RESOURCE_UPDATED",
                "ANNOTATION",
                annotation.getId(),
                annotation.getPackageId(),
                sampleId,
                revision,
                annotation.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toAnnotation(annotation));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetAnnotationResource>
    replaceAnnotationContent(
            String workspaceId,
            String sampleId,
            String annotationId,
            V2DatasetContentUpdateRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.expectedWorkspaceRevision()
                );
        requireActiveSample(workspaceId, sampleId);
        DatasetAnnotation annotation = requireAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        requireNotDeleted(annotation.getDeleted(), "ANNOTATION", annotationId);
        DatasetWorkspaceTextFilePolicy.ValidatedText text =
                textFilePolicy.validate(
                        request.content(),
                        fallback(request.fileName(), annotation.getFileName()),
                        fallback(request.format(), annotation.getFormat()),
                        fallback(request.contentType(), annotation.getContentType())
                );
        String oldPackageId = annotation.getPackageId();
        DatasetPackage datasetPackage = rawStorageService.storeText(
                access.asset(),
                access.workspace(),
                text
        );
        annotation.setPackageId(datasetPackage.getId());
        annotation.setOriginalPath(text.fileName());
        annotation.setFileName(text.fileName());
        annotation.setFormat(text.format());
        annotation.setContentType(text.contentType());
        annotation.setSizeBytes((long) text.bytes().length);
        annotation.setChecksum(text.sha256());
        annotation.setUpdatedAt(Instant.now());
        clearZipIndex(annotation);
        annotation = annotationRepo.saveAndFlush(annotation);
        rawStorageService.releaseIfUnreferenced(
                access.workspace(),
                oldPackageId,
                access.asset().getOwnerUserId()
        );
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "ANNOTATION_CONTENT_REPLACED",
                "ANNOTATION",
                annotation.getId(),
                datasetPackage.getId(),
                sampleId,
                revision,
                annotation.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toAnnotation(annotation));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetAnnotationResource> deleteAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        requireSample(workspaceId, sampleId, false);
        DatasetAnnotation annotation = requireAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        if (Boolean.TRUE.equals(annotation.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    DatasetWorkspaceResourceMapper.toAnnotation(annotation)
            );
        }
        Instant now = Instant.now();
        annotation.setDeleted(true);
        annotation.setDeletedAt(now);
        annotation.setUpdatedAt(now);
        annotation = annotationRepo.save(annotation);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "ANNOTATION_RESOURCE_DELETED",
                "ANNOTATION",
                annotation.getId(),
                annotation.getPackageId(),
                sampleId,
                revision,
                annotation.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toAnnotation(annotation));
    }

    @Transactional
    public V2DatasetMutationResult<V2DatasetAnnotationResource> restoreAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                lock(workspaceId, request);
        requireActiveSample(workspaceId, sampleId);
        DatasetAnnotation annotation = requireAnnotation(
                workspaceId,
                sampleId,
                annotationId
        );
        if (!Boolean.TRUE.equals(annotation.getDeleted())) {
            return mutation(
                    workspaceId,
                    commandService.revision(access.workspace()),
                    DatasetWorkspaceResourceMapper.toAnnotation(annotation)
            );
        }
        validateAnnotationTarget(
                workspaceId,
                sampleId,
                annotation.getSampleDataId()
        );
        annotation.setDeleted(false);
        annotation.setDeletedAt(null);
        annotation.setUpdatedAt(Instant.now());
        annotation = annotationRepo.save(annotation);
        long revision = commandService.incrementRevision(access.workspace());
        audit(
                access,
                "ANNOTATION_RESOURCE_RESTORED",
                "ANNOTATION",
                annotation.getId(),
                annotation.getPackageId(),
                sampleId,
                revision,
                annotation.getChecksum()
        );
        return mutation(workspaceId, revision, DatasetWorkspaceResourceMapper.toAnnotation(annotation));
    }

    AttachedResource attachUploadedFile(
            DatasetWorkspaceCommandService.WorkspaceAccess access,
            DatasetUploadSession session,
            DatasetPackage datasetPackage,
            long sizeBytes,
            String checksum
    ) {
        String workspaceId = access.workspace().getId();
        String sampleId = session.getTargetSampleId();
        requireActiveSample(workspaceId, sampleId);
        Instant now = Instant.now();
        if ("DATA".equals(session.getTargetKind())) {
            DatasetSampleData data;
            String oldPackageId = null;
            if ("REPLACE".equals(session.getTargetOperation())) {
                data = requireData(
                        workspaceId,
                        sampleId,
                        session.getTargetResourceId()
                );
                requireNotDeleted(data.getDeleted(), "DATA", data.getId());
                oldPackageId = data.getPackageId();
            } else {
                data = new DatasetSampleData();
                data.setId("dsd-" + compactUuid());
                data.setSampleId(sampleId);
                data.setDatasetVersionId(workspaceId);
                data.setCreatedAt(now);
                data.setDeleted(false);
            }
            data.setPackageId(datasetPackage.getId());
            data.setDataType(dataType(session.getTargetDataType()));
            data.setSensor(optionalText(session.getTargetSensor(), 64));
            data.setChannel(optionalText(session.getTargetChannel(), 32));
            data.setSeq(nonNegative(session.getTargetSeq(), "seq"));
            data.setFormat(session.getDeclaredFormat());
            data.setOriginalPath(session.getFileName());
            data.setFileName(session.getFileName());
            data.setSizeBytes(sizeBytes);
            data.setChecksum(checksum);
            data.setContentType(session.getDeclaredContentType());
            data.setMetadata(copyMap(session.getTargetMetadata()));
            data.setUpdatedAt(now);
            clearZipIndex(data);
            try {
                data = dataRepo.saveAndFlush(data);
            } catch (DataIntegrityViolationException exception) {
                throw conflict(
                        "DATA_RESOURCE_IDENTITY_CONFLICT",
                        "同一样本的数据类型、传感器、通道和序号组合已存在"
                );
            }
            if (oldPackageId != null) {
                rawStorageService.releaseIfUnreferenced(
                        access.workspace(),
                        oldPackageId,
                        access.asset().getOwnerUserId()
                );
            }
            return new AttachedResource(
                    "DATA",
                    data.getId(),
                    sampleId,
                    data.getChecksum()
            );
        }

        DatasetAnnotation annotation;
        String oldPackageId = null;
        if ("REPLACE".equals(session.getTargetOperation())) {
            annotation = requireAnnotation(
                    workspaceId,
                    sampleId,
                    session.getTargetResourceId()
            );
            requireNotDeleted(
                    annotation.getDeleted(),
                    "ANNOTATION",
                    annotation.getId()
            );
            oldPackageId = annotation.getPackageId();
        } else {
            annotation = new DatasetAnnotation();
            annotation.setId("dsa-" + compactUuid());
            annotation.setSampleId(sampleId);
            annotation.setDatasetVersionId(workspaceId);
            annotation.setCreatedAt(now);
            annotation.setDeleted(false);
        }
        validateAnnotationTarget(
                workspaceId,
                sampleId,
                session.getTargetSampleDataId()
        );
        annotation.setSampleDataId(session.getTargetSampleDataId());
        annotation.setPackageId(datasetPackage.getId());
        annotation.setAnnotationType(requiredText(
                session.getTargetAnnotationType(),
                "annotationType 不能为空",
                64
        ));
        annotation.setFormat(session.getDeclaredFormat());
        annotation.setOriginalPath(session.getFileName());
        annotation.setFileName(session.getFileName());
        annotation.setSizeBytes(sizeBytes);
        annotation.setChecksum(checksum);
        annotation.setContentType(session.getDeclaredContentType());
        annotation.setMetadata(copyMap(session.getTargetMetadata()));
        annotation.setUpdatedAt(now);
        clearZipIndex(annotation);
        annotation = annotationRepo.saveAndFlush(annotation);
        if (oldPackageId != null) {
            rawStorageService.releaseIfUnreferenced(
                    access.workspace(),
                    oldPackageId,
                    access.asset().getOwnerUserId()
            );
        }
        return new AttachedResource(
                "ANNOTATION",
                annotation.getId(),
                sampleId,
                annotation.getChecksum()
        );
    }

    private DatasetWorkspaceCommandService.WorkspaceAccess lock(
            String workspaceId,
            V2DatasetWorkspaceMutationRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        return commandService.lockForMutation(
                workspaceId,
                request.expectedWorkspaceRevision()
        );
    }

    private DatasetSample requireActiveSample(
            String workspaceId,
            String sampleId
    ) {
        DatasetSample sample = requireSample(workspaceId, sampleId, true);
        requireNotDeleted(sample.getDeleted(), "DATASET_SAMPLE", sampleId);
        return sample;
    }

    private DatasetSample requireSample(
            String workspaceId,
            String sampleId,
            boolean forUpdate
    ) {
        DatasetSample sample = forUpdate
                ? sampleRepo.findByIdForUpdate(sampleId).orElseThrow(
                        () -> notFound("DATASET_SAMPLE_NOT_FOUND", "样本不存在")
                )
                : sampleRepo.findByIdAndDatasetVersionId(sampleId, workspaceId)
                        .orElseThrow(
                                () -> notFound(
                                        "DATASET_SAMPLE_NOT_FOUND",
                                        "样本不存在"
                                )
                        );
        if (!workspaceId.equals(sample.getDatasetVersionId())) {
            throw notFound("DATASET_SAMPLE_NOT_FOUND", "样本不存在");
        }
        return sample;
    }

    private DatasetSampleData requireData(
            String workspaceId,
            String sampleId,
            String dataId
    ) {
        DatasetSampleData data = dataRepo
                .findByIdAndDatasetVersionIdForUpdate(dataId, workspaceId)
                .orElseThrow(() -> notFound(
                        "DATA_RESOURCE_NOT_FOUND",
                        "数据组件不存在"
                ));
        if (!sampleId.equals(data.getSampleId())) {
            throw notFound("DATA_RESOURCE_NOT_FOUND", "数据组件不存在");
        }
        return data;
    }

    private DatasetSampleData readData(
            String workspaceId,
            String sampleId,
            String dataId
    ) {
        DatasetSampleData data = dataRepo
                .findByIdAndDatasetVersionId(dataId, workspaceId)
                .orElseThrow(() -> notFound(
                        "DATA_RESOURCE_NOT_FOUND",
                        "数据组件不存在"
                ));
        if (!sampleId.equals(data.getSampleId())) {
            throw notFound("DATA_RESOURCE_NOT_FOUND", "数据组件不存在");
        }
        return data;
    }

    private DatasetAnnotation requireAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        DatasetAnnotation annotation = annotationRepo
                .findByIdAndDatasetVersionIdForUpdate(annotationId, workspaceId)
                .orElseThrow(() -> notFound(
                        "ANNOTATION_RESOURCE_NOT_FOUND",
                        "标注组件不存在"
                ));
        if (!sampleId.equals(annotation.getSampleId())) {
            throw notFound("ANNOTATION_RESOURCE_NOT_FOUND", "标注组件不存在");
        }
        return annotation;
    }

    private DatasetAnnotation readAnnotation(
            String workspaceId,
            String sampleId,
            String annotationId
    ) {
        DatasetAnnotation annotation = annotationRepo
                .findByIdAndDatasetVersionId(annotationId, workspaceId)
                .orElseThrow(() -> notFound(
                        "ANNOTATION_RESOURCE_NOT_FOUND",
                        "标注组件不存在"
                ));
        if (!sampleId.equals(annotation.getSampleId())) {
            throw notFound("ANNOTATION_RESOURCE_NOT_FOUND", "标注组件不存在");
        }
        return annotation;
    }

    private void validateAnnotationTarget(
            String workspaceId,
            String sampleId,
            String sampleDataId
    ) {
        DatasetWorkspaceResourcePolicy.validateAnnotationTarget(dataRepo, workspaceId, sampleId, sampleDataId);
    }

    private void requirePatchObject(JsonNode patch, Set<String> allowedFields) {
        if (patch == null || !patch.isObject()) {
            throw invalid("Merge Patch 请求体必须是 JSON 对象");
        }
        List<String> unknown = new ArrayList<>();
        patch.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "PATCH_FIELD_NOT_ALLOWED",
                    "Merge Patch 包含不可修改字段",
                    Map.of("fields", unknown)
            );
        }
    }

    private Long expectedRevision(JsonNode patch) {
        JsonNode value = patch.get("expectedWorkspaceRevision");
        if (value == null || !value.canConvertToLong()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                    "expectedWorkspaceRevision 不能为空"
            );
        }
        return value.longValue();
    }

    private Map<String, Object> mergePatchMap(
            Map<String, Object> current,
            JsonNode patch
    ) {
        if (patch == null || patch.isNull()) {
            return null;
        }
        if (!patch.isObject()) {
            throw invalid("tags 和 metadata 必须是 JSON 对象或 null");
        }
        ObjectNode target = current == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(current);
        mergeObject(target, (ObjectNode) patch);
        return objectMapper.convertValue(
                target,
                objectMapper.getTypeFactory().constructMapType(
                        LinkedHashMap.class,
                        String.class,
                        Object.class
                )
        );
    }

    private void mergeObject(ObjectNode target, ObjectNode patch) {
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                target.remove(field.getKey());
            } else if (value.isObject()) {
                JsonNode existing = target.get(field.getKey());
                ObjectNode child = existing != null && existing.isObject()
                        ? (ObjectNode) existing.deepCopy()
                        : objectMapper.createObjectNode();
                mergeObject(child, (ObjectNode) value);
                target.set(field.getKey(), child);
            } else {
                target.set(field.getKey(), value.deepCopy());
            }
        }
    }

    private V2DatasetSampleDetail toSampleDetail(DatasetSample sample) {
        List<V2DatasetDataResource> data = dataRepo
                .findBySampleIdAndDatasetVersionIdOrderBySeqAscIdAsc(
                        sample.getId(),
                        sample.getDatasetVersionId()
                )
                .stream()
                .map(DatasetWorkspaceResourceMapper::toData)
                .toList();
        List<V2DatasetAnnotationResource> annotations = annotationRepo
                .findBySampleIdAndDatasetVersionIdOrderByCreatedAtAscIdAsc(
                        sample.getId(),
                        sample.getDatasetVersionId()
                )
                .stream()
                .map(DatasetWorkspaceResourceMapper::toAnnotation)
                .toList();
        return new V2DatasetSampleDetail(
                sample.getId(),
                sample.getDatasetVersionId(),
                sample.getExternalId(),
                sample.getSampleIndex(),
                sample.getTags(),
                sample.getMetadata(),
                sample.getCreatedAt(),
                sample.getUpdatedAt(),
                Boolean.TRUE.equals(sample.getDeleted()),
                sample.getDeletedAt(),
                data,
                annotations
        );
    }







    private void audit(
            DatasetWorkspaceCommandService.WorkspaceAccess access,
            String operation,
            String targetType,
            String targetId,
            String packageId,
            String sampleId,
            long revision,
            String checksum
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceRevision", revision);
        if (checksum != null) {
            details.put("checksum", checksum);
        }
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                operation,
                targetType,
                targetId,
                packageId,
                sampleId,
                details
        );
    }

    private static void clearZipIndex(DatasetSampleData data) {
        data.setZipEntryOffset(null);
        data.setZipDataOffset(null);
        data.setCompressedSize(null);
        data.setUncompressedSize(null);
        data.setCompressionMethod(null);
        data.setCrc32(null);
    }

    private static void clearZipIndex(DatasetAnnotation annotation) {
        annotation.setZipEntryOffset(null);
        annotation.setZipDataOffset(null);
        annotation.setCompressedSize(null);
        annotation.setUncompressedSize(null);
        annotation.setCompressionMethod(null);
        annotation.setCrc32(null);
    }

    private static String dataType(String value) {
        return FIELD_POLICY.dataType(value);
    }

    private static int nonNegative(Integer value, String field) {
        return FIELD_POLICY.nonNegative(value, field);
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw invalid(field + " 必须是字符串");
        }
        return node.textValue();
    }

    private static String optionalTextNode(
            JsonNode node,
            int maxLength,
            String field
    ) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid(field + " 必须是字符串或 null");
        }
        return optionalText(node.textValue(), maxLength);
    }

    private static String requiredText(
            String value,
            String message,
            int maxLength
    ) {
        return FIELD_POLICY.requiredText(value, message, maxLength);
    }

    private static String optionalText(String value, int maxLength) {
        return FIELD_POLICY.optionalText(value, maxLength);
    }

    private static String fallback(String value, String current) {
        return DatasetWorkspaceResourcePolicy.fallback(value, current);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return DatasetWorkspaceResourcePolicy.copyMap(value);
    }

    private static void requireNotDeleted(
            Boolean deleted,
            String resourceType,
            String resourceId
    ) {
        if (Boolean.TRUE.equals(deleted)) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "RESOURCE_DELETED",
                    "资源已被软删除，请先恢复",
                    Map.of(
                            "resourceType", resourceType,
                            "resourceId", resourceId
                    )
            );
        }
    }

    private static V2BusinessException invalid(String message) {
        return new V2BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                message
        );
    }

    private static V2BusinessException conflict(String code, String message) {
        return new V2BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private static V2BusinessException notFound(String code, String message) {
        return new V2BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    private static <T> V2DatasetMutationResult<T> mutation(
            String workspaceId,
            long revision,
            T resource
    ) {
        return new V2DatasetMutationResult<>(workspaceId, revision, resource);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    record AttachedResource(
            String targetKind,
            String resourceId,
            String sampleId,
            String checksum
    ) {
    }
}
