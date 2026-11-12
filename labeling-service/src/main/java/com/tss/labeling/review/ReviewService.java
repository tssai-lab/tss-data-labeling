package com.tss.labeling.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ReviewService {

    private static final Set<String> ITEM_ACTIONS = Set.of("ACCEPT", "MODIFY", "REJECT", "CORRECTION");
    private static final Set<String> DECISION_ACTIONS = Set.of("ACCEPT", "REJECT", "CORRECTION");

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ReviewService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** 需求方或提交者读取当前审核结论；不校验项目开放状态，已提交结果始终可读。 */
    @Transactional
    public JsonNode get(ActorContext actor, UUID submissionId) {
        Map<String, Object> s = reviewable(actor, submissionId);
        return view(submissionId, s);
    }

    /** 需求方逐样本作出接受/修改/退回/待修正；owner 专属，按幂等键去重。 */
    @Transactional
    public JsonNode reviewItems(ActorContext actor, UUID submissionId,
            List<ReviewController.ItemInput> items, String key) {
        Map<String, Object> s = ownerSubmission(actor, submissionId);
        String op = "PUT:/submissions/" + submissionId + "/review/items";
        String requestHash = hash(write(items));
        JsonNode previous = replay(actor.actorId(), op, key, requestHash);
        if (previous != null) {
            return previous;
        }
        Set<UUID> seen = new HashSet<>();
        for (ReviewController.ItemInput item : items) {
            if (!seen.add(item.sampleId())) {
                throw error(422, "INVALID_REQUEST", "审核清单不能含重复样本");
            }
            if (!ITEM_ACTIONS.contains(item.action())) {
                throw error(422, "INVALID_REQUEST", "审核动作无效");
            }
            apply(actor, submissionId, s.get("project_id"), item.sampleId(), item.action(), item.reason(), item.result());
        }
        JsonNode response = view(submissionId, s);
        remember(actor.actorId(), op, key, requestHash, submissionId, response);
        return response;
    }

    /** 需求方对整份提交一次作出接受/退回/待修正；owner 专属。 */
    @Transactional
    public JsonNode decide(ActorContext actor, UUID submissionId,
            ReviewController.DecisionInput input, String key) {
        Map<String, Object> s = ownerSubmission(actor, submissionId);
        String op = "POST:/submissions/" + submissionId + "/review/decide";
        String requestHash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, requestHash);
        if (previous != null) {
            return previous;
        }
        if (!DECISION_ACTIONS.contains(input.action())) {
            throw error(422, "INVALID_REQUEST", "整份决策仅支持接受/退回/待修正");
        }
        var rows = db.queryForList(
                "SELECT sample_id, result::text AS result FROM submission_item WHERE submission_id=? ORDER BY sample_id",
                submissionId);
        for (var row : rows) {
            UUID sampleId = (UUID) row.get("sample_id");
            String before = (String) row.get("result");
            // 整份决策不写入新结果：接受沿用冻结答案，退回/待修正只记录结论。
            upsert(submissionId, sampleId, input.action(), null, null, input.reason(), actor.actorId());
            syncSampleReview(submissionId, sampleId, input.action(), input.reason(), actor.actorId());
            audit(s.get("project_id"), submissionId, actor.actorId(), "REVIEW_DECIDE", sampleId,
                    before, "ACCEPT".equals(input.action()) ? before : null, input.reason());
        }
        JsonNode response = view(submissionId, s);
        remember(actor.actorId(), op, key, requestHash, submissionId, response);
        return response;
    }

    /** 读取一条提交的审核审计记录，按时间升序分页。 */
    @Transactional
    public JsonNode audit(ActorContext actor, UUID submissionId, int limit, String cursor) {
        reviewable(actor, submissionId);
        if (limit < 1 || limit > 100) {
            throw error(400, "INVALID_REQUEST", "分页大小应为1至100");
        }
        UUID after;
        try {
            after = cursor == null ? new UUID(0, 0) : UUID.fromString(cursor);
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_CURSOR", "分页游标无效");
        }
        var rows = db.queryForList("""
                SELECT id AS "auditId", actor_id AS "actorId", action, sample_id AS "sampleId",
                  before_result::text AS "beforeResult", after_result::text AS "afterResult", reason,
                  created_at::text AS "createdAt"
                FROM review_audit WHERE submission_id=? AND id>? ORDER BY id LIMIT ?
                """, submissionId, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        for (var item : items) {
            if (item.get("beforeResult") != null) {
                item.put("beforeResult", read((String) item.get("beforeResult")));
            }
            if (item.get("afterResult") != null) {
                item.put("afterResult", read((String) item.get("afterResult")));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", more ? items.get(items.size() - 1).get("auditId").toString() : null);
        return json.valueToTree(response);
    }

    private void apply(ActorContext actor, UUID submissionId, Object projectId,
            UUID sampleId, String action, String reason, JsonNode result) {
        Map<String, Object> row = one(
                "SELECT result_format, result::text AS result FROM submission_item WHERE submission_id=? AND sample_id=?",
                submissionId, sampleId);
        String before = (String) row.get("result");
        String format = null;
        String after = null;
        if ("MODIFY".equals(action)) {
            format = validateModified(submissionId, result);
            after = write(result);
        }
        upsert(submissionId, sampleId, action, format, after, reason, actor.actorId());
        syncSampleReview(submissionId, sampleId, action, reason, actor.actorId());
        // 接受不改动答案，审计里用冻结答案作为 after，便于追溯“按原样接受”。
        String auditAfter = "ACCEPT".equals(action) ? before : after;
        audit(projectId, submissionId, actor.actorId(), "REVIEW_ITEM", sampleId, before, auditAfter, reason);
    }

    /** 修改结果仅支持首期图片单标签分类，标签必须属于提交绑定的规范版本。 */
    private String validateModified(UUID submissionId, JsonNode result) {
        Map<String, Object> spec = one("""
                SELECT sv.task_type, sv.labels::text AS labels
                FROM submission s JOIN spec_version sv ON sv.id = s.spec_version_id
                WHERE s.id = ?
                """, submissionId);
        if (!"IMAGE_CLASSIFICATION".equals(spec.get("task_type"))) {
            throw error(422, "UNSUPPORTED_FORMAT", "首期仅支持图片单标签分类结果");
        }
        if (result == null || !result.isObject() || result.size() != 1 || !result.has("labelId")) {
            throw error(422, "INVALID_RESULT", "修改结果必须为 labelId 对象");
        }
        JsonNode label = result.get("labelId");
        if (label == null || !label.isTextual() || label.asText().isBlank()) {
            throw error(422, "INVALID_RESULT", "请选择一个标签");
        }
        for (var allowed : read((String) spec.get("labels"))) {
            if (label.asText().equals(allowed.path("id").asText())) {
                return "image-classification-v1";
            }
        }
        throw error(422, "INVALID_RESULT", "标签不属于提交绑定的规范版本");
    }

    private void upsert(UUID submissionId, UUID sampleId, String action,
            String resultFormat, String resultJson, String reason, UUID actorId) {
        db.update("""
                INSERT INTO review_item(submission_id, sample_id, action, result_format, result, reason, reviewed_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (submission_id, sample_id) DO UPDATE SET
                  action = EXCLUDED.action,
                  result_format = EXCLUDED.result_format,
                  result = EXCLUDED.result,
                  reason = EXCLUDED.reason,
                  reviewed_by = EXCLUDED.reviewed_by,
                  reviewed_at = CURRENT_TIMESTAMP
                """, submissionId, sampleId, action, resultFormat, resultJson, reason, actorId);
    }

    private void audit(Object projectId, UUID submissionId, UUID actorId, String action,
            UUID sampleId, String before, String after, String reason) {
        db.update("""
                INSERT INTO review_audit(id, project_id, submission_id, actor_id, action, sample_id, before_result, after_result, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, UUID.randomUUID(), projectId, submissionId, actorId, action, sampleId, before, after, reason);
    }

    /** 将提交审核同步为最终成果使用的逐样本审核状态。 */
    private void syncSampleReview(UUID submissionId, UUID sampleId, String action, String reason, UUID actorId) {
        Map<String,Object> submission = one("""
                SELECT project_id, spec_version_id FROM submission WHERE id=?
                """, submissionId);
        UUID projectId = (UUID) submission.get("project_id");
        UUID specVersionId = (UUID) submission.get("spec_version_id");
        one("SELECT id FROM sample WHERE id=? AND project_id=? FOR UPDATE", sampleId, projectId);

        boolean accepted = "ACCEPT".equals(action) || "MODIFY".equals(action);
        String status = accepted ? "ACCEPTED" : "NEEDS_REVISION";
        String sourceKind = accepted ? "HUMAN" : null;
        UUID sourceSubmissionId = accepted ? submissionId : null;
        Long oldRevision = db.query("""
                SELECT revision FROM sample_review
                WHERE project_id=? AND sample_id=? AND spec_version_id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getLong(1) : 0L, projectId, sampleId, specVersionId);
        long beforeRevision = oldRevision == null ? 0L : oldRevision;
        long afterRevision = beforeRevision + 1;

        db.update("""
                INSERT INTO sample_review(project_id,sample_id,spec_version_id,status,source_kind,submission_id,
                  execution_id,reviewed_by,reason,revision)
                VALUES (?,?,?,?,?,?,NULL,?,?,?)
                ON CONFLICT (project_id,sample_id,spec_version_id) DO UPDATE SET
                  status=EXCLUDED.status, source_kind=EXCLUDED.source_kind,
                  submission_id=EXCLUDED.submission_id, execution_id=NULL,
                  reviewed_by=EXCLUDED.reviewed_by, reason=EXCLUDED.reason,
                  revision=sample_review.revision+1, updated_at=CURRENT_TIMESTAMP
                """, projectId, sampleId, specVersionId, status, sourceKind, sourceSubmissionId,
                actorId, reason, afterRevision);
        db.update("""
                INSERT INTO review_event(id,project_id,sample_id,spec_version_id,actor_id,action,
                  source_kind,submission_id,execution_id,reason,before_revision,after_revision)
                VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?)
                """, UUID.randomUUID(), projectId, sampleId, specVersionId, actorId,
                "SUBMISSION_" + action, "HUMAN", submissionId, reason, beforeRevision, afterRevision);
    }

    /** 汇总审核条目并推导整体状态：未审/全部接受/全部退回/全部待修正/部分。 */
    private JsonNode view(UUID submissionId, Map<String, Object> s) {
        long total = db.query("SELECT count(*) FROM submission_item WHERE submission_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, submissionId);
        Map<String, Object> counts = one("""
                SELECT count(*) AS reviewed,
                  count(*) FILTER (WHERE action IN ('ACCEPT','MODIFY')) AS accepted,
                  count(*) FILTER (WHERE action = 'REJECT') AS rejected,
                  count(*) FILTER (WHERE action = 'CORRECTION') AS correction
                FROM review_item WHERE submission_id=?
                """, submissionId);
        int reviewed = ((Number) counts.get("reviewed")).intValue();
        int accepted = ((Number) counts.get("accepted")).intValue();
        int rejected = ((Number) counts.get("rejected")).intValue();
        int correction = ((Number) counts.get("correction")).intValue();
        String status;
        if (reviewed == 0) {
            status = "OPEN";
        } else if (reviewed == total && accepted == total) {
            status = "ACCEPTED";
        } else if (reviewed == total && rejected == total) {
            status = "REJECTED";
        } else if (reviewed == total && correction == total) {
            status = "CORRECTION";
        } else {
            status = "PARTIAL";
        }
        var items = db.queryForList("""
                SELECT sample_id AS "sampleId", action, result_format AS "resultFormat",
                  result::text AS result, reason, reviewed_by AS "reviewedBy", reviewed_at::text AS "reviewedAt"
                FROM review_item WHERE submission_id=? ORDER BY sample_id
                """, submissionId);
        for (var item : items) {
            if (item.get("result") != null) {
                item.put("result", read((String) item.get("result")));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submissionId", submissionId);
        response.put("projectId", s.get("project_id"));
        response.put("status", status);
        response.put("sampleCount", total);
        response.put("items", items);
        return json.valueToTree(response);
    }

    /** owner 或提交者均可查看审核结论。 */
    private Map<String, Object> reviewable(ActorContext actor, UUID submissionId) {
        enabled(actor.actorId());
        return one("""
                SELECT s.* FROM submission s JOIN project p ON p.id = s.project_id
                WHERE s.id = ? AND (s.submitted_by = ? OR p.owner_actor_id = ?)
                """, submissionId, actor.actorId(), actor.actorId());
    }

    /** 仅 owner 可执行审核；不泄露非本人项目是否存在。 */
    private Map<String, Object> ownerSubmission(ActorContext actor, UUID submissionId) {
        enabled(actor.actorId());
        return one("""
                SELECT s.* FROM submission s JOIN project p ON p.id = s.project_id
                WHERE s.id = ? AND p.owner_actor_id = ? FOR SHARE
                """, submissionId, actor.actorId());
    }

    private void enabled(UUID actor) {
        if (!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE", actor).get("status"))) {
            throw error(403, "LABELING_DISABLED", "标注账号已停用");
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw error(404, "RESOURCE_NOT_FOUND", "请求资源不存在");
        }
        return rows.get(0);
    }

    private JsonNode replay(UUID actor, String op, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw error(400, "INVALID_REQUEST", "需要有效的幂等键");
        }
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                actor + ":" + op + ":" + key);
        return db.query(
                "SELECT request_hash, response_body::text FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    if (!requestHash.equals(rs.getString(1))) {
                        throw error(409, "IDEMPOTENCY_CONFLICT", "同一幂等键请求内容不同");
                    }
                    return read(rs.getString(2));
                }, actor, op, key);
    }

    private void remember(UUID actor, String op, String key, String requestHash, UUID id, JsonNode response) {
        db.update("""
                INSERT INTO idempotency_record(actor_id, operation, idempotency_key, request_hash, resource_id, response_body)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, actor, op, key, requestHash, id, write(response));
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BusinessException error(int status, String code, String message) {
        return new BusinessException(HttpStatus.valueOf(status), code, message);
    }
}
