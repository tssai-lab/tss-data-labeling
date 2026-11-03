package com.tss.platform.service;

import com.tss.platform.dto.v2.V2DatasetPublishBlocker;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.CvAnnotationFormat;
import com.tss.platform.model.CvTaskType;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetCatalogReadinessRepository;
import com.tss.platform.repository.DatasetCatalogReadinessSnapshot;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetWorkspaceReadinessService {

    private static final Set<String> TERMINAL_IMPORT_STATUSES =
            Set.of("SUCCESS", "SUPERSEDED");
    private static final Set<String> ACTIVE_UPLOAD_STATUSES =
            Set.of("UPLOADING", "COMPLETING");
    private static final Set<String> TERMINAL_UPLOAD_STATUSES =
            Set.of("COMPLETED", "DISCARDED");
    private static final Set<String> ALLOWED_PACKAGE_ROLES =
            Set.of("PRIMARY", "APPEND", "OVERLAY");

    private final DatasetVersionRepository versionRepo;
    private final DatasetCatalogReadinessRepository catalogReadinessRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetPackageRepository packageRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetSampleDataRepository dataRepo;
    private final DatasetAnnotationRepository annotationRepo;
    private final DatasetUploadSessionRepository uploadSessionRepo;
    private final ImportJobRepository importJobRepo;

    public DatasetWorkspaceReadinessService(
            DatasetVersionRepository versionRepo,
            DatasetCatalogReadinessRepository catalogReadinessRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetPackageRepository packageRepo,
            DatasetSampleRepository sampleRepo,
            DatasetSampleDataRepository dataRepo,
            DatasetAnnotationRepository annotationRepo,
            DatasetUploadSessionRepository uploadSessionRepo,
            ImportJobRepository importJobRepo
    ) {
        this.versionRepo = versionRepo;
        this.catalogReadinessRepo = catalogReadinessRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.packageRepo = packageRepo;
        this.sampleRepo = sampleRepo;
        this.dataRepo = dataRepo;
        this.annotationRepo = annotationRepo;
        this.uploadSessionRepo = uploadSessionRepo;
        this.importJobRepo = importJobRepo;
    }

    @Transactional(readOnly = true)
    public V2DatasetPublishReadiness evaluate(
            DatasetAsset asset,
            DatasetVersion workspace
    ) {
        List<V2DatasetPublishBlocker> blockers = new ArrayList<>();
        validateLineage(asset, workspace, blockers);
        validateOperations(workspace, blockers);
        Map<String, DatasetPackage> linkedPackages =
                validatePackages(asset, workspace, blockers);
        validateContent(workspace, linkedPackages, blockers);
        validateVersionMetadata(asset, workspace, blockers);
        return new V2DatasetPublishReadiness(
                blockers.isEmpty(),
                revision(workspace),
                blockers
        );
    }

    @Transactional(readOnly = true)
    public V2DatasetPublishReadiness evaluateCatalog(
            DatasetAsset asset,
            DatasetVersion workspace
    ) {
        List<V2DatasetPublishBlocker> blockers = new ArrayList<>();
        validateLineage(asset, workspace, blockers);
        DatasetCatalogReadinessSnapshot flags =
                catalogReadinessRepo.inspect(
                        workspace.getId(),
                        asset.getId()
                );
        if (flags.activeUpload()) {
            add(blockers, "ACTIVE_UPLOAD", "工作区仍有未完成上传");
        }
        if (flags.uploadNotSuccessful()) {
            add(
                    blockers,
                    "UPLOAD_NOT_SUCCESSFUL",
                    "工作区存在失败或状态无效的上传任务"
            );
        }
        if (flags.importNotSuccessful()) {
            add(
                    blockers,
                    "IMPORT_NOT_SUCCESSFUL",
                    "工作区导入任务尚未成功或已被替代"
            );
        }
        if (flags.packageRelationInvalid()) {
            add(
                    blockers,
                    "PACKAGE_RELATION_INVALID",
                    "包关系的顺序、角色或唯一性无效"
            );
        }
        if (flags.packageNotReady()) {
            add(
                    blockers,
                    "PACKAGE_NOT_READY",
                    "工作区关联包不可用或未就绪"
            );
        }
        if (flags.importPackageInvalid()) {
            add(
                    blockers,
                    "IMPORT_PACKAGE_INVALID",
                    "导入任务没有关联到工作区内的就绪包"
            );
        }
        if (flags.noActiveSample()) {
            add(blockers, "NO_ACTIVE_SAMPLE", "工作区至少需要一个未删除样本");
        }
        if (flags.duplicateExternalId()) {
            add(blockers, "DUPLICATE_EXTERNAL_ID", "工作区存在重复 externalId");
        }
        if (flags.duplicateSampleIndex()) {
            add(blockers, "DUPLICATE_SAMPLE_INDEX", "工作区存在重复 sampleIndex");
        }
        if (flags.emptySample()) {
            add(blockers, "EMPTY_SAMPLE", "未删除样本至少需要一个数据组件");
        }
        if (flags.resourceDescriptorInvalid()) {
            add(
                    blockers,
                    "RESOURCE_DESCRIPTOR_INVALID",
                    "资源文件描述不完整"
            );
        }
        if (flags.resourceStorageInvalid()) {
            add(
                    blockers,
                    "RESOURCE_STORAGE_INVALID",
                    "资源存储关系或索引无效"
            );
        }
        if (flags.annotationTargetInvalid()) {
            add(
                    blockers,
                    "ANNOTATION_TARGET_INVALID",
                    "标注关联的数据组件不存在、已删除或不属于同一样本"
            );
        }
        validateVersionMetadata(asset, workspace, blockers);
        return new V2DatasetPublishReadiness(
                blockers.isEmpty(),
                revision(workspace),
                blockers
        );
    }

    private void validateLineage(
            DatasetAsset asset,
            DatasetVersion workspace,
            List<V2DatasetPublishBlocker> blockers
    ) {
        if (!"DRAFT".equals(workspace.getStatus())) {
            add(blockers, "WORKSPACE_NOT_DRAFT", "版本工作区不是 DRAFT 状态");
        }
        if (workspace.getWorkspaceHeadVersionId() == null
                || !workspace.getWorkspaceHeadVersionId().equals(
                        asset.getCurrentVersionId()
                )) {
            add(
                    blockers,
                    "BASE_VERSION_STALE",
                    "工作区创建后数据集当前版本已变化"
            );
            return;
        }
        DatasetVersion head = versionRepo
                .findByIdAndDeletedFalse(workspace.getWorkspaceHeadVersionId())
                .orElse(null);
        if (head == null
                || !asset.getId().equals(head.getAssetId())
                || !"READY".equals(head.getStatus())) {
            add(
                    blockers,
                    "VERSION_LINEAGE_INVALID",
                    "工作区创建时的当前 READY 版本不存在或无效"
            );
            return;
        }
        if (workspace.getParentVersionId() == null
                || workspace.getParentVersionId().isBlank()) {
            add(
                    blockers,
                    "VERSION_LINEAGE_INVALID",
                    "工作区父 READY 版本缺失"
            );
            return;
        }
        DatasetVersion parent = versionRepo
                .findByIdAndDeletedFalse(workspace.getParentVersionId())
                .orElse(null);
        if (parent == null
                || !asset.getId().equals(parent.getAssetId())
                || !"READY".equals(parent.getStatus())) {
            add(blockers, "VERSION_LINEAGE_INVALID", "工作区父版本不存在或不是 READY");
            return;
        }
        if (workspace.getVersionNo() == null
                || head.getVersionNo() == null
                || workspace.getVersionNo() <= head.getVersionNo()) {
            add(
                    blockers,
                    "VERSION_LINEAGE_INVALID",
                    "目标版本号必须高于工作区创建时的当前 READY 版本"
            );
        }
    }

    private void validateOperations(
            DatasetVersion workspace,
            List<V2DatasetPublishBlocker> blockers
    ) {
        for (DatasetUploadSession upload : uploadSessionRepo.findByVersionId(workspace.getId())) {
            if (ACTIVE_UPLOAD_STATUSES.contains(upload.getStatus())) {
                add(
                        blockers,
                        "ACTIVE_UPLOAD",
                        "工作区仍有未完成上传",
                        "UPLOAD_SESSION",
                        upload.getId()
                );
            } else if (!TERMINAL_UPLOAD_STATUSES.contains(
                    upload.getStatus()
            )) {
                add(
                        blockers,
                        "UPLOAD_NOT_SUCCESSFUL",
                        "工作区存在失败或状态无效的上传任务",
                        "UPLOAD_SESSION",
                        upload.getId()
                );
            }
        }
        for (ImportJob job : importJobRepo.findByDatasetVersionId(workspace.getId())) {
            if (!TERMINAL_IMPORT_STATUSES.contains(job.getStatus())) {
                add(
                        blockers,
                        "IMPORT_NOT_SUCCESSFUL",
                        "工作区导入任务尚未成功或已被替代",
                        "IMPORT_JOB",
                        job.getId()
                );
            }
        }
    }

    private Map<String, DatasetPackage> validatePackages(
            DatasetAsset asset,
            DatasetVersion workspace,
            List<V2DatasetPublishBlocker> blockers
    ) {
        List<DatasetVersionPackage> relations =
                versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                        workspace.getId()
                );
        if (relations.isEmpty()) {
            add(blockers, "PACKAGE_RELATION_INVALID", "工作区没有包关系");
            return Map.of();
        }

        Map<String, DatasetPackage> linked = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        int primaryCount = 0;
        for (int index = 0; index < relations.size(); index++) {
            DatasetVersionPackage relation = relations.get(index);
            if (relation.getPackageOrder() == null
                    || relation.getPackageOrder() != index
                    || !ALLOWED_PACKAGE_ROLES.contains(relation.getPackageRole())
                    || relation.getPackageId() == null
                    || !ids.add(relation.getPackageId())) {
                add(
                        blockers,
                        "PACKAGE_RELATION_INVALID",
                        "包关系的顺序、角色或唯一性无效",
                        "DATASET_PACKAGE",
                        relation.getPackageId()
                );
                continue;
            }
            if ("PRIMARY".equals(relation.getPackageRole())) {
                primaryCount += 1;
                if (index != 0) {
                    add(
                            blockers,
                            "PACKAGE_RELATION_INVALID",
                            "PRIMARY 包必须位于 packageOrder=0",
                            "DATASET_PACKAGE",
                            relation.getPackageId()
                    );
                }
            }
            DatasetPackage datasetPackage = packageRepo
                    .findByIdAndDeletedFalse(relation.getPackageId())
                    .orElse(null);
            if (datasetPackage == null
                    || !asset.getId().equals(datasetPackage.getDatasetAssetId())
                    || datasetPackage.getStoragePath() == null
                    || datasetPackage.getStoragePath().isBlank()) {
                add(
                        blockers,
                        "PACKAGE_NOT_READY",
                        "工作区关联包不可用或未就绪",
                        "DATASET_PACKAGE",
                        relation.getPackageId()
                );
                continue;
            }
            if ("SUPERSEDED".equals(datasetPackage.getStatus())) {
                continue;
            }
            if (!"READY".equals(datasetPackage.getStatus())) {
                add(
                        blockers,
                        "PACKAGE_NOT_READY",
                        "工作区关联包不可用或未就绪",
                        "DATASET_PACKAGE",
                        relation.getPackageId()
                );
                continue;
            }
            linked.put(datasetPackage.getId(), datasetPackage);
        }
        if (primaryCount != 1) {
            add(
                    blockers,
                    "PACKAGE_RELATION_INVALID",
                    "工作区必须且只能有一个 PRIMARY 包"
            );
        }
        for (ImportJob job : importJobRepo.findByDatasetVersionId(
                workspace.getId()
        )) {
            if ("SUPERSEDED".equals(job.getStatus())) {
                continue;
            }
            if (job.getPackageId() == null
                    || !linked.containsKey(job.getPackageId())) {
                add(
                        blockers,
                        "IMPORT_PACKAGE_INVALID",
                        "导入任务没有关联到工作区内的就绪包",
                        "IMPORT_JOB",
                        job.getId()
                );
            }
        }
        return linked;
    }

    private void validateContent(
            DatasetVersion workspace,
            Map<String, DatasetPackage> linkedPackages,
            List<V2DatasetPublishBlocker> blockers
    ) {
        List<DatasetSample> samples =
                sampleRepo.findByDatasetVersionIdAndDeletedFalseOrderBySampleIndexAscIdAsc(
                        workspace.getId()
                );
        if (samples.isEmpty()) {
            add(blockers, "NO_ACTIVE_SAMPLE", "工作区至少需要一个未删除样本");
            return;
        }

        if (!sampleRepo.findDuplicateExternalIdsByDatasetVersionId(workspace.getId()).isEmpty()) {
            add(blockers, "DUPLICATE_EXTERNAL_ID", "工作区存在重复 externalId");
        }
        if (!sampleRepo.findDuplicateSampleIndexesByDatasetVersionId(workspace.getId()).isEmpty()) {
            add(blockers, "DUPLICATE_SAMPLE_INDEX", "工作区存在重复 sampleIndex");
        }

        Set<String> activeSampleIds = new HashSet<>();
        for (DatasetSample sample : samples) {
            activeSampleIds.add(sample.getId());
        }
        List<DatasetSampleData> dataItems = dataRepo.findByDatasetVersionId(workspace.getId());
        Map<String, DatasetSampleData> activeDataById = new HashMap<>();
        Map<String, Integer> dataCountBySample = new HashMap<>();
        for (DatasetSampleData data : dataItems) {
            if (Boolean.TRUE.equals(data.getDeleted())
                    || !activeSampleIds.contains(data.getSampleId())) {
                continue;
            }
            activeDataById.put(data.getId(), data);
            dataCountBySample.merge(data.getSampleId(), 1, Integer::sum);
            validateResourceStorage(
                    "DATA",
                    data.getId(),
                    data.getPackageId(),
                    data.getFileName(),
                    data.getFormat(),
                    data.getContentType(),
                    data.getSizeBytes(),
                    data.getChecksum(),
                    data.getZipDataOffset(),
                    data.getCompressedSize(),
                    data.getCompressionMethod(),
                    linkedPackages,
                    blockers
            );
        }
        for (DatasetSample sample : samples) {
            if (dataCountBySample.getOrDefault(sample.getId(), 0) == 0) {
                add(
                        blockers,
                        "EMPTY_SAMPLE",
                        "未删除样本至少需要一个数据组件",
                        "DATASET_SAMPLE",
                        sample.getId()
                );
            }
        }

        for (DatasetAnnotation annotation :
                annotationRepo.findByDatasetVersionId(workspace.getId())) {
            if (Boolean.TRUE.equals(annotation.getDeleted())
                    || !activeSampleIds.contains(annotation.getSampleId())) {
                continue;
            }
            validateResourceStorage(
                    "ANNOTATION",
                    annotation.getId(),
                    annotation.getPackageId(),
                    annotation.getFileName(),
                    annotation.getFormat(),
                    annotation.getContentType(),
                    annotation.getSizeBytes(),
                    annotation.getChecksum(),
                    annotation.getZipDataOffset(),
                    annotation.getCompressedSize(),
                    annotation.getCompressionMethod(),
                    linkedPackages,
                    blockers
            );
            if (annotation.getSampleDataId() != null) {
                DatasetSampleData target = activeDataById.get(annotation.getSampleDataId());
                if (target == null
                        || !annotation.getSampleId().equals(target.getSampleId())) {
                    add(
                            blockers,
                            "ANNOTATION_TARGET_INVALID",
                            "标注关联的数据组件不存在、已删除或不属于同一样本",
                            "ANNOTATION",
                            annotation.getId()
                    );
                }
            }
        }
    }

    private void validateResourceStorage(
            String resourceType,
            String resourceId,
            String packageId,
            String fileName,
            String format,
            String contentType,
            Long sizeBytes,
            String checksum,
            Long zipDataOffset,
            Long compressedSize,
            String compressionMethod,
            Map<String, DatasetPackage> linkedPackages,
            List<V2DatasetPublishBlocker> blockers
    ) {
        DatasetPackage datasetPackage = linkedPackages.get(packageId);
        if (datasetPackage == null) {
            add(
                    blockers,
                    "RESOURCE_STORAGE_INVALID",
                    "资源没有关联到工作区内的就绪包",
                    resourceType,
                    resourceId
            );
            return;
        }
        if (fileName == null || fileName.isBlank()
                || format == null || format.isBlank()
                || contentType == null || contentType.isBlank()
                || sizeBytes == null || sizeBytes < 0) {
            add(
                    blockers,
                    "RESOURCE_DESCRIPTOR_INVALID",
                    "资源文件描述不完整",
                    resourceType,
                    resourceId
            );
        }
        if ("RAW".equals(datasetPackage.getStorageKind())) {
            if (checksum == null || !checksum.matches("[a-fA-F0-9]{64}")) {
                add(
                        blockers,
                        "RESOURCE_STORAGE_INVALID",
                        "RAW 资源缺少可信 SHA-256",
                        resourceType,
                        resourceId
                );
            }
            return;
        }
        if (zipDataOffset == null || zipDataOffset < 0
                || compressedSize == null || compressedSize < 0
                || compressionMethod == null
                || (!"STORED".equalsIgnoreCase(compressionMethod)
                && !"DEFLATED".equalsIgnoreCase(compressionMethod))) {
            add(
                    blockers,
                    "RESOURCE_STORAGE_INVALID",
                    "ZIP 资源索引不完整",
                    resourceType,
                    resourceId
            );
        }
    }

    private void validateVersionMetadata(
            DatasetAsset asset,
            DatasetVersion workspace,
            List<V2DatasetPublishBlocker> blockers
    ) {
        try {
            CvTaskType.normalizeForTask(asset.getType(), workspace.getCvTaskType());
            CvAnnotationFormat.normalizeForTask(
                    asset.getType(),
                    workspace.getAnnotationFormat()
            );
        } catch (IllegalArgumentException exception) {
            add(blockers, "VERSION_METADATA_INVALID", "版本任务类型或标注格式无效");
        }
    }

    private static long revision(DatasetVersion workspace) {
        return workspace.getWorkspaceRevision() == null
                ? 0L
                : workspace.getWorkspaceRevision();
    }

    private static void add(
            List<V2DatasetPublishBlocker> blockers,
            String code,
            String message
    ) {
        blockers.add(new V2DatasetPublishBlocker(code, message));
    }

    private static void add(
            List<V2DatasetPublishBlocker> blockers,
            String code,
            String message,
            String resourceType,
            String resourceId
    ) {
        blockers.add(new V2DatasetPublishBlocker(
                code,
                message,
                resourceType,
                resourceId
        ));
    }
}
