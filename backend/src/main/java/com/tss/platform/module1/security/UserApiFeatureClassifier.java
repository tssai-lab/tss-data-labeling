package com.tss.platform.module1.security;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 将稳定的业务 API 前缀映射到六个功能组。共享文件下载、登录自助接口和内部回调不在用户开关范围内。
 */
@Component
public class UserApiFeatureClassifier {

    public Optional<UserApiFeatureGroup> classify(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return Optional.empty();
        }
        String uri = requestUri.trim();
        if (startsWithAny(uri,
                "/api/internal/",
                "/api/files",
                "/api/download-tickets",
                "/api/user/login",
                "/api/user/register/",
                "/api/user/sms/code",
                "/api/user/forget/password",
                "/api/user/current-user",
                "/api/user/logout")) {
            return Optional.empty();
        }
        if (startsWithAny(uri,
                "/api/training-plans",
                "/api/admin/training-plans",
                "/api/code/",
                "/api/v2/code-",
                "/api/v2/admin/code")) {
            return Optional.of(UserApiFeatureGroup.TRAINING_DEFINITION);
        }
        if (startsWithAny(uri,
                "/api/model",
                "/api/model-assets",
                "/api/model-versions",
                "/api/v2/model-",
                "/api/v2/models")) {
            return Optional.of(UserApiFeatureGroup.MODEL_ASSET);
        }
        if (startsWithAny(uri,
                "/api/dataset",
                "/api/dataset-assets",
                "/api/dataset-versions",
                "/api/v2/dataset-",
                "/api/v2/datasets",
                "/api/v2/import-jobs")) {
            return Optional.of(UserApiFeatureGroup.DATASET_ASSET);
        }
        if (startsWithAny(uri,
                "/api/experiments",
                "/api/task",
                "/api/training/")) {
            return Optional.of(UserApiFeatureGroup.TRAINING_TASK);
        }
        if (startsWithAny(uri, "/api/inference/")) {
            return Optional.of(UserApiFeatureGroup.INFERENCE_TASK);
        }
        if (startsWithAny(uri,
                "/api/system/",
                "/api/v2/admin/demo-assets",
                "/api/log/",
                "/api/role/",
                "/api/resource-monitor/",
                "/api/user/")) {
            return Optional.of(UserApiFeatureGroup.SYSTEM_ADMIN_AUDIT);
        }
        return Optional.empty();
    }

    private static boolean startsWithAny(String uri, String... prefixes) {
        for (String prefix : prefixes) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
