package com.tss.platform.dto.v2;

import com.tss.platform.entity.CodeRiskFinding;

/** Rule metadata only: findings never expose source excerpts or detected secret values. */
public record V2AdminCodeRiskFinding(
        String id,
        String riskAssessmentId,
        String ruleId,
        String severity,
        String category,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String description
) {

    private static final int MAX_DESCRIPTION_LENGTH = 2_000;

    public static V2AdminCodeRiskFinding from(CodeRiskFinding finding) {
        if (finding == null) {
            throw new IllegalArgumentException("Code risk finding is required");
        }
        return new V2AdminCodeRiskFinding(
                finding.getId(),
                finding.getRiskAssessmentId(),
                finding.getRuleId(),
                finding.getSeverity(),
                finding.getCategory(),
                finding.getFilePath(),
                finding.getLineStart(),
                finding.getLineEnd(),
                safeDescription(finding.getDescription())
        );
    }

    private static String safeDescription(String description) {
        if (description == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(Math.min(
                description.length(), MAX_DESCRIPTION_LENGTH
        ));
        for (int index = 0;
             index < description.length() && safe.length() < MAX_DESCRIPTION_LENGTH;
             index++) {
            char character = description.charAt(index);
            if (!Character.isISOControl(character) || character == '\n' || character == '\t') {
                safe.append(character);
            }
        }
        return safe.toString();
    }
}
