package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.service.DatasetUploadService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DatasetVersionPackageUploadController {

    private final DatasetUploadService uploadService;

    public DatasetVersionPackageUploadController(DatasetUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/api/dataset-versions/{draftVersionId}/packages/init")
    public ApiResponse<DatasetUploadProgressDto> init(
            @PathVariable String draftVersionId,
            @RequestBody DatasetPackageAppendInitRequest request
    ) {
        try {
            return ApiResponse.ok(uploadService.initAppendPackage(draftVersionId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/api/dataset-versions/{draftVersionId}/packages/complete")
    public ApiResponse<Map<String, Object>> complete(
            @PathVariable String draftVersionId,
            @RequestBody DatasetUploadCompleteRequest request
    ) {
        try {
            return ApiResponse.ok(uploadService.completeAppendPackage(draftVersionId, request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
