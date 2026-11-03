package com.tss.platform.model;

import java.util.Locale;

public enum TrainingCodeReviewMode {
    DIRECT_PASS,
    STANDARD_REVIEW,
    MANUAL_ONLY;

    public static TrainingCodeReviewMode fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trainingCodeReviewMode 不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "trainingCodeReviewMode 仅支持 DIRECT_PASS、STANDARD_REVIEW 或 MANUAL_ONLY"
            );
        }
    }

    public boolean automaticDecisionsEnabled() {
        return this == STANDARD_REVIEW;
    }
}
