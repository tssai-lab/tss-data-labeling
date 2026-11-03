package com.tss.platform.service;

import com.tss.platform.dto.PageResponse;
import com.tss.platform.entity.DatasetAsset;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.model.DatasetTaskType;
import com.tss.platform.repository.DatasetAssetRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import com.tss.platform.security.AuthContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DatasetCatalogQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final DatasetAssetRepository assetRepo;
    private final DatasetVersionRepository versionRepo;
    private final ImportJobRepository importJobRepo;
    private final DatasetVersionFileCountService fileCountService;
    private final AuthContext authContext;

    public DatasetCatalogQueryService(
            DatasetAssetRepository assetRepo,
            DatasetVersionRepository versionRepo,
            ImportJobRepository importJobRepo,
            DatasetVersionFileCountService fileCountService,
            AuthContext authContext
    ) {
        this.assetRepo = assetRepo;
        this.versionRepo = versionRepo;
        this.importJobRepo = importJobRepo;
        this.fileCountService = fileCountService;
        this.authContext = authContext;
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogItem> list(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize
    ) {
        return list(type, keyword, page, current, pageSize, false, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogItem> listTrainingCandidates(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize,
            List<String> artifactSpecIds
    ) {
        return list(type, keyword, page, current, pageSize, false, artifactSpecIds);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogItem> listAllWhenPageSizeAbsent(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize
    ) {
        return list(type, keyword, page, current, pageSize, true, null);
    }

    private PageResponse<CatalogItem> list(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize,
            boolean unpagedWhenPageSizeAbsent,
            List<String> artifactSpecIds
    ) {
        String normalizedType = type == null || type.isBlank()
                ? null
                : DatasetTaskType.normalize(type);
        String normalizedKeyword = normalizeKeyword(keyword);
        int pageNo = resolvePage(page, current);
        boolean unpaged = unpagedWhenPageSizeAbsent && (pageSize == null || pageSize <= 0);
        int size = unpaged ? 0 : resolvePageSize(pageSize);
        Pageable pageable = unpaged ? Pageable.unpaged() : PageRequest.of(pageNo - 1, size);
        Page<DatasetAsset> assetPage;
        if (artifactSpecIds != null) {
            List<String> normalizedSpecIds = artifactSpecIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(50)
                    .toList();
            assetPage = normalizedSpecIds.isEmpty()
                    ? Page.empty(pageable)
                    : assetRepo.searchTrainingCandidates(
                            authContext.isAdmin() ? null : authContext.currentUserId(),
                            normalizedType,
                            normalizedKeyword,
                            normalizedSpecIds,
                            pageable
                    );
        } else {
            assetPage = authContext.isAdmin()
                    ? assetRepo.searchCatalogForAdmin(normalizedType, normalizedKeyword, pageable)
                    : assetRepo.searchCatalogForOwner(
                            authContext.currentUserId(),
                            normalizedType,
                            normalizedKeyword,
                            pageable
                    );
        }

        PageResponse<CatalogItem> response = new PageResponse<>();
        response.setData(catalogItems(assetPage.getContent()));
        response.setTotal(assetPage.getTotalElements());
        response.setPage(pageNo);
        response.setPageSize(unpaged ? safeTotalAsPageSize(assetPage.getTotalElements()) : size);
        response.setTotalPages(unpaged
                ? (assetPage.getTotalElements() == 0 ? 0 : 1)
                : assetPage.getTotalPages());
        return response;
    }

    /** 详情按编号查同一套聚合信息，不依赖资产在列表中的位置。 */
    @Transactional(readOnly = true)
    public CatalogItem getByAssetId(String assetId) {
        DatasetAsset asset = assetRepo.findByIdAndDeletedFalse(assetId)
                .filter(value -> authContext.canAccessOwner(value.getOwnerUserId()))
                .orElseThrow(() -> new IllegalArgumentException("not found or no permission: " + assetId));
        return catalogItems(List.of(asset)).get(0);
    }

    private List<CatalogItem> catalogItems(List<DatasetAsset> assets) {
        Set<String> assetIds = assets.stream()
                .map(DatasetAsset::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        List<DatasetVersion> versions = assetIds.isEmpty()
                ? List.of()
                : versionRepo.findByAssetIdInAndDeletedFalse(assetIds);
        Map<String, List<DatasetVersion>> versionsByAsset = versions.stream()
                .collect(Collectors.groupingBy(DatasetVersion::getAssetId));
        Map<String, DatasetVersion> draftsByAsset = versions.stream()
                .filter(version -> "DRAFT".equals(version.getStatus()))
                .collect(Collectors.toMap(
                        DatasetVersion::getAssetId,
                        Function.identity(),
                        this::newerVersion
                ));
        Set<String> draftIds = draftsByAsset.values().stream()
                .map(DatasetVersion::getId)
                .collect(Collectors.toSet());
        Map<String, List<ImportJob>> jobsByVersion = importJobsByVersion(draftIds);

        return assets.stream()
                .map(asset -> {
                    List<DatasetVersion> assetVersions =
                            versionsByAsset.getOrDefault(asset.getId(), List.of());
                    DatasetVersion ready = currentReadyVersion(asset, assetVersions);
                    DatasetVersion draft = draftsByAsset.get(asset.getId());
                    List<ImportJob> draftJobs = draft == null
                            ? List.of()
                            : jobsByVersion.getOrDefault(draft.getId(), List.of());
                    ImportJob latestJob = latestImportJob(draftJobs);
                    Long fileCount = fileCountService.countCurrentVersionFiles(asset, ready);
                    return new CatalogItem(
                            asset,
                            assetVersions,
                            ready,
                            draft,
                            draftJobs,
                            latestJob,
                            fileCount
                    );
                })
                .toList();

    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private Map<String, List<ImportJob>> importJobsByVersion(
            Collection<String> draftIds
    ) {
        if (draftIds.isEmpty()) {
            return Map.of();
        }
        return importJobRepo.findByDatasetVersionIdIn(draftIds).stream()
                .collect(Collectors.groupingBy(ImportJob::getDatasetVersionId));
    }

    private ImportJob latestImportJob(List<ImportJob> jobs) {
        return jobs.stream()
                .reduce(this::newerImportJob)
                .orElse(null);
    }

    private DatasetVersion currentReadyVersion(
            DatasetAsset asset,
            List<DatasetVersion> versions
    ) {
        if (asset.getCurrentVersionId() != null) {
            DatasetVersion selected = versions.stream()
                    .filter(version -> asset.getCurrentVersionId().equals(version.getId()))
                    .filter(version -> "READY".equals(version.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (selected != null) {
                return selected;
            }
        }
        return versions.stream()
                .filter(version -> "READY".equals(version.getStatus()))
                .max(this::compareVersions)
                .orElse(null);
    }

    private DatasetVersion newerVersion(DatasetVersion left, DatasetVersion right) {
        return compareVersions(left, right) >= 0 ? left : right;
    }

    private int compareVersions(DatasetVersion left, DatasetVersion right) {
        return Comparator
                .comparing(
                        DatasetVersion::getVersionNo,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                )
                .thenComparing(
                        DatasetVersion::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                )
                .compare(left, right);
    }

    private ImportJob newerImportJob(ImportJob left, ImportJob right) {
        Comparator<ImportJob> comparator = Comparator
                .comparing(
                        ImportJob::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                )
                .thenComparing(
                        ImportJob::getId,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                );
        return comparator.compare(left, right) >= 0 ? left : right;
    }

    private int resolvePage(Integer page, Integer current) {
        if (current != null && current > 0) {
            return current;
        }
        return page != null && page > 0 ? page : 1;
    }

    private int resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private int safeTotalAsPageSize(long total) {
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public record CatalogItem(
            DatasetAsset asset,
            List<DatasetVersion> versions,
            DatasetVersion currentVersion,
            DatasetVersion latestDraft,
            List<ImportJob> latestDraftImportJobs,
            ImportJob latestDraftImportJob,
            Long currentVersionFileCount
    ) {
    }
}
