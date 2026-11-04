package com.tss.platform.service;

public record CodeRiskScanFinding(
        String ruleId,
        String severity,
        String category,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String safeMessage,
        boolean blocking
) {
}
