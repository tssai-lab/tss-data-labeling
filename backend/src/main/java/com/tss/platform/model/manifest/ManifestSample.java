package com.tss.platform.model.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ManifestSample(
        String externalId,
        int sampleIndex,
        Map<String, Object> tags,
        Map<String, Object> metadata,
        List<ManifestData> data,
        List<ManifestAnnotation> annotations
) {
    public ManifestSample {
        tags = immutableMap(tags);
        metadata = immutableMap(metadata);
        data = List.copyOf(data);
        annotations = List.copyOf(annotations);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
