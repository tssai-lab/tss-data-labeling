package com.tss.platform.training;

import java.util.Locale;
import java.util.regex.Pattern;

public final class KubernetesJobNaming {

    private static final Pattern INVALID = Pattern.compile("[^a-z0-9-]");
    private static final int MAX_LEN = 63;

    private KubernetesJobNaming() {
    }

    public static String jobNameForTraining(String trainingId) {
        String suffix = trainingId == null ? "unknown" : trainingId.toLowerCase(Locale.ROOT);
        suffix = INVALID.matcher(suffix).replaceAll("-");
        suffix = suffix.replaceAll("-+", "-");
        suffix = trimHyphens(suffix);
        String prefix = "tss-train-";
        int maxSuffixLen = MAX_LEN - prefix.length();
        if (suffix.length() > maxSuffixLen) {
            suffix = suffix.substring(0, maxSuffixLen);
            suffix = trimHyphens(suffix);
        }
        if (suffix.isEmpty()) {
            suffix = "task";
        }
        return prefix + suffix;
    }

    public static String labelSelectorForTraining(String trainingId) {
        return "tss.ai/training-id=" + sanitizeLabelValue(trainingId);
    }

    public static String sanitizeLabelValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = value.toLowerCase(Locale.ROOT);
        sanitized = INVALID.matcher(sanitized).replaceAll("-");
        sanitized = sanitized.replaceAll("-+", "-");
        sanitized = trimHyphens(sanitized);
        if (sanitized.length() > 63) {
            sanitized = sanitized.substring(0, 63);
            sanitized = trimHyphens(sanitized);
        }
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private static String trimHyphens(String value) {
        String result = value;
        while (result.startsWith("-")) {
            result = result.substring(1);
        }
        while (result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
