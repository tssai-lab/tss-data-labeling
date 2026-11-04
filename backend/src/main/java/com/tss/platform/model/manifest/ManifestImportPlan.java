package com.tss.platform.model.manifest;

import java.util.List;

public record ManifestImportPlan(
        String version,
        List<ManifestSample> samples,
        int totalSamples,
        int totalDataCount,
        int totalAnnotationCount,
        List<String> warnings
) {
    public ManifestImportPlan {
        samples = List.copyOf(samples);
        warnings = List.copyOf(warnings);
    }
}
