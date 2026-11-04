package com.tss.platform.controller.v2;

import com.tss.platform.dto.DatasetMultimodalExternalIdAnnotationDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdDataDto;
import com.tss.platform.dto.DatasetMultimodalExternalIdSampleDto;
import com.tss.platform.dto.PageResponse;
import com.tss.platform.service.SampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/dataset-samples")
public class V2DatasetSampleController {

    private final SampleService service;

    public V2DatasetSampleController(SampleService service) {
        this.service = service;
    }

    @GetMapping("/multimodal")
    public PageResponse<DatasetMultimodalExternalIdSampleDto> findMultimodalByExternalId(
            @RequestParam String externalId,
            @RequestParam List<String> datasetVersionIds,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        PageResponse<DatasetMultimodalExternalIdSampleDto> response =
                service.findMultimodalByExternalId(
                        externalId,
                        datasetVersionIds,
                        page,
                        pageSize
                );
        applyV2FileLinks(response);
        return response;
    }

    private static void applyV2FileLinks(
            PageResponse<DatasetMultimodalExternalIdSampleDto> response
    ) {
        if (response == null || response.getData() == null) {
            return;
        }
        for (DatasetMultimodalExternalIdSampleDto sample : response.getData()) {
            if (sample == null) {
                continue;
            }
            if (sample.getData() != null) {
                for (DatasetMultimodalExternalIdDataDto data : sample.getData()) {
                    applyV2DataLinks(data);
                }
            }
            if (sample.getAnnotations() != null) {
                for (DatasetMultimodalExternalIdAnnotationDto annotation
                        : sample.getAnnotations()) {
                    applyV2AnnotationLink(annotation);
                }
            }
        }
    }

    private static void applyV2DataLinks(DatasetMultimodalExternalIdDataDto data) {
        if (data == null || data.getSampleDataId() == null
                || data.getSampleDataId().isBlank()) {
            return;
        }
        String basePath = "/api/v2/dataset-sample-data/" + data.getSampleDataId();
        data.setPreviewUrl(basePath + "/preview");
        data.setDownloadUrl(basePath + "/download");
    }

    private static void applyV2AnnotationLink(
            DatasetMultimodalExternalIdAnnotationDto annotation
    ) {
        if (annotation == null || annotation.getAnnotationId() == null
                || annotation.getAnnotationId().isBlank()) {
            return;
        }
        annotation.setDownloadUrl(
                "/api/v2/dataset-annotations/"
                        + annotation.getAnnotationId()
                        + "/download"
        );
    }
}
