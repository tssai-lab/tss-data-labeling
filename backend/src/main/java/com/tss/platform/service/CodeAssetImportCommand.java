package com.tss.platform.service;

public record CodeAssetImportCommand(
        String name,
        String version,
        String trainingProfile,
        String purpose,
        String runtime,
        String entryScript,
        String trainingType,
        String remark
) {
}
