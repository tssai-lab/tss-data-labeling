package com.tss.platform.controller.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetAnnotationResource;
import com.tss.platform.dto.v2.V2DatasetContentUpdateRequest;
import com.tss.platform.dto.v2.V2DatasetDataResource;
import com.tss.platform.dto.v2.V2DatasetInlineAnnotationCreateRequest;
import com.tss.platform.dto.v2.V2DatasetInlineDataCreateRequest;
import com.tss.platform.dto.v2.V2DatasetMutationResult;
import com.tss.platform.dto.v2.V2DatasetSampleCreateRequest;
import com.tss.platform.dto.v2.V2DatasetSampleDetail;
import com.tss.platform.dto.v2.V2DatasetSampleListItem;
import com.tss.platform.dto.v2.V2DatasetWorkspaceMutationRequest;
import com.tss.platform.service.V2DatasetWorkspaceResourceService;
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

@RestController
@RequestMapping("/api/v2/dataset-workspaces/{workspaceId}/samples")
public class V2DatasetWorkspaceResourceController {

    private final V2DatasetWorkspaceResourceService service;

    public V2DatasetWorkspaceResourceController(
            V2DatasetWorkspaceResourceService service
    ) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<V2DatasetSampleListItem> listSamples(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return service.listSamples(
                workspaceId,
                page,
                pageSize,
                includeDeleted
        );
    }

    @PostMapping
    public V2DatasetMutationResult<V2DatasetSampleDetail> createSample(
            @PathVariable String workspaceId,
            @RequestBody V2DatasetSampleCreateRequest request
    ) {
        return service.createSample(workspaceId, request);
    }

    @GetMapping("/{sampleId}")
    public V2DatasetSampleDetail getSample(
            @PathVariable String workspaceId,
            @PathVariable String sampleId
    ) {
        return service.getSample(workspaceId, sampleId);
    }

    @PatchMapping(
            value = "/{sampleId}",
            consumes = "application/merge-patch+json"
    )
    public V2DatasetMutationResult<V2DatasetSampleDetail> patchSample(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @RequestBody JsonNode patch
    ) {
        return service.patchSample(workspaceId, sampleId, patch);
    }

    @DeleteMapping("/{sampleId}")
    public V2DatasetMutationResult<V2DatasetSampleDetail> deleteSample(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.deleteSample(workspaceId, sampleId, request);
    }

    @PostMapping("/{sampleId}/restore")
    public V2DatasetMutationResult<V2DatasetSampleDetail> restoreSample(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.restoreSample(workspaceId, sampleId, request);
    }

    @PostMapping("/{sampleId}/data")
    public V2DatasetMutationResult<V2DatasetDataResource> createData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @RequestBody V2DatasetInlineDataCreateRequest request
    ) {
        return service.createInlineData(workspaceId, sampleId, request);
    }

    @GetMapping("/{sampleId}/data/{dataId}")
    public V2DatasetDataResource getData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId
    ) {
        return service.getData(workspaceId, sampleId, dataId);
    }

    @PatchMapping(
            value = "/{sampleId}/data/{dataId}",
            consumes = "application/merge-patch+json"
    )
    public V2DatasetMutationResult<V2DatasetDataResource> patchData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId,
            @RequestBody JsonNode patch
    ) {
        return service.patchData(workspaceId, sampleId, dataId, patch);
    }

    @DeleteMapping("/{sampleId}/data/{dataId}")
    public V2DatasetMutationResult<V2DatasetDataResource> deleteData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.deleteData(workspaceId, sampleId, dataId, request);
    }

    @PutMapping("/{sampleId}/data/{dataId}/content")
    public V2DatasetMutationResult<V2DatasetDataResource> replaceDataContent(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId,
            @RequestBody V2DatasetContentUpdateRequest request
    ) {
        return service.replaceDataContent(
                workspaceId,
                sampleId,
                dataId,
                request
        );
    }

    @PostMapping("/{sampleId}/data/{dataId}/restore")
    public V2DatasetMutationResult<V2DatasetDataResource> restoreData(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String dataId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.restoreData(workspaceId, sampleId, dataId, request);
    }

    @PostMapping("/{sampleId}/annotations")
    public V2DatasetMutationResult<V2DatasetAnnotationResource> createAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @RequestBody V2DatasetInlineAnnotationCreateRequest request
    ) {
        return service.createInlineAnnotation(workspaceId, sampleId, request);
    }

    @GetMapping("/{sampleId}/annotations/{annotationId}")
    public V2DatasetAnnotationResource getAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId
    ) {
        return service.getAnnotation(workspaceId, sampleId, annotationId);
    }

    @PatchMapping(
            value = "/{sampleId}/annotations/{annotationId}",
            consumes = "application/merge-patch+json"
    )
    public V2DatasetMutationResult<V2DatasetAnnotationResource> patchAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId,
            @RequestBody JsonNode patch
    ) {
        return service.patchAnnotation(
                workspaceId,
                sampleId,
                annotationId,
                patch
        );
    }

    @DeleteMapping("/{sampleId}/annotations/{annotationId}")
    public V2DatasetMutationResult<V2DatasetAnnotationResource> deleteAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.deleteAnnotation(
                workspaceId,
                sampleId,
                annotationId,
                request
        );
    }

    @PutMapping("/{sampleId}/annotations/{annotationId}/content")
    public V2DatasetMutationResult<V2DatasetAnnotationResource>
    replaceAnnotationContent(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId,
            @RequestBody V2DatasetContentUpdateRequest request
    ) {
        return service.replaceAnnotationContent(
                workspaceId,
                sampleId,
                annotationId,
                request
        );
    }

    @PostMapping("/{sampleId}/annotations/{annotationId}/restore")
    public V2DatasetMutationResult<V2DatasetAnnotationResource> restoreAnnotation(
            @PathVariable String workspaceId,
            @PathVariable String sampleId,
            @PathVariable String annotationId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.restoreAnnotation(
                workspaceId,
                sampleId,
                annotationId,
                request
        );
    }
}
