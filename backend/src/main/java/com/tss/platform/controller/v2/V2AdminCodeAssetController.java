package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2AdminCodeAssetDto;
import com.tss.platform.dto.v2.V2AdminCodeAssetPage;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceOpenRequest;
import com.tss.platform.service.V2AdminCodeAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/code-assets")
public class V2AdminCodeAssetController {

    private final V2AdminCodeAssetService service;

    public V2AdminCodeAssetController(V2AdminCodeAssetService service) {
        this.service = service;
    }

    @GetMapping
    public V2AdminCodeAssetPage list(
            @RequestParam(required = false) Integer ownerUserId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String trainingProfile,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "UPDATED_AT") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection
    ) {
        return service.list(
                ownerUserId,
                keyword,
                trainingProfile,
                page,
                pageSize,
                sortBy,
                sortDirection
        );
    }

    @GetMapping("/{assetId}")
    public V2AdminCodeAssetDto get(@PathVariable String assetId) {
        return service.get(assetId);
    }

    @PatchMapping("/{assetId}")
    public V2AdminCodeAssetDto patch(
            @PathVariable String assetId,
            @RequestBody(required = false) V2CodeAssetPatchRequest request
    ) {
        return service.patch(assetId, request);
    }

    @DeleteMapping("/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String assetId,
            @RequestParam long expectedAssetRevision
    ) {
        service.delete(assetId, expectedAssetRevision);
    }

    @GetMapping("/{assetId}/workspaces")
    public List<V2CodeWorkspaceDto> workspaces(@PathVariable String assetId) {
        return service.openWorkspaces(assetId);
    }

    @PostMapping("/{assetId}/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public V2CodeWorkspaceDto openWorkspace(
            @PathVariable String assetId,
            @RequestBody(required = false) V2CodeWorkspaceOpenRequest request
    ) {
        return service.openWorkspace(assetId, request);
    }
}
