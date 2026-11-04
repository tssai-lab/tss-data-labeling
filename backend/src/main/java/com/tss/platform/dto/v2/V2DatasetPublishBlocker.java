package com.tss.platform.dto.v2;

public record V2DatasetPublishBlocker(
        String code,
        String message,
        String resourceType,
        String resourceId
) {
    public V2DatasetPublishBlocker(String code, String message) {
        this(code, message, null, null);
    }
}
