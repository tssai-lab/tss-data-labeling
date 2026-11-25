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
    private final ResultScopeService scopes;

    public OutcomeService(JdbcTemplate db, ObjectMapper json, ResultScopeService scopes) {
        this.db = db;
        this.json = json;
        this.scopes = scopes;
    }

    // ---------- 逐样本审核（统一状态，跨人工/AI 来源） ----------

    @Transactional
    public JsonNode reviewSamples(ActorContext actor, UUID project, List<SampleReviewInput> items, String key) {
        lockOwner(actor, project);
        String op = "POST:/projects/" + project + "/sample-reviews", hash = hash(write(items));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        mutable(project);
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
        UUID after = new UUID(0, 0), afterSpec = new UUID(0, 0);
        if (cursor != null) {
            try {
                String[] parts = cursor.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                after = UUID.fromString(parts[0]);
                afterSpec = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ex) {
                throw error(400, "INVALID_CURSOR", "审核游标无效");
            }
        }
        var rows = db.queryForList(
                        """
SELECT r.project_id AS "projectId", r.sample_id AS "sampleId", r.spec_version_id AS "specVersionId",
  r.status, r.source_kind AS "sourceKind", r.submission_id AS "submissionId", r.execution_id AS "executionId",
  r.reason, r.revision, r.updated_at::text AS "updatedAt",
  s.source_path AS "sourcePath", s.metadata->>'displayName' AS "displayName"
FROM sample_review r JOIN sample s ON s.id=r.sample_id
WHERE r.project_id=? AND (r.sample_id,r.spec_version_id)>(?,?)
ORDER BY r.sample_id,r.spec_version_id LIMIT ?
""", project, after, afterSpec, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", more
                ? items.get(items.size() - 1).get("sampleId") + ":" + items.get(items.size() - 1).get("specVersionId")
                : null);
        return json.valueToTree(response);
    }

    // ---------- 最终成果版本 ----------

    @Transactional
    public JsonNode createFinalSnapshot(ActorContext actor, UUID project, FinalInput input, String key) {
        lockOwner(actor,project);
        String op = "POST:/projects/" + project + "/final-snapshots", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        mutable(project);
        if (input.name() == null || input.name().isBlank()) throw error(422,"INVALID_REQUEST","成果名称不能为空");
        if (input.selections() != null
                && (input.sampleIds() != null || input.specVersionId() != null || input.selectionId() != null))
            throw error(400,"INVALID_REQUEST","多规范成果不能同时提供单规范字段");
        if (input.selectionId() != null) {
            if (input.sampleIds() != null)
                throw error(422, "INVALID_REQUEST", "selectionId 与 sampleIds 必须二选一");
            var scope = scopes.selection(actor, project, input.selectionId(), "FINALIZE", null);
            if (!scope.specVersionId().equals(input.specVersionId()))
                throw error(409, "SPEC_MISMATCH", "成果规范与范围不一致");
            scopes.validate(project, input.specVersionId(), scope.items(), true, true);
            for (var item : scope.items()) {
                var selected =
                        one(
                                "SELECT status,result_id FROM sample_review WHERE project_id=? AND"
                                    + " sample_id=? AND spec_version_id=?",
                                project,
                                item.sampleId(),
                                input.specVersionId());
                UUID adopted = (UUID) selected.get("result_id");
                if (adopted == null)
                    adopted =
                            db.query(
                                    "SELECT result_id FROM project_result_candidate c JOIN"
                                        + " sample_review sr ON sr.project_id=c.project_id AND"
                                        + " sr.sample_id=c.sample_id AND"
                                        + " sr.spec_version_id=c.spec_version_id WHERE"
                                        + " c.project_id=? AND c.sample_id=? AND"
                                        + " c.spec_version_id=? AND c.current_version AND"
                                        + " ((sr.source_kind='HUMAN' AND"
                                        + " c.submission_id=sr.submission_id) OR"
                                        + " (sr.source_kind='AI' AND"
                                        + " c.execution_id=sr.execution_id))",
                                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                                    project,
                                    item.sampleId(),
                                    input.specVersionId());
                if (!"ACCEPTED".equals(selected.get("status")) || !item.resultId().equals(adopted))
                    throw error(409, "RESULT_VERSION_CHANGED", "当前采用答案已变化");
            }
            input =
                    new FinalInput(
                            input.name(),
                            input.specVersionId(),
                            scope.items().stream().map(ResultScopeService.Item::sampleId).toList(),
                            null,
                            null);
        }
        Map<UUID,UUID> bindings = new TreeMap<>();
        if (input.selections() != null) {
            if (input.sampleIds() != null || input.specVersionId() != null || input.selectionId() != null)
                throw error(400,"INVALID_REQUEST","多规范成果不能同时提供单规范字段");
            for (FinalSelection selection : input.selections()) {
                if (selection == null || selection.sampleId() == null || selection.specVersionId() == null)
                    throw error(400,"INVALID_REQUEST","样本和规范不能为空");
                if (bindings.put(selection.sampleId(), selection.specVersionId()) != null)
                    throw error(422,"DUPLICATE_SAMPLE","同一样本不能重复进入成果");
            }
        } else {
            if (input.sampleIds() == null || input.specVersionId() == null)
                throw error(422,"EMPTY_SELECTION","成果范围不能为空");
            for (UUID sid : input.sampleIds()) {
                if (sid == null || bindings.put(sid, input.specVersionId()) != null)
                    throw error(422,"DUPLICATE_SAMPLE","成果样本为空或重复");
            }
        }
        if (bindings.isEmpty() || bindings.size() > 10000)
            throw error(422,"EMPTY_SELECTION","成果范围为空或超过上限");
        for (UUID specId : new HashSet<>(bindings.values()))
            one("SELECT id FROM spec_version WHERE id=? AND project_id=?", specId, project);
        Set<UUID> specs = new HashSet<>(bindings.values());
        UUID headerSpec = specs.size() == 1 ? specs.iterator().next() : null;
        List<UUID> ids = new ArrayList<>(bindings.keySet());
        UUID snapshotId = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " final_snapshot(id,project_id,spec_version_id,name,scope_hash,created_by)"
                    + " VALUES (?,?,?,?,?,?)",
                snapshotId, project, headerSpec, input.name(), hash(write(bindings)), actor.actorId());
        for (UUID sampleId : ids) {
            UUID itemSpec = bindings.get(sampleId);
            boolean active = Boolean.TRUE.equals(db.queryForObject(
                                    """
                                    SELECT EXISTS (
                                      SELECT 1 FROM sample_work_claim c
                                      JOIN publication_round r ON r.id=c.round_id
                                      WHERE c.project_id=? AND c.sample_id=? AND r.status='OPEN'
                                    )
                                    """, Boolean.class, project, sampleId));
            if (active) throw error(409,"SAMPLE_BUSY","所选样本仍在处理，不能固化最终成果");
            Map<String,Object> sample = one(
                            "SELECT source_path, content_sha256 FROM sample WHERE id=? AND"
                                + " project_id=?", sampleId, project);
            Map<String,Object> rev = one(
                            "SELECT * FROM sample_review WHERE project_id=? AND sample_id=? AND"
                                + " spec_version_id=? FOR UPDATE", project, sampleId, itemSpec);
            if (!"ACCEPTED".equals(rev.get("status"))) throw error(422,"FINAL_RESULTS_INCOMPLETE","存在未确认样本");
            UUID resultId = (UUID) rev.get("result_id");
            if (resultId == null)
                resultId =
                        (UUID)
                                currentSource(project, itemSpec,
                                                sampleId, (String) rev.get("source_kind"),
                    (UUID) rev.get("submission_id"), (UUID) rev.get("execution_id"))
                                        .get("result_id");
            JsonNode result =
                    read(
                            (String)
                                    scopes.candidate(
                                                    project,
                                                    itemSpec, sampleId,
                                                    resultId)
                                            .get("content"));
            var provenance = new LinkedHashMap<String,Object>();
            provenance.put("reviewer",rev.get("reviewed_by"));
            provenance.put("updated_at",String.valueOf(rev.get("updated_at")));
            var author=db.queryForList("SELECT source_actor FROM project_result_candidate WHERE result_id=?",resultId);
            provenance.put("annotator",author.isEmpty()?null:author.get(0).get("source_actor"));
            db.update(
                    """
INSERT INTO final_snapshot_item(snapshot_id,project_id,sample_id,spec_version_id,source_kind,submission_id,execution_id,result,review_revision,source_path,content_hash,result_id,annotation_provenance)
VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb)
""", snapshotId, project, sampleId, itemSpec, rev.get("source_kind"),
                    rev.get("submission_id"), rev.get("execution_id"), write(result), ((Number) rev.get("revision")).longValue(),
                    sample.get("source_path"), sample.get("content_sha256"),
                    resultId,write(provenance));
        }
        UUID duplicate = db.query("""
SELECT previous.id FROM final_snapshot previous
WHERE previous.project_id=? AND previous.id<>? AND previous.scope_hash=?
  AND (SELECT count(*) FROM final_snapshot_item WHERE snapshot_id=previous.id)=?
  AND NOT EXISTS (
    SELECT 1 FROM final_snapshot_item current_item
    LEFT JOIN final_snapshot_item previous_item
      ON previous_item.snapshot_id=previous.id AND previous_item.sample_id=current_item.sample_id
    WHERE current_item.snapshot_id=? AND
      (previous_item.sample_id IS NULL OR previous_item.spec_version_id<>current_item.spec_version_id
       OR previous_item.result<>current_item.result)
  )
LIMIT 1
""", rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                project, snapshotId, hash(write(bindings)), ids.size(), snapshotId);
        if (duplicate != null)
            throw error(409, "DUPLICATE_FINAL_SNAPSHOT", "相同标注内容的成果版本已存在，请修改已有版本名称");
        JsonNode response = finalSnapshotView(snapshotId);
        remember(actor.actorId(), op, key, hash, snapshotId, response);
        return response;
    }

    /** 重新发布已确认样本时撤销当前确认指针，历史事件仍保留。 */
    @Transactional
    public void resetAcceptedForRepublish(UUID projectId, UUID specVersionId, Collection<UUID> sampleIds, UUID actorId) {
        if (sampleIds == null || sampleIds.isEmpty()) return;
        for (UUID sampleId : new TreeSet<>(sampleIds)) {
            var rows = db.queryForList(
                            """
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
            db.update(
                    """
UPDATE sample_review
SET status='PENDING', source_kind=NULL, submission_id=NULL, execution_id=NULL, result_id=NULL,
    reviewed_by=?, reason=?, revision=?, updated_at=CURRENT_TIMESTAMP
WHERE project_id=? AND sample_id=? AND spec_version_id=?
""", actorId, reason, afterRevision, projectId, sampleId, specVersionId);
            db.update(
                    """
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
        Map<String,Object> s = one(
                        "SELECT s.*, p.owner_actor_id FROM final_snapshot s JOIN project p ON"
                            + " p.id=s.project_id WHERE s.id=?", id);
        if (!actor.actorId().equals(s.get("owner_actor_id"))) throw error(404,"RESOURCE_NOT_FOUND","资源不存在");
        return finalSnapshotView(id);
    }

    @Transactional
    public JsonNode renameFinalSnapshot(ActorContext actor, UUID id, String name, String key) {
        UUID project = (UUID) one("SELECT project_id FROM final_snapshot WHERE id=?", id).get("project_id");
        lockOwner(actor, project);
        String op = "PATCH:/final-snapshots/" + id, requestHash = hash(write(name));
        JsonNode previous = replay(actor.actorId(), op, key, requestHash);
        if (previous != null) return previous;
        if (name == null || name.isBlank() || name.trim().length() > 120)
            throw error(422, "INVALID_REQUEST", "成果名称应为1至120个字符");
        db.update("UPDATE final_snapshot SET name=? WHERE id=?", name.trim(), id);
        JsonNode response = finalSnapshotView(id);
        remember(actor.actorId(), op, key, requestHash, id, response);
        return response;
    }

    @Transactional
    public JsonNode deleteFinalSnapshot(ActorContext actor, UUID id) {
        enabled(actor.actorId());
        UUID project = (UUID) one(
                "SELECT s.project_id FROM final_snapshot s JOIN project p ON p.id=s.project_id"
                        + " WHERE s.id=? AND p.owner_actor_id=? AND p.deleted_at IS NULL",
                id, actor.actorId()).get("project_id");
        lockOwner(actor, project);
        mutable(project);
        one("SELECT id FROM final_snapshot WHERE id=? AND project_id=? FOR UPDATE", id, project);
        boolean isCompletedSnapshot = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM project WHERE completed_snapshot_id=?)",
                Boolean.class, id));
        if (isCompletedSnapshot)
            throw error(409, "SNAPSHOT_IN_USE", "项目完成时固定的成果版本不能删除");
        boolean hasExports = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM source_export_job WHERE snapshot_id=?)"
                        + " OR EXISTS(SELECT 1 FROM export_job WHERE jsonb_exists(snapshot_ids, ?))",
                Boolean.class, id, id.toString()));
        if (hasExports)
            throw error(409, "SNAPSHOT_HAS_EXPORTS", "已有导出记录的成果版本不能删除");
        db.update("DELETE FROM final_snapshot_item WHERE snapshot_id=? AND project_id=?", id, project);
        db.update("DELETE FROM final_snapshot WHERE id=? AND project_id=?", id, project);
        return json.valueToTree(Map.of("finalSnapshotId", id, "deleted", true));
    }

    @Transactional
    public JsonNode finalSnapshots(ActorContext actor, UUID project, int limit, String cursor) {
        owner(actor, project);
        if (limit < 1 || limit > 100) throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after = cursor(cursor);
        var rows = db.queryForList(
                        """
SELECT fs.id AS "finalSnapshotId", fs.spec_version_id AS "specVersionId", fs.name,
  fs.scope_hash AS "scopeHash", fs.created_at::text AS "createdAt",
  (SELECT count(*) FROM final_snapshot_item i WHERE i.snapshot_id=fs.id) AS "sampleCount"
FROM final_snapshot fs WHERE fs.project_id=? AND fs.id>? ORDER BY fs.id LIMIT ?
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
        Long oldRev = db.query(
                        "SELECT revision FROM sample_review WHERE project_id=? AND sample_id=? AND"
                                + " spec_version_id=? FOR UPDATE",
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
        db.update(
                """
INSERT INTO sample_review(project_id,sample_id,spec_version_id,status,source_kind,submission_id,execution_id,reviewed_by,reason,revision)
VALUES (?,?,?,?,?,?,?,?,?,?)
ON CONFLICT (project_id,sample_id,spec_version_id) DO UPDATE SET
  status=EXCLUDED.status, result_id=NULL, source_kind=EXCLUDED.source_kind, submission_id=EXCLUDED.submission_id,
  execution_id=EXCLUDED.execution_id, reviewed_by=EXCLUDED.reviewed_by, reason=EXCLUDED.reason,
  revision=sample_review.revision+1, updated_at=CURRENT_TIMESTAMP
""", project, item.sampleId(), item.specVersionId(),
                "ACCEPT".equals(action) ? "ACCEPTED" : action, sourceKind, submissionId, executionId, actor.actorId(), item.reason(), oldRev + 1);
        db.update(
                """
INSERT INTO review_event(id,project_id,sample_id,spec_version_id,actor_id,action,source_kind,submission_id,execution_id,reason,before_revision,after_revision)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
""", UUID.randomUUID(), project, item.sampleId(), item.specVersionId(), actor.actorId(), action,
                sourceKind, submissionId, executionId, item.reason(), oldRev, oldRev + 1);
    }

    /** 校验来源归属与规范一致，返回冻结的结果内容。 */
    private
            Map<String,Object> currentSource(
            UUID project, UUID spec, UUID sample, String kind, UUID submission, UUID execution) {
        if ("AI".equals(kind)) {
            var e = one(
                            "SELECT project_id,spec_version_id,status FROM ai_execution WHERE id=?", execution);
        if (!project.equals(e.get("project_id"))) throw error(409,"SOURCE_MISMATCH","来源不属于该项目");
        if (!spec.equals(e.get("spec_version_id"))) throw error(409,"SPEC_MISMATCH","来源规范与审核规范不一致");
        if (!List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(e.get("status")))
            throw error(409,"AI_RESULTS_NOT_READY", "执行尚未终结");
        } else {
            var binding =
                    one(
                            "SELECT spec_version_id FROM submission WHERE id=? AND project_id=?",
                            submission,
                            project);
            if (!spec.equals(binding.get("spec_version_id")))
                throw error(409, "SPEC_MISMATCH", "来源规范与审核规范不一致");
        }
        var rows = db.queryForList(
                        "SELECT result_id,result::text AS result FROM project_result_candidate"
                            + " WHERE project_id=? AND spec_version_id=? AND sample_id=? AND"
                            + " current_version AND "
                                + ("HUMAN".equals(kind) ? "submission_id=?" : "execution_id=?"),
                        project,
                        spec,
                        sample,
                        "HUMAN".equals(kind) ? submission : execution);
        if (rows.size() != 1) throw error(422,"RESULT_NOT_REVIEWABLE", "来源没有唯一的当前可审核答案，请按明确结果版本审核");
        Map<String,Object> source = rows.get(0);
        if ("HUMAN".equals(kind)) {
            String reviewed = db.query(
                    "SELECT result::text FROM review_item WHERE submission_id=? AND sample_id=?"
                            + " AND action='MODIFY' AND result IS NOT NULL",
                    rs -> rs.next() ? rs.getString(1) : null,
                    submission, sample);
            if (reviewed != null) source.put("result", reviewed);
        }
        return source;
    }

    private JsonNode freezeResult(
            UUID project, UUID spec, String kind, UUID submission, UUID execution, UUID sample) {
        return read((String)
                        currentSource(project, spec, sample, kind, submission, execution).get("result"));
    }

    private JsonNode finalSnapshotView(UUID id) {
        Map<String,Object> s = one("SELECT * FROM final_snapshot WHERE id=?", id);
        var items = db.queryForList(
                        """
SELECT sample_id AS "sampleId", source_kind AS "sourceKind", submission_id AS "submissionId",
  execution_id AS "executionId", spec_version_id AS "specVersionId",
  result::text AS result, review_revision AS "reviewRevision",
  result_id AS "resultId", source_path AS "sourcePath", content_hash AS "contentHash"
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
        var specVersionIds = db.queryForList(
                "SELECT DISTINCT spec_version_id FROM final_snapshot_item WHERE snapshot_id=? ORDER BY spec_version_id",
                UUID.class, id);
        out.put("specVersionIds", specVersionIds);
        out.put("items", items);
        return json.valueToTree(out);
    }

    private void owner(ActorContext actor, UUID project) {
        enabled(actor.actorId());
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=? AND deleted_at IS NULL", project, actor.actorId());
    }

    private void lockOwner(ActorContext actor, UUID project) {
        enabled(actor.actorId());
        one("SELECT status FROM project WHERE id=? AND owner_actor_id=? AND deleted_at IS NULL FOR UPDATE",project,actor.actorId());
    }

    private void mutable(UUID project){com.tss.labeling.common.WorkflowStore.requireMutable(one("SELECT status FROM project WHERE id=?",project));}

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
        return db.query(
                "SELECT request_hash, response_body::text FROM idempotency_record WHERE actor_id=?"
                    + " AND operation=? AND idempotency_key=?",
                rs -> {
                    if (!rs.next()) return null;
                    if (!requestHash.equals(rs.getString(1))) throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
                    return read(rs.getString(2));
                }, actor, op, key);
    }

    private void remember(UUID actor, String op, String key, String requestHash, UUID id, JsonNode response) {
        db.update(
                "INSERT INTO"
                    + " idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body)"
                    + " VALUES (?,?,?,?,?,?::jsonb)",
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

    public record FinalSelection(@NotNull UUID sampleId, @NotNull UUID specVersionId) {}
    public record FinalInput(@NotNull String name, UUID specVersionId, List<UUID> sampleIds,
            UUID selectionId, List<FinalSelection> selections) {
        public FinalInput(String name, UUID specVersionId, List<UUID> sampleIds) {
            this(name, specVersionId, sampleIds, null, null);
        }
        public FinalInput(String name, UUID specVersionId, List<UUID> sampleIds, UUID selectionId) {
            this(name, specVersionId, sampleIds, selectionId, null);
        }
    }
}
