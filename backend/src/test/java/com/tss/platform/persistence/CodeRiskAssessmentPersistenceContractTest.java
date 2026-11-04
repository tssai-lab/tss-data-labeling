package com.tss.platform.persistence;

import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAssetAuditLog;
import com.tss.platform.entity.CodeRiskAssessment;
import com.tss.platform.entity.CodeRiskFinding;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.PlatformSystemConfig;
import com.tss.platform.model.CodeApprovalDecisionSource;
import com.tss.platform.model.CodeAuditActorType;
import com.tss.platform.model.CodeRiskAssessmentStatus;
import com.tss.platform.model.CodeRiskDisposition;
import com.tss.platform.model.CodeRiskFindingSeverity;
import com.tss.platform.model.CodeRiskLevel;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRiskAssessmentPersistenceContractTest {

    @Test
    void exposesRiskAssessmentFindingApprovalSummaryAndSystemActorMetadata() {
        Instant now = Instant.parse("2026-07-15T01:02:03Z");

        CodeRiskAssessment assessment = new CodeRiskAssessment();
        assessment.setId("risk-1");
        assessment.setVersionId("version-1");
        assessment.setValidationRunId("validation-1");
        assessment.setArtifactSha256("a".repeat(64));
        assessment.setRiskPolicyVersion("risk-policy-v1");
        assessment.setScannerVersion("scanner-v1");
        assessment.setStatus(CodeRiskAssessmentStatus.COMPLETED);
        assessment.setRiskLevel(CodeRiskLevel.MEDIUM);
        assessment.setDisposition(CodeRiskDisposition.MANUAL_REVIEW);
        assessment.setFindingCount(1);
        assessment.setRequestedByUserId(7);
        assessment.setCreatedAt(now);
        assessment.setStartedAt(now);
        assessment.setCompletedAt(now);

        assertEquals("code_risk_assessment", tableName(CodeRiskAssessment.class));
        assertEquals("validation-1", assessment.getValidationRunId());
        assertEquals(CodeRiskDisposition.MANUAL_REVIEW, assessment.getDisposition());

        CodeRiskFinding finding = new CodeRiskFinding();
        finding.setId("finding-1");
        finding.setRiskAssessmentId(assessment.getId());
        finding.setRuleId("PYTHON_SUBPROCESS");
        finding.setSeverity(CodeRiskFindingSeverity.HIGH);
        finding.setCategory("PROCESS_EXECUTION");
        finding.setFilePath("src/train.py");
        finding.setLineStart(12);
        finding.setLineEnd(12);
        finding.setDescription("Subprocess invocation requires manual review");
        finding.setCreatedAt(now);

        assertEquals("code_risk_finding", tableName(CodeRiskFinding.class));
        assertEquals("src/train.py", finding.getFilePath());

        CodeApprovalRecord approval = new CodeApprovalRecord();
        approval.setDecisionSource(CodeApprovalDecisionSource.AUTO_POLICY);
        approval.setRiskAssessmentId(assessment.getId());
        approval.setApprovalPolicyVersion("approval-policy-v1");
        assertEquals(CodeApprovalDecisionSource.AUTO_POLICY, approval.getDecisionSource());

        CodeVersion version = new CodeVersion();
        version.setLatestRiskAssessmentId(assessment.getId());
        version.setRiskStatus(assessment.getStatus());
        version.setRiskLevel(assessment.getRiskLevel());
        version.setReviewDisposition(assessment.getDisposition());
        version.setRiskPolicyVersion(assessment.getRiskPolicyVersion());
        assertEquals("risk-1", version.getLatestRiskAssessmentId());

        CodeAssetAuditLog audit = new CodeAssetAuditLog();
        audit.setActorType(CodeAuditActorType.SYSTEM);
        audit.setActorUserId(null);
        assertEquals(CodeAuditActorType.SYSTEM, audit.getActorType());
    }

    @Test
    void v42AddsTwoModeSystemConfigurationAndExplicitDirectPassEvidence() throws Exception {
        String sql = migrationSql(
                "db/migration/V43__training_code_review_mode.sql"
        ).toLowerCase(Locale.ROOT);

        assertEquals("platform_system_config", tableName(PlatformSystemConfig.class));
        assertTrue(sql.contains("create table platform_system_config"));
        assertTrue(sql.contains("'direct_pass', 'standard_review'"));
        assertTrue(sql.contains("decision_source = 'system_config'"));
        assertTrue(sql.contains("'training-code-direct-pass-approval-v1'"));
        assertTrue(sql.contains("'direct_pass'"));
    }

    @Test
    void v30CreatesRiskEvidenceAndSafeFindingSchema() throws Exception {
        String sql = migrationSql(
                "db/migration/V30__code_risk_assessment_and_review_queue.sql"
        ).toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("create table code_risk_assessment"));
        assertTrue(sql.contains("create table code_risk_finding"));
        assertTrue(sql.contains("validation_run_id"));
        assertTrue(sql.contains("artifact_sha256"));
        assertTrue(sql.contains("risk_policy_version"));
        assertTrue(sql.contains("scanner_version"));
        assertTrue(sql.contains("'queued', 'running', 'completed', 'error', 'canceled'"));
        assertTrue(sql.contains("'low', 'medium', 'high', 'unknown'"));
        assertTrue(sql.contains("'auto_approve', 'manual_review', 'block'"));
        assertTrue(sql.contains("finding_count >= 0"));
        assertTrue(sql.contains("fk_code_risk_assessment_validation_evidence"));
        assertTrue(sql.contains("fk_code_risk_finding_assessment"));
        assertTrue(sql.contains("ck_code_risk_finding_line_end"));

        assertFalse(sql.contains("source_code"));
        assertFalse(sql.contains("source_snippet"));
        assertFalse(sql.contains("storage_path"));
        assertFalse(sql.contains("download_url"));
        assertFalse(sql.contains("secret_value"));
    }

    @Test
    void v30BindsAutomaticAndAdministrativeDecisionsToExplicitEvidence() throws Exception {
        String sql = migrationSql(
                "db/migration/V30__code_risk_assessment_and_review_queue.sql"
        ).toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("add column decision_source"));
        assertTrue(sql.contains("add column risk_assessment_id"));
        assertTrue(sql.contains("add column approval_policy_version"));
        assertTrue(sql.contains("'auto_policy', 'admin', 'legacy'"));
        assertTrue(sql.contains("decision in ('approved', 'rejected')"));
        assertTrue(sql.contains("reviewer_user_id is null"));
        assertTrue(sql.contains("risk_assessment_id is not null"));
        assertTrue(sql.contains("decision_source = 'admin'"));
        assertTrue(sql.contains("reviewer_user_id is not null"));
        assertTrue(sql.contains("decision_source = 'legacy'"));
        assertTrue(sql.contains("decision = 'legacy_approval_imported'"));
        assertTrue(sql.contains("set decision_source = 'legacy'"));
    }

    @Test
    void v30AddsReviewQueueSummaryAndTypedAuditActorWithoutTrainingMutation() throws Exception {
        String sql = migrationSql(
                "db/migration/V30__code_risk_assessment_and_review_queue.sql"
        ).toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("add column latest_risk_assessment_id"));
        assertTrue(sql.contains("add column risk_status"));
        assertTrue(sql.contains("add column risk_level"));
        assertTrue(sql.contains("add column review_disposition"));
        assertTrue(sql.contains("add column risk_policy_version"));
        assertTrue(sql.contains("idx_code_version_pending_review_queue"));
        assertTrue(sql.contains("approval_status = 'pending'"));

        assertTrue(sql.contains("add column actor_type"));
        assertTrue(sql.contains("actor_type = 'user' and actor_user_id is not null"));
        assertTrue(sql.contains("actor_type = 'system' and actor_user_id is null"));
        assertFalse(sql.contains("alter table training_experiment_version"));
        assertFalse(sql.contains("k8s"));
    }

    @Test
    void v45AddsExplicitAdministratorAuditActorWithoutWeakeningSystemActor() throws Exception {
        String sql = migrationSql(
                "db/migration/V46__admin_code_asset_management.sql"
        ).toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("drop constraint ck_code_asset_audit_log_actor"));
        assertTrue(sql.contains("actor_type in ('user', 'admin')"));
        assertTrue(sql.contains("actor_user_id is not null"));
        assertTrue(sql.contains("actor_type = 'system'"));
        assertTrue(sql.contains("actor_user_id is null"));
    }

    private static String tableName(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        assertNotNull(table);
        return table.name();
    }

    private static String migrationSql(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
