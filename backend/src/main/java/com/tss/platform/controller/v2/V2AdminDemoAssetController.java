package com.tss.platform.controller.v2;

import com.tss.platform.service.DemoAssetAdministrationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 演示资产标记/取消标记（超级管理员）。
 * <p>PUT /api/v2/admin/demo-assets/{type}/{assetId}  body: {"isDemo": true|false}
 * <p>type ∈ {dataset, model, code, inference-script}
 */
@RestController
@RequestMapping("/api/v2/admin/demo-assets")
public class V2AdminDemoAssetController {

    private final DemoAssetAdministrationService service;

    public V2AdminDemoAssetController(DemoAssetAdministrationService service) {
        this.service = service;
    }

    @PutMapping("/{type}/{assetId}")
    public Map<String, Object> setDemo(
            @PathVariable String type,
            @PathVariable String assetId,
            @RequestBody(required = false) DemoMarkRequest request
    ) {
        boolean isDemo = request != null && Boolean.TRUE.equals(request.isDemo());
        return service.setDemo(type, assetId, isDemo);
    }

    public record DemoMarkRequest(Boolean isDemo) {
    }
}
