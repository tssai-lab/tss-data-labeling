package com.tss.platform.controller.v2;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class V2ErrorDetailsSanitizer {

    private static final String DEFAULT_REASON = "请求暂时无法完成，请稍后重试";

    private V2ErrorDetailsSanitizer() {
    }

    public static Map<String, Object> reasonDetails(
            RuntimeException exception,
            String fallbackReason
    ) {
        String message = exception == null ? null : exception.getMessage();
        String reason = safeReason(message, fallbackReason);
        return reason == null ? Map.of() : Map.of("reason", reason);
    }

    public static Map<String, Object> sanitizeDetails(
            Map<String, Object> details,
            String fallbackReason
    ) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Object reason = details.get("reason");
        if (!(reason instanceof String reasonText)) {
            return Map.copyOf(details);
        }
        String safeReason = safeReason(reasonText, fallbackReason);
        if (safeReason == null) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(details);
            copy.remove("reason");
            return Map.copyOf(copy);
        }
        if (safeReason.equals(reasonText)) {
            return Map.copyOf(details);
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(details);
        copy.put("reason", safeReason);
        return Map.copyOf(copy);
    }

    public static String safeReason(String message, String fallbackReason) {
        if (message == null || message.isBlank()) {
            return null;
        }
        if (containsInternalDetails(message)) {
            return fallbackReason == null || fallbackReason.isBlank()
                    ? DEFAULT_REASON
                    : fallbackReason;
        }
        return message;
    }

    private static boolean containsInternalDetails(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("minio")
                || normalized.contains("bucket=")
                || normalized.contains("bucket ")
                || normalized.contains("object=")
                || normalized.contains("objectname")
                || normalized.contains("object name")
                || normalized.contains("storagepath")
                || normalized.contains("storage path")
                || normalized.contains("rootmessage")
                || normalized.contains("root message")
                || normalized.contains("io.minio")
                || normalized.contains("nosuchkey")
                || looksLikeInternalPath(normalized);
    }

    private static boolean looksLikeInternalPath(String normalized) {
        return normalized.contains(":\\")
                || normalized.contains(":/")
                || normalized.contains("\\\\")
                || normalized.contains("/tmp/")
                || normalized.contains("users/")
                || normalized.contains("/datasets/")
                || normalized.contains("/models/");
    }
}
