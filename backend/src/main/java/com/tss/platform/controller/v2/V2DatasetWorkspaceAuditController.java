package com.tss.platform.controller.v2;

import com.tss.platform.dto.DatasetWorkspaceAuditLogDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.DatasetWorkspaceAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class V2DatasetWorkspaceAuditController {

    private final DatasetWorkspaceAuditService service;

    public V2DatasetWorkspaceAuditController(DatasetWorkspaceAuditService service) {
        this.service = service;
    }

    @GetMapping("/dataset-versions/{datasetVersionId}/workspace/audit-logs")
    public PageResponse<DatasetWorkspaceAuditLogDto> list(
            @PathVariable String datasetVersionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        return service.listByVersion(datasetVersionId, page, pageSize);
    }
}
