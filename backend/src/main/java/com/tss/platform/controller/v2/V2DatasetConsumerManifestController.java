package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2DatasetConsumerManifest;
import com.tss.platform.service.V2DatasetConsumerManifestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/dataset-versions")
public class V2DatasetConsumerManifestController {

    private final V2DatasetConsumerManifestService service;

    public V2DatasetConsumerManifestController(
            V2DatasetConsumerManifestService service
    ) {
        this.service = service;
    }

    @GetMapping("/{versionId}/consumer-manifest")
    public V2DatasetConsumerManifest get(
            @PathVariable String versionId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        return service.get(versionId, page, pageSize);
    }
}
