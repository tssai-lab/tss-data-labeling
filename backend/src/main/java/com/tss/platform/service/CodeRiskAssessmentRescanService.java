package com.tss.platform.service;

import com.tss.platform.entity.CodeRiskAssessment;

/** Administrator-facing boundary for explicitly requesting a new risk scan. */
public interface CodeRiskAssessmentRescanService {

    CodeRiskAssessment rescan(String versionId);
}
