package com.tss.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** 将失败转换为稳定的 API 错误码与用户提示；不改变导入状态机。 */
final class ImportFailureMapper {
    private static final ObjectMapper ERROR_DETAILS_MAPPER = new ObjectMapper();
    private ImportFailureMapper() {}
    static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    static ImportFailure importFailure(Exception exception) {
        ManifestValidationException validation = findCause(
                exception,
                ManifestValidationException.class
        );
        if (validation != null) {
            if (!"INVALID_MANIFEST".equals(validation.getErrorCode())) {
                return new ImportFailure(
                        validation.getErrorCode(),
                        validation.getMessage(),
                        toJson(validation.getDetails())
                );
            }
            String code = validation.getFailureKind() == null
                    ? LegacyImportFailureClassifier.manifestCode(validation.getMessage())
                    : validation.getFailureKind().name();
            return new ImportFailure(
                    code,
                    manifestUserMessage(code),
                    toJson(validation.getDetails())
            );
        }

        String message = rootMessage(exception);
        if (LegacyImportFailureClassifier.duplicateSample(message)) {
            return new ImportFailure(
                    "DUPLICATE_SAMPLE",
                    "上传内容包含已存在的样本",
                    null
            );
        }
        return new ImportFailure(
                "IMPORT_FAILED",
                "数据导入失败，请检查上传内容后重试",
                null
        );
    }

    static String manifestUserMessage(String code) {
        return switch (code) {
            case "DUPLICATE_SAMPLE" -> "上传内容包含重复样本";
            case "ANNOTATION_TARGET_NOT_FOUND" -> "标注文件找不到对应的数据文件";
            case "ANNOTATION_TARGET_AMBIGUOUS" -> "标注文件对应多个数据文件";
            case "UNSUPPORTED_SAMPLE_FILE" -> "上传内容包含不支持的样本文件";
            case "INVALID_SAMPLE_DIRECTORY" -> "样本目录结构不符合要求";
            default -> "Manifest 内容无效，请检查后重试";
        };
    }

    static String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return ERROR_DETAILS_MAPPER.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    static <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> type
    ) {
        Throwable current = throwable;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    static String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "Import failed";
        }
        return message.length() > 4000 ? message.substring(0, 4000) : message;
    }
    record ImportFailure(
            String code,
            String message,
            String detailsJson
    ) {
    }
}
