package com.tss.platform.service;

import com.tss.platform.entity.ImportJob;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class V2ImportJobStatusSelector {

    static final Set<String> IMPORTING_STATUSES = Set.of("PENDING", "RUNNING");

    private V2ImportJobStatusSelector() {
    }

    static ImportJob statusJobOf(List<ImportJob> importJobs) {
        return activeImportJob(importJobs)
                .or(() -> unresolvedImportJob(importJobs))
                .orElse(latestImportJob(importJobs));
    }

    static ImportJob latestImportJob(List<ImportJob> importJobs) {
        if (importJobs == null) {
            return null;
        }
        return importJobs.stream()
                .filter(job -> !"SUPERSEDED".equals(job.getStatus()))
                .max(Comparator
                        .comparing(
                                ImportJob::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ImportJob::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        ))
                .orElse(null);
    }

    private static Optional<ImportJob> unresolvedImportJob(List<ImportJob> importJobs) {
        if (importJobs == null) {
            return Optional.empty();
        }
        return importJobs.stream()
                .filter(job -> "FAILED".equals(job.getStatus())
                        || "PARTIAL".equals(job.getStatus()))
                .max(Comparator
                        .comparing(
                                ImportJob::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ImportJob::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        ));
    }

    private static Optional<ImportJob> activeImportJob(List<ImportJob> importJobs) {
        if (importJobs == null) {
            return Optional.empty();
        }
        return importJobs.stream()
                .filter(job -> IMPORTING_STATUSES.contains(job.getStatus()))
                .max(Comparator
                        .comparing(
                                ImportJob::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ImportJob::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        ));
    }
}
