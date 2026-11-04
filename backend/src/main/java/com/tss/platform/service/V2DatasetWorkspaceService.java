package com.tss.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.DatasetPackageCleanupPlanDto;
import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.dto.v2.V2DatasetActiveOperation;
import com.tss.platform.dto.v2.V2DatasetEditability;
import com.tss.platform.dto.v2.V2DatasetPublishBlocker;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.dto.v2.V2DatasetPublishResult;
import com.tss.platform.dto.v2.V2DatasetVersionAllocationDto;
import com.tss.platform.dto.v2.V2DatasetVersionSummary;
import com.tss.platform.dto.v2.V2DatasetWorkspaceAbandonResult;
import com.tss.platform.dto.v2.V2DatasetWorkspaceCreateRequest;
import com.tss.platform.dto.v2.V2DatasetWorkspaceDto;
import com.tss.platform.dto.v2.V2UserError;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.CvAnnotationFormat;
import com.tss.platform.model.CvTaskType;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class V2DatasetWorkspaceService {

    private static final String DRAFT = "DRAFT";
    private static final String ABANDONED = "ABANDONED";
    private static final Set<String> ACTIVE_UPLOAD_STATUSES =
            Set.of("UPLOADING", "COMPLETING");
    private static final Set<String> ACTIVE_IMPORT_STATUSES =
            Set.of("PENDING", "RUNNING");
    private static final Set<String> PATCH_FIELDS = Set.of(
            "expectedWorkspaceRevision",
            "description",
            "changeLog",
            "cvTaskType",
            "annotationFormat"
    );

    private final DatasetAssetRepository assetRepo;
    private final DatasetVersionRepository versionRepo;
    private final DatasetSampleRepository sampleRepo;
    private final DatasetUploadSessionRepository uploadSessionRepo;
    private final DatasetUploadChunkRepository uploadChunkRepo;
    private final ImportJobRepository importJobRepo;
    private final DatasetVersionPackageRepository versionPackageRepo;
    private final DatasetPackageRepository packageRepo;
    private final AuthContext authContext;
    private final DatasetWorkspaceService workspaceService;
    private final DatasetWorkspacePublishService publishService;
    private final DatasetWorkspaceCommandService commandService;
    private final DatasetWorkspaceReadinessService readinessService;
    private final DatasetWorkspaceAuditService auditService;
    private final MinioDeleteTaskService deleteTaskService;
    private final DatasetWorkspaceSourceInspector sourceInspector;

    public V2DatasetWorkspaceService(
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            DatasetSampleRepository sampleRepo,
            DatasetUploadSessionRepository uploadSessionRepo,
            DatasetUploadChunkRepository uploadChunkRepo,
            ImportJobRepository importJobRepo,
            DatasetVersionPackageRepository versionPackageRepo,
            DatasetPackageRepository packageRepo,
            AuthContext authContext,
            DatasetWorkspaceService workspaceService,
            DatasetWorkspacePublishService publishService,
            DatasetWorkspaceCommandService commandService,
            DatasetWorkspaceReadinessService readinessService,
            DatasetWorkspaceAuditService auditService,
            MinioDeleteTaskService deleteTaskService,
            DatasetWorkspaceSourceInspector sourceInspector
    ) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.sampleRepo = sampleRepo;
        this.uploadSessionRepo = uploadSessionRepo;
        this.uploadChunkRepo = uploadChunkRepo;
        this.importJobRepo = importJobRepo;
        this.versionPackageRepo = versionPackageRepo;
        this.packageRepo = packageRepo;
        this.authContext = authContext;
        this.workspaceService = workspaceService;
        this.publishService = publishService;
        this.commandService = commandService;
        this.readinessService = readinessService;
        this.auditService = auditService;
        this.deleteTaskService = deleteTaskService;
        this.sourceInspector = sourceInspector;
    }

    @Transactional
    public V2DatasetWorkspaceDto create(String datasetId) {
        return create(datasetId, null);
    }

    @Transactional
    public V2DatasetWorkspaceDto create(
            String datasetId,
            V2DatasetWorkspaceCreateRequest request
    ) {
        String requestedBaseVersionId = normalizeBaseVersionId(
                request == null ? null : request.baseVersionId()
        );
        String requestedVersionLabel = normalizeVersionLabel(
                request == null ? null : request.versionLabel()
        );
        DatasetAsset asset = requireOwnedAssetForUpdate(datasetId);
        Optional<DatasetVersion> active = versionRepo
                .findTopByAssetIdAndDeletedFalseAndStatusOrderByVersionNoDesc(
                        asset.getId(),
                        DRAFT
                );
        if (active.isPresent()) {
            if (requestedBaseVersionId != null
                    && !Objects.equals(
                            requestedBaseVersionId,
                            active.get().getParentVersionId()
                    )) {
                throw workspaceBaseConflict(
                        active.get(),
                        requestedBaseVersionId
                );
            }
            String activeVersionLabel = displayVersionLabel(active.get());
            if (requestedVersionLabel != null
                    && !Objects.equals(
                            requestedVersionLabel,
                            activeVersionLabel
                    )) {
                DatasetWorkspaceService.VersionAllocationPreview allocation =
                        previewAllocation(
                                asset.getId(),
                                requestedVersionLabel
                        );
                throw versionLabelConflict(
                        allocation,
                        "ACTIVE_WORKSPACE_LABEL_MISMATCH",
                        active.get()
                );
            }
            return describe(asset, active.get());
        }
        if (asset.getCurrentVersionId() == null
                || asset.getCurrentVersionId().isBlank()) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "DATASET_NOT_EDITABLE",
                    "数据集当前没有可作为工作区基线的已发布版本"
            );
        }
        DatasetVersion current = requireCurrentReadyVersion(asset);
        DatasetVersion ready = requestedBaseVersionId == null
                ? current
                : requireRequestedBaseVersion(
                        asset,
                        requestedBaseVersionId
                );
        V2DatasetEditability editability =
                sourceInspector.inspect(asset, ready);
        if (!editability.canCreateWorkspace()) {
            V2DatasetPublishBlocker blocker =
                    editability.blockers().get(0);
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    blocker.code(),
                    blocker.message()
            );
        }
        try {
            DatasetWorkspaceDraftDto created =
                    workspaceService.createDraft(
                            ready.getId(),
                            requestedVersionLabel
                    );
            DatasetVersion workspace = versionRepo
                    .findByIdAndDeletedFalse(created.getDraftVersionId())
                    .orElseThrow(this::notFound);
            if (!Objects.equals(
                    ready.getId(),
                    workspace.getParentVersionId()
            )) {
                throw workspaceBaseConflict(
                        workspace,
                        ready.getId()
                );
            }
            if (requestedVersionLabel != null
                    && !Objects.equals(
                            requestedVersionLabel,
                            displayVersionLabel(workspace)
                    )) {
                throw versionLabelConflict(
                        previewAllocation(
                                asset.getId(),
                                requestedVersionLabel
                        ),
                        "ACTIVE_WORKSPACE_LABEL_MISMATCH",
                        workspace
                );
            }
            return describe(asset, workspace);
        } catch (DatasetWorkspaceService.InvalidVersionLabelException exception) {
            throw invalidVersionLabel();
        } catch (DatasetWorkspaceService.VersionLabelConflictException exception) {
            throw versionLabelConflict(
                    exception.getAllocation(),
                    exception.getAllocation().unavailableReason(),
                    null
            );
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            if (message != null
                    && message.startsWith("DATASET_WORKSPACE_SOURCE_AMBIGUOUS")) {
                throw new V2BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DATASET_WORKSPACE_SOURCE_AMBIGUOUS",
                        "历史数据存储映射无法唯一推导，需要先执行数据迁移"
                );
            }
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATASET_NOT_EDITABLE",
                    "数据集当前无法创建版本工作区"
            );
        }
    }

    @Transactional(readOnly = true)
    public V2DatasetVersionAllocationDto versionAllocation(
            String datasetId,
            String requestedVersionLabel
    ) {
        DatasetAsset asset = requireOwnedAsset(datasetId);
        return allocationDto(previewAllocation(
                asset.getId(),
                requestedVersionLabel
        ));
    }

    @Transactional(readOnly = true)
    public V2DatasetWorkspaceDto get(String workspaceId) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.requireReadable(workspaceId);
        return describe(access.asset(), access.workspace());
    }

    @Transactional(readOnly = true)
    public V2DatasetPublishReadiness readiness(String workspaceId) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.requireReadable(workspaceId);
        return readinessService.evaluate(access.asset(), access.workspace());
    }

    @Transactional
    public V2DatasetWorkspaceDto patch(String workspaceId, JsonNode patch) {
        requireObjectPatch(patch);
        long expectedRevision = requiredRevision(patch);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(workspaceId, expectedRevision);
        rejectUnknownFields(patch, PATCH_FIELDS);

        DatasetVersion workspace = access.workspace();
        if (patch.has("description")) {
            workspace.setDescription(nullableText(patch.get("description"), 2048));
        }
        if (patch.has("changeLog")) {
            workspace.setChangeLog(nullableText(patch.get("changeLog"), 65535));
        }
        if (patch.has("cvTaskType")) {
            workspace.setCvTaskType(CvTaskType.normalizeForTask(
                    access.asset().getType(),
                    nullableText(patch.get("cvTaskType"), 64)
            ));
        }
        if (patch.has("annotationFormat")) {
            workspace.setAnnotationFormat(CvAnnotationFormat.normalizeForTask(
                    access.asset().getType(),
                    nullableText(patch.get("annotationFormat"), 64)
            ));
        }
        long revision = commandService.incrementRevision(workspace);
        auditService.recordUserAction(
                access.asset(),
                workspace,
                "WORKSPACE_METADATA_PATCHED",
                "DATASET_VERSION",
                workspace.getId(),
                null,
                null,
                Map.of("workspaceRevision", revision)
        );
        return describe(access.asset(), workspace);
    }

    @Transactional
    public V2DatasetPublishResult publish(
            String workspaceId,
            Long expectedRevision
    ) {
        V2DatasetPublishResult replay = publishedReplay(workspaceId);
        if (replay != null) {
            return replay;
        }

        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(workspaceId, expectedRevision);
        V2DatasetPublishReadiness readiness =
                readinessService.evaluate(access.asset(), access.workspace());
        if (!readiness.canPublish()) {
            boolean stale = readiness.blockers().stream()
                    .map(V2DatasetPublishBlocker::code)
                    .anyMatch("BASE_VERSION_STALE"::equals);
            throw new V2BusinessException(
                    stale ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY,
                    stale ? "BASE_VERSION_STALE" : "DATASET_NOT_PUBLISHABLE",
                    stale
                            ? "版本工作区基线已过期，请放弃后重新创建"
                            : "数据集尚不满足发布条件",
                    Map.of(
                            "evaluatedRevision", readiness.evaluatedRevision(),
                            "blockers", readiness.blockers()
                    )
            );
        }
        DatasetWorkspacePublishDto published = publishService.publish(workspaceId);
        return publishResult(published);
    }

    @Transactional
    public V2DatasetWorkspaceAbandonResult abandon(
            String workspaceId,
            Long expectedRevision
    ) {
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForAbandon(workspaceId, expectedRevision);
        DatasetVersion workspace = access.workspace();
        if (ABANDONED.equals(workspace.getStatus())) {
            return abandonResult(access.asset(), workspace);
        }

        Instant now = Instant.now();
        List<DatasetUploadSession> uploads =
                uploadSessionRepo.findByVersionIdForUpdate(workspace.getId());
        for (DatasetUploadSession upload : uploads) {
            upload.setStatus("DISCARDED");
            upload.setUpdatedAt(now);
            uploadSessionRepo.save(upload);
            cleanupUploadChunks(upload);
        }
        for (ImportJob job : importJobRepo.findByDatasetVersionIdForUpdate(
                workspace.getId()
        )) {
            if (!"SUPERSEDED".equals(job.getStatus())) {
                job.setStatus("SUPERSEDED");
                job.setErrorCode("WORKSPACE_ABANDONED");
                job.setErrorMessage("数据集版本工作区已放弃");
                job.setErrorDetailsJson(null);
                job.setExecutorId(null);
                job.setHeartbeatAt(null);
                job.setFinishedAt(job.getFinishedAt() == null ? now : job.getFinishedAt());
                job.setUpdatedAt(now);
                importJobRepo.save(job);
            }
        }

        Set<String> inheritedPackageIds = parentPackageIds(workspace);
        List<DatasetVersionPackage> relations =
                versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                        workspace.getId()
                );
        LinkedHashSet<String> workspaceOwnedPackages = new LinkedHashSet<>();
        for (DatasetVersionPackage relation : relations) {
            if (!inheritedPackageIds.contains(relation.getPackageId())) {
                workspaceOwnedPackages.add(relation.getPackageId());
            }
        }
        if (!relations.isEmpty()) {
            versionPackageRepo.deleteAll(relations);
            versionPackageRepo.flush();
        }
        for (String packageId : workspaceOwnedPackages) {
            cleanupWorkspaceOwnedPackage(
                    packageId,
                    access.asset(),
                    workspace,
                    now
            );
        }

        workspace.setStatus(ABANDONED);
        workspace.setDeleted(true);
        workspace.setDeletedAt(now);
        workspace.setUpdatedAt(now);
        long revision = commandService.incrementRevision(workspace);
        auditService.recordUserAction(
                access.asset(),
                workspace,
                "WORKSPACE_ABANDONED",
                "DATASET_VERSION",
                workspace.getId(),
                null,
                null,
                Map.of(
                        "workspaceRevision", revision,
                        "releasedVersionLabel", workspace.getVersionLabel(),
                        "releasedVersionNo", workspace.getVersionNo(),
                        "uploadCount", uploads.size(),
                        "packageCount", workspaceOwnedPackages.size()
                )
        );
        return abandonResult(access.asset(), workspace);
    }

    private V2DatasetWorkspaceDto describe(
            DatasetAsset asset,
            DatasetVersion workspace
    ) {
        V2DatasetPublishReadiness readiness =
                readinessService.evaluate(asset, workspace);
        V2DatasetWorkspaceDto dto = new V2DatasetWorkspaceDto();
        dto.setWorkspaceId(workspace.getId());
        dto.setDatasetId(asset.getId());
        dto.setBaseVersion(summary(
                versionRepo.findByIdAndDeletedFalse(workspace.getParentVersionId())
                        .orElse(null)
        ));
        dto.setTargetVersion(summary(workspace));
        dto.setStatus(workspace.getStatus());
        dto.setWorkspaceRevision(revision(workspace));
        dto.setSampleCount(
                sampleRepo.countByDatasetVersionIdAndDeletedFalse(workspace.getId())
        );
        dto.setActiveOperation(activeOperation(workspace.getId()));
        dto.setPublishReadiness(readiness);
        List<String> actions = new ArrayList<>();
        actions.add("VIEW");
        actions.add("ADD_DATA");
        actions.add("ABANDON");
        if (readiness.canPublish()) {
            actions.add("PUBLISH");
        }
        dto.setAvailableActions(List.copyOf(actions));
        dto.setUserError(latestUserError(workspace.getId()));
        return dto;
    }

    private V2DatasetActiveOperation activeOperation(String workspaceId) {
        DatasetUploadSession upload = uploadSessionRepo.findByVersionId(workspaceId).stream()
                .filter(value -> ACTIVE_UPLOAD_STATUSES.contains(value.getStatus()))
                .max(Comparator.comparing(
                        DatasetUploadSession::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .orElse(null);
        if (upload != null) {
            return new V2DatasetActiveOperation(
                    "UPLOAD",
                    upload.getId(),
                    upload.getStatus(),
                    null
            );
        }
        ImportJob job = importJobRepo.findByDatasetVersionId(workspaceId).stream()
                .filter(value -> ACTIVE_IMPORT_STATUSES.contains(value.getStatus()))
                .max(Comparator.comparing(
                        ImportJob::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .orElse(null);
        return job == null
                ? null
                : new V2DatasetActiveOperation(
                        "IMPORT",
                        job.getId(),
                        job.getStatus(),
                        job.getProgress()
                );
    }

    private V2UserError latestUserError(String workspaceId) {
        ImportJob job = V2ImportJobStatusSelector.statusJobOf(
                importJobRepo.findByDatasetVersionId(workspaceId)
        );
        return V2ImportJobDisplayHelper.userError(job);
    }

    private V2DatasetPublishResult publishedReplay(String workspaceId) {
        DatasetVersion version = versionRepo.findByIdAndDeletedFalse(workspaceId)
                .orElse(null);
        if (version == null || !"READY".equals(version.getStatus())) {
            return null;
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .orElse(null);
        if (asset == null
                || !authContext.canAccessOwner(asset.getOwnerUserId())
                || !version.getId().equals(asset.getCurrentVersionId())) {
            return null;
        }
        V2DatasetPublishResult result = new V2DatasetPublishResult();
        result.setDatasetId(asset.getId());
        result.setCurrentVersion(summary(version));
        result.setPublishedAt(version.getPublishedAt());
        return result;
    }

    private V2DatasetPublishResult publishResult(DatasetWorkspacePublishDto published) {
        V2DatasetPublishResult result = new V2DatasetPublishResult();
        result.setDatasetId(published.getDatasetAssetId());
        result.setCurrentVersion(new V2DatasetVersionSummary(
                published.getDatasetVersionId(),
                publishVersionLabel(published),
                published.getVersionNo(),
                published.getStatus()
        ));
        result.setPublishedAt(published.getPublishedAt());
        return result;
    }

    private String publishVersionLabel(DatasetWorkspacePublishDto published) {
        if (published.getVersionLabel() != null
                && !published.getVersionLabel().isBlank()) {
            return published.getVersionLabel();
        }
        return published.getVersionNo() == null
                ? null
                : "v" + published.getVersionNo();
    }

    private V2DatasetWorkspaceAbandonResult abandonResult(
            DatasetAsset asset,
            DatasetVersion workspace
    ) {
        return new V2DatasetWorkspaceAbandonResult(
                workspace.getId(),
                asset.getId(),
                ABANDONED,
                workspace.getUpdatedAt(),
                revision(workspace)
        );
    }

    private Set<String> parentPackageIds(DatasetVersion workspace) {
        if (workspace.getParentVersionId() == null) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (DatasetVersionPackage relation :
                versionPackageRepo.findByDatasetVersionIdOrderByPackageOrderAsc(
                        workspace.getParentVersionId()
                )) {
            ids.add(relation.getPackageId());
        }
        return ids;
    }

    private void cleanupWorkspaceOwnedPackage(
            String packageId,
            DatasetAsset asset,
            DatasetVersion workspace,
            Instant now
    ) {
        if (!versionPackageRepo.findByPackageId(packageId).isEmpty()) {
            return;
        }
        DatasetPackage datasetPackage = packageRepo
                .findByIdAndDeletedFalse(packageId)
                .orElse(null);
        if (datasetPackage == null
                || !asset.getId().equals(datasetPackage.getDatasetAssetId())) {
            return;
        }
        DatasetVersion parent = workspace.getParentVersionId() == null
                ? null
                : versionRepo.findByIdAndDeletedFalse(
                        workspace.getParentVersionId()
                ).orElse(null);
        if (parent != null
                && parent.getStoragePath() != null
                && parent.getStoragePath().equals(
                        datasetPackage.getStoragePath()
                )) {
            datasetPackage.setStatus("SUPERSEDED");
            datasetPackage.setDeleted(true);
            datasetPackage.setDeletedAt(now);
            packageRepo.save(datasetPackage);
            return;
        }
        deleteTaskService.enqueueDefaultBucketDelete(
                datasetPackage.getStoragePath(),
                MinioDeleteTaskService.SOURCE_DATASET_PACKAGE,
                datasetPackage.getId(),
                asset.getOwnerUserId()
        );
        datasetPackage.setStatus("SUPERSEDED");
        datasetPackage.setDeleted(true);
        datasetPackage.setDeletedAt(now);
        packageRepo.save(datasetPackage);
    }

    private void cleanupUploadChunks(DatasetUploadSession upload) {
        List<DatasetUploadChunk> chunks =
                uploadChunkRepo.findByUploadIdOrderByPartIndexAsc(upload.getId());
        for (DatasetUploadChunk chunk : chunks) {
            if (chunk.getObjectName() == null || chunk.getObjectName().isBlank()) {
                continue;
            }
            deleteTaskService.enqueueDefaultBucketDelete(
                    chunk.getObjectName(),
                    MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                    upload.getId(),
                    upload.getOwnerUserId()
            );
        }
        uploadChunkRepo.deleteByUploadId(upload.getId());
    }

    private DatasetAsset requireOwnedAsset(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw notFound();
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(datasetId)
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        return asset;
    }

    private DatasetAsset requireOwnedAssetForUpdate(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw notFound();
        }
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(datasetId)
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        return asset;
    }

    private DatasetVersion requireCurrentReadyVersion(DatasetAsset asset) {
        DatasetVersion current = versionRepo
                .findByIdAndDeletedFalse(asset.getCurrentVersionId())
                .orElseThrow(() -> new V2BusinessException(
                        HttpStatus.CONFLICT,
                        "DATASET_NOT_EDITABLE",
                        "数据集当前没有有效的已发布版本"
                ));
        if (!Objects.equals(asset.getId(), current.getAssetId())
                || !"READY".equals(current.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "DATASET_NOT_EDITABLE",
                    "数据集当前没有有效的已发布版本"
            );
        }
        return current;
    }

    private DatasetVersion requireRequestedBaseVersion(
            DatasetAsset asset,
            String baseVersionId
    ) {
        DatasetVersion base = versionRepo
                .findByIdAndDeletedFalse(baseVersionId)
                .orElseThrow(this::notFound);
        if (!Objects.equals(asset.getId(), base.getAssetId())) {
            throw notFound();
        }
        if (!"READY".equals(base.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BASE_VERSION_NOT_READY",
                    "所选基线版本不是 READY 状态",
                    Map.of("baseVersionId", baseVersionId)
            );
        }
        return base;
    }

    private String normalizeBaseVersionId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BASE_VERSION_ID",
                    "baseVersionId 不能为空"
            );
        }
        return normalized;
    }

    private String normalizeVersionLabel(String value) {
        try {
            return workspaceService.normalizeRequestedVersionLabel(value);
        } catch (DatasetWorkspaceService.InvalidVersionLabelException exception) {
            throw invalidVersionLabel();
        }
    }

    private DatasetWorkspaceService.VersionAllocationPreview previewAllocation(
            String datasetId,
            String requestedVersionLabel
    ) {
        try {
            return workspaceService.previewVersionAllocation(
                    datasetId,
                    requestedVersionLabel
            );
        } catch (DatasetWorkspaceService.InvalidVersionLabelException exception) {
            throw invalidVersionLabel();
        }
    }

    private V2DatasetVersionAllocationDto allocationDto(
            DatasetWorkspaceService.VersionAllocationPreview allocation
    ) {
        return new V2DatasetVersionAllocationDto(
                allocation.nextVersionNo(),
                allocation.defaultVersionLabel(),
                allocation.requestedVersionLabel(),
                allocation.requestedVersionLabelAvailable(),
                allocation.unavailableReason()
        );
    }

    private V2BusinessException invalidVersionLabel() {
        return new V2BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_VERSION_LABEL",
                "版本标签不能为空且长度不能超过 64 个字符"
        );
    }

    private V2BusinessException versionLabelConflict(
            DatasetWorkspaceService.VersionAllocationPreview allocation,
            String reasonCode,
            DatasetVersion activeWorkspace
    ) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put(
                "reasonCode",
                reasonCode == null
                        ? DatasetWorkspaceService.ACTIVE_VERSION_EXISTS
                        : reasonCode
        );
        if (allocation.requestedVersionLabel() != null) {
            details.put(
                    "requestedVersionLabel",
                    allocation.requestedVersionLabel()
            );
        }
        details.put("nextVersionNo", allocation.nextVersionNo());
        details.put("defaultVersionLabel", allocation.defaultVersionLabel());
        if (activeWorkspace != null) {
            details.put("workspaceId", activeWorkspace.getId());
            details.put(
                    "currentVersionLabel",
                    displayVersionLabel(activeWorkspace)
            );
        }
        return new V2BusinessException(
                HttpStatus.CONFLICT,
                "DATASET_VERSION_LABEL_CONFLICT",
                "版本标签已被占用或与当前工作区不一致，请使用建议标签后重试",
                details
        );
    }

    private V2BusinessException workspaceBaseConflict(
            DatasetVersion activeWorkspace,
            String requestedBaseVersionId
    ) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("workspaceId", activeWorkspace.getId());
        if (activeWorkspace.getParentVersionId() != null) {
            details.put(
                    "activeBaseVersionId",
                    activeWorkspace.getParentVersionId()
            );
        }
        details.put("requestedBaseVersionId", requestedBaseVersionId);
        return new V2BusinessException(
                HttpStatus.CONFLICT,
                "WORKSPACE_BASE_CONFLICT",
                "已有活动工作区使用不同基线，请先发布或放弃该工作区",
                details
        );
    }

    private static V2DatasetVersionSummary summary(DatasetVersion version) {
        if (version == null) {
            return null;
        }
        String label = version.getVersionLabel() == null
                || version.getVersionLabel().isBlank()
                ? version.getVersion()
                : version.getVersionLabel();
        return new V2DatasetVersionSummary(
                version.getId(),
                label,
                version.getVersionNo(),
                version.getStatus()
        );
    }

    private static String displayVersionLabel(DatasetVersion version) {
        if (version == null) {
            return null;
        }
        return version.getVersionLabel() == null
                || version.getVersionLabel().isBlank()
                ? version.getVersion()
                : version.getVersionLabel();
    }

    private static long revision(DatasetVersion version) {
        return version.getWorkspaceRevision() == null
                ? 0L
                : version.getWorkspaceRevision();
    }

    private static void requireObjectPatch(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "PATCH 请求体必须是 JSON 对象"
            );
        }
    }

    private static long requiredRevision(JsonNode patch) {
        JsonNode node = patch.get("expectedWorkspaceRevision");
        if (node == null || !node.canConvertToLong()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                    "expectedWorkspaceRevision 不能为空"
            );
        }
        return node.longValue();
    }

    private static void rejectUnknownFields(JsonNode patch, Set<String> allowed) {
        patch.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new V2BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "PATCH_FIELD_NOT_ALLOWED",
                        "PATCH 包含不允许修改的字段",
                        Map.of("field", field)
                );
            }
        });
    }

    private static String nullableText(JsonNode node, int maxLength) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "字段必须是字符串或 null"
            );
        }
        String value = node.textValue().trim();
        if (value.length() > maxLength) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "字段长度超过限制"
            );
        }
        return value.isEmpty() ? null : value;
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_NOT_FOUND",
                "数据集或版本工作区不存在或无权访问"
        );
    }
}
