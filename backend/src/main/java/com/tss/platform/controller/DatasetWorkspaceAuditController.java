package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetWorkspaceAuditLogDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.DatasetWorkspaceAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatasetWorkspaceAuditController {

    private final DatasetWorkspaceAuditService service;

    public DatasetWorkspaceAuditController(DatasetWorkspaceAuditService service) {
        this.service = service;
    }

    @GetMapping("/api/dataset-versions/{datasetVersionId}/workspace/audit-logs")
    public ApiResponse<PageResponse<DatasetWorkspaceAuditLogDto>> list(
            @PathVariable String datasetVersionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        try {
            return ApiResponse.ok(service.listByVersion(datasetVersionId, page, pageSize));
        } catch (DatasetWorkspaceAuditService.DatasetWorkspaceAuditAccessException
                | IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
