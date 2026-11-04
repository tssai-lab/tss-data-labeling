package com.tss.platform.controller;

import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import org.springframework.beans.factory.annotation.Autowired;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.model.CvAnnotationFormat;
import com.tss.platform.model.CvTaskType;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.TrainingExperimentVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.AssetNameConflictException;
import com.tss.platform.service.AssetNamePolicy;
import com.tss.platform.service.AssetNameValidationException;
import com.tss.platform.service.MinioDeleteTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/dataset-assets")
public class DatasetAssetCrudController {
    @Autowired
    private AuditHooks auditHooks;


    private static final Logger log = LoggerFactory.getLogger(DatasetAssetCrudController.class);

    private final DatasetAssetRepository repo;
    private final DatasetVersionRepository versionRepo;
    private final TrainingExperimentVersionRepository trainingRepo;
    private final MinioDeleteTaskService minioDeleteTaskService;
    private final AuthContext authContext;

    public DatasetAssetCrudController(
            DatasetAssetRepository repo,
            DatasetVersionRepository versionRepo,
            TrainingExperimentVersionRepository trainingRepo,
            MinioDeleteTaskService minioDeleteTaskService,
            AuthContext authContext
    ) {
        this.repo = repo;
        this.versionRepo = versionRepo;
        this.trainingRepo = trainingRepo;
        this.minioDeleteTaskService = minioDeleteTaskService;
        this.authContext = authContext;
    }

    @PostMapping
    public ApiResponse<DatasetAsset> create(@RequestBody DatasetAsset body) {
        if (body == null) {
            throw new AssetNameValidationException("request body cannot be empty");
        }
        String normalizedName = AssetNamePolicy.normalizeRequired(body.getName());
        Integer ownerUserId = authContext.currentUserId();
        requireUniqueName(ownerUserId, normalizedName, null);
        try {
            body.setType(DatasetTaskType.normalize(body.getType()));
            body.setCvTaskType(CvTaskType.normalizeForTask(body.getType(), body.getCvTaskType()));
            body.setAnnotationFormat(CvAnnotationFormat.normalizeForTask(body.getType(), body.getAnnotationFormat()));
            body.setName(normalizedName);
            body.setId("dataset-asset-" + UUID.randomUUID().toString().replace("-", ""));
            body.setOwnerUserId(ownerUserId);
            if (body.getCreatedAt() == null) {
                body.setCreatedAt(Instant.now());
            }
            body.setUpdatedAt(Instant.now());
            body.setDeleted(false);
            body.setDeletedAt(null);
            return ApiResponse.ok(repo.saveAndFlush(body));
        } catch (DataIntegrityViolationException exception) {
            throwNameConflictOrRethrow(exception);
            throw exception;
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasetAsset> get(@PathVariable String id) {
        Optional<DatasetAsset> v = repo.findByIdAndDeletedFalse(id);
        if (v.isEmpty() || !authContext.canAccessOwner(v.get().getOwnerUserId())) {
            return ApiResponse.fail("not found or no permission: " + id);
        }
        return ApiResponse.ok(v.get());
    }

    @GetMapping
    public ApiResponse<List<DatasetAsset>> list() {
        if (authContext.isAdmin()) {
            return ApiResponse.ok(repo.findByDeletedFalse());
        }
        return ApiResponse.ok(repo.findByOwnerUserIdAndDeletedFalse(authContext.currentUserId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<DatasetAsset> update(@PathVariable String id, @RequestBody DatasetAsset body) {
        Optional<DatasetAsset> existing = repo.findByIdAndDeletedFalse(id);
        if (existing.isEmpty()) {
            return ApiResponse.fail("未找到: " + id);
        }
        DatasetAsset e = existing.get();
        if (!authContext.canAccessOwner(e.getOwnerUserId())) {
            return ApiResponse.fail("no permission: " + id);
        }
        authContext.rejectDemoWrite(e.getIsDemo());
        if (body == null) {
            throw new AssetNameValidationException("request body cannot be empty");
        }
        String normalizedName = AssetNamePolicy.normalizeRequired(body.getName());
        String normalizedType;
        String normalizedCvTaskType;
        String normalizedAnnotationFormat;
        try {
            normalizedType = DatasetTaskType.normalize(body.getType());
            normalizedCvTaskType = CvTaskType.normalizeForTask(
                    normalizedType,
                    body.getCvTaskType()
            );
            normalizedAnnotationFormat = CvAnnotationFormat.normalizeForTask(
                    normalizedType,
                    body.getAnnotationFormat()
            );
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
        requireUniqueName(e.getOwnerUserId(), normalizedName, id);
        e.setName(normalizedName);
        e.setType(normalizedType);
        e.setCvTaskType(normalizedCvTaskType);
        e.setAnnotationFormat(normalizedAnnotationFormat);
        e.setRemark(body.getRemark());
        e.setUpdatedAt(Instant.now());
        try {
            return ApiResponse.ok(repo.saveAndFlush(e));
        } catch (DataIntegrityViolationException exception) {
            throwNameConflictOrRethrow(exception);
            throw exception;
        }
    }

    @PutMapping("/{id}/current-version")
    public ApiResponse<DatasetAsset> setCurrentVersion(
            @PathVariable String id,
            @RequestBody Map<String, String> body
    ) {
        Optional<DatasetAsset> existing = repo.findByIdAndDeletedFalse(id);
        if (existing.isEmpty() || !authContext.canAccessOwner(existing.get().getOwnerUserId())) {
            return ApiResponse.fail("not found or no permission: " + id);
        }
        authContext.rejectDemoWrite(existing.get().getIsDemo());
        String versionId = body == null ? null : body.get("versionId");
        if (versionId == null || versionId.isBlank()) {
            return ApiResponse.fail("versionId cannot be empty");
        }
        Optional<DatasetVersion> target = versionRepo.findByIdAndDeletedFalse(versionId.trim());
        if (target.isEmpty()) {
            return ApiResponse.fail("dataset version not found: " + versionId);
        }
        DatasetVersion version = target.get();
        if (!id.equals(version.getAssetId())) {
            return ApiResponse.fail("version must belong to asset: " + id);
        }
        if (!"READY".equals(version.getStatus())) {
            return ApiResponse.fail("current version must be READY");
        }
        DatasetAsset asset = existing.get();
        asset.setCurrentVersionId(version.getId());
        asset.setUpdatedAt(Instant.now());
        return ApiResponse.ok(repo.save(asset));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        Optional<DatasetAsset> existing = repo.findByIdAndDeletedFalse(id);
        if (existing.isEmpty() || !authContext.canAccessOwner(existing.get().getOwnerUserId())) {
            auditHooks.delete(AuditObjectType.DATASET, id, "DATASET_DELETE", false, "not found or no permission");
            return ApiResponse.fail("not found or no permission: " + id);
        }
        authContext.rejectDemoWrite(existing.get().getIsDemo());

        List<DatasetVersion> versions = versionRepo.findByAssetId(id);
        List<String> versionIds = versions.stream().map(DatasetVersion::getId).toList();
        if (!versionIds.isEmpty() && trainingRepo.countByDatasetVersionIdIn(versionIds) > 0) {
            log.warn("Reject dataset asset delete because versions are referenced by training experiments: id={}", id);
            auditHooks.delete(AuditObjectType.DATASET, id, "DATASET_DELETE", false, "referenced by training");
            return ApiResponse.fail("dataset asset has versions referenced by training experiments");
        }
        LinkedHashSet<String> objectNames = new LinkedHashSet<>();
        for (DatasetVersion version : versions) {
            if (Boolean.TRUE.equals(version.getDeleted())) {
                continue;
            }
            String storagePath = version.getStoragePath();
            if (storagePath != null && !storagePath.isBlank()) {
                objectNames.add(storagePath);
            }
        }

        try {
            for (String objectName : objectNames) {
                authContext.requireObjectAccess(objectName, existing.get().getOwnerUserId(), "object not found or no permission");
                minioDeleteTaskService.enqueueDefaultBucketDelete(
                        objectName,
                        MinioDeleteTaskService.SOURCE_DATASET_ASSET,
                        id,
                        existing.get().getOwnerUserId()
                );
            }
        } catch (Exception e) {
            log.warn("Reject dataset asset delete because MinIO delete task cannot be queued: id={}, error={}", id, e.getMessage());
            return ApiResponse.fail("创建数据集文件删除任务失败: " + e.getMessage());
        }

        Instant now = Instant.now();
        int deletedVersions = 0;
        for (DatasetVersion version : versions) {
            if (!Boolean.TRUE.equals(version.getDeleted())) {
                version.setDeleted(true);
                version.setDeletedAt(now);
                deletedVersions += 1;
            }
        }
        versionRepo.saveAll(versions);
        DatasetAsset asset = existing.get();
        asset.setDeleted(true);
        asset.setDeletedAt(now);
        asset.setUpdatedAt(now);
        repo.save(asset);
        log.info(
                "Dataset asset soft deleted: id={}, deletedVersions={}, queuedObjects={}, ownerUserId={}",
                id,
                deletedVersions,
                objectNames.size(),
                asset.getOwnerUserId()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("deletedVersions", deletedVersions);
        result.put("deleted", true);
        result.put("minioDeleteQueued", objectNames.size());
        auditHooks.delete(AuditObjectType.DATASET, id, "DATASET_DELETE", true, null);
        return ApiResponse.ok(result);
    }

    private void requireUniqueName(
            Integer ownerUserId,
            String normalizedName,
            String excludedId
    ) {
        if (repo.existsActiveNormalizedName(ownerUserId, normalizedName, excludedId)) {
            throw new AssetNameConflictException("dataset");
        }
    }

    private void throwNameConflictOrRethrow(DataIntegrityViolationException exception) {
        if (AssetNamePolicy.isNameConstraintViolation(exception)) {
            throw new AssetNameConflictException("dataset");
        }
    }
}

