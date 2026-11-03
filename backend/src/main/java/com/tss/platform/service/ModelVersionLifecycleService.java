package com.tss.platform.service;

import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@Service
public class ModelVersionLifecycleService {

    private static final String READY = "READY";
    private static final Set<String> RETIRE_STATUSES = Set.of("DEPRECATED", "ARCHIVED");

    private final ModelAssetRepository assetRepo;
    private final ModelVersionRepository versionRepo;
    private final TrainingExperimentVersionRepository trainingRepo;
    private final MinioDeleteTaskService deleteTaskService;
    private final ModelArtifactAttestationService attestationService;
    private final AuthContext authContext;
    private final TransactionTemplate transactionTemplate;

    public ModelVersionLifecycleService(
            ModelAssetRepository assetRepo,
            ModelVersionRepository versionRepo,
            TrainingExperimentVersionRepository trainingRepo,
            MinioDeleteTaskService deleteTaskService,
            ModelArtifactAttestationService attestationService,
            AuthContext authContext,
            PlatformTransactionManager transactionManager
    ) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.trainingRepo = trainingRepo;
        this.deleteTaskService = deleteTaskService;
        this.attestationService = attestationService;
        this.authContext = authContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ModelAsset switchCurrent(String assetId, String versionId) {
        ModelAsset snapshot = requireAsset(assetId);
        authContext.requireOwnerAccess(snapshot.getOwnerUserId(), "model asset not found or no permission");
        authContext.rejectDemoWrite(snapshot.getIsDemo());
        ModelVersion version = versionRepo.findByIdAndDeletedFalse(versionId)
                .orElseThrow(() -> new IllegalArgumentException("model version not found: " + versionId));
        if (!assetId.equals(version.getAssetId())) {
            throw new IllegalArgumentException("model version must belong to asset: " + assetId);
        }
        attestationService.attestReady(versionId);

        ModelAsset updated = transactionTemplate.execute(status -> {
            ModelAsset asset = assetRepo.findByIdAndDeletedFalseForUpdate(assetId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "model asset not found: " + assetId
                    ));
            ModelVersion current = versionRepo.findByIdAndDeletedFalseForUpdate(versionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "model version not found: " + versionId
                    ));
            if (!assetId.equals(current.getAssetId()) || !READY.equals(current.getStatus())) {
                throw new IllegalArgumentException("current model version must be READY and belong to the asset");
            }
            if (current.getArtifactSha256() == null || current.getArtifactSha256().isBlank()) {
                throw new IllegalArgumentException("model artifact has not passed integrity verification");
            }
            asset.setCurrentVersionId(current.getId());
            asset.setUpdatedAt(Instant.now());
            return assetRepo.saveAndFlush(asset);
        });
        if (updated == null) {
            throw new IllegalArgumentException("model current version could not be updated");
        }
        return updated;
    }

    public ModelVersion retire(String versionId, String targetStatus) {
        String normalized = targetStatus == null
                ? ""
                : targetStatus.trim().toUpperCase(Locale.ROOT);
        if (!RETIRE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("model status can only change to DEPRECATED or ARCHIVED");
        }
        ModelVersion snapshot = requireVersion(versionId);
        ModelAsset asset = requireAsset(snapshot.getAssetId());
        authContext.requireOwnerAccess(effectiveOwner(snapshot, asset), "model version not found or no permission");
        authContext.rejectDemoWrite(asset.getIsDemo());
        ModelVersion updated = transactionTemplate.execute(status -> {
            ModelAsset lockedAsset = assetRepo.findByIdAndDeletedFalseForUpdate(asset.getId())
                    .orElseThrow(() -> new IllegalArgumentException("model asset not found"));
            ModelVersion lockedVersion = versionRepo.findByIdAndDeletedFalseForUpdate(versionId)
                    .orElseThrow(() -> new IllegalArgumentException("model version not found"));
            rejectCurrent(lockedAsset, lockedVersion);
            if (!READY.equals(lockedVersion.getStatus())) {
                throw new IllegalArgumentException("only READY model versions can be retired");
            }
            lockedVersion.setStatus(normalized);
            return versionRepo.saveAndFlush(lockedVersion);
        });
        if (updated == null) {
            throw new IllegalArgumentException("model version status could not be updated");
        }
        return updated;
    }

    public Map<String, Object> deleteVersion(String versionId) {
        ModelVersion snapshot = requireVersion(versionId);
        ModelAsset snapshotAsset = requireAsset(snapshot.getAssetId());
        Integer owner = effectiveOwner(snapshot, snapshotAsset);
        authContext.requireOwnerAccess(owner, "model version not found or no permission");
        authContext.rejectDemoWrite(snapshotAsset.getIsDemo());
        DeleteVersionResult deleted = transactionTemplate.execute(status -> {
            ModelAsset lockedAsset = assetRepo.findByIdAndDeletedFalseForUpdate(
                            snapshotAsset.getId()
                    )
                    .orElseThrow(() -> new IllegalArgumentException("model asset not found"));
            ModelVersion lockedVersion = versionRepo.findByIdAndDeletedFalseForUpdate(versionId)
                    .orElseThrow(() -> new IllegalArgumentException("model version not found"));
            rejectCurrent(lockedAsset, lockedVersion);
            if (versionRepo.countByAssetIdAndDeletedFalse(lockedAsset.getId()) <= 1) {
                throw new IllegalArgumentException("last model version can only be deleted with the model asset");
            }
            if (trainingRepo.countByModelVersionId(versionId) > 0
                    || trainingRepo.countByProducedModelVersionId(versionId) > 0) {
                throw new IllegalArgumentException("model version is referenced by training experiments");
            }
            boolean queued = queueVersionObject(
                    lockedVersion,
                    effectiveOwner(lockedVersion, lockedAsset)
            );
            lockedVersion.setDeleted(true);
            lockedVersion.setDeletedAt(Instant.now());
            versionRepo.saveAndFlush(lockedVersion);
            return new DeleteVersionResult(lockedAsset.getId(), queued);
        });
        if (deleted == null) {
            throw new IllegalArgumentException("model version could not be deleted");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", versionId);
        result.put("assetId", deleted.assetId());
        result.put("deleted", true);
        result.put("minioDeleteQueued", deleted.queued());
        return result;
    }

    public Map<String, Object> deleteAsset(String assetId) {
        ModelAsset snapshot = requireAsset(assetId);
        authContext.requireOwnerAccess(
                snapshot.getOwnerUserId(),
                "model asset not found or no permission"
        );
        authContext.rejectDemoWrite(snapshot.getIsDemo());
        DeleteAssetResult deleted = transactionTemplate.execute(status -> {
            ModelAsset locked = assetRepo.findByIdAndDeletedFalseForUpdate(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("model asset not found"));
            List<ModelVersion> currentVersions = versionRepo.findByAssetIdAndDeletedFalse(assetId);
            List<String> versionIds = currentVersions.stream().map(ModelVersion::getId).toList();
            if (!versionIds.isEmpty()
                    && (trainingRepo.countByModelVersionIdIn(versionIds) > 0
                    || trainingRepo.countByProducedModelVersionIdIn(versionIds) > 0)) {
                throw new IllegalArgumentException(
                        "model asset has versions referenced by training experiments"
                );
            }
            LinkedHashSet<String> objectNames = new LinkedHashSet<>();
            for (ModelVersion version : currentVersions) {
                String objectName = version.getStoragePath();
                if (objectName == null || objectName.isBlank() || !objectNames.add(objectName)) {
                    continue;
                }
                authContext.requireObjectAccess(
                        objectName,
                        effectiveOwner(version, locked),
                        "object not found or no permission"
                );
                try {
                    deleteTaskService.enqueueDefaultBucketDelete(
                            objectName,
                            MinioDeleteTaskService.SOURCE_MODEL_ASSET,
                            assetId,
                            locked.getOwnerUserId()
                    );
                } catch (Exception exception) {
                    throw new IllegalArgumentException(
                            "创建模型文件删除任务失败: " + exception.getMessage()
                    );
                }
            }
            Instant now = Instant.now();
            for (ModelVersion version : currentVersions) {
                version.setDeleted(true);
                version.setDeletedAt(now);
            }
            versionRepo.saveAll(currentVersions);
            locked.setCurrentVersionId(null);
            locked.setDeleted(true);
            locked.setDeletedAt(now);
            locked.setUpdatedAt(now);
            assetRepo.saveAndFlush(locked);
            return new DeleteAssetResult(currentVersions.size(), objectNames.size());
        });
        if (deleted == null) {
            throw new IllegalArgumentException("model asset could not be deleted");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", assetId);
        result.put("deletedVersions", deleted.deletedVersions());
        result.put("deleted", true);
        result.put("minioDeleteQueued", deleted.queuedObjects());
        return result;
    }

    private boolean queueVersionObject(ModelVersion version, Integer owner) {
        if (version.getStoragePath() == null || version.getStoragePath().isBlank()) {
            return false;
        }
        authContext.requireObjectAccess(
                version.getStoragePath(),
                owner,
                "object not found or no permission"
        );
        try {
            deleteTaskService.enqueueDefaultBucketDelete(
                    version.getStoragePath(),
                    MinioDeleteTaskService.SOURCE_MODEL_VERSION,
                    version.getId(),
                    owner
            );
            return true;
        } catch (Exception exception) {
            throw new IllegalArgumentException("创建模型版本文件删除任务失败: " + exception.getMessage());
        }
    }

    private void rejectCurrent(ModelAsset asset, ModelVersion version) {
        if (version.getId().equals(asset.getCurrentVersionId())) {
            throw new IllegalArgumentException("current model version cannot be changed or deleted");
        }
    }

    private ModelAsset requireAsset(String id) {
        return assetRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("model asset not found: " + id));
    }

    private ModelVersion requireVersion(String id) {
        return versionRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("model version not found: " + id));
    }

    private Integer effectiveOwner(ModelVersion version, ModelAsset asset) {
        return version.getOwnerUserId() == null
                ? asset.getOwnerUserId()
                : version.getOwnerUserId();
    }

    private record DeleteVersionResult(String assetId, boolean queued) {
    }

    private record DeleteAssetResult(int deletedVersions, int queuedObjects) {
    }
}
