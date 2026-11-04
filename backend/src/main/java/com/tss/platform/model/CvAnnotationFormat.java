package com.tss.platform.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public enum CvAnnotationFormat {
    NONE,
    FOLDER_CLASSIFICATION,
    CSV,
    YOLO,
    COCO,
    VOC,
    MASK,
    LABELME,
    OTHER;

    private static final Map<String, CvAnnotationFormat> ALIASES = Map.ofEntries(
            Map.entry("NO_ANNOTATION", NONE),
            Map.entry("UNLABELED", NONE),
            Map.entry("无标注", NONE),
            Map.entry("NONE", NONE),
            Map.entry("FOLDER", FOLDER_CLASSIFICATION),
            Map.entry("FOLDER_CLASS", FOLDER_CLASSIFICATION),
            Map.entry("FOLDER_CLASSIFICATION", FOLDER_CLASSIFICATION),
            Map.entry("文件夹分类", FOLDER_CLASSIFICATION),
            Map.entry("CSV", CSV),
            Map.entry("YOLO", YOLO),
            Map.entry("COCO", COCO),
            Map.entry("VOC", VOC),
            Map.entry("PASCAL_VOC", VOC),
            Map.entry("MASK", MASK),
            Map.entry("掩码", MASK),
            Map.entry("LABELME", LABELME),
            Map.entry("LABEL_ME", LABELME),
            Map.entry("OTHER", OTHER),
            Map.entry("其他", OTHER)
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp", ".tif", ".tiff"
    );
    private static final Set<String> SAFE_ANNOTATION_EXTENSIONS = Set.of(
            ".txt", ".json", ".xml", ".csv", ".yaml", ".yml"
    );

    public static String normalizeForTask(String taskType, String value) {
        if (!"CV".equals(taskType)) {
            return null;
        }
        if (value == null || value.isBlank()) {
            return NONE.name();
        }
        String key = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        CvAnnotationFormat format = ALIASES.get(key);
        if (format == null) {
            throw new IllegalArgumentException(
                    "CV annotationFormat only supports NONE, FOLDER_CLASSIFICATION, CSV, YOLO, COCO, VOC, MASK, LABELME, OTHER"
            );
        }
        return format.name();
    }

    public static boolean isAllowedFile(String annotationFormat, String extension) {
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return true;
        }
        CvAnnotationFormat format = valueOf(annotationFormat);
        return switch (format) {
            case NONE, FOLDER_CLASSIFICATION, MASK -> false;
            case CSV -> ".csv".equals(extension);
            case YOLO -> ".txt".equals(extension) || ".yaml".equals(extension) || ".yml".equals(extension);
            case COCO, LABELME -> ".json".equals(extension);
            case VOC -> ".xml".equals(extension);
            case OTHER -> SAFE_ANNOTATION_EXTENSIONS.contains(extension);
        };
    }

    public static boolean isAnnotationFile(String annotationFormat, String extension) {
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return false;
        }
        return isAllowedFile(annotationFormat, extension);
    }

    public static boolean requiresAnnotationFile(String annotationFormat) {
        CvAnnotationFormat format = valueOf(annotationFormat);
        return switch (format) {
            case CSV, YOLO, COCO, VOC, LABELME -> true;
            case NONE, FOLDER_CLASSIFICATION, MASK, OTHER -> false;
        };
    }
}
