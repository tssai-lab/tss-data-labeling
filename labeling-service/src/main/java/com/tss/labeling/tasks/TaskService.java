package com.tss.labeling.tasks;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

@Service
public class TaskService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportStorage storage;
    private final OutcomeService outcomes;
    private final com.tss.labeling.review.ReferenceService references;
    private final com.tss.labeling.review.ResultScopeService scopes;
    private final org.springframework.beans.factory.ObjectProvider<com.tss.labeling.imports.NativeTaskService> nativeInputs;
    private final com.tss.labeling.plugins.TaskPluginRegistry plugins;
    private final com.tss.labeling.annotators.AnnotatorProviderRegistry annotators;
    private final org.springframework.beans.factory.ObjectProvider<com.tss.labeling.ai.AiService> aiServices;
    private final int maxSamples;
    private final int maxPackageSize;
    private final int maxAnnotators;

    public TaskService(JdbcTemplate db, ObjectMapper json, ImportStorage storage, OutcomeService outcomes,
            com.tss.labeling.review.ResultScopeService scopes,
            com.tss.labeling.review.ReferenceService references,
            org.springframework.beans.factory.ObjectProvider<com.tss.labeling.imports.NativeTaskService> nativeInputs,
            com.tss.labeling.plugins.TaskPluginRegistry plugins,
            com.tss.labeling.annotators.AnnotatorProviderRegistry annotators,
            org.springframework.beans.factory.ObjectProvider<com.tss.labeling.ai.AiService> aiServices,
            @Value("${labeling.tasks.max-samples:10000}") int maxSamples,
            @Value("${labeling.tasks.max-package-size:1000}") int maxPackageSize,
            @Value("${labeling.tasks.max-annotators:10}") int maxAnnotators) {
        this.db = db; this.json = json; this.storage = storage;
        this.outcomes = outcomes;
        this.scopes = scopes;
        this.references = references;
        this.nativeInputs = nativeInputs; this.plugins = plugins; this.annotators = annotators;
        this.aiServices = aiServices;
        this.maxSamples = maxSamples; this.maxPackageSize = maxPackageSize; this.maxAnnotators = maxAnnotators;
    }

    @Transactional
    public JsonNode createPlan(ActorContext actor, UUID project, PlanInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> p = project(project, true);
        owner(actor, p); writable(p);
        if ((input.batchId() != null ? 1 : 0)
                        + (input.samples() != null ? 1 : 0)
                        + (input.selectionId() != null ? 1 : 0)
                != 1)
            throw error(400, "INVALID_REQUEST", "batchId、samples、selectionId 必须三选一");
        if (input.referenceMode() != null
                && !Set.of("NONE", "SELECTED").contains(input.referenceMode()))
            throw error(422, "INVALID_REFERENCE_MODE", "referenceMode 仅支持 NONE 或 SELECTED");
        if ("SELECTED".equals(input.referenceMode()) && input.selectionId() == null)
            throw error(422, "INVALID_REFERENCE_MODE", "SELECTED 参考模式需要 selectionId");
        ResolvedRoster roster = resolveRoster(input);
        String executionMode = roster.executionMode();
        List<UUID> aiAgentIds = roster.aiAgentIds();
        int size = input.packageSize() == null ? 10 : input.packageSize();
        int people = roster.humanCount();
        if (size < 1 || size > maxPackageSize)
            throw error(400, "INVALID_REQUEST", "包大小或标注人数超出限制");
        String op = "POST:/projects/" + project + "/package-plans", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        Map<String,Object> spec = one("SELECT * FROM spec_version WHERE id = ? AND project_id = ?", input.specVersionId(), project);
        plugins.writable(plugins.frozen(spec));
        if (!Objects.equals(spec.get("task_type"), p.get("task_type"))) throw error(409,"SPEC_MISMATCH","规范任务类型不匹配");
        List<Map<String,Object>> rows;
        String selectionJson = null;
        if (input.batchId() != null) {
            Map<String,Object> batch = one("SELECT * FROM import_batch WHERE id = ? AND project_id = ? FOR SHARE", input.batchId(), project);
            if (!"READY".equals(batch.get("status"))) throw error(409,"BATCH_NOT_READY","数据尚未导入完成");
            rows = samples(input.batchId());
        } else {
            List<SelectionSample> chosen = input.samples();
            if (input.selectionId() != null) {
                var scope = scopes.selection(actor, project, input.selectionId(), "PUBLISH", null);
                if (!scope.specVersionId().equals(input.specVersionId()))
                    throw error(409, "SPEC_MISMATCH", "范围规范不一致");
                scopes.validate(project, input.specVersionId(), scope.items(), false, true);
                chosen =
                        scope.items().stream()
                                .map(
                                        i ->
                                                new SelectionSample(
                                                        i.sampleId(),
                                                        null,
                                                        "SELECTED".equals(input.referenceMode())
                                                                ? i.resultId()
                                                                : null))
                                .toList();
            }
            rows = selectionSamples(project, input.specVersionId(), chosen);
            selectionJson = write(rows);
        }
        for (UUID agentId : aiAgentIds) {
            Map<String, Object> agent = one(
                    "SELECT task_type,enabled,supports_reference FROM ai_agent_config WHERE id=?",
                    agentId);
            if (!Boolean.TRUE.equals(agent.get("enabled"))
                    || !Objects.equals(agent.get("task_type"), p.get("task_type")))
                throw error(422, "AGENT_INCOMPATIBLE", "所选 AI 标注员未启用或不支持当前任务类型");
            if (!Boolean.TRUE.equals(agent.get("supports_reference"))
                    && rows.stream().anyMatch(row -> row.get("referenceResultId") != null
                            || row.get("referenceCandidateId") != null))
                throw error(422, "REFERENCE_UNSUPPORTED", "所选 AI 标注员不支持本轮参考答案");
        }
        scopes.checkReopen(
                project,
                input.specVersionId(),
                rows.stream().map(x -> (UUID) x.get("id")).toList(),
                input.reopenAccepted());
        UUID plan = UUID.randomUUID();
        db.update(
                """
INSERT INTO package_plan(id, project_id, batch_id, spec_version_id, package_size,
  annotators_per_sample, sample_count, selection_hash, selection_json, instructions, execution_mode, ai_agent_ids,
  annotator_roster,assignment_policy,workflow_snapshot)
VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
""", plan, project, input.batchId(), input.specVersionId(), size, people, rows.size(),
                selection(rows), selectionJson, input.instructions(), executionMode, write(aiAgentIds),
                write(roster.entries()),
                write(Map.of("strategy", "OPEN_POOL", "packageSize", size, "humanSeatsPerSample", people)),
                write(workflowSnapshot(executionMode)));
        db.update(
                "UPDATE package_plan SET reopen_accepted=? WHERE id=?",
                !Boolean.FALSE.equals(input.reopenAccepted()),
                plan);
        if (input.selectionId() != null)
            db.update(
                    "UPDATE package_plan SET selection_id=? WHERE id=?", input.selectionId(), plan);
        JsonNode response = planView(plan);
        remember(actor.actorId(), op, key, hash, plan, response);
        return response;
    }

    @Transactional
    public JsonNode publish(ActorContext actor, UUID planId, String expectedHash, String key) {
        return publishForGroup(actor, planId, expectedHash, key, null);
    }

    @Transactional
    public JsonNode publishForGroup(ActorContext actor, UUID planId, String expectedHash, String key, UUID groupId) {
        enabled(actor.actorId());
        Map<String,Object> plan = one("SELECT * FROM package_plan WHERE id = ?", planId);
        UUID projectId = (UUID) plan.get("project_id");
        Map<String,Object> p = project(projectId, true);
        owner(actor,p); writable(p);
        String op = "POST:/package-plans/" + planId + "/publish", hash = hash(expectedHash);
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if (previous != null) return previous;
        plan = one("SELECT * FROM package_plan WHERE id = ? FOR UPDATE",planId);
        if (!expectedHash.equals(plan.get("selection_hash"))) throw error(409,"SELECTION_CHANGED","样本摘要不匹配");
        if (!"PUBLISHED".equals(plan.get("status"))) {
            if (plan.get("selection_id") != null) {
                var scope =
                        scopes.selection(
                                actor, projectId, (UUID) plan.get("selection_id"), "PUBLISH", null);
                scopes.validate(
                        projectId, (UUID) plan.get("spec_version_id"), scope.items(), false, true);
            }
            List<Map<String,Object>> samples = planSamples(plan);
            if (!selection(samples).equals(expectedHash)) throw error(409,"SELECTION_CHANGED","样本集合已改变");
            List<UUID> sampleIds = new ArrayList<>();
            for (var s : samples) sampleIds.add((UUID) s.get("id"));
            nativeInputs.getObject().validateForPublication(
                    projectId, (UUID) plan.get("spec_version_id"), sampleIds);
            scopes.checkReopen(
                    projectId,
                    (UUID) plan.get("spec_version_id"),
                    sampleIds,
                    (Boolean) plan.get("reopen_accepted"));
            checkClaimConflict(projectId, sampleIds, groupId);
            int size = ((Number)plan.get("package_size")).intValue(), people = ((Number)plan.get("annotators_per_sample")).intValue();
            String executionMode = Objects.toString(plan.get("execution_mode"), "HUMAN_POOL");
            JsonNode aiIdsJson = read(Objects.toString(plan.get("ai_agent_ids"), "[]"));
            List<UUID> aiAgentIds = new ArrayList<>();
            aiIdsJson.forEach(value -> aiAgentIds.add(UUID.fromString(value.asText())));
            int nextAiRoundNo = db.queryForObject(
                    "SELECT COALESCE(MAX(round_no),0)+1 FROM publication_round WHERE project_id=? AND package_id IS NULL",
                    Integer.class, projectId);
            // ponytail: 有界同步发布，超过样本上限需拆分批次；大规模发布再引入后台任务。
            for (int start=0, sequence=1; start<samples.size(); start+=size, sequence++) {
                int count = Math.min(size, samples.size()-start);
                UUID pkg = "AI_AGENT".equals(executionMode) ? null : UUID.randomUUID();
                if (pkg != null)
                    db.update(
                            "INSERT INTO task_package(id,plan_id,sequence_no,sample_count,required_count)"
                                    + " VALUES (?,?,?,?,?)",pkg,planId,sequence,count,people);
                UUID roundId = UUID.randomUUID();
                db.update(
                        """
INSERT INTO publication_round(id,project_id,spec_version_id,package_id,round_no,mode,instructions,status,package_plan_id,publication_group_id)
VALUES (?,?,?,?,?,?,?,'OPEN',?,?)
""", roundId, projectId, plan.get("spec_version_id"), pkg,
                        pkg == null ? nextAiRoundNo++ : 1, executionMode, plan.get("instructions"), planId, groupId);
                for (int i=0;i<count;i++) {
                    Map<String,Object> s = samples.get(start+i);
                    if (pkg != null) db.update(
                            "INSERT INTO"
                                + " package_item(package_id,plan_id,project_id,sample_id,ordinal)"
                                + " VALUES (?,?,?,?,?)",pkg,planId,projectId,s.get("id"),i+1);
                    db.update(
                            "INSERT INTO"
                                + " round_item(round_id,project_id,spec_version_id,sample_id,ordinal,reference_result_id,reference_candidate_id)"
                                + " VALUES (?,?,?,?,?,?,?)",
                            roundId, projectId, plan.get("spec_version_id"), s.get("id"), i+1, s.get("referenceResultId"),
                            s.get("referenceCandidateId"));
                    db.update(
                            "INSERT INTO sample_work_claim(project_id,sample_id,round_id) VALUES"
                                + " (?,?,?)", projectId, s.get("id"), roundId);
                }
                for (int slot=1;pkg != null && slot<=people;slot++) db.update(
                            "INSERT INTO package_slot(id,package_id,round_id,slot_no) VALUES"
                                + " (?,?,?,?)",UUID.randomUUID(),pkg,roundId,slot);
                for (UUID agentId : aiAgentIds)
                    aiServices.getObject().createExecution(
                            actor,
                            projectId,
                            new com.tss.labeling.ai.AiService.CreateInput(
                                    (UUID) plan.get("spec_version_id"),
                                    agentId,
                                    null,
                                    plan.get("instructions") == null ? null : plan.get("instructions").toString(),
                                    null,
                                    null,
                                    (Boolean) plan.get("reopen_accepted"),
                                    roundId),
                            "publish:" + planId + ":" + roundId + ":" + agentId);
            }
            outcomes.resetAcceptedForRepublish(projectId, (UUID) plan.get("spec_version_id"), sampleIds, actor.actorId());
            db.update(
                    "UPDATE package_plan SET"
                        + " status='PUBLISHED',published_at=CURRENT_TIMESTAMP,selection_consumed_at=CURRENT_TIMESTAMP"
                        + " WHERE id=?",planId);
            db.update(
                    "UPDATE project SET"
                        + " status='OPEN',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE"
                        + " id=?",projectId);
        }
        JsonNode response = planView(planId);
        remember(actor.actorId(),op,key,hash,planId,response);
        return response;
    }

    @Transactional
    public JsonNode getPlan(ActorContext actor, UUID plan) {
        enabled(actor.actorId());
        Map<String,Object> row = one("SELECT * FROM package_plan WHERE id=?",plan);
        owner(actor,project((UUID)row.get("project_id"),false));
        return planView(plan);
    }

    @Transactional
    public JsonNode pool(ActorContext actor, int limit, String cursor, String taskType) {
        enabled(actor.actorId()); pageLimit(limit);
        TimeCursor after = timeCursor(cursor);
        String cursorClause = after == null ? "" : " AND (r.created_at,t.id) < (?,?)";
        List<Object> arguments = new ArrayList<>();
        arguments.add(taskType == null ? "" : taskType);
        arguments.add(taskType == null ? "" : taskType);
        if (after != null) {
            arguments.add(Timestamp.from(after.createdAt()));
            arguments.add(after.id());
        }
        arguments.add(actor.actorId());
        arguments.add(limit + 1);
        List<Map<String,Object>> rows = db.queryForList(
                        """
SELECT t.id AS "packageId", p.id AS "projectId", p.name AS "projectName", p.task_type AS "taskType",
  t.sample_count AS "sampleCount", t.required_count AS "requiredCount", r.id AS "roundId", r.created_at AS "publishedAt",
  t.required_count - (SELECT count(*) FROM assignment a WHERE a.round_id=r.id
    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED')) AS "remainingSlots"
FROM task_package t JOIN publication_round r ON r.package_id=t.id AND r.status='OPEN'
JOIN package_plan plan ON plan.id=t.plan_id JOIN project p ON p.id=plan.project_id
WHERE p.status='OPEN' AND p.visibility='PLATFORM' AND t.status='OPEN' AND plan.status='PUBLISHED'
  AND (? = '' OR p.task_type = ?)
""" + cursorClause + """
  AND NOT EXISTS(SELECT 1 FROM assignment a WHERE a.round_id=r.id AND a.actor_id=?
    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
  AND EXISTS(SELECT 1 FROM package_slot s WHERE s.round_id=r.id AND NOT EXISTS
    (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED')))
ORDER BY r.created_at DESC, t.id DESC LIMIT ?
""", arguments.toArray());
        return timePage(rows, limit, "packageId", "publishedAt");
    }

    @Transactional
    public JsonNode claim(ActorContext actor, UUID packageId, String key) {
        enabled(actor.actorId());
        Map<String,Object> mapping = one(
                        "SELECT plan.project_id FROM task_package t JOIN package_plan plan ON"
                            + " plan.id=t.plan_id WHERE t.id=?",packageId);
        open(project((UUID)mapping.get("project_id"),false));
        String op="POST:/packages/"+packageId+"/claims", hash=hash("{}");
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        Map<String,Object> pkg=one("SELECT * FROM task_package WHERE id=? FOR UPDATE",packageId);
        if(!"OPEN".equals(pkg.get("status")))throw error(409,"PACKAGE_CLOSED","任务包不可领取");
        Map<String,Object> round=one(
                        "SELECT * FROM publication_round WHERE package_id=? AND status='OPEN' FOR"
                            + " UPDATE",packageId);
        open(project((UUID) round.get("project_id"), false));
        if(Boolean.TRUE.equals(db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND actor_id=? AND"
                            + " status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))",Boolean.class,round.get("id"),actor.actorId())))
            throw error(409,"ALREADY_CLAIMED","已领取此任务包");
        List<Map<String,Object>> slots=db.queryForList(
                        """
SELECT s.id, s.slot_no FROM package_slot s WHERE s.round_id=? AND NOT EXISTS
  (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
ORDER BY s.slot_no LIMIT 1
""",round.get("id"));
        if(slots.isEmpty())throw error(409,"SLOT_FULL","任务包已领满");
        UUID assignment=UUID.randomUUID();
        db.update(
                "INSERT INTO assignment(id,slot_id,package_id,round_id,actor_id) VALUES"
                    + " (?,?,?,?,?)",assignment,slots.get(0).get("id"),packageId,round.get("id"),actor.actorId());
        JsonNode response=json.valueToTree(Map.of("assignmentId",assignment,"packageId",packageId,"roundId",round.get("id"),"slotNo",slots.get(0).get("slot_no"),"status","CLAIMED","revision",1));
        remember(actor.actorId(),op,key,hash,assignment,response);
        return response;
    }

    @Transactional
    public JsonNode mine(ActorContext actor,int limit,String cursor) {
        enabled(actor.actorId());pageLimit(limit);
        TimeCursor after = timeCursor(cursor);
        String cursorClause = after == null ? "" : " AND (a.claimed_at,a.id) < (?,?)";
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.actorId());
        if (after != null) {
            arguments.add(Timestamp.from(after.createdAt()));
            arguments.add(after.id());
        }
        arguments.add(limit + 1);
        List<Map<String,Object>> rows = db.queryForList(
                        """
SELECT a.id AS "assignmentId", a.package_id AS "packageId", a.round_id AS "roundId", a.status, a.revision, a.claimed_at AS "claimedAt", (SELECT sub.id FROM submission sub WHERE sub.assignment_id=a.id) AS "submissionId",
  s.slot_no AS "slotNo", plan.spec_version_id AS "specVersionId", plan.project_id AS "projectId", p.name AS "projectName"
FROM assignment a JOIN package_slot s ON s.id=a.slot_id JOIN task_package t ON t.id=a.package_id
JOIN package_plan plan ON plan.id=t.plan_id JOIN project p ON p.id=plan.project_id
WHERE a.actor_id=?
""" + cursorClause + " ORDER BY a.claimed_at DESC, a.id DESC LIMIT ?",
                arguments.toArray());
        return timePage(rows, limit, "assignmentId", "claimedAt");
    }

    @Transactional
    public JsonNode items(ActorContext actor,UUID assignment,int limit,String cursor) {
        Map<String,Object> a=assignment(actor,assignment);pageLimit(limit);
        List<Map<String,Object>> rows=db.queryForList(
                        """
SELECT s.id AS "sampleId", s.source_path AS "sourcePath", s.media_type AS "mediaType",
  s.content_sha256 AS "contentSha256", i.ordinal,
  d.revision AS "draftRevision", d.explicit_done AS "explicitDone",
  d.result_format AS "resultFormat", d.result::text AS result,
  ri.reference_candidate_id AS "referenceCandidateId",ri.reference_result_id AS "referenceResultId"
FROM package_item i JOIN sample s ON s.id=i.sample_id
LEFT JOIN round_item ri ON ri.round_id=? AND ri.sample_id=s.id
LEFT JOIN annotation_draft d ON d.assignment_id=? AND d.sample_id=s.id
WHERE i.package_id=? AND s.id>?
ORDER BY s.id LIMIT ?
""",a.get("round_id"),assignment,a.get("package_id"),cursor(cursor),limit+1);
        for(var row:rows) {
            if(row.get("result")!=null)row.put("result",read((String)row.get("result")));
            boolean structured = "application/json".equals(row.get("mediaType"));
            row.put("fileUrl",
                    structured
                            ? null
                            :"/api/labeling/v1/assignments/"+assignment+"/samples/"+row.get("sampleId")+"/file");
            row.put(
                    "dataUrl",
                    structured
                            ? "/api/labeling/v1/assignments/"
                                    + assignment
                                    + "/samples/"
                                    + row.get("sampleId")
                                    + "/data"
                            : null);
            row.put("inputFormat", structured ? "energy-station-day-input-v1" : "image");
            Object candidate = row.remove("referenceCandidateId");
            if (candidate != null) {
                row.put(
                        "referenceResult",
                        references.candidate(
                                (UUID) a.get("project_id"),
                                (UUID) a.get("spec_version_id"),
                                (UUID) row.get("sampleId"),
                                (UUID) candidate));
                row.remove("referenceResultId");
                continue;
            }
            Object ref = row.remove("referenceResultId");
            if (ref != null) {
                row.put("referenceResult", db.query(
                                """
                                SELECT result_format, result::text AS result FROM submission_item
                                WHERE submission_id=? AND sample_id=?
                                """, rs -> rs.next()
                            ? Map.of("resultFormat", rs.getString(1), "result", read(rs.getString(2)), "sourceKind", "HUMAN")
                            : null, (UUID) ref, (UUID) row.get("sampleId")));
            } else {
                row.put("referenceResult", null);
            }
        }
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)page(rows,limit,"sampleId");
        Map<String,Object> spec = one(
                        """
SELECT s.id AS "specVersionId", s.template_id AS "templateId",s.template_version AS "templateVersion",s.input_schema_id AS "inputSchemaId",s.result_schema_id AS "resultSchemaId",s.schema_digest AS "schemaDigest",s.task_type AS "taskType",s.schema_version AS "schemaVersion",s.labels::text AS labels, s.instructions, s.editor_config::text AS "editorConfig"
FROM spec_version s WHERE s.id=?
""",a.get("spec_version_id"));
        spec.put("labels",read((String)spec.get("labels")));
        spec.put("editorConfig",read((String)spec.get("editorConfig")));
        var binding = plugins.resolve((String)spec.get("taskType"),(String)spec.get("templateId"),(String)spec.get("templateVersion")).descriptor();
        spec.put("editorKey",binding.editorKey()); spec.put("inputFormat",binding.inputFormat());
        response.set("specification",json.valueToTree(spec));
        response.put("assignmentId", assignment.toString());
        response.put("packageId", a.get("package_id").toString());
        response.put("roundId", a.get("round_id").toString());
        response.put("status", a.get("status").toString());
        response.put("readOnly", !List.of("CLAIMED", "IN_PROGRESS").contains(a.get("status")));
        return response;
    }

    @Transactional
    public SampleFile file(ActorContext actor,UUID assignment,UUID sample) {
        Map<String,Object> a=assignment(actor,assignment);
        Map<String,Object> row=one(
                        """
SELECT s.batch_id,s.media_type,s.metadata->>'sizeBytes' AS size FROM sample s JOIN package_item i ON i.sample_id=s.id
WHERE i.package_id=? AND s.id=?
""",a.get("package_id"),sample);
        if ("application/json".equals(row.get("media_type")))
            throw error(422, "STRUCTURED_INPUT_REQUIRED", "请使用按样本授权的 data 接口");
        try {
            var path=storage.sample((UUID)row.get("batch_id"),sample);long size=Long.parseLong((String)row.get("size"));
            if(Files.size(path)!=size)throw new java.io.IOException("图片大小不一致");
            return new SampleFile(new FileSystemResource(path),(String)row.get("media_type"),size);
        }catch(java.io.IOException exception){throw error(503,"STORAGE_UNAVAILABLE","图片存储不可用");}
    }

    @Transactional
    public JsonNode round(ActorContext actor, UUID roundId) {
        enabled(actor.actorId());
        Map<String,Object> r = one(
                        """
SELECT r.*, p.owner_actor_id FROM publication_round r JOIN project p ON p.id=r.project_id WHERE r.id=?
""", roundId);
        boolean owner = actor.actorId().equals(r.get("owner_actor_id"));
        boolean participant = Boolean.TRUE.equals(db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND"
                                    + " actor_id=?)", Boolean.class, roundId, actor.actorId()));
        if (!owner && !participant) throw error(404,"RESOURCE_NOT_FOUND","轮次不存在");
        return roundView(roundId, r);
    }

    @Transactional
    public JsonNode claimRound(ActorContext actor, UUID roundId, String key) {
        enabled(actor.actorId());
        Map<String,Object> r = one("SELECT * FROM publication_round WHERE id=? FOR UPDATE", roundId);
        if (!(annotators.executionMode("human-pool").equals(r.get("mode"))
                || "MIXED".equals(r.get("mode")))) throw error(422,"INVALID_EXECUTION_MODE","仅含人工席位的轮次可领取");
        if (!"OPEN".equals(r.get("status"))) throw error(409,"ROUND_CLOSED","轮次不可领取");
        open(project((UUID) r.get("project_id"), false));
        String op="POST:/rounds/"+roundId+"/claims", hash=hash("{}");
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        if(Boolean.TRUE.equals(db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND actor_id=? AND"
                            + " status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))",Boolean.class,roundId,actor.actorId())))
            throw error(409,"ALREADY_CLAIMED","本轮已领取");
        List<Map<String,Object>> slots=db.queryForList(
                        """
SELECT s.id, s.slot_no FROM package_slot s WHERE s.round_id=? AND NOT EXISTS
  (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
ORDER BY s.slot_no LIMIT 1
""",roundId);
        if(slots.isEmpty())throw error(409,"SLOT_FULL","本轮已领满");
        UUID assignment=UUID.randomUUID();
        db.update(
                "INSERT INTO assignment(id,slot_id,package_id,round_id,actor_id) VALUES"
                    + " (?,?,?,?,?)",assignment,slots.get(0).get("id"),r.get("package_id"),roundId,actor.actorId());
        JsonNode response=json.valueToTree(Map.of("assignmentId",assignment,"roundId",roundId,"packageId",r.get("package_id"),"slotNo",slots.get(0).get("slot_no"),"status","CLAIMED","revision",1));
        remember(actor.actorId(),op,key,hash,assignment,response);
        return response;
    }

    @Transactional
    public JsonNode cancelRound(ActorContext actor, UUID roundId, long baseRevision, String reason, String key) {
        enabled(actor.actorId());
        Map<String,Object> r = one("SELECT * FROM publication_round WHERE id=? FOR UPDATE", roundId);
        owner(actor, project((UUID) r.get("project_id"), false));
        String op="POST:/rounds/"+roundId+"/cancel", hash=hash(baseRevision+":"+reason);
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        if (!"OPEN".equals(r.get("status"))) throw error(409,"ROUND_CLOSED","轮次已结束");
        if (((Number) r.get("revision")).longValue() != baseRevision) throw error(409,"REVISION_CONFLICT","轮次已变化，请重新读取");
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ai_execution WHERE round_id=? AND status NOT IN"
                        + " ('SUCCEEDED','PARTIAL','FAILED','CANCELLED'))",
                Boolean.class, roundId)))
            throw error(409,"AI_EXECUTION_ACTIVE","请先取消或等待本轮 AI 执行结束，再取消轮次");
        boolean hasSubmitted = Boolean.TRUE.equals(db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND"
                                    + " status='SUBMITTED')",Boolean.class,roundId));
        hasSubmitted = hasSubmitted || Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ai_execution WHERE round_id=? AND status IN ('SUCCEEDED','PARTIAL','FAILED'))",
                Boolean.class, roundId));
        db.update("UPDATE publication_round SET status=?, revision=revision+1 WHERE id=?", hasSubmitted ? "CLOSED" : "CANCELLED", roundId);
        db.update(
                "UPDATE assignment SET status='REVOKED', ended_at=CURRENT_TIMESTAMP,"
                    + " release_reason=? WHERE round_id=? AND status IN ('CLAIMED','IN_PROGRESS')", reason, roundId);
        db.update("DELETE FROM sample_work_claim WHERE round_id=?", roundId);
        JsonNode response = roundView(roundId, one("SELECT * FROM publication_round WHERE id=?", roundId));
        remember(actor.actorId(),op,key,hash,roundId,response);
        return response;
    }

    @Transactional
    public JsonNode reRoundPackage(ActorContext actor, UUID packageId, ReRoundInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> identity = one(
                        """
                        SELECT plan.project_id FROM task_package t
                        JOIN package_plan plan ON plan.id=t.plan_id WHERE t.id=?
                        """, packageId);
        UUID projectId = (UUID) identity.get("project_id");
        Map<String,Object> project = project(projectId, true);
        owner(actor, project);
        writable(project);
        Map<String,Object> pkg = one(
                        """
                        SELECT t.*, plan.project_id, plan.spec_version_id FROM task_package t
                        JOIN package_plan plan ON plan.id=t.plan_id WHERE t.id=? FOR UPDATE OF t
                        """, packageId);
        String op="POST:/packages/"+packageId+"/rounds", hash=hash(write(input));
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        var items = db.queryForList("SELECT sample_id FROM package_item WHERE package_id=? ORDER BY ordinal", packageId);
        if (items.isEmpty()) throw error(409,"PACKAGE_NOT_READY","任务包没有可发布成员");
        List<UUID> sampleIds = new ArrayList<>();
        for (var item : items) sampleIds.add((UUID) item.get("sample_id"));
        checkClaimConflict((UUID) pkg.get("project_id"), sampleIds);
        int roundNo = db.queryForObject(
                        "SELECT COALESCE(MAX(round_no),0)+1 FROM publication_round WHERE"
                            + " package_id=?", Integer.class, packageId);
        UUID roundId = UUID.randomUUID();
        String humanMode = annotators.executionMode("human-pool");
        db.update(
                """
INSERT INTO publication_round(id,project_id,spec_version_id,package_id,round_no,mode,instructions,status)
VALUES (?,?,?,?,?,?,?,'OPEN')
""", roundId, pkg.get("project_id"), pkg.get("spec_version_id"), packageId, roundNo, humanMode, input.instructions());
        int ordinal = 1;
        for (var item : items) {
            UUID sampleId = (UUID) item.get("sample_id");
            db.update(
                    "INSERT INTO round_item(round_id,project_id,spec_version_id,sample_id,ordinal)"
                        + " VALUES (?,?,?,?,?)",
                    roundId, pkg.get("project_id"), pkg.get("spec_version_id"), sampleId, ordinal++);
            db.update("INSERT INTO sample_work_claim(project_id,sample_id,round_id) VALUES (?,?,?)",
                    pkg.get("project_id"), sampleId, roundId);
        }
        int people = ((Number) pkg.get("required_count")).intValue();
        for (int slot=1; slot<=people; slot++)
            db.update("INSERT INTO package_slot(id,package_id,round_id,slot_no) VALUES (?,?,?,?)", UUID.randomUUID(), packageId, roundId, slot);
        db.update("UPDATE task_package SET status='OPEN', revision=revision+1 WHERE id=?", packageId);
        outcomes.resetAcceptedForRepublish((UUID) pkg.get("project_id"), (UUID) pkg.get("spec_version_id"), sampleIds, actor.actorId());
        JsonNode response = roundView(roundId, one("SELECT * FROM publication_round WHERE id=?", roundId));
        remember(actor.actorId(),op,key,hash,roundId,response);
        return response;
    }

    @Transactional
    public JsonNode releaseAssignment(ActorContext actor, UUID assignmentId, long baseRevision, String reason, String key) {
        enabled(actor.actorId());
        Map<String,Object> a = one("SELECT * FROM assignment WHERE id=? AND actor_id=? FOR UPDATE", assignmentId, actor.actorId());
        String op = "POST:/assignments/" + assignmentId + "/release", requestHash = hash(baseRevision + ":" + reason);
        JsonNode previous = replay(actor.actorId(), op, key, requestHash);
        if (previous != null) return previous;
        if (!List.of("CLAIMED","IN_PROGRESS").contains(a.get("status"))) throw error(409,"ASSIGNMENT_CLOSED","领取已结束，不能释放");
        if (((Number) a.get("revision")).longValue() != baseRevision) throw error(409,"REVISION_CONFLICT","领取已变化，请重新读取");
        db.update(
                "UPDATE assignment SET status='RELEASED', ended_at=CURRENT_TIMESTAMP,"
                    + " release_reason=?, revision=revision+1 WHERE id=?", reason, assignmentId);
        closeRoundIfDone((UUID) a.get("round_id"));
        long revision = ((Number) a.get("revision")).longValue() + 1;
        JsonNode response = json.valueToTree(Map.of("assignmentId", assignmentId, "status", "RELEASED", "revision", revision));
        remember(actor.actorId(), op, key, requestHash, assignmentId, response);
        return response;
    }

    @Transactional
    public JsonNode revokeAssignment(ActorContext actor, UUID assignmentId, long baseRevision, String reason, String key) {
        enabled(actor.actorId());
        if (!actor.labelingAdmin()) throw error(403,"FORBIDDEN","仅标注管理员可撤销领取");
        Map<String,Object> a = one("SELECT * FROM assignment WHERE id=? FOR UPDATE", assignmentId);
        String op = "POST:/admin/assignments/" + assignmentId + "/revoke", requestHash = hash(baseRevision + ":" + reason);
        JsonNode previous = replay(actor.actorId(), op, key, requestHash);
        if (previous != null) return previous;
        if (!List.of("CLAIMED","IN_PROGRESS").contains(a.get("status"))) throw error(409,"ASSIGNMENT_CLOSED","领取已结束，不能撤销");
        if (((Number) a.get("revision")).longValue() != baseRevision) throw error(409,"REVISION_CONFLICT","领取已变化，请重新读取");
        db.update(
                "UPDATE assignment SET status='REVOKED', ended_at=CURRENT_TIMESTAMP,"
                    + " release_reason=?, revision=revision+1 WHERE id=?", reason, assignmentId);
        closeRoundIfDone((UUID) a.get("round_id"));
        long revision = ((Number) a.get("revision")).longValue() + 1;
        JsonNode response = json.valueToTree(Map.of("assignmentId", assignmentId, "status", "REVOKED", "revision", revision));
        remember(actor.actorId(), op, key, requestHash, assignmentId, response);
        return response;
    }

    /** 仅当本轮既无进行中的领取、也无空余可领取席位时，才关闭轮次并释放样本占用。 */
    private void closeRoundIfDone(UUID roundId) {
        one("SELECT id FROM publication_round WHERE id=? FOR UPDATE", roundId);
        boolean active = Boolean.TRUE.equals(db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND status"
                                    + " IN ('CLAIMED','IN_PROGRESS'))", Boolean.class, roundId));
        boolean claimable = Boolean.TRUE.equals(db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM package_slot s WHERE s.round_id=? AND"
                                    + " NOT EXISTS(SELECT 1 FROM assignment a WHERE a.slot_id=s.id"
                                    + " AND a.status='SUBMITTED'))", Boolean.class, roundId));
        boolean aiActive = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM ai_execution WHERE round_id=? AND status NOT IN"
                        + " ('SUCCEEDED','PARTIAL','FAILED','CANCELLED'))",
                Boolean.class, roundId));
        if (!active && !claimable && !aiActive) {
            db.update(
                    "UPDATE publication_round SET status='CLOSED', revision=revision+1 WHERE id=?"
                        + " AND status='OPEN'", roundId);
            db.update("DELETE FROM sample_work_claim WHERE round_id=?", roundId);
        }
    }

    private Map<String,Object> assignment(ActorContext actor,UUID id) {
        enabled(actor.actorId());
        Map<String,Object> a=one(
                        """
SELECT a.*,plan.project_id,plan.spec_version_id FROM assignment a
JOIN task_package t ON t.id=a.package_id JOIN package_plan plan ON plan.id=t.plan_id
WHERE a.id=? AND a.actor_id=?
""",id,actor.actorId());
        open(project((UUID)a.get("project_id"),false));
        one(
                "SELECT id FROM assignment WHERE id=? AND status IN"
                    + " ('CLAIMED','IN_PROGRESS','SUBMITTED') FOR SHARE",id);
        return a;
    }

    private ResolvedRoster resolveRoster(PlanInput input) {
        List<AnnotatorSelection> entries = input.annotatorRoster();
        if (entries == null) {
            String mode = input.executionMode() == null ? "HUMAN_POOL" : input.executionMode();
            List<UUID> agents = input.aiAgentIds() == null ? List.of() : input.aiAgentIds();
            int people = input.annotatorsPerSample() == null
                    ? ("AI_AGENT".equals(mode) ? 0 : 1)
                    : input.annotatorsPerSample();
            boolean valid = switch (mode) {
                case "HUMAN_POOL" -> people > 0 && agents.isEmpty();
                case "AI_AGENT" -> people == 0 && !agents.isEmpty();
                case "MIXED" -> people > 0 && !agents.isEmpty();
                default -> false;
            };
            if (!valid) throw error(400, "INVALID_REQUEST", "标注员清单与执行模式不匹配");
            entries = new ArrayList<>();
            if (people > 0) entries.add(new AnnotatorSelection(
                    "human-pool", "1.0.0", people, List.of()));
            if (!agents.isEmpty()) entries.add(new AnnotatorSelection(
                    "ai-agent-worker", "1.0.0", agents.size(), agents));
        }
        if (entries.isEmpty() || entries.size() > 10)
            throw error(422, "INVALID_ANNOTATOR_ROSTER", "标注员清单不能为空且最多包含 10 项");
        int humanCount = 0;
        List<UUID> aiAgentIds = new ArrayList<>();
        Set<String> modes = new HashSet<>();
        Set<UUID> uniqueAgents = new HashSet<>();
        List<AnnotatorSelection> frozen = new ArrayList<>();
        for (AnnotatorSelection entry : entries) {
            if (entry == null || entry.providerId() == null || entry.providerVersion() == null
                    || entry.count() == null || entry.count() < 1)
                throw error(422, "INVALID_ANNOTATOR_ROSTER", "标注员提供方、版本和数量均为必填");
            com.tss.labeling.annotators.AnnotatorProvider.Descriptor provider;
            try {
                provider = annotators.require(entry.providerId(), entry.providerVersion()).descriptor();
            } catch (IllegalArgumentException exception) {
                throw error(422, "ANNOTATOR_PROVIDER_UNAVAILABLE", "标注员提供方版本未安装");
            }
            String mode = provider.executionMode();
            if (!Set.of("HUMAN_POOL", "AI_AGENT").contains(mode) || !modes.add(mode))
                throw error(422, "ANNOTATOR_PROVIDER_UNSUPPORTED", "本版本不支持重复或未知的标注员执行方式");
            List<UUID> agentIds = entry.agentIds() == null ? List.of() : entry.agentIds();
            if ("HUMAN_POOL".equals(mode)) {
                if (!agentIds.isEmpty() || entry.count() > maxAnnotators)
                    throw error(422, "INVALID_ANNOTATOR_ROSTER", "人工席位数量超出限制或包含 AI Agent");
                humanCount = entry.count();
            } else {
                if (agentIds.isEmpty() || entry.count() != agentIds.size()
                        || agentIds.size() > 10 || new HashSet<>(agentIds).size() != agentIds.size()
                        || agentIds.stream().anyMatch(id -> !uniqueAgents.add(id)))
                    throw error(422, "INVALID_ANNOTATOR_ROSTER", "AI 数量必须与不重复的 Agent 清单一致");
                aiAgentIds.addAll(agentIds);
            }
            frozen.add(new AnnotatorSelection(provider.id(), provider.version(), entry.count(), agentIds));
        }
        String executionMode = humanCount > 0
                ? (aiAgentIds.isEmpty() ? "HUMAN_POOL" : "MIXED")
                : "AI_AGENT";
        return new ResolvedRoster(List.copyOf(frozen), executionMode, humanCount, List.copyOf(aiAgentIds));
    }

    private Map<String, Object> workflowSnapshot(String executionMode) {
        List<Map<String, String>> participants = new ArrayList<>();
        if ("HUMAN_POOL".equals(executionMode) || "MIXED".equals(executionMode))
            participants.add(Map.of("providerId", "human-pool", "providerVersion", "1.0.0"));
        if ("AI_AGENT".equals(executionMode) || "MIXED".equals(executionMode))
            participants.add(Map.of("providerId", "ai-agent-worker", "providerVersion", "1.0.0"));
        return Map.of(
                "templateId", "standard-annotation",
                "version", "1.0.0",
                "stages", List.of(
                        Map.of("stageId", "ANNOTATION", "participants", participants, "execution", "PARALLEL"),
                        Map.of("stageId", "OWNER_REVIEW", "participants", List.of("PROJECT_OWNER")),
                        Map.of("stageId", "OUTCOME_DELIVERY", "participants", List.of("PROJECT_OWNER"))));
    }

    private JsonNode planView(UUID id) {
        Map<String,Object> p=one("SELECT * FROM package_plan WHERE id=?",id);
        int count=((Number)p.get("sample_count")).intValue(),size=((Number)p.get("package_size")).intValue();
        List<Integer> counts=new ArrayList<>();for(int start=0;start<count;start+=size)counts.add(Math.min(size,count-start));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", p.get("project_id"));
        out.put("specVersionId", p.get("spec_version_id"));
        out.put("planId", id);
        out.put("status", p.get("status"));
        out.put("selectionHash", p.get("selection_hash"));
        out.put("executionMode", p.get("execution_mode"));
        out.put("annotatorsPerSample", p.get("annotators_per_sample"));
        JsonNode planAgents = read(Objects.toString(p.get("ai_agent_ids"), "[]"));
        out.put("aiAgentIds", planAgents);
        out.put("annotatorRoster", read(Objects.toString(p.get("annotator_roster"), "[]")));
        out.put("assignmentPolicy", read(Objects.toString(p.get("assignment_policy"), "{}")));
        out.put("workflowSnapshot", read(Objects.toString(p.get("workflow_snapshot"), "{}")));
        out.put("sampleCount", count);
        out.put("packageSampleCounts", counts);
        out.put("totalWorkload", (long) count * (((Number) p.get("annotators_per_sample")).intValue() + planAgents.size()));
        out.put("packages", db.queryForList(
                                """
SELECT r.package_id AS "packageId",COALESCE(t.sequence_no,r.round_no) AS "sequenceNo",
  (SELECT count(*) FROM round_item ri WHERE ri.round_id=r.id) AS "sampleCount",
  COALESCE(t.required_count,0) AS "requiredCount",r.id AS "roundId"
FROM publication_round r LEFT JOIN task_package t ON t.id=r.package_id
WHERE r.package_plan_id=? OR t.plan_id=? ORDER BY "sequenceNo"
""",id,id));
        out.put("aiExecutions", db.queryForList(
                "SELECT e.id AS \"executionId\",e.round_id AS \"roundId\",e.agent_actor_id AS \"agentActorId\","
                        + "e.model_version AS \"modelVersion\",e.status FROM ai_execution e"
                        + " JOIN publication_round r ON r.id=e.round_id WHERE r.package_plan_id=?"
                        + " ORDER BY e.created_at,e.id", id));
        return json.valueToTree(out);
    }
    private List<Map<String,Object>> samples(UUID batch) {
        var rows=db.queryForList("SELECT id,content_sha256 FROM sample WHERE batch_id=? ORDER BY id LIMIT ?",batch,maxSamples+1);
        if(rows.isEmpty()||rows.size()>maxSamples)throw error(422,"SAMPLE_LIMIT","批次样本为空或超过同步打包上限");return rows;
    }
    /** 按 owner 冻结的样本清单选样；可选携带同一项目内该样本的已提交结果作为参考。 */
    private List<Map<String,Object>> selectionSamples(UUID project, UUID spec, List<SelectionSample> input) {
        if (input == null || input.isEmpty()) throw error(422,"INVALID_REQUEST","样本范围不能为空");
        if (input.size() > maxSamples) throw error(422,"SAMPLE_LIMIT","样本范围超过同步打包上限");
        Map<UUID,UUID> refs = new LinkedHashMap<>();
        Map<UUID, UUID> candidates = new LinkedHashMap<>();
        for (var s : input) {
            if (s.sampleId() == null) throw error(422,"INVALID_REQUEST","样本ID不能为空");
            if (refs.containsKey(s.sampleId())) throw error(422,"INVALID_REQUEST","样本范围不能含重复样本");
            if (s.referenceResultId() != null && s.referenceCandidateId() != null)
                throw error(422, "INVALID_REFERENCE", "不能混用旧提交参考与新答案参考");
            refs.put(s.sampleId(), s.referenceResultId());
            candidates.put(s.sampleId(), s.referenceCandidateId());
        }
        List<UUID> ids = new ArrayList<>(refs.keySet());
        Collections.sort(ids);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (UUID id : ids) {
            one("SELECT id FROM sample WHERE id=? AND project_id=?", id, project);
            if (refs.get(id) != null) {
                Map<String,Object> reference = db.query(
                        """
                        SELECT si.result_format AS result_format, source_spec.task_type AS task_type
                        FROM submission_item si
                        JOIN submission s ON s.id=si.submission_id
                        JOIN spec_version source_spec ON source_spec.id=s.spec_version_id
                        WHERE si.submission_id=? AND si.sample_id=? AND s.project_id=?
                        """,
                        rs -> rs.next()
                                ? Map.of("result_format",rs.getString("result_format"),"task_type",rs.getString("task_type"))
                                : null,
                        refs.get(id),id,project);
                Map<String,Object> target = one("SELECT * FROM spec_version WHERE id=? AND project_id=?",spec,project);
                String taskType=(String)target.get("task_type");
                String expectedFormat="label-studio-v1".equals(target.get("schema_version"))
                        ? "label-studio-v1"
                        : plugins.frozen(target).descriptor().resultFormat();
                if(reference==null || !taskType.equals(reference.get("task_type"))
                        || !expectedFormat.equals(reference.get("result_format")))
                    throw error(422,"INVALID_REFERENCE","参考结果不属于该样本、项目或兼容的任务类型");
            }
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("project_id", project);
            if (candidates.get(id) != null)
                references.candidate(project, spec, id, candidates.get(id));
            row.put("referenceResultId", refs.get(id));
            row.put("referenceCandidateId", candidates.get(id));
            rows.add(row);
        }
        return rows;
    }
    /** 发布时从计划读取冻结样本；batch 计划取批次样本，selection 计划取冻结清单。 */
    private List<Map<String,Object>> planSamples(Map<String,Object> plan) {
        if (plan.get("batch_id") != null) return samples((UUID) plan.get("batch_id"));
        JsonNode list = read( plan.get("selection_json").toString());
        List<Map<String,Object>> rows = new ArrayList<>();
        for (JsonNode node : list) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", UUID.fromString(node.path("id").asText()));
            row.put("project_id", UUID.fromString(node.path("project_id").asText()));
            row.put("referenceResultId", node.hasNonNull("referenceResultId") ? UUID.fromString(node.path("referenceResultId").asText()) : null);
            row.put(
                    "referenceCandidateId",
                    node.hasNonNull("referenceCandidateId")
                            ? UUID.fromString(node.path("referenceCandidateId").asText())
                            : null);
            rows.add(row);
        }
        return rows;
    }
    private void checkClaimConflict(UUID project, List<UUID> sampleIds) {
        checkClaimConflict(project, sampleIds, null);
    }
    private void checkClaimConflict(UUID project, List<UUID> sampleIds, UUID groupId) {
        Set<UUID> wanted = new HashSet<>(sampleIds);
        var claimed = db.queryForList(
                        """
SELECT c.sample_id FROM sample_work_claim c JOIN publication_round r ON r.id=c.round_id
WHERE r.status='OPEN' AND c.project_id=? AND (CAST(? AS uuid) IS NULL OR r.publication_group_id IS DISTINCT FROM CAST(? AS uuid))
""", project, groupId, groupId);
        for (var row : claimed) if (wanted.contains((UUID) row.get("sample_id"))) throw error(409,"SAMPLE_BUSY","所选样本存在进行中的轮次");
    }
    private JsonNode roundView(UUID id, Map<String,Object> r) {
        long sampleCount = db.query("SELECT count(*) FROM round_item WHERE round_id=?", rs -> rs.next() ? rs.getLong(1) : 0L, id);
        long submitted = db.query("SELECT count(*) FROM assignment WHERE round_id=? AND status='SUBMITTED'", rs -> rs.next() ? rs.getLong(1) : 0L, id);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("roundId", id);
        out.put("packageId", r.get("package_id"));
        out.put("projectId", r.get("project_id"));
        out.put("specVersionId", r.get("spec_version_id"));
        out.put("mode", r.get("mode"));
        out.put("status", r.get("status"));
        out.put("roundNo", r.get("round_no"));
        out.put("revision", r.get("revision"));
        out.put("instructions", r.get("instructions"));
        out.put("sampleCount", sampleCount);
        out.put("submittedCount", submitted);
        return json.valueToTree(out);
    }
    private String selection(List<Map<String,Object>> rows){return hash(write(rows));}
    private Map<String,Object> project(UUID id,boolean exclusive){return one(
                "SELECT * FROM project WHERE deleted_at IS NULL AND id=? FOR "+(exclusive?"UPDATE":"SHARE"),id);}
    private void enabled(UUID actor){if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor).get("status")))throw error(403,"LABELING_DISABLED","标注账号已停用");}
    private void owner(ActorContext actor,Map<String,Object> project){if(!actor.actorId().equals(project.get("owner_actor_id")))throw error(404,"RESOURCE_NOT_FOUND","项目不存在");}
    private void writable(Map<String,Object> p){if(!List.of("DRAFT","OPEN").contains(p.get("status")))throw error(409,"PROJECT_CLOSED","项目当前不可发布");}
    private void open(Map<String,Object> p){if(!"OPEN".equals(p.get("status"))||!"PLATFORM".equals(p.get("visibility")))throw error(409,"PROJECT_CLOSED","项目当前不可访问");}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","请求资源不存在");return rows.get(0);}
    private void pageLimit(int limit){if(limit<1||limit>100)throw error(400,"INVALID_REQUEST","分页大小应为1至100");}
    private UUID cursor(String cursor){try{return cursor==null?new UUID(0,0):UUID.fromString(cursor);}catch(IllegalArgumentException ex){throw error(400,"INVALID_CURSOR","分页游标无效");}}
    private JsonNode page(List<Map<String,Object>> rows,int limit,String id){boolean more=rows.size()>limit;var selected=more?rows.subList(0,limit):rows;Map<String,Object> result=new LinkedHashMap<>();result.put("items",selected);result.put("nextCursor",more?selected.get(selected.size()-1).get(id).toString():null);return json.valueToTree(result);}
    private TimeCursor timeCursor(String cursor){
        if(cursor==null||cursor.isBlank())return null;
        try{
            String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
            int separator=decoded.lastIndexOf('#');
            if(separator<1)throw new IllegalArgumentException();
            return new TimeCursor(Instant.parse(decoded.substring(0,separator)),UUID.fromString(decoded.substring(separator+1)));
        }catch(RuntimeException ex){throw error(400,"INVALID_CURSOR","分页游标无效");}
    }
    private JsonNode timePage(List<Map<String,Object>> rows,int limit,String id,String time){
        boolean more=rows.size()>limit;
        List<Map<String,Object>> selected=more?rows.subList(0,limit):rows;
        String nextCursor=more&&!selected.isEmpty()?encodeTimeCursor(selected.get(selected.size()-1).get(time),selected.get(selected.size()-1).get(id)):null;
        for(Map<String,Object> row:selected){
            Object value=row.get(time);
            if(value instanceof Timestamp timestamp)row.put(time,timestamp.toInstant().toString());
            else if(value instanceof Instant instant)row.put(time,instant.toString());
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("items",selected);
        result.put("nextCursor",nextCursor);
        return json.valueToTree(result);
    }
    private String encodeTimeCursor(Object createdAt,Object id){
        Instant instant=createdAt instanceof Timestamp timestamp?timestamp.toInstant():createdAt instanceof Instant value?value:Instant.parse(createdAt.toString());
        String value=instant+"#"+id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private record TimeCursor(Instant createdAt,UUID id){}
    private JsonNode replay(UUID actor,String op,String key,String hash){
        if(key==null||key.isBlank()||key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+op+":"+key);
        return db.query(
                "SELECT request_hash,response_body::text FROM idempotency_record WHERE actor_id=?"
                    + " AND operation=? AND idempotency_key=?",rs->{
            if(!rs.next())return null;if(!hash.equals(rs.getString(1)))throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
            return read(rs.getString(2));},actor,op,key);
    }
    private void remember(UUID actor,String op,String key,String hash,UUID id,JsonNode response){db.update(
                "INSERT INTO"
                    + " idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body)"
                    + " VALUES (?,?,?,?,?,?::jsonb)",actor,op,key,hash,id,write(response));}
    private JsonNode read(String s){try{return json.readTree(s);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    public static BusinessException error(int status,String code,String message){return new BusinessException(HttpStatus.valueOf(status),code,message);}
    public record PlanInput(UUID batchId, UUID specVersionId, Integer packageSize, Integer annotatorsPerSample, List<SelectionSample> samples, String instructions,
            UUID selectionId,
            String referenceMode,
            Boolean reopenAccepted,
            String executionMode,
            List<UUID> aiAgentIds,
            List<AnnotatorSelection> annotatorRoster) {
        public PlanInput(
                UUID batchId,
                UUID specVersionId,
                Integer packageSize,
                Integer annotatorsPerSample,
                List<SelectionSample> samples,
                String instructions) {
            this(
                    batchId,
                    specVersionId,
                    packageSize,
                    annotatorsPerSample,
                    samples,
                    instructions,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        public PlanInput(
                UUID batchId,
                UUID specVersionId,
                Integer packageSize,
                Integer annotatorsPerSample,
                List<SelectionSample> samples,
                String instructions,
                UUID selectionId,
                String referenceMode,
                Boolean reopenAccepted) {
            this(batchId, specVersionId, packageSize, annotatorsPerSample, samples, instructions,
                    selectionId, referenceMode, reopenAccepted, null, null, null);
        }
    }
    public record AnnotatorSelection(String providerId, String providerVersion, Integer count, List<UUID> agentIds) {}
    private record ResolvedRoster(List<AnnotatorSelection> entries, String executionMode, int humanCount, List<UUID> aiAgentIds) {}
    public record SelectionSample(@NotNull UUID sampleId, UUID referenceResultId, UUID referenceCandidateId) {
        public SelectionSample(UUID sampleId, UUID referenceResultId) {
            this(sampleId, referenceResultId, null);
        }}
    public record ReRoundInput(String instructions) {}
}
