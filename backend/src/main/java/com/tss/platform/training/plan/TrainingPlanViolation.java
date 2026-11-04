package com.tss.platform.training.plan;

public record TrainingPlanViolation(
        TrainingPlanErrorCode code,
        String path,
        String message
) {
}
