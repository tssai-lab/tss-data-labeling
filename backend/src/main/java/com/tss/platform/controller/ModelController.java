package com.tss.platform.controller;

import com.tss.platform.module1.common.AuditObjectType;
import com.tss.platform.module1.service.AuditHooks;
import org.springframework.beans.factory.annotation.Autowired;
import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.ModelCodeFileDto;
import com.tss.platform.dto.ModelCodePreviewDto;
import com.tss.platform.entity.ModelAsset;
import com.tss.platform.entity.ModelVersion;
import com.tss.platform.repository.ModelAssetRepository;
import com.tss.platform.repository.ModelVersionRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.ModelCodePreviewService;
import com.tss.platform.service.ModelVersionLifecycleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/model")
public class ModelController {
    @Autowired
    private AuditHooks auditHooks;


    private final ModelAssetRepository modelAssetRepo;
    private final ModelVersionRepository modelVersionRepo;
    private final ModelCodePreviewService codePreviewService;
    private final AuthContext authContext;
    private final ModelVersionLifecycleService lifecycleService;

    public ModelController(ModelAssetRepository modelAssetRepo,
                           ModelVersionRepository modelVersionRepo,
                           ModelCodePreviewService codePreviewService,
                           AuthContext authContext,
                           ModelVersionLifecycleService lifecycleService) {
        this.modelAssetRepo = modelAssetRepo;
        this.modelVersionRepo = modelVersionRepo;
        this.codePreviewService = codePreviewService;
        this.authContext = authContext;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "current", required = false) Integer current,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "artifactSpecIds", required = false) List<String> artifactSpecIds
    ) {
        String normalizedType = null;
        if (type != null && !type.isBlank()) {
            try {
                normalizedType = com.tss.platform.model.TaskType.normalize(type);
            } catch (IllegalArgumentException e) {
                return ApiResponse.fail(e.getMessage());
            }
        }

        int pageNo = resolvePage(page, current);
        int size = resolvePageSize(pageSize, 0);
        Pageable pageable = size > 0
                ? PageRequest.of(pageNo - 1, size)
                : Pageable.unpaged();
        Integer ownerUserId = authContext.isAdmin() ? null : authContext.currentUserId();
        List<String> normalizedSpecIds = normalizeSpecIds(artifactSpecIds);
        Page<ModelVersion> versionPage;
        if (normalizedSpecIds == null) {
            versionPage = modelVersionRepo.searchVisibleCatalog(
                    ownerUserId,
                    normalizedType,
                    toLikeKeyword(keyword),
                    pageable
            );
        } else if (normalizedSpecIds.isEmpty()) {
            versionPage = Page.empty(pageable);
        } else {
            versionPage = modelVersionRepo.searchVisibleTrainingCandidates(
                    ownerUserId,
                    normalizedType,
                    toLikeKeyword(keyword),
                    normalizedSpecIds,
                    pageable
            );
        }
        List<ModelVersion> versions = versionPage.getContent();
        Set<String> visibleAssetIds = versions.stream()
                .map(ModelVersion::getAssetId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, ModelAsset> assetsById = visibleAssetIds.isEmpty()
                ? Map.of()
                : modelAssetRepo.findAllById(visibleAssetIds).stream()
                .collect(Collectors.toMap(ModelAsset::getId, asset -> asset));

        List<Map<String, Object>> data = versions.stream()
                .map(v -> toListItem(v, assetsById.get(v.getAssetId())))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", versionPage.getTotalElements());
        result.put("page", pageNo);
        result.put("pageSize", size > 0 ? size : data.size());
        return ApiResponse.ok(result);
    }

    /** Keeps direct Java callers source-compatible while the HTTP route gains an optional filter. */
    public ApiResponse<Map<String, Object>> list(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize
    ) {
        return list(type, keyword, page, current, pageSize, null);
    }

    private Map<String, Object> toListItem(ModelVersion version, ModelAsset asset) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", version.getId());
        item.put("assetId", version.getAssetId());
        item.put("name", asset != null ? asset.getName() : null);
        item.put("version", version.getVersion());
        item.put("type", asset != null ? asset.getType() : null);
        item.put("remark", asset != null ? asset.getRemark() : null);
        item.put("ownerUserId", version.getOwnerUserId());
        item.put("isDemo", asset != null && Boolean.TRUE.equals(asset.getIsDemo()));
        item.put("fileName", version.getFileName());
        item.put("sizeBytes", version.getSizeBytes());
        item.put("artifactSha256", version.getArtifactSha256());
        item.put("artifactSpecId", version.getArtifactSpecId());
        item.put("commitInfo", version.getCommitInfo());
        item.put("hyperParams", version.getHyperParams());
        item.put("isCurrent", asset != null
                && version.getId().equals(asset.getCurrentVersionId()));
        item.put("description", version.getDescription());
        item.put("changeLog", version.getChangeLog());
        item.put("status", version.getStatus());
        item.put("publishedAt", version.getPublishedAt());
        item.put("createdBy", version.getCreatedBy());
        item.put("createdAt", version.getCreatedAt());
        return item;
    }

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestParam String id) {
        Optional<ModelVersion> v = modelVersionRepo.findByIdAndDeletedFalse(id);
        if (v.isEmpty()) {
            return ApiResponse.fail("model not found");
        }
        ModelVersion ver = v.get();
        if (!authContext.canAccessOwner(effectiveOwner(ver))) {
            return ApiResponse.fail("model not found or no permission");
        }
        Optional<ModelAsset> a = modelAssetRepo.findByIdAndDeletedFalse(ver.getAssetId());
        Map<String, Object> item = new HashMap<>();
        item.put("id", ver.getId());
        item.put("assetId", ver.getAssetId());
        item.put("name", a.map(ModelAsset::getName).orElse(null));
        item.put("version", ver.getVersion());
        item.put("type", a.map(ModelAsset::getType).orElse(null));
        item.put("remark", a.map(ModelAsset::getRemark).orElse(null));
        item.put("ownerUserId", ver.getOwnerUserId());
        item.put("isDemo", a.map(ModelAsset::getIsDemo).orElse(false));
        item.put("fileName", ver.getFileName());
        item.put("sizeBytes", ver.getSizeBytes());
        item.put("artifactSha256", ver.getArtifactSha256());
        item.put("artifactSpecId", ver.getArtifactSpecId());
        item.put("commitInfo", ver.getCommitInfo());
        item.put("hyperParams", ver.getHyperParams());
        item.put("isCurrent", a.map(asset -> ver.getId().equals(asset.getCurrentVersionId()))
                .orElse(false));
        item.put("description", ver.getDescription());
        item.put("changeLog", ver.getChangeLog());
        item.put("status", ver.getStatus());
        item.put("publishedAt", ver.getPublishedAt());
        item.put("createdBy", ver.getCreatedBy());
        item.put("createdAt", ver.getCreatedAt());
        return ApiResponse.ok(item);
    }

    @GetMapping("/code-files")
    public ApiResponse<List<ModelCodeFileDto>> codeFiles(@RequestParam String id) {
        try {
            return ApiResponse.ok(codePreviewService.listCodeFiles(id));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/previewCode")
    public ApiResponse<ModelCodePreviewDto> previewCode(
            @RequestParam String id,
            @RequestParam String path
    ) {
        try {
            return ApiResponse.ok(codePreviewService.previewCode(id, path));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestParam String id) {
        try {
            var __auditData = lifecycleService.deleteVersion(id);
            auditHooks.delete(AuditObjectType.MODEL, id, "MODEL_DELETE", true, null);
            return ApiResponse.ok(__auditData);
        } catch (IllegalArgumentException exception) {
            auditHooks.delete(AuditObjectType.MODEL, id, "MODEL_DELETE", false, exception.getMessage());
            return ApiResponse.fail(exception.getMessage());
        }
    }

    private Integer effectiveOwner(ModelVersion version) {
        if (version.getOwnerUserId() != null) {
            return version.getOwnerUserId();
        }
        return modelAssetRepo.findByIdAndDeletedFalse(version.getAssetId())
                .map(ModelAsset::getOwnerUserId)
                .orElse(null);
    }

    private String toLikeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private int resolvePage(Integer page, Integer current) {
        if (current != null && current > 0) {
            return current;
        }
        return page != null && page > 0 ? page : 1;
    }

    private int resolvePageSize(Integer pageSize, int total) {
        return pageSize != null && pageSize > 0 ? pageSize : total;
    }

    private List<String> normalizeSpecIds(List<String> artifactSpecIds) {
        if (artifactSpecIds == null) {
            return null;
        }
        return artifactSpecIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(50)
                .toList();
    }
}
