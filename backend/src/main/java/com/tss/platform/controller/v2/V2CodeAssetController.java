package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2CodeAssetCreateRequest;
import com.tss.platform.dto.v2.V2CodeAssetDto;
import com.tss.platform.dto.v2.V2CodeAssetPatchRequest;
import com.tss.platform.dto.v2.V2CodeWorkspaceDto;
import com.tss.platform.dto.v2.V2CodeWorkspaceOpenRequest;
import com.tss.platform.service.V2CodeAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v2/code-assets")
public class V2CodeAssetController {

    private final V2CodeAssetService service;

    public V2CodeAssetController(V2CodeAssetService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public V2CodeAssetDto create(@RequestBody V2CodeAssetCreateRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<V2CodeAssetDto> list() {
        return service.list();
    }

    @GetMapping("/{assetId}")
    public V2CodeAssetDto get(@PathVariable String assetId) {
        return service.get(assetId);
    }

    @PatchMapping("/{assetId}")
    public V2CodeAssetDto patch(
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
