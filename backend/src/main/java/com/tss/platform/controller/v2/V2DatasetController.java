package com.tss.platform.controller.v2;

import com.tss.platform.dto.PageResponse;
import com.tss.platform.dto.v2.V2DatasetListItem;
import com.tss.platform.service.V2DatasetCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/datasets")
public class V2DatasetController {

    private final V2DatasetCatalogService service;

    public V2DatasetController(V2DatasetCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<V2DatasetListItem> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) List<String> artifactSpecIds
    ) {
        return artifactSpecIds == null
                ? service.list(type, keyword, page, current, pageSize)
                : service.list(type, keyword, page, current, pageSize, artifactSpecIds);
    }
}
