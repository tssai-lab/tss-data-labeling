package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeRiskFinding;

/** Sanitized static-analysis finding. Source snippets and secret values are never returned. */
public record V2CodeRiskFinding(
        String ruleId,
        String severity,
        String category,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String description
) {

    public static V2CodeRiskFinding from(CodeRiskFinding finding) {
        return new V2CodeRiskFinding(
                finding.getRuleId(),
                finding.getSeverity(),
                finding.getCategory(),
                finding.getFilePath(),
                finding.getLineStart(),
                finding.getLineEnd(),
                finding.getDescription()
        );
    }
}
