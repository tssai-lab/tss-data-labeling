package com.tss.platform.dto;

import com.tss.platform.training.plan.TrainingPlanDefinition;

import java.time.Instant;
import java.util.List;

public final class TrainingPlanAdminDtos {

    private TrainingPlanAdminDtos() {
    }

    public record Issue(
            String code,
            String path,
            String message
    ) {
    }

    public record Change(
            String section,
            String changeType,
            String riskLevel
    ) {
    }

    public record Reference(
            String planId,
            String planVersion,
            String source,
            String status,
            String sha256
    ) {
    }

    public record Preview(
            String sha256,
            boolean publishable,
            TrainingPlanDefinition definition,
            Reference currentActive,
            List<Issue> issues,
            List<Issue> warnings,
            List<Change> changes
    ) {
    }

    public record Summary(
            Long recordId,
            String source,
            String status,
            String planId,
            String planVersion,
            String schemaVersion,
            TrainingPlanDefinition.PlanCategory category,
            String displayName,
            String sha256,
            Integer importedByUserId,
            Instant importedAt,
            Integer publishedByUserId,
            Instant publishedAt,
            Integer disabledByUserId,
            Instant disabledAt
    ) {
    }

    public record Detail(
            Summary summary,
            TrainingPlanDefinition definition,
            String yamlContent
    ) {
    }

    public record TemplateFile(
            String templateId,
            String fileName,
            String yamlContent
    ) {
    }
}
