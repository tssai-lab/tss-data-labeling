package com.tss.platform.persistence;

import com.tss.platform.entity.CodeApprovalRecord;
import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeAssetAuditLog;
import com.tss.platform.entity.CodeValidationRun;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAssetManagementPersistenceContractTest {

    @Test
    void exposesCodeAssetAndVersionManagementMetadata() throws Exception {
        CodeAsset asset = new CodeAsset();
        asset.setPurpose("robot training");
        asset.setRuntime("python:3.11");
        asset.setEntryScript("train.py");
        asset.setTrainingType("ROBOT");
        asset.setRowVersion(3L);

        assertEquals("robot training", asset.getPurpose());
        assertEquals("python:3.11", asset.getRuntime());
        assertEquals("train.py", asset.getEntryScript());
        assertEquals("ROBOT", asset.getTrainingType());
        assertEquals(3L, asset.getRowVersion());
        assertNotNull(CodeAsset.class.getDeclaredField("rowVersion").getAnnotation(Version.class));

        CodeVersion version = new CodeVersion();
        Instant updatedAt = Instant.parse("2026-07-13T01:02:03Z");
        Instant deprecatedAt = updatedAt.plusSeconds(60);
        Instant archivedAt = deprecatedAt.plusSeconds(60);
        version.setArtifactSha256("a".repeat(64));
        version.setValidationStatus("PASSED");
        version.setValidationPolicyVersion("policy-2026-07");
        version.setUpdatedAt(updatedAt);
        version.setDeprecatedAt(deprecatedAt);
        version.setArchivedAt(archivedAt);

        assertEquals("a".repeat(64), version.getArtifactSha256());
        assertEquals("PASSED", version.getValidationStatus());
        assertEquals("policy-2026-07", version.getValidationPolicyVersion());
        assertEquals(updatedAt, version.getUpdatedAt());
        assertEquals(deprecatedAt, version.getDeprecatedAt());
        assertEquals(archivedAt, version.getArchivedAt());
    }

    @Test
    void exposesWorkspaceDeltaValidationApprovalAndAuditEntities() {
        Instant now = Instant.parse("2026-07-13T01:02:03Z");

        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId("workspace-1");
        workspace.setAssetId("asset-1");
        workspace.setBaseVersionId("version-1");
        workspace.setClosedVersionId("version-2");
        workspace.setStatus("OPEN");
        workspace.setRevision(4L);
        workspace.setOwnerUserId(7);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspace.setClosedAt(now);
        workspace.setDeleted(false);
        workspace.setDeletedAt(now);

        assertEquals("code_workspace", tableName(CodeWorkspace.class));
        assertEquals("asset-1", workspace.getAssetId());
        assertEquals(4L, workspace.getRevision());

        byte[] content = "print('ok')".getBytes(StandardCharsets.UTF_8);
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-1");
        delta.setWorkspaceId(workspace.getId());
        delta.setPath("src/train.py");
        delta.setOperation("UPSERT");
        delta.setContentBytes(content);
        delta.setContentHash("b".repeat(64));
        delta.setSizeBytes((long) content.length);
        delta.setCreatedAt(now);
        delta.setUpdatedAt(now);

        assertEquals("code_workspace_file_delta", tableName(CodeWorkspaceFileDelta.class));
        assertArrayEquals(content, delta.getContentBytes());
        content[0] = 'X';
        assertEquals('p', delta.getContentBytes()[0]);
        byte[] returned = delta.getContentBytes();
        returned[0] = 'Y';
        assertEquals('p', delta.getContentBytes()[0]);
        assertEquals("src/train.py", delta.getPath());

        CodeValidationRun validation = new CodeValidationRun();
        validation.setId("validation-1");
        validation.setVersionId("version-2");
        validation.setArtifactSha256("c".repeat(64));
        validation.setPolicyVersion("policy-2026-07");
        validation.setStatus("FAILED");
        validation.setFailureCode("ENTRY_SCRIPT_MISSING");
        validation.setFailureMessage("entry script is missing");
        validation.setRequestedByUserId(7);
        validation.setCreatedAt(now);
        validation.setCompletedAt(now);

        assertEquals("code_validation_run", tableName(CodeValidationRun.class));
        assertEquals("FAILED", validation.getStatus());

        CodeApprovalRecord approval = new CodeApprovalRecord();
        approval.setId("approval-1");
        approval.setVersionId("version-2");
        approval.setArtifactSha256("c".repeat(64));
        approval.setValidationRunId(validation.getId());
        approval.setPolicyVersion(validation.getPolicyVersion());
        approval.setDecision("REJECTED");
        approval.setReason("validation failed");
        approval.setReviewerUserId(8);
        approval.setCreatedAt(now);

        assertEquals("code_approval_record", tableName(CodeApprovalRecord.class));
        assertEquals("REJECTED", approval.getDecision());

        CodeAssetAuditLog audit = new CodeAssetAuditLog();
        audit.setId("audit-1");
        audit.setAssetId("asset-1");
        audit.setVersionId("version-2");
        audit.setWorkspaceId("workspace-1");
        audit.setAction("VALIDATION_FAILED");
        audit.setActorUserId(7);
        audit.setMetadataJson("{\"failureCode\":\"ENTRY_SCRIPT_MISSING\"}");
        audit.setCreatedAt(now);

        assertEquals("code_asset_audit_log", tableName(CodeAssetAuditLog.class));
        assertEquals("VALIDATION_FAILED", audit.getAction());
    }

    @Test
    void v29AddsLifecycleTablesConstraintsIndexesAndLegacyApprovalReset() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains("alter table code_asset"));
        assertTrue(sql.contains("add column purpose"));
        assertTrue(sql.contains("add column runtime"));
        assertTrue(sql.contains("add column entry_script"));
        assertTrue(sql.contains("add column training_type"));
        assertTrue(sql.contains("add column row_version"));

        assertTrue(sql.contains("add column artifact_sha256"));
        assertTrue(sql.contains("add column validation_status"));
        assertTrue(sql.contains("add column validation_policy_version"));
        assertTrue(sql.contains("add column updated_at"));
        assertTrue(sql.contains("add column deprecated_at"));
        assertTrue(sql.contains("add column archived_at"));
        assertTrue(sql.contains("fk_code_version_asset"));
        assertTrue(sql.contains("orphan code_version.asset_id"));
        assertTrue(sql.contains("ck_code_version_status"));
        assertTrue(sql.contains("'ready', 'deprecated', 'archived'"));
        assertTrue(sql.contains("ck_code_version_validation_status"));
        assertTrue(sql.contains("'not_run', 'passed', 'failed'"));
        assertTrue(sql.contains("ck_code_version_approval_status"));
        assertTrue(sql.contains("'pending', 'approved', 'rejected', 'revoked'"));

        assertTrue(sql.contains("create table code_workspace"));
        assertTrue(sql.contains("create table code_workspace_file_delta"));
        assertTrue(sql.contains("create table code_validation_run"));
        assertTrue(sql.contains("create table code_approval_record"));
        assertTrue(sql.contains("create table code_asset_audit_log"));
        assertTrue(sql.contains("ck_code_workspace_status"));
        assertTrue(sql.contains("'open', 'published', 'abandoned'"));
        assertTrue(sql.contains("ck_code_workspace_revision"));
        assertTrue(sql.contains("revision >= 0"));
        assertTrue(sql.contains("bytea"));
        assertTrue(sql.contains("ck_code_workspace_file_delta_operation"));
        assertTrue(sql.contains("'upsert', 'delete'"));
        assertTrue(sql.contains("ck_code_workspace_file_delta_payload"));
        assertTrue(sql.contains("size_bytes = octet_length(content_bytes)"));
        assertTrue(sql.contains("size_bytes >= 0"));
        assertTrue(sql.contains("content_hash ~ '^[0-9a-f]{64}$'"));
        assertTrue(sql.contains("operation = 'delete'"));
        assertTrue(sql.contains("content_bytes is null"));
        assertTrue(sql.contains("content_hash is null"));
        assertTrue(sql.contains("size_bytes is null"));
        assertTrue(sql.contains("ck_code_validation_run_status"));
        assertTrue(sql.contains("ck_code_approval_record_decision"));
        assertTrue(sql.contains("'legacy_approval_imported'"));
        assertTrue(sql.contains("uk_code_workspace_file_delta_path"));
        assertTrue(sql.contains("uk_code_workspace_open_asset"));
        assertTrue(sql.contains("where status = 'open' and deleted = false"));

        assertTrue(sql.contains("idx_code_asset_owner_deleted_created"));
        assertTrue(sql.contains("idx_code_version_asset_created"));
        assertTrue(sql.contains("idx_code_validation_run_version_created"));
        assertTrue(sql.contains("idx_code_approval_record_version_created"));
        assertTrue(sql.contains("idx_code_asset_audit_log_asset_created"));
        assertTrue(sql.contains("create function fn_code_asset_audit_log_reject_mutation"));
        assertTrue(sql.contains("returns trigger"));
        assertTrue(sql.contains("before update or delete on code_asset_audit_log"));
        assertTrue(sql.contains("raise exception"));

        int legacyInsert = sql.indexOf("insert into code_approval_record");
        int approvalReset = sql.indexOf("set approval_status = 'pending'");
        assertTrue(legacyInsert >= 0);
        assertTrue(approvalReset > legacyInsert);
        assertTrue(sql.contains("'legacy_approval_imported'"));
        assertFalse(sql.contains("drop column storage_path"));
    }

    @Test
    void v29ReviewerConstraintEnforcesLegacyAndNonLegacyNullabilityInBothDirections() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains(
                "decision = 'legacy_approval_imported' and reviewer_user_id is null"));
        assertTrue(sql.contains(
                "decision <> 'legacy_approval_imported' and reviewer_user_id is not null"));
    }

    @Test
    void v29HistoryIndexesMatchDescendingRepositoryOrder() throws Exception {
        String sql = migrationSql().toLowerCase(Locale.ROOT);

        assertTrue(sql.contains(
                "on code_validation_run (version_id, created_at desc, id desc)"));
        assertTrue(sql.contains(
                "on code_approval_record (version_id, created_at desc, id desc)"));
        assertTrue(sql.contains(
                "on code_asset_audit_log (asset_id, created_at desc, id desc)"));
        assertTrue(sql.contains(
                "on code_asset_audit_log (version_id, created_at desc, id desc)"));
        assertTrue(sql.contains(
                "on code_asset_audit_log (workspace_id, created_at desc, id desc)"));
    }

    @Test
    void readmeRegistersV60AsTheCurrentMigration() throws Exception {
        String resource = "db/migration/README.md";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String readme = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(readme.contains("当前已使用的最高迁移版本为 `V60`"));
            assertTrue(readme.contains("V1-V30"));
            assertTrue(readme.contains("V31-V40"));
            assertFalse(readme.contains("当前最高迁移版本为 `V32`"));
            assertTrue(readme.contains("V29__code_asset_workspace_validation_approval.sql"));
            assertTrue(readme.contains("V30__code_risk_assessment_and_review_queue.sql"));
            assertTrue(readme.contains("V31__training_produced_model.sql"));
            assertTrue(readme.contains("V32__training_plan_run_spec_snapshot.sql"));
            assertTrue(readme.contains("V41__model_version_integrity_and_current.sql"));
            assertTrue(readme.contains("V43__training_code_review_mode.sql"));
            assertTrue(readme.contains("V57__online_training_plan_definition.sql"));
            assertTrue(readme.contains("V60__add_is_demo.sql"));
        }
    }

    private static String tableName(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        assertNotNull(table);
        return table.name();
    }

    private static String migrationSql() throws Exception {
        String resource = "db/migration/V29__code_asset_workspace_validation_approval.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
