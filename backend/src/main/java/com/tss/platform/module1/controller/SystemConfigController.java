package com.tss.platform.module1.controller;

import com.tss.platform.dto.KubernetesResourcePolicyDto;
import com.tss.platform.dto.KubernetesResourcePolicyUpdateRequest;
import com.tss.platform.dto.SystemConfigDto;
import com.tss.platform.dto.SystemConfigUpdateRequest;
import com.tss.platform.module1.common.Result;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.KubernetesResourcePolicyService;
import com.tss.platform.service.SystemConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system/config")
public class SystemConfigController {

    private final SystemConfigService service;
    private final KubernetesResourcePolicyService resourcePolicyService;

    public SystemConfigController(
            SystemConfigService service,
            KubernetesResourcePolicyService resourcePolicyService
    ) {
        this.service = service;
        this.resourcePolicyService = resourcePolicyService;
    }

    @GetMapping("/get")
    public Result<Map<String, Object>> get() {
        try {
            return Result.success(toFrontendMap(service.getForAdministration()), "查询成功");
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("无权限访问，仅超级管理员可操作");
        }
    }

    @PostMapping("/update")
    public Result<Map<String, Object>> update(
            @RequestBody(required = false) SystemConfigUpdateRequest request
    ) {
        try {
            return Result.success(toFrontendMap(service.updateForAdministration(request)), "保存成功");
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("无权限访问，仅超级管理员可操作");
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        }
    }

    @GetMapping("/resource-policy/get")
    public Result<KubernetesResourcePolicyDto> getResourcePolicy() {
        try {
            return Result.success(
                    resourcePolicyService.getForSuperAdministration(),
                    "查询成功"
            );
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("无权限访问，仅超级管理员可操作");
        } catch (IllegalStateException exception) {
            return Result.serverError(exception.getMessage());
        }
    }

    @PostMapping("/resource-policy/update")
    public Result<KubernetesResourcePolicyDto> updateResourcePolicy(
            @RequestBody(required = false) KubernetesResourcePolicyUpdateRequest request
    ) {
        try {
            return Result.success(
                    resourcePolicyService.updateForSuperAdministration(request),
                    "保存成功"
            );
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("无权限访问，仅超级管理员可操作");
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        } catch (IllegalStateException exception) {
            return Result.serverError(exception.getMessage());
        }
    }

    private static Map<String, Object> toFrontendMap(SystemConfigDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trainingCodeReviewMode", dto.trainingCodeReviewMode());
        map.put("logMaxSize", dto.logMaxSize());
        map.put("userLogStorageLimitMb", dto.userLogStorageLimitMb());
        map.put("updatedAt", dto.updatedAt());
        return map;
    }
}
