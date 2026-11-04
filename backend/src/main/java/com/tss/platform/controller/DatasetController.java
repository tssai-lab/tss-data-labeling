package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import com.tss.platform.service.DatasetCatalogQueryService;
import com.tss.platform.service.DatasetVersionFileCountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dataset")
public class DatasetController {

    private final DatasetCatalogQueryService catalogQueryService;

    @Autowired
    public DatasetController(DatasetCatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    public DatasetController(
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            ImportJobRepository importJobRepo,
            AuthContext authContext,
            DatasetVersionFileCountService fileCountService
    ) {
        this(new DatasetCatalogQueryService(
                assetRepo,
                versionRepo,
                importJobRepo,
                fileCountService,
                authContext
        ));
    }

    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "current", required = false) Integer current,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        PageResponse<DatasetCatalogQueryService.CatalogItem> catalog;
        try {
            catalog = catalogQueryService.listAllWhenPageSizeAbsent(type, keyword, page, current, pageSize);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
        List<Map<String, Object>> data = catalog.getData().stream()
                .map(this::toListItem)
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", catalog.getTotal());
        result.put("page", catalog.getPage());
        result.put("pageSize", catalog.getPageSize());
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable String id) {
        try {
            return ApiResponse.ok(toListItem(catalogQueryService.getByAssetId(id)));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    private Map<String, Object> toListItem(
            DatasetCatalogQueryService.CatalogItem catalogItem
    ) {
        DatasetAsset asset = catalogItem.asset();
        DatasetVersion version = catalogItem.currentVersion();
        DatasetVersion latestDraft = catalogItem.latestDraft();
        ImportJob importJob = catalogItem.latestDraftImportJob();

        Map<String, Object> item = new HashMap<>();
        item.put("id", asset.getId());
        item.put("assetId", asset.getId());
        item.put("name", asset.getName());
        item.put("type", asset.getType());
        item.put("cvTaskType", version != null && version.getCvTaskType() != null
                ? version.getCvTaskType()
                : asset.getCvTaskType());
        item.put("annotationFormat", version != null && version.getAnnotationFormat() != null
                ? version.getAnnotationFormat()
                : asset.getAnnotationFormat());
        item.put("remark", asset.getRemark());
        item.put("ownerUserId", asset.getOwnerUserId());
        item.put("isDemo", Boolean.TRUE.equals(asset.getIsDemo()));
        item.put("versionId", version != null ? version.getId() : null);
        item.put("version", version != null ? displayVersionLabel(version) : null);
        item.put("currentVersionId", version != null ? version.getId() : null);
        item.put("currentVersionNo", version != null ? version.getVersionNo() : null);
        item.put("currentVersionLabel", version != null ? displayVersionLabel(version) : null);
        item.put("versionStatus", version != null ? version.getStatus() : null);
        item.put("versionDescription", version != null ? version.getDescription() : null);
        item.put("fileName", version != null ? version.getFileName() : null);
        item.put("sizeBytes", version != null ? version.getSizeBytes() : null);
        item.put("size", formatBytes(version != null ? version.getSizeBytes() : null));
        item.put("versionRemark", version != null ? version.getRemark() : null);
        item.put("versionCount", catalogItem.versions().size());
        item.put("currentVersionFileCount", catalogItem.currentVersionFileCount());
        item.put("fileCount", catalogItem.currentVersionFileCount());
        item.put("uploadTime", version != null && version.getCreatedAt() != null
                ? version.getCreatedAt()
                : asset.getCreatedAt());
        item.put("createdAt", asset.getCreatedAt());
        item.put("updatedAt", asset.getUpdatedAt());
        item.put("latestDraftVersionId", latestDraft != null ? latestDraft.getId() : null);
        item.put("workspaceId", latestDraft != null ? latestDraft.getId() : null);
        item.put("workspaceRevision", latestDraft != null ? latestDraft.getWorkspaceRevision() : null);
        item.put("hasDraft", latestDraft != null);
        item.put("importJobId", importJob != null ? importJob.getId() : null);
        item.put("importStatus", importJob != null ? importJob.getStatus() : null);
        item.put("importProgress", importJob != null ? importJob.getProgress() : null);
        item.put("importErrorMessage", importJob != null ? importJob.getErrorMessage() : null);
        return item;
    }

    private String displayVersionLabel(DatasetVersion version) {
        if (version.getVersionLabel() != null && !version.getVersionLabel().isBlank()) {
            return version.getVersionLabel();
        }
        return version.getVersion();
    }

    private String formatBytes(Long bytes) {
        if (bytes == null) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit += 1;
        }
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[unit]);
    }
}
