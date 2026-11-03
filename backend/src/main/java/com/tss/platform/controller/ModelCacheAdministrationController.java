package com.tss.platform.controller;

import com.tss.platform.dto.modelcache.ModelCacheDtos;
import com.tss.platform.module1.common.Result;
import com.tss.platform.service.CodeApprovalForbiddenException;
import com.tss.platform.service.ModelCacheAdministrationService;
import com.tss.platform.service.ModelCachePolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/model-cache")
public class ModelCacheAdministrationController {

    private final ModelCacheAdministrationService service;
    private final ModelCachePolicyService policyService;

    public ModelCacheAdministrationController(
            ModelCacheAdministrationService service,
            ModelCachePolicyService policyService
    ) {
        this.service = service;
        this.policyService = policyService;
    }

    @GetMapping
    public Result<ModelCacheDtos.Overview> overview() {
        try {
            return Result.success(service.overview(policyService.currentPolicy()), "查询成功");
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("仅管理员可以查看模型缓存");
        } catch (Exception exception) {
            return Result.serverError(exception.getMessage());
        }
    }

    @PostMapping("/policy")
    public Result<ModelCacheDtos.Overview> updatePolicy(
            @RequestBody(required = false) ModelCacheDtos.PolicyUpdateRequest request
    ) {
        try {
            return Result.success(
                    service.overview(policyService.updateForSuperAdministration(request)),
                    "模型缓存磁盘保护策略保存成功"
            );
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("仅超级管理员可以修改模型缓存磁盘保护策略");
        } catch (IllegalArgumentException exception) {
            return Result.fail(exception.getMessage());
        } catch (IllegalStateException exception) {
            return Result.serverError(exception.getMessage());
        }
    }

    @PostMapping("/clear")
    public Result<ModelCacheDtos.ClearResponse> clear(
            @RequestBody(required = false) ModelCacheDtos.ClearRequest request
    ) {
        try {
            return Result.success(service.clear(request), "清理请求执行完成");
        } catch (CodeApprovalForbiddenException exception) {
            return Result.noAuth("仅超级管理员可以清理模型缓存");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.fail(exception.getMessage());
        } catch (Exception exception) {
            return Result.serverError(exception.getMessage());
        }
    }
}
