package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetWorkspacePublishDto;
import com.tss.platform.service.DatasetWorkspacePublishService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetWorkspacePublishController {

    private final DatasetWorkspacePublishService service;

    public DatasetWorkspacePublishController(DatasetWorkspacePublishService service) {
        this.service = service;
    }

    @PostMapping("/api/dataset-versions/{draftVersionId}/publish")
    public ApiResponse<DatasetWorkspacePublishDto> publish(
            @PathVariable String draftVersionId
    ) {
        try {
            return ApiResponse.ok(service.publish(draftVersionId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
