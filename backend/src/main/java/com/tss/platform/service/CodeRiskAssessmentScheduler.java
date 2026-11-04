package com.tss.platform.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CodeRiskAssessmentScheduler {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    private final CodeRiskAssessmentService assessmentService;
    private final SchedulerLockService lockService;

    public CodeRiskAssessmentScheduler(
            CodeRiskAssessmentService assessmentService,
            SchedulerLockService lockService
    ) {
        this.assessmentService = assessmentService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${code-assets.risk.scan-interval-ms:5000}")
    public void processRiskAssessments() {
        lockService.runWithLock(
                "code-risk-assessment",
                LOCK_AT_MOST_FOR,
                assessmentService::processPendingBatch
        );
    }
}
