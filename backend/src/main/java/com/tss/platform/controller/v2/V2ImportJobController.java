package com.tss.platform.controller.v2;

import com.tss.platform.dto.v2.V2ImportJobStatusDto;
import com.tss.platform.dto.v2.V2ImportJobRetryRequest;
import com.tss.platform.service.V2ImportJobService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/import-jobs")
public class V2ImportJobController {

    private final V2ImportJobService service;

    public V2ImportJobController(V2ImportJobService service) {
        this.service = service;
    }

    @PostMapping("/{importJobId}/retry")
    public V2ImportJobStatusDto retry(
            @PathVariable String importJobId,
            @RequestBody V2ImportJobRetryRequest request
    ) {
        return service.retry(importJobId, request);
    }

    @GetMapping("/{importJobId}")
    public V2ImportJobStatusDto getStatus(@PathVariable String importJobId) {
        return service.getStatus(importJobId);
    }
}
