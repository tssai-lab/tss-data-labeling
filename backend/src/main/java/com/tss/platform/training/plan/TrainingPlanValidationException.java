package com.tss.platform.training.plan;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrainingPlanValidationException extends IllegalArgumentException {

    private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z][A-Z0-9_]+):\\s*(.*)$");
    private static final Pattern FIELD_PREFIX = Pattern.compile(
            "^([a-z][A-Za-z0-9_.]*(?:\\[[0-9]+])?)(?:\\s|$).*"
    );

    private final List<String> violations;
    private final List<TrainingPlanViolation> details;

    public TrainingPlanValidationException(String planSource, List<String> violations) {
        super("训练方案配置非法 " + planSource + ": " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
        this.details = violations.stream().map(TrainingPlanValidationException::detailOf).toList();
    }

    public List<String> getViolations() {
        return violations;
    }

    public List<TrainingPlanViolation> getDetails() {
        return details;
    }

    private static TrainingPlanViolation detailOf(String violation) {
        String raw = violation == null ? "配置非法" : violation.trim();
        TrainingPlanErrorCode code = TrainingPlanErrorCode.PLAN_FIELD_INVALID;
        String message = raw;
        Matcher codeMatcher = CODE_PREFIX.matcher(raw);
        if (codeMatcher.matches()) {
            try {
                code = TrainingPlanErrorCode.valueOf(codeMatcher.group(1));
                message = codeMatcher.group(2);
            } catch (IllegalArgumentException ignored) {
                // Unknown prefixes remain ordinary field validation messages.
            }
        }
        String path = null;
        Matcher fieldMatcher = FIELD_PREFIX.matcher(message);
        if (fieldMatcher.matches()) {
            path = fieldMatcher.group(1);
        }
        return new TrainingPlanViolation(code, path, message);
    }
}
