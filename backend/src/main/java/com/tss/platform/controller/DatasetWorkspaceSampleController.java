package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetWorkspaceSampleListItemDto;
import com.tss.platform.dto.DatasetWorkspaceSampleMutationDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.DatasetWorkspaceSampleMutationService;
import com.tss.platform.service.DatasetWorkspaceSampleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Deprecated(forRemoval = false)
public class DatasetWorkspaceSampleController {

    private final DatasetWorkspaceSampleService service;
    private final DatasetWorkspaceSampleMutationService mutationService;

    public DatasetWorkspaceSampleController(
            DatasetWorkspaceSampleService service,
            DatasetWorkspaceSampleMutationService mutationService
    ) {
        this.service = service;
        this.mutationService = mutationService;
    }

    @GetMapping("/api/dataset-versions/{draftVersionId}/workspace/samples")
    public ApiResponse<PageResponse<DatasetWorkspaceSampleListItemDto>> listSamples(
            @PathVariable String draftVersionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "includeDeleted", required = false)
            Boolean includeDeleted
    ) {
        try {
            return ApiResponse.ok(service.listSamples(
                    draftVersionId,
                    page,
                    pageSize,
                    includeDeleted
            ));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/api/dataset-samples/{sampleId}/workspace")
    public ApiResponse<DatasetSampleDetailDto> getSample(@PathVariable String sampleId) {
        try {
            return ApiResponse.ok(service.getSample(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/api/dataset-samples/{sampleId}/workspace/data")
    public ApiResponse<List<DatasetSampleDataDto>> listSampleData(
            @PathVariable String sampleId
    ) {
        try {
            return ApiResponse.ok(service.listSampleData(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @DeleteMapping("/api/dataset-samples/{sampleId}/workspace")
    public ApiResponse<DatasetWorkspaceSampleMutationDto> deleteSample(
            @PathVariable String sampleId
    ) {
        try {
            return ApiResponse.ok(mutationService.deleteSample(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/api/dataset-samples/{sampleId}/workspace/restore")
    public ApiResponse<DatasetWorkspaceSampleMutationDto> restoreSample(
            @PathVariable String sampleId
    ) {
        try {
            return ApiResponse.ok(mutationService.restoreSample(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
