package com.tss.platform.model.manifest;

import com.tss.platform.model.ZipEntryInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ManifestAnnotation(
        String path,
        String annotationType,
        String format,
        String refDataPath,
        String fileName,
        String contentType,
        Map<String, Object> metadata,
        ZipEntryInfo zipEntryInfo
) {
    public ManifestAnnotation {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
