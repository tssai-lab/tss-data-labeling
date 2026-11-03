package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetEditability;
import com.tss.platform.dto.v2.V2DatasetListItem;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.dto.v2.V2DatasetVersionSummary;
import com.tss.platform.dto.v2.V2UserError;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class V2DatasetCatalogService {

    private final DatasetCatalogQueryService catalogQueryService;
    private final ObjectMapper objectMapper;
    private final DatasetWorkspaceReadinessService readinessService;
    private final DatasetWorkspaceSourceInspector sourceInspector;

    public V2DatasetCatalogService(
            DatasetCatalogQueryService catalogQueryService,
            ObjectMapper objectMapper,
            DatasetWorkspaceReadinessService readinessService,
            DatasetWorkspaceSourceInspector sourceInspector
    ) {
        this.catalogQueryService = catalogQueryService;
        this.objectMapper = objectMapper;
        this.readinessService = readinessService;
        this.sourceInspector = sourceInspector;
    }

    @Transactional(readOnly = true)
    public PageResponse<V2DatasetListItem> list(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize
    ) {
        return list(type, keyword, page, current, pageSize, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<V2DatasetListItem> list(
            String type,
            String keyword,
            Integer page,
            Integer current,
            Integer pageSize,
            List<String> artifactSpecIds
    ) {
        PageResponse<DatasetCatalogQueryService.CatalogItem> catalog =
                artifactSpecIds == null
                        ? catalogQueryService.list(type, keyword, page, current, pageSize)
                        : catalogQueryService.listTrainingCandidates(
                                type,
                                keyword,
                                page,
                                current,
                                pageSize,
                                artifactSpecIds
                        );
        PageResponse<V2DatasetListItem> response = new PageResponse<>();
        response.setData(catalog.getData().stream()
                .map(this::toItem)
                .toList());
        response.setTotal(catalog.getTotal());
        response.setPage(catalog.getPage());
        response.setPageSize(catalog.getPageSize());
        response.setTotalPages(catalog.getTotalPages());
        return response;
    }

    private V2DatasetListItem toItem(
            DatasetCatalogQueryService.CatalogItem catalogItem
    ) {
        DatasetVersion ready = catalogItem.currentVersion();
        DatasetVersion draft = catalogItem.latestDraft();
        ImportJob statusJob =
                V2ImportJobStatusSelector.statusJobOf(catalogItem.latestDraftImportJobs());
        String displayStatus = displayStatus(ready, draft, statusJob);
        V2DatasetPublishReadiness publishReadiness = draft == null
                ? null
                : readiness(catalogItem, draft);
        boolean canPublish = publishReadiness != null
                && publishReadiness.canPublish();

        List<String> actions = new ArrayList<>();
        actions.add("VIEW");
        if (ready != null) {
            actions.add("PREVIEW");
        }
        if (draft != null) {
            actions.add("OPEN_WORKSPACE");
        } else if (ready != null) {
            actions.add("CREATE_WORKSPACE");
        }
        if (draft != null) {
            actions.add("ADD_DATA");
        }
        if (canPublish) {
            actions.add("PUBLISH");
        }

        V2DatasetListItem item = new V2DatasetListItem();
        item.setDatasetId(catalogItem.asset().getId());
        item.setName(catalogItem.asset().getName());
        item.setType(catalogItem.asset().getType());
        item.setCurrentVersion(ready == null ? null : new V2DatasetVersionSummary(
                ready.getId(),
                displayVersion(ready),
                ready.getVersionNo(),
                ready.getStatus()
        ));
        item.setCurrentVersionFileCount(catalogItem.currentVersionFileCount());
        item.setFileCount(catalogItem.currentVersionFileCount());
        item.setArtifactSpecId(ready == null ? null : ready.getArtifactSpecId());
        item.setDisplayStatus(displayStatus);
        item.setHasDraft(draft != null);
        item.setWorkspaceId(draft == null ? null : draft.getId());
        item.setWorkspaceRevision(draft == null
                ? null
                : workspaceRevision(draft));
        item.setPublishReadiness(publishReadiness);
        item.setEditability(editability(catalogItem, ready));
        item.setImportProgress(statusJob == null ? null : statusJob.getProgress());
        item.setAvailableActions(List.copyOf(actions));
        item.setUserError(V2ImportJobDisplayHelper.userError(
                statusJob,
                objectMapper
        ));
        item.setIsDemo(catalogItem.asset().getIsDemo());
        return item;
    }

    private V2DatasetPublishReadiness readiness(
            DatasetCatalogQueryService.CatalogItem catalogItem,
            DatasetVersion draft
    ) {
        return readinessService.evaluateCatalog(catalogItem.asset(), draft);
    }

    private V2DatasetEditability editability(
            DatasetCatalogQueryService.CatalogItem catalogItem,
            DatasetVersion ready
    ) {
        return sourceInspector.inspect(catalogItem.asset(), ready);
    }

    private static long workspaceRevision(DatasetVersion version) {
        return version.getWorkspaceRevision() == null
                ? 0L
                : version.getWorkspaceRevision();
    }

    private String displayStatus(
            DatasetVersion ready,
            DatasetVersion draft,
            ImportJob importJob
    ) {
        if (importJob != null && "PARTIAL".equals(importJob.getStatus())) {
            return "IMPORT_PARTIAL";
        }
        if (importJob != null && "FAILED".equals(importJob.getStatus())) {
            return "IMPORT_FAILED";
        }
        if (importJob != null
                && V2ImportJobStatusSelector.IMPORTING_STATUSES.contains(importJob.getStatus())) {
            return "IMPORTING";
        }
        if (draft != null) {
            return "EDITING";
        }
        return ready != null ? "READY" : "EMPTY";
    }

    private String displayVersion(DatasetVersion version) {
        return version.getVersionLabel() != null && !version.getVersionLabel().isBlank()
                ? version.getVersionLabel()
                : version.getVersion();
    }
}
