package com.tss.platform.service;

import java.util.List;

public record CodeRiskScanResult(
        String scannerVersion,
        String riskPolicyVersion,
        String riskLevel,
        String disposition,
        String summaryCode,
        List<CodeRiskScanFinding> findings
) {
    public CodeRiskScanResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
