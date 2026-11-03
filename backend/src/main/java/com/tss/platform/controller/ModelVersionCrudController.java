package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.ModelVersionCreateRequest;
import com.tss.platform.dto.ModelVersionUpdateRequest;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ModelVersionLifecycleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/model-versions")
public class ModelVersionCrudController {

    private static final String STATUS_DRAFT = "DRAFT";

    private final ModelVersionRepository repo;
    private final ModelAssetRepository assetRepo;
    private final AuthContext authContext;
    private final ModelVersionLifecycleService lifecycleService;

    public ModelVersionCrudController(
            ModelVersionRepository repo,
            ModelAssetRepository assetRepo,
            AuthContext authContext,
            ModelVersionLifecycleService lifecycleService
    ) {
        this.repo = repo;
        this.assetRepo = assetRepo;
        this.authContext = authContext;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    public ApiResponse<ModelVersion> create(@RequestBody ModelVersionCreateRequest body) {
        if (body == null) {
            return ApiResponse.fail("request body cannot be empty");
        }
        if (hasCreateServerManagedFields(body)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status and artifact fields are managed by the upload service"
            );
        }
        String assetId = normalize(body.getAssetId());
        String versionName = normalize(body.getVersion());
        if (assetId == null) {
            return ApiResponse.fail("assetId cannot be empty");
        }
        if (versionName == null) {
            return ApiResponse.fail("version cannot be empty");
        }
        ModelAsset asset = assetRepo.findByIdAndDeletedFalse(assetId).orElse(null);
        if (asset == null || !authContext.canAccessOwner(asset.getOwnerUserId())) {
            return ApiResponse.fail("not found or no permission: " + assetId);
        }
        authContext.rejectDemoWrite(asset.getIsDemo());
        if (repo.existsByAssetIdAndVersion(assetId, versionName)) {
            return ApiResponse.fail("model version already exists for asset: " + assetId);
        }
        String commitInfo = normalize(body.getCommitInfo());
        if (commitInfo != null && commitInfo.length() > 1024) {
            return ApiResponse.fail("commitInfo length cannot exceed 1024");
        }

        ModelVersion version = new ModelVersion();
        version.setId("model-ver-" + UUID.randomUUID().toString().replace("-", ""));
        version.setAssetId(assetId);
        version.setVersion(versionName);
        version.setDescription(body.getDescription());
        version.setChangeLog(body.getChangeLog());
        version.setCommitInfo(commitInfo);
        version.setHyperParams(copyMap(body.getHyperParams()));
        version.setOwnerUserId(asset.getOwnerUserId());
        version.setCreatedBy(authContext.currentUserId());
        version.setCreatedAt(Instant.now());
        version.setStatus(STATUS_DRAFT);
        version.setDeleted(false);
        version.setDeletedAt(null);
        version.setCurrent(false);
        return ApiResponse.ok(repo.save(version));
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelVersion> get(@PathVariable String id) {
        Optional<ModelVersion> found = repo.findByIdAndDeletedFalse(id);
        if (found.isEmpty() || !authContext.canAccessOwner(effectiveOwner(found.get()))) {
            return ApiResponse.fail("not found or no permission: " + id);
        }
        return ApiResponse.ok(markCurrent(found.get()));
    }

    @GetMapping
    public ApiResponse<List<ModelVersion>> list(
            @RequestParam(value = "assetId", required = false) String assetId
    ) {
        List<ModelVersion> versions;
        if (assetId != null && !assetId.isBlank()) {
            ModelAsset asset = assetRepo.findByIdAndDeletedFalse(assetId).orElse(null);
            if (asset == null || !authContext.canAccessOwner(asset.getOwnerUserId())) {
                return ApiResponse.fail("not found or no permission: " + assetId);
            }
            versions = repo.findByAssetIdAndDeletedFalse(assetId);
            versions.forEach(version -> version.setCurrent(
                    Objects.equals(asset.getCurrentVersionId(), version.getId())
            ));
            return ApiResponse.ok(versions);
        }
        if (authContext.isAdmin()) {
            versions = repo.findByDeletedFalse();
        } else {
            Set<String> assetIds = assetRepo
                    .findByOwnerUserIdAndDeletedFalse(authContext.currentUserId())
                    .stream()
                    .map(ModelAsset::getId)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.toSet());
            versions = assetIds.isEmpty()
                    ? List.of()
                    : repo.findByAssetIdInAndDeletedFalse(assetIds);
        }
        versions.forEach(this::markCurrent);
        return ApiResponse.ok(versions);
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelVersion> update(
            @PathVariable String id,
            @RequestBody ModelVersionUpdateRequest body
    ) {
        if (body == null) {
            return ApiResponse.fail("request body cannot be empty");
        }
        Optional<ModelVersion> existing = repo.findByIdAndDeletedFalse(id);
        if (existing.isEmpty() || !authContext.canAccessOwner(effectiveOwner(existing.get()))) {
            return ApiResponse.fail("not found or no permission: " + id);
        }
        if (hasUpdateServerManagedFields(body)) {
            return ApiResponse.fail("asset, status and artifact fields cannot be modified");
        }
        ModelVersion version = existing.get();
        String targetVersion = normalize(body.getVersion());
        if (targetVersion == null) {
            return ApiResponse.fail("version cannot be empty");
        }
        if (repo.existsByAssetIdAndVersionAndIdNot(
                version.getAssetId(),
                targetVersion,
                id
        )) {
            return ApiResponse.fail(
                    "model version already exists for asset: " + version.getAssetId()
            );
        }
        String commitInfo = normalize(body.getCommitInfo());
        if (commitInfo != null && commitInfo.length() > 1024) {
            return ApiResponse.fail("commitInfo length cannot exceed 1024");
        }
        version.setVersion(targetVersion);
        version.setDescription(body.getDescription());
        version.setChangeLog(body.getChangeLog());
        version.setCommitInfo(commitInfo);
        if (body.getHyperParams() != null) {
            version.setHyperParams(copyMap(body.getHyperParams()));
        }
        return ApiResponse.ok(markCurrent(repo.save(version)));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ModelVersion> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body
    ) {
        try {
            return ApiResponse.ok(markCurrent(lifecycleService.retire(
                    id,
                    body == null ? null : body.get("status")
            )));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        try {
            return ApiResponse.ok(lifecycleService.deleteVersion(id));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    private ModelVersion markCurrent(ModelVersion version) {
        String currentVersionId = assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .map(ModelAsset::getCurrentVersionId)
                .orElse(null);
        version.setCurrent(Objects.equals(currentVersionId, version.getId()));
        return version;
    }

    private Integer effectiveOwner(ModelVersion version) {
        if (version.getOwnerUserId() != null) {
            return version.getOwnerUserId();
        }
        return assetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .map(ModelAsset::getOwnerUserId)
                .orElse(null);
    }

    private boolean hasCreateServerManagedFields(ModelVersionCreateRequest body) {
        return body.getId() != null
                || body.getStatus() != null
                || body.getFileName() != null
                || body.getStoragePath() != null
                || body.getSizeBytes() != null
                || body.getArtifactSha256() != null
                || body.getArtifactSpecId() != null
                || body.getPublishedAt() != null
                || body.getCreatedAt() != null
                || body.getCreatedBy() != null
                || body.getOwnerUserId() != null
                || body.getDeleted() != null
                || body.getDeletedAt() != null;
    }

    private boolean hasUpdateServerManagedFields(ModelVersionUpdateRequest body) {
        return body.getId() != null
                || body.getAssetId() != null
                || body.getStatus() != null
                || body.getFileName() != null
                || body.getStoragePath() != null
                || body.getSizeBytes() != null
                || body.getArtifactSha256() != null
                || body.getArtifactSpecId() != null
                || body.getPublishedAt() != null
                || body.getCreatedAt() != null
                || body.getCreatedBy() != null
                || body.getOwnerUserId() != null
                || body.getDeleted() != null
                || body.getDeletedAt() != null;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }
}
