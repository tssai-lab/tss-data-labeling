package com.tss.platform.model.manifest;

import com.tss.platform.model.ZipEntryInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ManifestData(
        String path,
        String dataType,
        String sensor,
        String channel,
        int seq,
        String format,
        String fileName,
        String contentType,
        Map<String, Object> metadata,
        ZipEntryInfo zipEntryInfo
) {
    public ManifestData {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
