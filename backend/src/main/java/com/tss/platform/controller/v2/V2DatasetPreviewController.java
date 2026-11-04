package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2DatasetPreviewDescriptor;
import com.tss.platform.service.V2DatasetPreviewDescriptorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/dataset-versions")
public class V2DatasetPreviewController {

    private final V2DatasetPreviewDescriptorService service;

    public V2DatasetPreviewController(V2DatasetPreviewDescriptorService service) {
        this.service = service;
    }

    @GetMapping("/{versionId}/preview")
    public V2DatasetPreviewDescriptor preview(@PathVariable String versionId) {
        return service.describe(versionId);
    }
}
