package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetWorkspaceDraftDto;
import com.tss.platform.service.DatasetWorkspaceService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetWorkspaceController {

    private final DatasetWorkspaceService service;

    public DatasetWorkspaceController(DatasetWorkspaceService service) {
        this.service = service;
    }

    @PostMapping("/api/dataset-versions/{readyVersionId}/draft")
    public ApiResponse<DatasetWorkspaceDraftDto> createDraft(
            @PathVariable String readyVersionId
    ) {
        try {
            return ApiResponse.ok(service.createDraft(readyVersionId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}

