package com.tss.labeling.project;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WorkspaceService {
    private final WorkflowStore s;

    public WorkspaceService(WorkflowStore s) {
        this.s = s;
    }

    public record StateInput(Long baseRevision, String reason) {}

    @Transactional
    public JsonNode transition(
            ActorContext actor, UUID project, String action, StateInput input, String key) {
        s.enabled(actor);
        // Replay remains available after a successful tombstone.
        var p =
                s.one(
                        "SELECT * FROM project WHERE id=? AND owner_actor_id=? FOR UPDATE",
                        project,
                        actor.actorId());
        String op =
                ("delete".equals(action) ? "DELETE:/projects/" : "POST:/projects/")
                        + project
                        + ("delete".equals(action) ? "" : "/" + action);
        var replay = s.replay(actor, op, key, input);
        if (replay != null) return replay;
        if (p.get("deleted_at") != null) throw error(404, "RESOURCE_NOT_FOUND", "项目不存在");
        long rev = ((Number) p.get("revision")).longValue();
        if (input.baseRevision() == null || rev != input.baseRevision())
            throw error(409, "REVISION_CONFLICT", "项目版本已变化");
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000)
            throw error(422, "REASON_REQUIRED", "请填写操作原因（最多1000字）");
        String from = (String) p.get("status"), to;
        switch (action) {
            case "pause" -> {
                if (!"OPEN".equals(from)) throw error(409, "INVALID_PROJECT_STATE", "只有开放项目可以暂停");
                to = "PAUSED";
            }
            case "resume" -> {
                if (!"PAUSED".equals(from)) throw error(409, "INVALID_PROJECT_STATE", "只有暂停项目可以恢复");
                to = "OPEN";
            }
            case "archive" -> {
                if (!Set.of("DRAFT", "OPEN", "PAUSED").contains(from))
                    throw error(409, "INVALID_PROJECT_STATE", "项目已归档");
                idle(project);
                to = "ARCHIVED";
            }
            case "delete" -> {
                if (!"DRAFT".equals(from)) throw error(409, "DRAFT_ONLY", "仅草稿项目可删除");
                idle(project);
                if (Boolean.TRUE.equals(
                        s.db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM package_plan WHERE project_id=? AND"
                                        + " status='PUBLISHED')",
                                Boolean.class,
                                project))) throw error(409, "DRAFT_ONLY", "已有发布历史的项目不可删除");
                to = "ARCHIVED";
            }
            default -> throw error(404, "RESOURCE_NOT_FOUND", "操作不存在");
        }
        s.db.update(
                "UPDATE project SET"
                    + " status=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP,deleted_at=CASE"
                    + " WHEN ? THEN CURRENT_TIMESTAMP ELSE deleted_at END WHERE id=?",
                to,
                "delete".equals(action),
                project);
        s.db.update(
                "INSERT INTO"
                    + " project_event(id,project_id,actor_id,action,reason,before_revision,after_revision)"
                    + " VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                project,
                actor.actorId(),
                action.toUpperCase(Locale.ROOT),
                input.reason(),
                rev,
                rev + 1);
        return s.remember(
                actor,
                op,
                key,
                input,
                project,
                Map.of(
                        "projectId",
                        project,
                        "status",
                        to,
                        "revision",
                        rev + 1,
                        "deleted",
                        "delete".equals(action)));
    }

    private void idle(UUID project) {
        if (Boolean.TRUE.equals(
                s.db.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM publication_round WHERE project_id=? AND status='OPEN')
  OR EXISTS(SELECT 1 FROM import_batch WHERE project_id=? AND status IN ('UPLOADING','PROCESSING'))
""",
                        Boolean.class,
                        project,
                        project))) throw error(409, "PROJECT_BUSY", "项目仍有活动轮次或导入，需先结束处理");
    }

    @Transactional
    public JsonNode list(ActorContext actor, UUID project, String kind, int limit, String cursor) {
        s.owner(actor, project, false);
        UUID after = s.cursor(limit, cursor);
        String sql, field;
        switch (kind) {
            case "imports" -> {
                field = "batchId";
                sql =
                        "SELECT id AS \"batchId\",project_id AS \"projectId\",status,original_name"
                            + " AS filename,valid_sample_count AS \"validSampleCount\",error_count"
                            + " AS \"errorCount\",created_at AS \"createdAt\" FROM import_batch"
                            + " WHERE project_id=? AND id>? ORDER BY id LIMIT ?";
            }
            case "rounds" -> {
                field = "roundId";
                sql =
                        "SELECT id AS \"roundId\",project_id AS \"projectId\",spec_version_id AS"
                            + " \"specVersionId\",package_id AS \"packageId\",mode,status,round_no"
                            + " AS \"roundNo\",revision,instructions,created_at AS \"createdAt\","
                            + "(SELECT count(*) FROM round_item ri WHERE ri.round_id=publication_round.id) AS \"sampleCount\""
                            + " FROM publication_round WHERE project_id=? AND id>? ORDER BY id"
                            + " LIMIT ?";
            }
            case "ai-executions" -> {
                field = "executionId";
                sql =
                        "SELECT e.id AS \"executionId\",e.project_id AS \"projectId\",e.spec_version_id"
                            + " AS \"specVersionId\",e.round_id AS \"roundId\",e.agent_actor_id AS \"agentActorId\","
                            + "a.id AS \"agentId\",a.display_name AS \"agentDisplayName\",e.status,e.revision,"
                            + "e.model_version AS \"modelVersion\",e.annotator_provider_id AS \"annotatorProviderId\","
                            + "e.annotator_provider_version AS \"annotatorProviderVersion\",e.annotator_type AS \"annotatorType\","
                            + "e.retry_of_execution_id AS \"retryOfExecutionId\",e.success_count AS \"successCount\","
                            + "e.failed_count AS \"failedCount\",e.pending_count AS \"pendingCount\","
                            + "e.created_at AS \"createdAt\" FROM ai_execution e LEFT JOIN ai_agent_config a"
                            + " ON a.actor_id=e.agent_actor_id AND a.model_version=e.model_version"
                            + " WHERE e.project_id=? AND e.id>? ORDER BY e.id LIMIT ?";
            }
            case "package-plans" -> {
                field = "planId";
                sql =
                        "SELECT id AS \"planId\",project_id AS \"projectId\",spec_version_id AS"
                            + " \"specVersionId\",status,selection_hash AS"
                            + " \"selectionHash\",sample_count AS \"sampleCount\",created_at AS"
                            + " \"createdAt\" FROM package_plan WHERE project_id=? AND id>? ORDER"
                            + " BY id LIMIT ?";
            }
            default -> throw error(404, "RESOURCE_NOT_FOUND", "列表不存在");
        }
        return s.page(s.db.queryForList(sql, project, after, limit + 1), limit, field);
    }

    @Transactional
    public JsonNode submissions(ActorContext actor, int limit, String cursor) {
        s.enabled(actor);
        var after = s.cursor(limit, cursor);
        return s.page(
                s.db.queryForList(
                        "SELECT id AS \"submissionId\",project_id AS \"projectId\",spec_version_id"
                            + " AS \"specVersionId\",assignment_id AS \"assignmentId\",round_id AS"
                            + " \"roundId\",package_id AS \"packageId\",submitted_at AS"
                            + " \"submittedAt\" FROM submission WHERE submitted_by=? AND id>? ORDER"
                            + " BY id LIMIT ?",
                        actor.actorId(),
                        after,
                        limit + 1),
                limit,
                "submissionId");
    }

    @Transactional
    public JsonNode receipts(ActorContext actor, int limit, String cursor) {
        return receipts(actor, limit, cursor, false);
    }

    @Transactional
    public JsonNode adminReceipts(ActorContext actor, int limit, String cursor) {
        if (!actor.labelingAdmin()) throw error(403, "FORBIDDEN", "仅标注管理员可查看全平台操作记录");
        return receipts(actor, limit, cursor, true);
    }

    private JsonNode receipts(ActorContext actor, int limit, String cursor, boolean global) {
        s.enabled(actor);
        s.cursor(limit, null);
        java.sql.Timestamp after = new java.sql.Timestamp(0);
        UUID afterActor = new UUID(0, 0);
        String operation = "", key = "";
        if (cursor != null) {
            try {
                if (cursor.length() > 4096) throw new IllegalArgumentException();
                var decoded =
                        s.read(
                                new String(
                                        Base64.getUrlDecoder().decode(cursor),
                                        java.nio.charset.StandardCharsets.UTF_8));
                if (!decoded.isArray() || decoded.size() != (global ? 4 : 3))
                    throw new IllegalArgumentException();
                after = java.sql.Timestamp.from(java.time.Instant.parse(decoded.get(0).asText()));
                if (global) {
                    afterActor = UUID.fromString(decoded.get(1).asText());
                    operation = decoded.get(2).asText();
                    key = decoded.get(3).asText();
                } else {
                    operation = decoded.get(1).asText();
                    key = decoded.get(2).asText();
                }
            } catch (RuntimeException ex) {
                throw error(400, "INVALID_CURSOR", "操作回执游标无效");
            }
        }
        var rows = global
                ? s.db.queryForList(
                        "SELECT r.actor_id AS \"actorId\",a.display_name AS \"actorName\","
                            + "a.identity_user_id AS \"identityUserId\",r.resource_id AS"
                            + " \"resourceId\",r.operation,r.idempotency_key AS"
                            + " \"idempotencyKey\",r.response_body::text AS response,r.created_at"
                            + " AS \"createdAt\" FROM idempotency_record r JOIN actor a ON"
                            + " a.id=r.actor_id WHERE r.response_body IS NOT NULL AND"
                            + " (r.created_at,r.actor_id,r.operation,r.idempotency_key)>(?,?,?,?)"
                            + " ORDER BY r.created_at,r.actor_id,r.operation,r.idempotency_key LIMIT ?",
                        after,
                        afterActor,
                        operation,
                        key,
                        limit + 1)
                : s.db.queryForList(
                        "SELECT r.actor_id AS \"actorId\",a.display_name AS \"actorName\","
                            + "a.identity_user_id AS \"identityUserId\",r.resource_id AS"
                            + " \"resourceId\",r.operation,r.idempotency_key AS"
                            + " \"idempotencyKey\",r.response_body::text AS response,r.created_at"
                            + " AS \"createdAt\" FROM idempotency_record r JOIN actor a ON"
                            + " a.id=r.actor_id WHERE r.actor_id=? AND r.response_body IS NOT NULL AND"
                            + " (r.created_at,r.operation,r.idempotency_key)>(?,?,?) ORDER BY"
                            + " r.created_at,r.operation,r.idempotency_key LIMIT ?",
                        actor.actorId(),
                        after,
                        operation,
                        key,
                        limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = items.get(items.size() - 1);
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                    s.write(
                                    global
                                            ? List.of(
                                                    ((java.sql.Timestamp) last.get("createdAt"))
                                                            .toInstant().toString(),
                                                    last.get("actorId").toString(),
                                                    last.get("operation"),
                                                    last.get("idempotencyKey"))
                                            : List.of(
                                                    ((java.sql.Timestamp) last.get("createdAt"))
                                                            .toInstant().toString(),
                                                    last.get("operation"),
                                                    last.get("idempotencyKey")))
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        items.forEach(r -> r.put("response", s.read((String) r.get("response"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", next);
        return s.json.valueToTree(out);
    }
}
