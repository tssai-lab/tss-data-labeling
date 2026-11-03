package com.tss.platform.controller.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.platform.dto.v2.V2DatasetPublishReadiness;
import com.tss.platform.dto.v2.V2DatasetPublishResult;
import com.tss.platform.dto.v2.V2DatasetVersionAllocationDto;
import com.tss.platform.dto.v2.V2DatasetWorkspaceAbandonResult;
import com.tss.platform.dto.v2.V2DatasetWorkspaceCreateRequest;
import com.tss.platform.dto.v2.V2DatasetWorkspaceDto;
import com.tss.platform.dto.v2.V2DatasetWorkspaceMutationRequest;
import com.tss.platform.service.V2DatasetWorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class V2DatasetWorkspaceController {

    private final V2DatasetWorkspaceService service;

    public V2DatasetWorkspaceController(V2DatasetWorkspaceService service) {
        this.service = service;
    }

    @PostMapping("/datasets/{datasetId}/workspaces")
    public V2DatasetWorkspaceDto create(
            @PathVariable String datasetId,
            @RequestBody(required = false)
            V2DatasetWorkspaceCreateRequest request
    ) {
        return service.create(datasetId, request);
    }

    @GetMapping("/datasets/{datasetId}/version-allocation")
    public V2DatasetVersionAllocationDto versionAllocation(
            @PathVariable String datasetId,
            @RequestParam(required = false) String versionLabel
    ) {
        return service.versionAllocation(datasetId, versionLabel);
    }

    @GetMapping("/dataset-workspaces/{workspaceId}")
    public V2DatasetWorkspaceDto get(@PathVariable String workspaceId) {
        return service.get(workspaceId);
    }

    @PatchMapping(
            value = "/dataset-workspaces/{workspaceId}",
            consumes = "application/merge-patch+json"
    )
    public V2DatasetWorkspaceDto patch(
            @PathVariable String workspaceId,
            @RequestBody JsonNode patch
    ) {
        return service.patch(workspaceId, patch);
    }

    @DeleteMapping("/dataset-workspaces/{workspaceId}")
    public V2DatasetWorkspaceAbandonResult abandon(
            @PathVariable String workspaceId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.abandon(
                workspaceId,
                request == null ? null : request.expectedWorkspaceRevision()
        );
    }

    @GetMapping("/dataset-workspaces/{workspaceId}/readiness")
    public V2DatasetPublishReadiness readiness(
            @PathVariable String workspaceId
    ) {
        return service.readiness(workspaceId);
    }

    @PostMapping("/dataset-workspaces/{workspaceId}/publish")
    public V2DatasetPublishResult publish(
            @PathVariable String workspaceId,
            @RequestBody V2DatasetWorkspaceMutationRequest request
    ) {
        return service.publish(
                workspaceId,
                request == null ? null : request.expectedWorkspaceRevision()
        );
    }
}
