package com.tss.platform.service;

/** 仅兼容旧版未携带类别的异常。新代码不得依赖英文提示分类。 */
final class LegacyImportFailureClassifier {
    private LegacyImportFailureClassifier() {}
    static String manifestCode(String message) {
        String normalized = message == null
                ? ""
                : message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("duplicate external_id")
                || normalized.contains("duplicate sample_index")) {
            return "DUPLICATE_SAMPLE";
        }
        if (normalized.contains("ambiguous")
                && normalized.contains("annotation")) {
            return "ANNOTATION_TARGET_AMBIGUOUS";
        }
        if (normalized.contains("annotation")
                && normalized.contains("not found")) {
            return "ANNOTATION_TARGET_NOT_FOUND";
        }
        if (normalized.contains("unsupported")) {
            return "UNSUPPORTED_SAMPLE_FILE";
        }
        if (normalized.contains("sample directory")
                || normalized.contains("root-level")
                || normalized.contains("auto_directory")) {
            return "INVALID_SAMPLE_DIRECTORY";
        }
        return "INVALID_MANIFEST";
    }

    static boolean duplicateSample(String message) {
        return message.contains("external_id already exists") || message.contains("sample_index already exists");
    }
}

