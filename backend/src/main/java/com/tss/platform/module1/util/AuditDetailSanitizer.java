package com.tss.platform.module1.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 防止密码、Token、Cookie、密钥等敏感信息写入操作记录。
 */
public final class AuditDetailSanitizer {

    private static final Pattern SENSITIVE_KV = Pattern.compile(
            "(?i)(password|passwd|pwd|token|authorization|cookie|secret|api[_-]?key|access[_-]?key|private[_-]?key|connection[_-]?string)\\s*[:=]\\s*[^,;\\s}\"']+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");

    private AuditDetailSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw;
        text = SENSITIVE_KV.matcher(text).replaceAll("$1=***");
        text = BEARER.matcher(text).replaceAll("Bearer ***");
        if (text.length() > 2000) {
            text = text.substring(0, 2000) + "...(truncated)";
        }
        return text;
    }

    public static String sanitizeFailReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("token") || lower.contains("secret")) {
            return "操作失败";
        }
        String cleaned = sanitize(reason);
        if (cleaned != null && cleaned.length() > 512) {
            return cleaned.substring(0, 512);
        }
        return cleaned;
    }
}
