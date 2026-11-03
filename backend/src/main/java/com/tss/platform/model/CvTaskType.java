package com.tss.platform.model;

import java.util.Locale;
import java.util.Map;

public enum CvTaskType {
    IMAGE_CLASSIFICATION,
    OBJECT_DETECTION,
    SEMANTIC_SEGMENTATION,
    INSTANCE_SEGMENTATION,
    UNLABELED,
    OTHER;

    private static final Map<String, CvTaskType> ALIASES = Map.ofEntries(
            Map.entry("IMAGE_CLASSIFICATION", IMAGE_CLASSIFICATION),
            Map.entry("CLASSIFICATION", IMAGE_CLASSIFICATION),
            Map.entry("图像分类", IMAGE_CLASSIFICATION),
            Map.entry("OBJECT_DETECTION", OBJECT_DETECTION),
            Map.entry("DETECTION", OBJECT_DETECTION),
            Map.entry("目标检测", OBJECT_DETECTION),
            Map.entry("SEMANTIC_SEGMENTATION", SEMANTIC_SEGMENTATION),
            Map.entry("语义分割", SEMANTIC_SEGMENTATION),
            Map.entry("INSTANCE_SEGMENTATION", INSTANCE_SEGMENTATION),
            Map.entry("实例分割", INSTANCE_SEGMENTATION),
            Map.entry("UNLABELED", UNLABELED),
            Map.entry("NO_ANNOTATION", UNLABELED),
            Map.entry("无标注", UNLABELED),
            Map.entry("OTHER", OTHER),
            Map.entry("其他", OTHER)
    );

    public static String normalizeForTask(String taskType, String value) {
        if (!"CV".equals(taskType)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            return UNLABELED.name();
        }
        String key = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        CvTaskType type = ALIASES.get(key);
        if (type == null) {
            throw new IllegalArgumentException(
                    "CV cvTaskType only supports IMAGE_CLASSIFICATION, OBJECT_DETECTION, SEMANTIC_SEGMENTATION, INSTANCE_SEGMENTATION, UNLABELED, OTHER"
            );
        }
        return type.name();
    }
}
