package com.tss.labeling.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class OutcomeService {

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public OutcomeService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---------- 逐样本审核（统一状态，跨人工/AI 来源） ----------

    @Transactional
    public JsonNode reviewSamples(ActorContext actor, UUID project, List<SampleReviewInput> items, String key) {
        lockOwner(actor, project);
        String op = "POST:/projects/" + project + "/sample-reviews", hash = hash(write(items));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        if (items == null || items.isEmpty()) throw error(422,"INVALID_REQUEST","审核清单不能为空");
        Set<String> seen = new HashSet<>();
        for (SampleReviewInput item : items) {
            if (!seen.add(item.sampleId() + ":" + item.specVersionId())) throw error(422,"INVALID_REQUEST","审核清单不能含重复样本");
            reviewOne(actor, project, item);
        }
        JsonNode response = json.valueToTree(Map.of("projectId", project, "reviewedCount", items.size()));
        remember(actor.actorId(), op, key, hash, project, response);
        return response;
    }

    @Transactional
    public JsonNode sampleReviews(ActorContext actor, UUID project, int limit, String cursor) {
        owner(actor, project);
        if (limit < 1 || limit > 100) throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after = cursor(cursor);
        var rows = db.queryForList("""
                SELECT project_id AS "projectId", sample_id AS "sampleId", spec_version_id AS "specVersionId",
                  status, source_kind AS "sourceKind", submission_id AS "submissionId", execution_id AS "executionId",
                  reason, revision, updated_at::text AS "updatedAt"
                FROM sample_review WHERE project_id=? AND sample_id>? ORDER BY sample_id LIMIT ?
                """, project, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", more ? items.get(items.size() - 1).get("sampleId").toString() : null);
        return json.valueToTree(response);
    }

    // ---------- 最终成果版本 ----------

    @Transactional
    public JsonNode createFinalSnapshot(ActorContext actor, UUID project, FinalInput input, String key) {
        enabled(actor.actorId());
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=? FOR UPDATE", project, actor.actorId());
        String op = "POST:/projects/" + project + "/final-snapshots", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        if (input.name() == null || input.name().isBlank()) throw error(422,"INVALID_REQUEST","成果名称不能为空");
        if (input.sampleIds() == null || input.sampleIds().isEmpty()) throw error(422,"EMPTY_SELECTION","成果范围不能为空");
        one("SELECT id FROM spec_version WHERE id=? AND project_id=?", input.specVersionId(), project);
        Set<UUID> unique = new TreeSet<>();
        for (UUID sid : input.sampleIds()) {
            if (sid == null) throw error(422,"INVALID_REQUEST","样本ID不能为空");
            unique.add(sid);
        }
        List<UUID> ids = new ArrayList<>(unique);
        UUID snapshotId = UUID.randomUUID();
        db.update("INSERT INTO final_snapshot(id,project_id,spec_version_id,name,scope_hash,created_by) VALUES (?,?,?,?,?,?)",
                snapshotId, project, input.specVersionId(), input.name(), hash(write(ids)), actor.actorId());
        for (UUID sampleId : ids) {
            boolean active = Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM sample_work_claim c
                      JOIN publication_round r ON r.id=c.round_id
                      WHERE c.project_id=? AND c.sample_id=? AND r.status='OPEN'
                    )
                    """, Boolean.class, project, sampleId));
            if (active) throw error(409,"SAMPLE_BUSY","所选样本仍在处理，不能固化最终成果");
            Map<String,Object> sample = one("SELECT source_path, content_sha256 FROM sample WHERE id=? AND project_id=?", sampleId, project);
            Map<String,Object> rev = one("SELECT * FROM sample_review WHERE project_id=? AND sample_id=? AND spec_version_id=? FOR UPDATE", project, sampleId, input.specVersionId());
            if (!"ACCEPTED".equals(rev.get("status"))) throw error(422,"FINAL_RESULTS_INCOMPLETE","存在未确认样本");
            JsonNode result = freezeResult(project, input.specVersionId(), (String) rev.get("source_kind"),
                    (UUID) rev.get("submission_id"), (UUID) rev.get("execution_id"), sampleId);
            db.update("""
                    INSERT INTO final_snapshot_item(snapshot_id,project_id,sample_id,spec_version_id,source_kind,submission_id,execution_id,result,review_revision,source_path,content_hash)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?)
                    """, snapshotId, project, sampleId, input.specVersionId(), rev.get("source_kind"),
                    rev.get("submission_id"), rev.get("execution_id"), write(result), ((Number) rev.get("revision")).longValue(),
                    sample.get("source_path"), sample.get("content_sha256"));
        }
        JsonNode response = finalSnapshotView(snapshotId);
        remember(actor.actorId(), op, key, hash, snapshotId, response);
        return response;
    }

    /** 重新发布已确认样本时撤销当前确认指针，历史事件仍保留。 */
    @Transactional
    public void resetAcceptedForRepublish(UUID projectId, UUID specVersionId, Collection<UUID> sampleIds, UUID actorId) {
        if (sampleIds == null || sampleIds.isEmpty()) return;
        for (UUID sampleId : new TreeSet<>(sampleIds)) {
            var rows = db.queryForList("""
                    SELECT source_kind, submission_id, execution_id, revision
                    FROM sample_review
                    WHERE project_id=? AND sample_id=? AND spec_version_id=? AND status='ACCEPTED'
                    FOR UPDATE
                    """, projectId, sampleId, specVersionId);
            if (rows.isEmpty()) continue;
            Map<String,Object> previous = rows.get(0);
            long beforeRevision = ((Number) previous.get("revision")).longValue();
            long afterRevision = beforeRevision + 1;
            String reason = "样本重新发布，等待新结果审核";
            db.update("""
                    UPDATE sample_review
                    SET status='PENDING', source_kind=NULL, submission_id=NULL, execution_id=NULL,
                        reviewed_by=?, reason=?, revision=?, updated_at=CURRENT_TIMESTAMP
                    WHERE project_id=? AND sample_id=? AND spec_version_id=?
                    """, actorId, reason, afterRevision, projectId, sampleId, specVersionId);
            db.update("""
                    INSERT INTO review_event(id,project_id,sample_id,spec_version_id,actor_id,action,
                      source_kind,submission_id,execution_id,reason,before_revision,after_revision)
                    VALUES (?,?,?,?,?,'REPUBLISHED',?,?,?,?,?,?)
                    """, UUID.randomUUID(), projectId, sampleId, specVersionId, actorId,
                    previous.get("source_kind"), previous.get("submission_id"), previous.get("execution_id"),
                    reason, beforeRevision, afterRevision);
        }
    }

    @Transactional
    public JsonNode finalSnapshot(ActorContext actor, UUID id) {
        Map<String,Object> s = one("SELECT s.*, p.owner_actor_id FROM final_snapshot s JOIN project p ON p.id=s.project_id WHERE s.id=?", id);
        if (!actor.actorId().equals(s.get("owner_actor_id"))) throw error(404,"RESOURCE_NOT_FOUND","资源不存在");
        return finalSnapshotView(id);
    }

    @Transactional
    public JsonNode finalSnapshots(ActorContext actor, UUID project, int limit, String cursor) {
        owner(actor, project);
        if (limit < 1 || limit > 100) throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after = cursor(cursor);
        var rows = db.queryForList("""
                SELECT id AS "finalSnapshotId", spec_version_id AS "specVersionId", name, scope_hash AS "scopeHash", created_at::text AS "createdAt"
                FROM final_snapshot WHERE project_id=? AND id>? ORDER BY id LIMIT ?
                """, project, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", more ? items.get(items.size() - 1).get("finalSnapshotId").toString() : null);
        return json.valueToTree(response);
    }

    // ---------- 内部辅助 ----------

    private void reviewOne(ActorContext actor, UUID project, SampleReviewInput item) {
        one("SELECT id FROM sample WHERE id=? AND project_id=?", item.sampleId(), project);
        one("SELECT id FROM spec_version WHERE id=? AND project_id=?", item.specVersionId(), project);
        String action = item.action();
        if (!List.of("ACCEPT","NEEDS_REVISION").contains(action)) throw error(422,"INVALID_REQUEST","动作仅支持 ACCEPT 或 NEEDS_REVISION");
        Long oldRev = db.query("SELECT revision FROM sample_review WHERE project_id=? AND sample_id=? AND spec_version_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : 0L, project, item.sampleId(), item.specVersionId());
        String sourceKind = null;
        UUID submissionId = null, executionId = null;
        if ("ACCEPT".equals(action)) {
            if (!List.of("HUMAN","AI").contains(item.sourceKind())) throw error(422,"INVALID_REQUEST","接受需指定 HUMAN 或 AI 来源");
            sourceKind = item.sourceKind();
            if ("HUMAN".equals(sourceKind)) {
                submissionId = item.submissionId();
                one("SELECT id FROM submission WHERE id=? AND project_id=?", submissionId, project);
                freezeResult(project, item.specVersionId(), sourceKind, submissionId, null, item.sampleId());
            } else {
                executionId = item.executionId();
                freezeResult(project, item.specVersionId(), sourceKind, null, executionId, item.sampleId());
            }
        }
        db.update("""
                INSERT INTO sample_review(project_id,sample_id,spec_version_id,status,source_kind,submission_id,execution_id,reviewed_by,reason,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (project_id,sample_id,spec_version_id) DO UPDATE SET
                  status=EXCLUDED.status, source_kind=EXCLUDED.source_kind, submission_id=EXCLUDED.submission_id,
                  execution_id=EXCLUDED.execution_id, reviewed_by=EXCLUDED.reviewed_by, reason=EXCLUDED.reason,
                  revision=sample_review.revision+1, updated_at=CURRENT_TIMESTAMP
                """, project, item.sampleId(), item.specVersionId(), action, sourceKind, submissionId, executionId, actor.actorId(), item.reason(), oldRev + 1);
        db.update("""
                INSERT INTO review_event(id,project_id,sample_id,spec_version_id,actor_id,action,source_kind,submission_id,execution_id,reason,before_revision,after_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), project, item.sampleId(), item.specVersionId(), actor.actorId(), action,
                sourceKind, submissionId, executionId, item.reason(), oldRev, oldRev + 1);
    }

    /** 校验来源归属与规范一致，返回冻结的结果内容。 */
    private JsonNode freezeResult(UUID project, UUID specVersionId, String sourceKind, UUID submissionId, UUID executionId, UUID sampleId) {
        if ("HUMAN".equals(sourceKind)) {
            // owner 修改过的结果优先于原始提交，保证审核后的答案进入最终成果。
            JsonNode modified = db.query("""
                    SELECT result::text AS result FROM review_item
                    WHERE submission_id=? AND sample_id=? AND action='MODIFY'
                    """, rs -> rs.next() ? read(rs.getString(1)) : null, submissionId, sampleId);
            if (modified != null) return modified;
            Map<String,Object> sub = one("""
                    SELECT s.spec_version_id, si.result::text AS result
                    FROM submission s JOIN submission_item si ON si.submission_id=s.id
                    WHERE s.id=? AND si.sample_id=? AND s.project_id=?
                    """, submissionId, sampleId, project);
            if (!specVersionId.equals(sub.get("spec_version_id"))) throw error(409,"SPEC_MISMATCH","来源规范与审核规范不一致");
            return read((String) sub.get("result"));
        }
        Map<String,Object> execution = one("SELECT project_id, spec_version_id, status FROM ai_execution WHERE id=?", executionId);
        if (!project.equals(execution.get("project_id"))) throw error(409,"SOURCE_MISMATCH","来源不属于该项目");
        if (!specVersionId.equals(execution.get("spec_version_id"))) throw error(409,"SPEC_MISMATCH","来源规范与审核规范不一致");
        if (!List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(execution.get("status")))
            throw error(409,"AI_RESULTS_NOT_READY","AI 执行尚未终结，结果暂不可审核");
        var rows = db.queryForList("""
                SELECT si.result::text AS result
                FROM ai_result_snapshot s
                JOIN ai_result_snapshot_item si ON si.snapshot_id=s.id AND si.execution_id=s.execution_id
                WHERE s.execution_id=? AND si.sample_id=? AND si.outcome='SUCCEEDED'
                """, executionId, sampleId);
        if (rows.isEmpty()) throw error(422,"RESULT_NOT_REVIEWABLE","该 AI 样本没有可审核的成功快照结果");
        return read((String) rows.get(0).get("result"));
    }

    private JsonNode finalSnapshotView(UUID id) {
        Map<String,Object> s = one("SELECT * FROM final_snapshot WHERE id=?", id);
        var items = db.queryForList("""
                SELECT sample_id AS "sampleId", source_kind AS "sourceKind", submission_id AS "submissionId",
                  execution_id AS "executionId", result::text AS result, review_revision AS "reviewRevision",
                  source_path AS "sourcePath", content_hash AS "contentHash"
                FROM final_snapshot_item WHERE snapshot_id=? ORDER BY sample_id
                """, id);
        for (var item : items) if (item.get("result") != null) item.put("result", read((String) item.get("result")));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("finalSnapshotId", id);
        out.put("projectId", s.get("project_id"));
        out.put("specVersionId", s.get("spec_version_id"));
        out.put("name", s.get("name"));
        out.put("scopeHash", s.get("scope_hash"));
        out.put("createdAt", s.get("created_at"));
        out.put("sampleCount", items.size());
        out.put("items", items);
        return json.valueToTree(out);
    }

    private void owner(ActorContext actor, UUID project) {
        enabled(actor.actorId());
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=?", project, actor.actorId());
    }

    private void lockOwner(ActorContext actor, UUID project) {
        enabled(actor.actorId());
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=? FOR UPDATE", project, actor.actorId());
    }

    private void enabled(UUID actor) {
        if (!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE", actor).get("status")))
            throw error(403,"LABELING_DISABLED","标注账号已停用");
    }

    private UUID cursor(String cursor) {
        try { return cursor == null ? new UUID(0,0) : UUID.fromString(cursor); }
        catch (IllegalArgumentException ex) { throw error(400,"INVALID_CURSOR","分页游标无效"); }
    }

    private Map<String,Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw error(404,"RESOURCE_NOT_FOUND","资源不存在");
        return rows.get(0);
    }

    private JsonNode replay(UUID actor, String op, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 200) throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, actor + ":" + op + ":" + key);
        return db.query("SELECT request_hash, response_body::text FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",
                rs -> {
                    if (!rs.next()) return null;
                    if (!requestHash.equals(rs.getString(1))) throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
                    return read(rs.getString(2));
                }, actor, op, key);
    }

    private void remember(UUID actor, String op, String key, String requestHash, UUID id, JsonNode response) {
        db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body) VALUES (?,?,?,?,?,?::jsonb)",
                actor, op, key, requestHash, id, write(response));
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static BusinessException error(int status, String code, String message) {
        return new BusinessException(HttpStatus.valueOf(status), code, message);
    }

    public record SampleReviewInput(@NotNull UUID sampleId, @NotNull UUID specVersionId, @NotNull String action,
            String sourceKind, UUID submissionId, UUID executionId, String reason) {}

    public record FinalInput(@NotNull String name, @NotNull UUID specVersionId, @NotNull List<UUID> sampleIds) {}
}
