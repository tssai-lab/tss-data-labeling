package com.tss.platform.inference;

import java.util.Locale;
import java.util.regex.Pattern;

public final class KubernetesInferenceJobNaming {

    private static final Pattern INVALID = Pattern.compile("[^a-z0-9-]");
    private static final int MAX_LEN = 63;

    private KubernetesInferenceJobNaming() {
    }

    public static String jobNameForInference(String taskId) {
        String suffix = sanitizeLabelValue(taskId);
        String prefix = "tss-infer-";
        int maxSuffixLen = MAX_LEN - prefix.length();
        if (suffix.length() > maxSuffixLen) {
            suffix = trimHyphens(suffix.substring(0, maxSuffixLen));
        }
        return prefix + (suffix.isEmpty() ? "task" : suffix);
    }

    public static String jobNameForInference(String taskId, Integer attempt) {
        int safeAttempt = Math.max(attempt == null ? 1 : attempt, 1);
        if (safeAttempt == 1) {
            return jobNameForInference(taskId);
        }
        String suffix = sanitizeLabelValue(taskId);
        String attemptSuffix = "-a" + safeAttempt;
        String prefix = "tss-infer-";
        int maxSuffixLen = MAX_LEN - prefix.length() - attemptSuffix.length();
        if (suffix.length() > maxSuffixLen) {
            suffix = trimHyphens(suffix.substring(0, maxSuffixLen));
        }
        return prefix + (suffix.isEmpty() ? "task" : suffix) + attemptSuffix;
    }

    public static String sanitizeLabelValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = INVALID.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("-");
        sanitized = sanitized.replaceAll("-+", "-");
        sanitized = trimHyphens(sanitized);
        if (sanitized.length() > 63) {
            sanitized = trimHyphens(sanitized.substring(0, 63));
        }
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private static String trimHyphens(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("-")) {
            result = result.substring(1);
        }
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
