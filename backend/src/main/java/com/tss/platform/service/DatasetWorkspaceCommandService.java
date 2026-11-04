package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DatasetWorkspaceCommandService {

    private static final String DRAFT = "DRAFT";
    private static final Set<String> ACTIVE_UPLOAD_STATUSES =
            Set.of("UPLOADING", "COMPLETING");
    private static final Set<String> ACTIVE_IMPORT_STATUSES =
            Set.of("PENDING", "RUNNING");

    private final DatasetVersionRepository versionRepo;
    private final DatasetAssetRepository assetRepo;
    private final DatasetUploadSessionRepository uploadSessionRepo;
    private final ImportJobRepository importJobRepo;
    private final AuthContext authContext;

    public DatasetWorkspaceCommandService(
            DatasetVersionRepository versionRepo,
            DatasetAssetRepository assetRepo,
            DatasetUploadSessionRepository uploadSessionRepo,
            ImportJobRepository importJobRepo,
            AuthContext authContext
    ) {
        this.versionRepo = versionRepo;
        this.assetRepo = assetRepo;
        this.uploadSessionRepo = uploadSessionRepo;
        this.importJobRepo = importJobRepo;
        this.authContext = authContext;
    }

    public WorkspaceAccess requireReadable(String workspaceId) {
        DatasetVersion workspace = versionRepo.findByIdAndDeletedFalse(workspaceId)
                .orElseThrow(this::notFound);
        if (!DRAFT.equals(workspace.getStatus())) {
            throw notFound();
        }
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(workspace.getAssetId())
                .orElseThrow(this::notFound);
        if (!authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        return new WorkspaceAccess(asset, workspace);
    }

    public WorkspaceAccess lockForMutation(
            String workspaceId,
            Long expectedRevision
    ) {
        return lockForMutation(workspaceId, expectedRevision, null, false);
    }

    public WorkspaceAccess lockForOperationSettlement(
            String workspaceId,
            Long expectedRevision,
            String operationId
    ) {
        return lockForMutation(workspaceId, expectedRevision, operationId, true);
    }

    public WorkspaceAccess lockForLegacyMutation(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw notFound();
        }
        DatasetVersion snapshot = versionRepo
                .findByIdAndDeletedFalse(workspaceId)
                .orElseThrow(this::notFound);
        DatasetAsset asset = assetRepo
                .findByIdAndDeletedFalseForUpdate(snapshot.getAssetId())
                .orElseThrow(this::notFound);
        DatasetVersion workspace = versionRepo
                .findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(this::notFound);
        if (!DRAFT.equals(workspace.getStatus())
                || !asset.getId().equals(workspace.getAssetId())
                || !authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        if (isBusy(workspace.getId(), null)) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "WORKSPACE_BUSY",
                    "版本工作区正在执行上传或导入任务"
            );
        }
        return new WorkspaceAccess(asset, workspace);
    }

    public WorkspaceAccess lockForAbandon(
            String workspaceId,
            Long expectedRevision
    ) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw notFound();
        }
        DatasetVersion snapshot = versionRepo.findById(workspaceId)
                .orElseThrow(this::notFound);
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalseForUpdate(snapshot.getAssetId())
                .orElseThrow(this::notFound);
        DatasetVersion workspace = versionRepo.findByIdForUpdate(workspaceId)
                .orElseThrow(this::notFound);
        if (!asset.getId().equals(workspace.getAssetId())
                || !authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        if ("ABANDONED".equals(workspace.getStatus())) {
            return new WorkspaceAccess(asset, workspace);
        }
        if (!DRAFT.equals(workspace.getStatus())) {
            throw notFound();
        }
        requireRevision(workspace, expectedRevision);
        return new WorkspaceAccess(asset, workspace);
    }

    public long incrementRevision(DatasetVersion workspace) {
        long next = revision(workspace) + 1L;
        workspace.setWorkspaceRevision(next);
        workspace.setUpdatedAt(Instant.now());
        versionRepo.saveAndFlush(workspace);
        return next;
    }

    public long revision(DatasetVersion workspace) {
        return workspace.getWorkspaceRevision() == null
                ? 0L
                : workspace.getWorkspaceRevision();
    }

    public boolean isBusy(String workspaceId, String ignoredOperationId) {
        List<DatasetUploadSession> uploads = uploadSessionRepo.findByVersionIdAndStatusIn(
                workspaceId,
                List.copyOf(ACTIVE_UPLOAD_STATUSES)
        );
        for (DatasetUploadSession upload : uploads) {
            if (ignoredOperationId == null || !ignoredOperationId.equals(upload.getId())) {
                return true;
            }
        }
        for (ImportJob job : importJobRepo.findByDatasetVersionId(workspaceId)) {
            if (ACTIVE_IMPORT_STATUSES.contains(job.getStatus())
                    && (ignoredOperationId == null
                    || !ignoredOperationId.equals(job.getId()))) {
                return true;
            }
        }
        return false;
    }

    private WorkspaceAccess lockForMutation(
            String workspaceId,
            Long expectedRevision,
            String ignoredOperationId,
            boolean settlement
    ) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw notFound();
        }
        DatasetVersion snapshot = versionRepo.findByIdAndDeletedFalse(workspaceId)
                .orElseThrow(this::notFound);
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalseForUpdate(snapshot.getAssetId())
                .orElseThrow(this::notFound);
        DatasetVersion workspace = versionRepo
                .findByIdAndDeletedFalseForUpdate(workspaceId)
                .orElseThrow(this::notFound);
        if (!DRAFT.equals(workspace.getStatus())
                || !asset.getId().equals(workspace.getAssetId())
                || !authContext.canAccessOwner(asset.getOwnerUserId())) {
            throw notFound();
        }
        requireRevision(workspace, expectedRevision);
        if (isBusy(workspace.getId(), ignoredOperationId)) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "WORKSPACE_BUSY",
                    "版本工作区正在执行上传或导入任务",
                    Map.of(
                            "workspaceId", workspace.getId(),
                            "operationSettlement", settlement
                    )
            );
        }
        return new WorkspaceAccess(asset, workspace);
    }

    private void requireRevision(DatasetVersion workspace, Long expectedRevision) {
        if (expectedRevision == null) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                    "expectedWorkspaceRevision 不能为空"
            );
        }
        long current = revision(workspace);
        if (expectedRevision != current) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "WORKSPACE_REVISION_CONFLICT",
                    "版本工作区已发生变更，请刷新后重试",
                    Map.of(
                            "workspaceId", workspace.getId(),
                            "expectedRevision", expectedRevision,
                            "currentRevision", current
                    )
            );
        }
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_WORKSPACE_NOT_FOUND",
                "版本工作区不存在或无权访问"
        );
    }

    public record WorkspaceAccess(DatasetAsset asset, DatasetVersion workspace) {
    }
}
