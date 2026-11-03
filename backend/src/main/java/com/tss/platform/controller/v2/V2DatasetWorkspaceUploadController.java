package com.tss.platform.controller.v2;

import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.v2.V2DatasetUploadDto;
import com.tss.platform.dto.v2.V2DatasetWorkspaceFileUploadInitRequest;
import com.tss.platform.service.V2DatasetUploadService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/dataset-workspaces/{workspaceId}")
public class V2DatasetWorkspaceUploadController {

    private final V2DatasetUploadService service;

    public V2DatasetWorkspaceUploadController(V2DatasetUploadService service) {
        this.service = service;
    }

    @PostMapping("/file-uploads")
    public V2DatasetUploadDto initFileUpload(
            @PathVariable String workspaceId,
            @RequestBody V2DatasetWorkspaceFileUploadInitRequest request
    ) {
        return service.initWorkspaceFile(workspaceId, request);
    }

    @PostMapping("/package-uploads")
    public V2DatasetUploadDto initPackageUpload(
            @PathVariable String workspaceId,
            @RequestBody DatasetPackageAppendInitRequest request
    ) {
        return service.initAppend(workspaceId, request);
    }
}
