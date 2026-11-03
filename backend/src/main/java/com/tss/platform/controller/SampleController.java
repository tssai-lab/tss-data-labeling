package com.tss.platform.controller;

import com.tss.platform.dto.ApiResponse;
import com.tss.platform.dto.DatasetMultimodalExternalIdSampleDto;
import com.tss.platform.dto.DatasetSampleDataDto;
import com.tss.platform.dto.DatasetSampleDetailDto;
import com.tss.platform.dto.DatasetSampleListItemDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.SampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SampleController {

    private final SampleService service;

    public SampleController(SampleService service) {
        this.service = service;
    }

    @GetMapping("/api/dataset-versions/{versionId}/samples")
    public ApiResponse<PageResponse<DatasetSampleListItemDto>> listSamples(
            @PathVariable String versionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize
    ) {
        try {
            return ApiResponse.ok(service.listSamples(versionId, page, pageSize));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/api/dataset-samples/multimodal")
    public ApiResponse<PageResponse<DatasetMultimodalExternalIdSampleDto>>
            findMultimodalByExternalId(
                    @RequestParam String externalId,
                    @RequestParam List<String> datasetVersionIds,
                    @RequestParam(value = "page", required = false) Integer page,
                    @RequestParam(value = "pageSize", required = false) Integer pageSize
            ) {
        try {
            return ApiResponse.ok(service.findMultimodalByExternalId(
                    externalId,
                    datasetVersionIds,
                    page,
                    pageSize
            ));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/api/dataset-samples/{sampleId}")
    public ApiResponse<DatasetSampleDetailDto> getSample(@PathVariable String sampleId) {
        try {
            return ApiResponse.ok(service.getSample(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/api/dataset-samples/{sampleId}/data")
    public ApiResponse<List<DatasetSampleDataDto>> listSampleData(
            @PathVariable String sampleId
    ) {
        try {
            return ApiResponse.ok(service.listSampleData(sampleId));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }
}
