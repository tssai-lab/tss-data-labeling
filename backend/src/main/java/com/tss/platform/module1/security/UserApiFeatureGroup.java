package com.tss.platform.module1.security;

public enum UserApiFeatureGroup {
    MODEL_ASSET("模型资产"),
    DATASET_ASSET("数据集资产"),
    TRAINING_DEFINITION("训练代码与方案"),
    TRAINING_TASK("训练任务"),
    INFERENCE_TASK("推理任务"),
    SYSTEM_ADMIN_AUDIT("系统管理与审计");

    private final String displayName;

    UserApiFeatureGroup(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static UserApiFeatureGroup parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("功能组不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的功能组: " + value);
        }
    }
}
