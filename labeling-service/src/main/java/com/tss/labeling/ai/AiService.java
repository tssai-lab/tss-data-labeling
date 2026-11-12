package com.tss.labeling.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.ImportService.SampleFile;
import com.tss.labeling.imports.ImportStorage;
import com.tss.labeling.review.OutcomeService;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class AiService {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportStorage storage;
    private final OutcomeService outcomes;
    private final int maxSamples;

    public AiService(JdbcTemplate db, ObjectMapper json, ImportStorage storage, OutcomeService outcomes,
            @Value("${labeling.tasks.max-samples:10000}") int maxSamples) {
        this.db = db;
        this.json = json;
        this.storage = storage;
        this.outcomes = outcomes;
        this.maxSamples = maxSamples;
    }

    // ---------- 公开接口（需求方） ----------

    @Transactional
    public JsonNode agents(ActorContext actor, String taskType) {
        enabled(actor.actorId());
        return json.valueToTree(db.queryForList("""
                SELECT id AS "agentId", display_name AS "displayName", task_type AS "taskType",
                  model_version AS "modelVersion", supports_reference AS "supportsReference",
                  confidence_description AS "confidenceDescription", enabled
                FROM ai_agent_config WHERE enabled=TRUE AND (? = '' OR task_type = ?) ORDER BY display_name
                """, taskType, taskType));
    }

    @Transactional
    public JsonNode registerAgent(ActorContext actor, RegisterInput input, String key) {
        enabled(actor.actorId());
        if (!actor.labelingAdmin()) throw error(403,"FORBIDDEN","仅标注管理员可注册 Agent");
        JsonNode capabilities = input.capabilities() == null ? json.createObjectNode() : input.capabilities();
        JsonNode supportedLabels = input.supportedLabels() == null ? json.createArrayNode() : input.supportedLabels();
        if (!capabilities.isObject() || !supportedLabels.isArray())
            throw error(422,"INVALID_AGENT_CONFIG","capabilities 必须是对象，supportedLabels 必须是数组");
        String op = "POST:/admin/agents", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        UUID actorId = UUID.randomUUID();
        db.update("INSERT INTO actor(id, kind, identity_user_id, display_name) VALUES (?, 'MODEL', NULL, ?)", actorId, input.displayName());
        UUID agentId = UUID.randomUUID();
        db.update("""
                INSERT INTO ai_agent_config(id, actor_id, task_type, display_name, model_version, capabilities,
                  supported_labels, supports_reference, confidence_description, worker_key_hash)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
                """, agentId, actorId, input.taskType(), input.displayName(), input.modelVersion(),
                write(capabilities), write(supportedLabels), input.supportsReference(),
                input.confidenceDescription(), hash(input.workerKey()));
        JsonNode response = json.valueToTree(Map.of("agentId", agentId, "actorId", actorId));
        remember(actor.actorId(), op, key, hash, agentId, response);
        return response;
    }

    @Transactional
    public JsonNode confidencePolicy(ActorContext actor, UUID roundId, ConfidenceInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> r = one("SELECT r.*, p.owner_actor_id FROM publication_round r JOIN project p ON p.id=r.project_id WHERE r.id=? FOR UPDATE", roundId);
        if (!actor.actorId().equals(r.get("owner_actor_id"))) throw error(404,"RESOURCE_NOT_FOUND","轮次不存在");
        if (!"AI_AGENT".equals(r.get("mode"))) throw error(422,"INVALID_EXECUTION_MODE","仅 AI 轮次可设置置信度分档");
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE round_id=?", roundId);
        if (!TERMINAL.contains(e.get("status"))) throw error(409,"AI_RESULTS_NOT_READY","执行尚未终结");
        Map<String,Object> snap = one("SELECT * FROM ai_result_snapshot WHERE execution_id=?", e.get("id"));
        if (!input.snapshotId().equals(snap.get("id"))) throw error(409,"SNAPSHOT_MISMATCH","快照不属于该执行");
        if (input.lowerThreshold() == null || input.upperThreshold() == null
                || !(input.lowerThreshold() >= 0 && input.lowerThreshold() < input.upperThreshold() && input.upperThreshold() <= 1))
            throw error(422,"INVALID_THRESHOLDS","阈值需满足 0≤L<H≤1");
        String op = "POST:/rounds/" + roundId + "/confidence-policies", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO confidence_policy(id,project_id,round_id,execution_id,snapshot_id,model_version,lower_threshold,upper_threshold,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, r.get("project_id"), roundId, e.get("id"), snap.get("id"), e.get("model_version"),
                input.lowerThreshold(), input.upperThreshold(), actor.actorId());
        JsonNode response = json.valueToTree(Map.of("policyId", id, "roundId", roundId, "executionId", e.get("id"),
                "snapshotId", snap.get("id"), "modelVersion", e.get("model_version"),
                "lowerThreshold", input.lowerThreshold(), "upperThreshold", input.upperThreshold(), "revision", 1));
        remember(actor.actorId(), op, key, hash, id, response);
        return response;
    }

    @Transactional
    public JsonNode confidencePolicies(ActorContext actor, UUID roundId) {
        enabled(actor.actorId());
        Map<String,Object> r = one("SELECT r.*, p.owner_actor_id FROM publication_round r JOIN project p ON p.id=r.project_id WHERE r.id=?", roundId);
        if (!actor.actorId().equals(r.get("owner_actor_id"))) throw error(404,"RESOURCE_NOT_FOUND","轮次不存在");
        return json.valueToTree(db.queryForList("""
                SELECT id AS "policyId", execution_id AS "executionId", snapshot_id AS "snapshotId", model_version AS "modelVersion",
                  lower_threshold AS "lowerThreshold", upper_threshold AS "upperThreshold", revision, created_at::text AS "createdAt"
                FROM confidence_policy WHERE round_id=? ORDER BY created_at DESC
                """, roundId));
    }

    @Transactional
    public JsonNode createExecution(ActorContext actor, UUID project, CreateInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> p = project(project, true);
        owner(actor, p);
        writable(p);
        String op = "POST:/projects/" + project + "/ai-executions", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        Map<String,Object> spec = one("SELECT * FROM spec_version WHERE id=? AND project_id=?", input.specVersionId(), project);
        if (!Objects.equals(spec.get("task_type"), p.get("task_type"))) throw error(409,"SPEC_MISMATCH","规范任务类型不匹配");
        Map<String,Object> agent = one("SELECT * FROM ai_agent_config WHERE id=? AND enabled=TRUE", input.agentId());
        if (!spec.get("task_type").equals(agent.get("task_type"))) throw error(422,"AGENT_INCOMPATIBLE","Agent 不支持该项目任务类型");
        List<Map<String,Object>> samples = selectionSamples(project, input.samples());
        List<UUID> sampleIds = new ArrayList<>();
        for (var s : samples) sampleIds.add((UUID) s.get("id"));
        checkClaimConflict(project, sampleIds);
        int roundNo = db.queryForObject("SELECT COALESCE(MAX(round_no),0)+1 FROM publication_round WHERE project_id=?", Integer.class, project);
        UUID roundId = UUID.randomUUID();
        db.update("""
                INSERT INTO publication_round(id,project_id,spec_version_id,package_id,round_no,mode,instructions,status)
                VALUES (?,?,?,NULL,?,'AI_AGENT',?,'OPEN')
                """, roundId, project, input.specVersionId(), roundNo, input.instructions());
        int ordinal = 1;
        for (var s : samples) {
            UUID sampleId = (UUID) s.get("id");
            db.update("INSERT INTO round_item(round_id,project_id,spec_version_id,sample_id,ordinal,reference_result_id) VALUES (?,?,?,?,?,?)",
                    roundId, project, input.specVersionId(), sampleId, ordinal++, s.get("referenceResultId"));
            db.update("INSERT INTO sample_work_claim(project_id,sample_id,round_id) VALUES (?,?,?)", project, sampleId, roundId);
        }
        UUID executionId = UUID.randomUUID();
        db.update("""
                INSERT INTO ai_execution(id,round_id,project_id,spec_version_id,agent_actor_id,model_version,status,pending_count)
                VALUES (?,?,?,?,?,?,'QUEUED',?)
                """, executionId, roundId, project, input.specVersionId(), agent.get("actor_id"), agent.get("model_version"), samples.size());
        for (var s : samples)
            db.update("INSERT INTO ai_item_output(execution_id,round_id,sample_id,status) VALUES (?,?,?,'PENDING')", executionId, roundId, s.get("id"));
        outcomes.resetAcceptedForRepublish(project, input.specVersionId(), sampleIds, actor.actorId());
        JsonNode response = execView(executionId);
        remember(actor.actorId(), op, key, hash, executionId, response);
        return response;
    }

    @Transactional
    public JsonNode execution(ActorContext actor, UUID id) {
        owner(actor, id);
        return execView(id);
    }

    @Transactional
    public JsonNode results(ActorContext actor, UUID id, int limit, String cursor) {
        owner(actor, id);
        Map<String,Object> execution = one("SELECT status FROM ai_execution WHERE id=?", id);
        if (limit < 1 || limit > 100) throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after = cursor(cursor);
        boolean terminal = TERMINAL.contains(execution.get("status"));
        UUID snapshotId = terminal ? (UUID) db.queryForObject(
                "SELECT id FROM ai_result_snapshot WHERE execution_id=?", UUID.class, id) : null;
        var rows = db.queryForList("""
                SELECT o.sample_id AS "sampleId",
                  CASE WHEN ? THEN si.outcome ELSE o.status END AS status,
                  CASE WHEN ? THEN si.result::text ELSE o.result::text END AS result,
                  CASE WHEN ? THEN si.confidence ELSE o.confidence END AS confidence,
                  CASE WHEN ? THEN si.error_code ELSE o.error_code END AS "errorCode",
                  o.attempt_count AS "attemptCount", si.result_id AS "resultId"
                FROM ai_item_output o
                LEFT JOIN ai_result_snapshot_item si ON si.snapshot_id=? AND si.execution_id=o.execution_id AND si.sample_id=o.sample_id
                WHERE o.execution_id=? AND o.sample_id>? ORDER BY o.sample_id LIMIT ?
                """, terminal, terminal, terminal, terminal, snapshotId, id, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        for (var item : items) {
            if (item.get("result") != null) item.put("result", read((String) item.get("result")));
            item.put("snapshotId", snapshotId);
            item.put("resultId", terminal && "SUCCEEDED".equals(item.get("status")) ? item.get("resultId") : null);
            item.put("provisional", !terminal);
            item.put("reviewable", terminal && "SUCCEEDED".equals(item.get("status")));
            if ("FAILED".equals(item.get("status")))
                item.put("errorMessage", "AI 处理失败，请根据错误码排查或联系管理员。");
            else
                item.remove("errorMessage");
        }
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("snapshotId", snapshotId);
        response.put("provisional", !terminal);
        response.put("items", items);
        response.put("nextCursor", more ? items.get(items.size() - 1).get("sampleId").toString() : null);
        return json.valueToTree(response);
    }

    @Transactional
    public JsonNode cancel(ActorContext actor, UUID id, long baseRevision, String reason, String key) {
        enabled(actor.actorId());
        Map<String,Object> e = one("SELECT e.*, p.owner_actor_id FROM ai_execution e JOIN project p ON p.id=e.project_id WHERE e.id=? FOR UPDATE", id);
        owner(actor, e);
        String op = "POST:/ai-executions/" + id + "/cancel", hash = hash(baseRevision + ":" + reason);
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        if (((Number) e.get("revision")).longValue() != baseRevision)
            throw error(409,"REVISION_CONFLICT","执行状态已变化，请重新读取");
        if (TERMINAL.contains(e.get("status"))) throw error(409,"EXECUTION_CLOSED","执行已结束");
        db.update("UPDATE ai_execution SET generation=generation+1 WHERE id=?", id);
        db.update("UPDATE ai_item_output SET status='CANCELLED' WHERE execution_id=? AND status='PENDING'", id);
        db.update("UPDATE publication_round SET status='CANCELLED', revision=revision+1 WHERE id=?", e.get("round_id"));
        db.update("DELETE FROM sample_work_claim WHERE round_id=?", e.get("round_id"));
        finalizeSnapshot(id, "CANCELLED");
        JsonNode response = execView(id);
        remember(actor.actorId(), op, key, hash, id, response);
        return response;
    }

    // ---------- 内部工作端接口（D 调用） ----------

    @Transactional
    public JsonNode claim(String workerKey) {
        Map<String,Object> agent = agent(workerKey);
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT id FROM ai_execution
                WHERE agent_actor_id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until < CURRENT_TIMESTAMP))
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, agent.get("actor_id"));
        if (rows.isEmpty()) return null;
        UUID id = (UUID) rows.get(0).get("id");
        db.update("""
                UPDATE ai_execution SET status='RUNNING', generation=generation+1, revision=revision+1, lease_owner=?,
                  lease_until=CURRENT_TIMESTAMP + interval '120 seconds', started_at=COALESCE(started_at,CURRENT_TIMESTAMP)
                WHERE id=?
                """, agent.get("actor_id"), id);
        return claimView(id);
    }

    @Transactional
    public JsonNode heartbeat(String workerKey, UUID id, long generation) {
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR UPDATE", id);
        checkLease(agent, e, generation);
        db.update("UPDATE ai_execution SET lease_until=CURRENT_TIMESTAMP + interval '120 seconds' WHERE id=?", id);
        Map<String,Object> updated = one("SELECT lease_until::text AS \"leaseUntil\" FROM ai_execution WHERE id=?", id);
        return json.valueToTree(Map.of("executionId", id, "generation", generation, "leaseUntil", updated.get("leaseUntil")));
    }

    @Transactional
    public JsonNode items(String workerKey, UUID id, long generation, int limit, String cursor) {
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR SHARE", id);
        checkLease(agent, e, generation);
        if (limit < 1 || limit > 100) throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after = cursor(cursor);
        var rows = db.queryForList("""
                SELECT ri.sample_id AS "sampleId", s.source_path AS "sourcePath", s.media_type AS "mediaType",
                  o.status AS "outputStatus", ri.reference_result_id AS "referenceResultId"
                FROM round_item ri JOIN sample s ON s.id=ri.sample_id
                LEFT JOIN ai_item_output o ON o.execution_id=? AND o.sample_id=ri.sample_id
                WHERE ri.round_id=? AND ri.sample_id>? ORDER BY ri.sample_id LIMIT ?
                """, id, e.get("round_id"), after, limit + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        for (var row : items) {
            row.put("fileUrl", "/internal/labeling/v1/executions/" + id + "/samples/" + row.get("sampleId") + "/file");
            Object ref = row.remove("referenceResultId");
            if (ref != null) {
                row.put("referenceResult", db.query("""
                        SELECT result_format, result::text AS result FROM submission_item WHERE submission_id=? AND sample_id=?
                        """, rs -> rs.next()
                            ? Map.of("resultFormat", rs.getString(1), "result", read(rs.getString(2)), "sourceKind", "HUMAN")
                            : null, (UUID) ref, (UUID) row.get("sampleId")));
            } else {
                row.put("referenceResult", null);
            }
        }
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("nextCursor", more ? items.get(items.size() - 1).get("sampleId").toString() : null);
        return json.valueToTree(response);
    }

    @Transactional
    public SampleFile file(String workerKey, UUID id, UUID sampleId, long generation) {
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR SHARE", id);
        checkLease(agent, e, generation);
        Map<String,Object> row = one("""
                SELECT s.batch_id, s.media_type, s.metadata->>'sizeBytes' AS size FROM sample s
                JOIN round_item ri ON ri.sample_id=s.id WHERE ri.round_id=? AND s.id=?
                """, e.get("round_id"), sampleId);
        try {
            var path = storage.sample((UUID) row.get("batch_id"), sampleId);
            long size = Long.parseLong((String) row.get("size"));
            if (Files.size(path) != size) throw new java.io.IOException("图片大小不一致");
            return new SampleFile(new FileSystemResource(path), (String) row.get("media_type"), size);
        } catch (java.io.IOException exception) {
            throw error(503, "STORAGE_UNAVAILABLE", "图片存储不可用");
        }
    }

    @Transactional
    public JsonNode output(String workerKey, UUID id, UUID sampleId, long generation, OutputInput input) {
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR UPDATE", id);
        checkLease(agent, e, generation);
        one("SELECT sample_id FROM round_item WHERE round_id=? AND sample_id=?", e.get("round_id"), sampleId);
        if (input == null) throw error(422,"INVALID_REQUEST","输出内容不能为空");

        if ("SUCCEEDED".equals(input.status())) {
            if (input.errorCode() != null || input.errorMessage() != null)
                throw error(422,"INVALID_REQUEST","成功结果不能包含错误信息");
            validateResult(e, input.result());
            Double confidence = input.confidence();
            if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1))
                throw error(422,"INVALID_CONFIDENCE","置信度必须是 [0,1] 内的有限数值");
        } else if ("FAILED".equals(input.status())) {
            if (input.result() != null || input.confidence() != null)
                throw error(422,"INVALID_REQUEST","失败结果不能包含标注内容或置信度");
            validateFailureFields(input.errorCode(), input.errorMessage());
        } else {
            throw error(422,"INVALID_REQUEST","输出状态仅支持 SUCCEEDED 或 FAILED");
        }

        Map<String,Object> current = one("""
                SELECT status, result::text AS result, confidence, error_code, error_message
                FROM ai_item_output WHERE execution_id=? AND sample_id=?
                """, id, sampleId);
        if (!"PENDING".equals(current.get("status"))) {
            Double currentConfidence = current.get("confidence") == null
                    ? null : ((Number) current.get("confidence")).doubleValue();
            boolean same = input.status().equals(current.get("status"))
                    && Objects.equals(input.result(), current.get("result") == null ? null : read((String) current.get("result")))
                    && Objects.equals(input.confidence(), currentConfidence)
                    && Objects.equals(input.errorCode(), current.get("error_code"))
                    && Objects.equals(safeErrorMessage(input.errorMessage()), current.get("error_message"));
            if (same) return json.valueToTree(Map.of("sampleId", sampleId, "outputStatus", input.status()));
            throw error(409,"OUTPUT_CONFLICT","该样本已有不同的终结结果，不能覆盖");
        }

        if ("SUCCEEDED".equals(input.status())) {
            db.update("""
                    UPDATE ai_item_output SET status='SUCCEEDED', result=?::jsonb, confidence=?, generation=?,
                      attempt_count=attempt_count+1, error_code=NULL, error_message=NULL
                    WHERE execution_id=? AND sample_id=? AND status='PENDING'
                    """, write(input.result()), input.confidence(), generation, id, sampleId);
            int updated = db.update("UPDATE ai_execution SET success_count=success_count+1, pending_count=pending_count-1, revision=revision+1 WHERE id=? AND pending_count>0", id);
            if (updated != 1) throw error(409,"OUTPUT_CONFLICT","AI执行计数已变化，请重新读取");
        } else {
            db.update("""
                    UPDATE ai_item_output SET status='FAILED', error_code=?, error_message=?, generation=?,
                      attempt_count=attempt_count+1, result=NULL, confidence=NULL
                    WHERE execution_id=? AND sample_id=? AND status='PENDING'
                    """, input.errorCode(), safeErrorMessage(input.errorMessage()), generation, id, sampleId);
            int updated = db.update("UPDATE ai_execution SET failed_count=failed_count+1, pending_count=pending_count-1, revision=revision+1 WHERE id=? AND pending_count>0", id);
            if (updated != 1) throw error(409,"OUTPUT_CONFLICT","AI执行计数已变化，请重新读取");
        }
        return json.valueToTree(Map.of("sampleId", sampleId, "outputStatus", input.status()));
    }

    @Transactional
    public JsonNode finish(String workerKey, UUID id, long generation, String key) {
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR UPDATE", id);
        checkAgent(agent, e);
        UUID agentId = (UUID) agent.get("actor_id");
        String op = "POST:/executions/" + id + "/finish", hash = hash(generation + ":finish");
        JsonNode previous = replay(agentId, op, key, hash);
        if (previous != null) return previous;
        checkLease(agent, e, generation);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_item_output WHERE execution_id=? AND status='PENDING')", Boolean.class, id)))
            throw error(409,"EXECUTION_INCOMPLETE","仍有待处理样本");
        String status = computedStatus(id);
        finalizeSnapshot(id, status);
        JsonNode response = execView(id);
        remember(agentId, op, key, hash, id, response);
        return response;
    }

    @Transactional
    public JsonNode fail(String workerKey, UUID id, long generation, String errorCode, String errorMessage, String key) {
        validateFailureFields(errorCode, errorMessage);
        Map<String,Object> agent = agent(workerKey);
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=? FOR UPDATE", id);
        checkAgent(agent, e);
        UUID agentId = (UUID) agent.get("actor_id");
        String op = "POST:/executions/" + id + "/fail", hash = hash(write(new Object[] {generation, errorCode, errorMessage}));
        JsonNode previous = replay(agentId, op, key, hash);
        if (previous != null) return previous;
        checkLease(agent, e, generation);
        db.update("UPDATE ai_item_output SET status='FAILED', error_code=?, error_message=? WHERE execution_id=? AND status='PENDING'", errorCode, safeErrorMessage(errorMessage), id);
        String status = computedStatus(id);
        finalizeSnapshot(id, status);
        JsonNode response = execView(id);
        remember(agentId, op, key, hash, id, response);
        return response;
    }

    // ---------- 内部辅助 ----------

    private Map<String,Object> agent(String workerKey) {
        if (workerKey == null || workerKey.isBlank()) throw error(401,"UNAUTHENTICATED","缺少工作端凭据");
        var rows = db.queryForList("SELECT * FROM ai_agent_config WHERE worker_key_hash=? AND enabled=TRUE", hash(workerKey));
        if (rows.isEmpty()) throw error(401,"UNAUTHENTICATED","工作端凭据无效");
        return rows.get(0);
    }

    private void checkLease(Map<String,Object> agent, Map<String,Object> e, long generation) {
        checkAgent(agent, e);
        if (!"RUNNING".equals(e.get("status"))) throw error(409,"EXECUTION_LEASE_LOST","执行不在运行中");
        if (((Number) e.get("generation")).longValue() != generation) throw error(409,"EXECUTION_LEASE_LOST","generation 已失效");
        java.sql.Timestamp until = (java.sql.Timestamp) e.get("lease_until");
        if (until == null || until.toInstant().isBefore(Instant.now())) throw error(409,"EXECUTION_LEASE_LOST","租约已过期");
    }

    private void checkAgent(Map<String,Object> agent, Map<String,Object> e) {
        if (!agent.get("actor_id").equals(e.get("agent_actor_id"))) throw error(403,"FORBIDDEN","无权访问该执行");
    }

    private void validateFailureFields(String errorCode, String errorMessage) {
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 120)
            throw error(422,"INVALID_REQUEST","失败结果必须包含不超过120个字符的错误码");
        if (errorMessage != null && errorMessage.length() > 2000)
            throw error(422,"INVALID_REQUEST","错误信息不能超过2000个字符");
    }

    private String safeErrorMessage(String ignoredMessage) {
        return "AI 处理失败，请根据错误码排查或联系管理员。";
    }

    private List<Map<String,Object>> selectionSamples(UUID project, List<SelectionSample> input) {
        if (input == null || input.isEmpty()) throw error(422,"INVALID_REQUEST","样本范围不能为空");
        if (input.size() > maxSamples) throw error(422,"SAMPLE_LIMIT","样本范围超过同步执行上限");
        Map<UUID,UUID> refs = new LinkedHashMap<>();
        for (var s : input) {
            if (s.sampleId() == null) throw error(422,"INVALID_REQUEST","样本ID不能为空");
            if (refs.containsKey(s.sampleId())) throw error(422,"INVALID_REQUEST","样本范围不能含重复样本");
            refs.put(s.sampleId(), s.referenceResultId());
        }
        List<UUID> ids = new ArrayList<>(refs.keySet());
        Collections.sort(ids);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (UUID id : ids) {
            one("SELECT id FROM sample WHERE id=? AND project_id=?", id, project);
            if (refs.get(id) != null) {
                Boolean ok = db.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM submission_item si JOIN submission s ON s.id=si.submission_id
                        WHERE si.submission_id=? AND si.sample_id=? AND s.project_id=?)
                        """, Boolean.class, refs.get(id), id, project);
                if (!Boolean.TRUE.equals(ok)) throw error(422,"INVALID_REFERENCE","参考结果不属于该样本或项目");
            }
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("referenceResultId", refs.get(id));
            rows.add(row);
        }
        return rows;
    }

    private void checkClaimConflict(UUID project, List<UUID> sampleIds) {
        Set<UUID> wanted = new HashSet<>(sampleIds);
        var claimed = db.queryForList("""
                SELECT c.sample_id FROM sample_work_claim c JOIN publication_round r ON r.id=c.round_id
                WHERE r.status='OPEN' AND c.project_id=?
                """, project);
        for (var row : claimed) if (wanted.contains((UUID) row.get("sample_id"))) throw error(409,"SAMPLE_BUSY","所选样本存在进行中的轮次");
    }

    private void validateResult(Map<String,Object> e, JsonNode result) {
        Map<String,Object> spec = one("SELECT task_type, labels::text AS labels FROM spec_version WHERE id=?", e.get("spec_version_id"));
        if (!"IMAGE_CLASSIFICATION".equals(spec.get("task_type"))) throw error(422,"UNSUPPORTED_FORMAT","首期仅支持图片单标签分类结果");
        if (result == null || !result.isObject() || result.size() != 1 || !result.has("labelId"))
            throw error(422,"INVALID_RESULT","结果必须为 labelId 对象");
        JsonNode label = result.get("labelId");
        if (label == null || !label.isTextual() || label.asText().isBlank()) throw error(422,"INVALID_RESULT","请选择一个标签");
        for (var allowed : read((String) spec.get("labels")))
            if (label.asText().equals(allowed.path("id").asText())) return;
        throw error(422,"INVALID_RESULT","标签不属于本轮规范");
    }

    private String computedStatus(UUID id) {
        long success = count(id, "SUCCEEDED"), failed = count(id, "FAILED"), total = db.query(
                "SELECT count(*) FROM ai_item_output WHERE execution_id=?", rs -> rs.next() ? rs.getLong(1) : 0L, id);
        if (success == total) return "SUCCEEDED";
        if (success > 0) return "PARTIAL";
        if (failed > 0) return "FAILED";
        return "FAILED";
    }

    private long count(UUID id, String status) {
        return db.query("SELECT count(*) FROM ai_item_output WHERE execution_id=? AND status=?", rs -> rs.next() ? rs.getLong(1) : 0L, id, status);
    }

    private void finalizeSnapshot(UUID id, String status) {
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=?", id);
        var rows = db.queryForList("SELECT sample_id, status AS outcome, result::text AS result, confidence, error_code, error_message FROM ai_item_output WHERE execution_id=? ORDER BY sample_id", id);
        long success = 0, failed = 0, cancelled = 0;
        for (var r : rows) {
            String s = (String) r.get("outcome");
            if ("SUCCEEDED".equals(s)) success++;
            else if ("FAILED".equals(s)) failed++;
            else if ("CANCELLED".equals(s)) cancelled++;
        }
        String inputHash = hash(write(db.queryForList("SELECT sample_id FROM round_item WHERE round_id=? ORDER BY sample_id", e.get("round_id"))));
        UUID snap = UUID.randomUUID();
        db.update("INSERT INTO ai_result_snapshot(id,execution_id,model_version,input_hash,success_count,failed_count) VALUES (?,?,?,?,?,?)",
                snap, id, e.get("model_version"), inputHash, success, failed);
        for (var r : rows) {
            UUID resultId = "SUCCEEDED".equals(r.get("outcome")) ? UUID.randomUUID() : null;
            String errorMessage = "FAILED".equals(r.get("outcome")) ? safeErrorMessage((String) r.get("error_message")) : null;
            db.update("INSERT INTO ai_result_snapshot_item(snapshot_id,execution_id,sample_id,outcome,result,confidence,error_code,result_id,error_message) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
                    snap, id, r.get("sample_id"), r.get("outcome"), r.get("result"), r.get("confidence"), r.get("error_code"), resultId, errorMessage);
        }
        db.update("UPDATE ai_execution SET status=?, success_count=?, failed_count=?, pending_count=0, cancelled_count=?, revision=revision+1, ended_at=CURRENT_TIMESTAMP WHERE id=?",
                status, success, failed, cancelled, id);
        db.update("UPDATE publication_round SET status=?, revision=revision+1 WHERE id=?",
                "CANCELLED".equals(status) ? "CANCELLED" : "CLOSED", e.get("round_id"));
        db.update("DELETE FROM sample_work_claim WHERE round_id=?", e.get("round_id"));
    }

    private JsonNode execView(UUID id) {
        Map<String,Object> e = one("SELECT * FROM ai_execution WHERE id=?", id);
        long sampleCount = db.query("SELECT count(*) FROM round_item WHERE round_id=?", rs -> rs.next() ? rs.getLong(1) : 0L, e.get("round_id"));
        boolean terminal = TERMINAL.contains(e.get("status"));
        UUID snapshotId = terminal ? (UUID) db.queryForObject(
                "SELECT id FROM ai_result_snapshot WHERE execution_id=?", UUID.class, id) : null;
        var errors = db.queryForList("""
                SELECT COALESCE(error_code, 'UNKNOWN') AS "errorCode", count(*) AS count
                FROM ai_item_output WHERE execution_id=? AND status='FAILED'
                GROUP BY error_code ORDER BY error_code NULLS LAST
                """, id);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("executionId", id);
        out.put("roundId", e.get("round_id"));
        out.put("projectId", e.get("project_id"));
        out.put("specVersionId", e.get("spec_version_id"));
        out.put("agentId", e.get("agent_actor_id"));
        out.put("modelVersion", e.get("model_version"));
        out.put("status", e.get("status"));
        out.put("revision", e.get("revision"));
        out.put("generation", e.get("generation"));
        out.put("sampleCount", sampleCount);
        out.put("successCount", e.get("success_count"));
        out.put("failedCount", e.get("failed_count"));
        out.put("pendingCount", e.get("pending_count"));
        out.put("cancelledCount", e.get("cancelled_count"));
        out.put("resultSnapshotId", snapshotId);
        out.put("errorSummary", Map.of("failedCount", e.get("failed_count"), "byCode", errors));
        out.put("retryOfExecutionId", e.get("retry_of_execution_id"));
        out.put("createdAt", e.get("created_at"));
        return json.valueToTree(out);
    }

    private JsonNode claimView(UUID id) {
        Map<String,Object> e = one("SELECT e.*, sv.labels::text AS labels FROM ai_execution e JOIN spec_version sv ON sv.id=e.spec_version_id WHERE e.id=?", id);
        Map<String,Object> agent = one("SELECT supports_reference, confidence_description FROM ai_agent_config WHERE actor_id=?", e.get("agent_actor_id"));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("executionId", id);
        out.put("generation", e.get("generation"));
        out.put("leaseUntil", e.get("lease_until"));
        out.put("specVersionId", e.get("spec_version_id"));
        out.put("labels", read((String) e.get("labels")));
        out.put("modelVersion", e.get("model_version"));
        out.put("supportsReference", agent.get("supports_reference"));
        out.put("confidenceDescription", agent.get("confidence_description"));
        return json.valueToTree(out);
    }

    private void owner(ActorContext actor, UUID id) {
        enabled(actor.actorId());
        Map<String,Object> e = one("SELECT e.*, p.owner_actor_id FROM ai_execution e JOIN project p ON p.id=e.project_id WHERE e.id=?", id);
        owner(actor, e);
    }

    private void owner(ActorContext actor, Map<String,Object> e) {
        if (!actor.actorId().equals(e.get("owner_actor_id"))) throw error(404,"RESOURCE_NOT_FOUND","资源不存在");
    }

    private void writable(Map<String,Object> project) {
        if (!List.of("DRAFT", "OPEN").contains(project.get("status")))
            throw error(409,"PROJECT_CLOSED","项目当前不可发布 AI 执行");
    }

    private Map<String,Object> project(UUID id, boolean exclusive) {
        return one("SELECT * FROM project WHERE id=? FOR " + (exclusive ? "UPDATE" : "SHARE"), id);
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

    public record CreateInput(UUID specVersionId, UUID agentId, List<SelectionSample> samples, String instructions) {}
    public record SelectionSample(@NotNull UUID sampleId, UUID referenceResultId) {}
    public record OutputInput(String status, JsonNode result, Double confidence, String errorCode, String errorMessage) {}
    public record RegisterInput(@NotNull String taskType, @NotNull String displayName, @NotNull String modelVersion,
            JsonNode capabilities, JsonNode supportedLabels, boolean supportsReference, String confidenceDescription, @NotNull String workerKey) {}
    public record ConfidenceInput(@NotNull UUID snapshotId, @NotNull Double lowerThreshold, @NotNull Double upperThreshold) {}
}
