package com.tss.platform.training.plan;

import java.util.Map;

public record TrainingRunSnapshot(
        TrainingRunSpec runSpec,
        String runSpecJson,
        String runSpecSha256,
        Map<String, Object> resolvedParameters,
        String inputModelSha256,
        String inputDatasetSha256,
        String inputCodeSha256,
        String codeApprovalRecordId,
        String runtimeImage,
        String runtimeImageDigest
) {
}
