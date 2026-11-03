package com.tss.platform.model;

import java.util.Locale;

public enum TaskType {
    CV,
    NLP,
    POINT_CLOUD,
    ROBOT,
    OTHER;

    private static final String SUPPORTED_TYPES = "CV, NLP, POINT_CLOUD, ROBOT, OTHER";

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务类型不能为空，仅支持 " + SUPPORTED_TYPES);
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return TaskType.valueOf(normalized).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("任务类型仅支持 " + SUPPORTED_TYPES);
        }
    }
}
