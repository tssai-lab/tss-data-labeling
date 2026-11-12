package com.tss.labeling.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.ImportService.SampleFile;
import com.tss.labeling.imports.ImportStorage;
import com.tss.labeling.review.OutcomeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class TaskService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportStorage storage;
    private final OutcomeService outcomes;
    private final int maxSamples;
    private final int maxPackageSize;
    private final int maxAnnotators;

    public TaskService(JdbcTemplate db, ObjectMapper json, ImportStorage storage, OutcomeService outcomes,
            @Value("${labeling.tasks.max-samples:10000}") int maxSamples,
            @Value("${labeling.tasks.max-package-size:1000}") int maxPackageSize,
            @Value("${labeling.tasks.max-annotators:10}") int maxAnnotators) {
        this.db = db; this.json = json; this.storage = storage;
        this.outcomes = outcomes;
        this.maxSamples = maxSamples; this.maxPackageSize = maxPackageSize; this.maxAnnotators = maxAnnotators;
    }

    @Transactional
    public JsonNode createPlan(ActorContext actor, UUID project, PlanInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> p = project(project, false);
        owner(actor, p); writable(p);
        if ((input.batchId() == null) == (input.samples() == null))
            throw error(400, "INVALID_REQUEST", "batchId 与 samples 必须二选一");
        int size = input.packageSize() == null ? 10 : input.packageSize();
        int people = input.annotatorsPerSample() == null ? 1 : input.annotatorsPerSample();
        if (size < 1 || size > maxPackageSize || people < 1 || people > maxAnnotators)
            throw error(400, "INVALID_REQUEST", "包大小或标注人数超出限制");
        String op = "POST:/projects/" + project + "/package-plans", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        Map<String,Object> spec = one("SELECT * FROM spec_version WHERE id = ? AND project_id = ?", input.specVersionId(), project);
        if (!Objects.equals(spec.get("task_type"), p.get("task_type"))) throw error(409,"SPEC_MISMATCH","规范任务类型不匹配");
        List<Map<String,Object>> rows;
        String selectionJson = null;
        if (input.batchId() != null) {
            Map<String,Object> batch = one("SELECT * FROM import_batch WHERE id = ? AND project_id = ? FOR SHARE", input.batchId(), project);
            if (!"READY".equals(batch.get("status"))) throw error(409,"BATCH_NOT_READY","数据尚未导入完成");
            rows = samples(input.batchId());
        } else {
            rows = selectionSamples(project, input.samples());
            selectionJson = write(rows);
        }
        UUID plan = UUID.randomUUID();
        db.update("""
                INSERT INTO package_plan(id, project_id, batch_id, spec_version_id, package_size,
                  annotators_per_sample, sample_count, selection_hash, selection_json, instructions)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)
                """, plan, project, input.batchId(), input.specVersionId(), size, people, rows.size(),
                selection(rows), selectionJson, input.instructions());
        JsonNode response = planView(plan);
        remember(actor.actorId(), op, key, hash, plan, response);
        return response;
    }

    @Transactional
    public JsonNode publish(ActorContext actor, UUID planId, String expectedHash, String key) {
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
            List<Map<String,Object>> samples = planSamples(plan);
            if (!selection(samples).equals(expectedHash)) throw error(409,"SELECTION_CHANGED","样本集合已改变");
            List<UUID> sampleIds = new ArrayList<>();
            for (var s : samples) sampleIds.add((UUID) s.get("id"));
            checkClaimConflict(projectId, sampleIds);
            int size = ((Number)plan.get("package_size")).intValue(), people = ((Number)plan.get("annotators_per_sample")).intValue();
            // ponytail: 有界同步发布，超过样本上限需拆分批次；大规模发布再引入后台任务。
            for (int start=0, sequence=1; start<samples.size(); start+=size, sequence++) {
                UUID pkg = UUID.randomUUID(); int count = Math.min(size, samples.size()-start);
                db.update("INSERT INTO task_package(id,plan_id,sequence_no,sample_count,required_count) VALUES (?,?,?,?,?)",pkg,planId,sequence,count,people);
                UUID roundId = UUID.randomUUID();
                db.update("""
                        INSERT INTO publication_round(id,project_id,spec_version_id,package_id,round_no,mode,instructions,status)
                        VALUES (?,?,?,?,1,'HUMAN_POOL',?,'OPEN')
                        """, roundId, projectId, plan.get("spec_version_id"), pkg, plan.get("instructions"));
                for (int i=0;i<count;i++) {
                    Map<String,Object> s = samples.get(start+i);
                    db.update("INSERT INTO package_item(package_id,plan_id,project_id,sample_id,ordinal) VALUES (?,?,?,?,?)",pkg,planId,projectId,s.get("id"),i+1);
                    db.update("INSERT INTO round_item(round_id,project_id,spec_version_id,sample_id,ordinal,reference_result_id) VALUES (?,?,?,?,?,?)",
                            roundId, projectId, plan.get("spec_version_id"), s.get("id"), i+1, s.get("referenceResultId"));
                    db.update("INSERT INTO sample_work_claim(project_id,sample_id,round_id) VALUES (?,?,?)", projectId, s.get("id"), roundId);
                }
                for (int slot=1;slot<=people;slot++) db.update("INSERT INTO package_slot(id,package_id,round_id,slot_no) VALUES (?,?,?,?)",UUID.randomUUID(),pkg,roundId,slot);
            }
            outcomes.resetAcceptedForRepublish(projectId, (UUID) plan.get("spec_version_id"), sampleIds, actor.actorId());
            db.update("UPDATE package_plan SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP WHERE id=?",planId);
            db.update("UPDATE project SET status='OPEN',revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",projectId);
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
        UUID after = cursor(cursor);
        List<Map<String,Object>> rows = db.queryForList("""
                SELECT t.id AS "packageId", p.id AS "projectId", p.name AS "projectName", p.task_type AS "taskType",
                  t.sample_count AS "sampleCount", t.required_count AS "requiredCount", r.id AS "roundId",
                  t.required_count - (SELECT count(*) FROM assignment a WHERE a.round_id=r.id
                    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED')) AS "remainingSlots"
                FROM task_package t JOIN publication_round r ON r.package_id=t.id AND r.status='OPEN'
                JOIN package_plan plan ON plan.id=t.plan_id JOIN project p ON p.id=plan.project_id
                WHERE p.status='OPEN' AND p.visibility='PLATFORM' AND t.status='OPEN' AND plan.status='PUBLISHED'
                  AND t.id > ? AND (? = '' OR p.task_type = ?)
                  AND NOT EXISTS(SELECT 1 FROM assignment a WHERE a.round_id=r.id AND a.actor_id=?
                    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
                  AND EXISTS(SELECT 1 FROM package_slot s WHERE s.round_id=r.id AND NOT EXISTS
                    (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED')))
                ORDER BY t.id LIMIT ?
                """,after,taskType,taskType,actor.actorId(),limit+1);
        return page(rows,limit,"packageId");
    }

    @Transactional
    public JsonNode claim(ActorContext actor, UUID packageId, String key) {
        enabled(actor.actorId());
        Map<String,Object> mapping = one("SELECT plan.project_id FROM task_package t JOIN package_plan plan ON plan.id=t.plan_id WHERE t.id=?",packageId);
        open(project((UUID)mapping.get("project_id"),false));
        String op="POST:/packages/"+packageId+"/claims", hash=hash("{}");
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        Map<String,Object> pkg=one("SELECT * FROM task_package WHERE id=? FOR UPDATE",packageId);
        if(!"OPEN".equals(pkg.get("status")))throw error(409,"PACKAGE_CLOSED","任务包不可领取");
        Map<String,Object> round=one("SELECT * FROM publication_round WHERE package_id=? AND status='OPEN' FOR UPDATE",packageId);
        open(project((UUID) round.get("project_id"), false));
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND actor_id=? AND status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))",Boolean.class,round.get("id"),actor.actorId())))
            throw error(409,"ALREADY_CLAIMED","已领取此任务包");
        List<Map<String,Object>> slots=db.queryForList("""
                SELECT s.id, s.slot_no FROM package_slot s WHERE s.round_id=? AND NOT EXISTS
                  (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
                ORDER BY s.slot_no LIMIT 1
                """,round.get("id"));
        if(slots.isEmpty())throw error(409,"SLOT_FULL","任务包已领满");
        UUID assignment=UUID.randomUUID();
        db.update("INSERT INTO assignment(id,slot_id,package_id,round_id,actor_id) VALUES (?,?,?,?,?)",assignment,slots.get(0).get("id"),packageId,round.get("id"),actor.actorId());
        JsonNode response=json.valueToTree(Map.of("assignmentId",assignment,"packageId",packageId,"roundId",round.get("id"),"slotNo",slots.get(0).get("slot_no"),"status","CLAIMED","revision",1));
        remember(actor.actorId(),op,key,hash,assignment,response);
        return response;
    }

    @Transactional
    public JsonNode mine(ActorContext actor,int limit,String cursor) {
        enabled(actor.actorId());pageLimit(limit);
        return page(db.queryForList("""
                SELECT a.id AS "assignmentId", a.package_id AS "packageId", a.round_id AS "roundId", a.status, a.revision,
                  s.slot_no AS "slotNo", plan.spec_version_id AS "specVersionId", plan.project_id AS "projectId"
                FROM assignment a JOIN package_slot s ON s.id=a.slot_id JOIN task_package t ON t.id=a.package_id
                JOIN package_plan plan ON plan.id=t.plan_id WHERE a.actor_id=? AND a.id>? ORDER BY a.id LIMIT ?
                """,actor.actorId(),cursor(cursor),limit+1),limit,"assignmentId");
    }

    @Transactional
    public JsonNode items(ActorContext actor,UUID assignment,int limit,String cursor) {
        Map<String,Object> a=assignment(actor,assignment);pageLimit(limit);
        List<Map<String,Object>> rows=db.queryForList("""
                SELECT s.id AS "sampleId", s.source_path AS "sourcePath", s.media_type AS "mediaType",
                  s.content_sha256 AS "contentSha256", i.ordinal,
                  d.revision AS "draftRevision", d.explicit_done AS "explicitDone",
                  d.result_format AS "resultFormat", d.result::text AS result,
                  ri.reference_result_id AS "referenceResultId"
                FROM package_item i JOIN sample s ON s.id=i.sample_id
                LEFT JOIN round_item ri ON ri.round_id=? AND ri.sample_id=s.id
                LEFT JOIN annotation_draft d ON d.assignment_id=? AND d.sample_id=s.id
                WHERE i.package_id=? AND s.id>?
                ORDER BY s.id LIMIT ?
                """,a.get("round_id"),assignment,a.get("package_id"),cursor(cursor),limit+1);
        for(var row:rows) {
            if(row.get("result")!=null)row.put("result",read((String)row.get("result")));
            row.put("fileUrl","/api/labeling/v1/assignments/"+assignment+"/samples/"+row.get("sampleId")+"/file");
            Object ref = row.remove("referenceResultId");
            if (ref != null) {
                row.put("referenceResult", db.query("""
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
        Map<String,Object> spec = one("""
                SELECT s.id AS "specVersionId", s.labels::text AS labels, s.instructions, s.editor_config::text AS "editorConfig"
                FROM spec_version s WHERE s.id=?
                """,a.get("spec_version_id"));
        spec.put("labels",read((String)spec.get("labels")));
        spec.put("editorConfig",read((String)spec.get("editorConfig")));
        response.set("specification",json.valueToTree(spec));
        response.put("assignmentId", assignment.toString());
        response.put("packageId", a.get("package_id").toString());
        response.put("roundId", a.get("round_id").toString());
        response.put("status", a.get("status").toString());
        response.put("readOnly", "SUBMITTED".equals(a.get("status")));
        return response;
    }

    @Transactional
    public SampleFile file(ActorContext actor,UUID assignment,UUID sample) {
        Map<String,Object> a=assignment(actor,assignment);
        Map<String,Object> row=one("""
                SELECT s.batch_id,s.media_type,s.metadata->>'sizeBytes' AS size FROM sample s JOIN package_item i ON i.sample_id=s.id
                WHERE i.package_id=? AND s.id=?
                """,a.get("package_id"),sample);
        try {
            var path=storage.sample((UUID)row.get("batch_id"),sample);long size=Long.parseLong((String)row.get("size"));
            if(Files.size(path)!=size)throw new java.io.IOException("图片大小不一致");
            return new SampleFile(new FileSystemResource(path),(String)row.get("media_type"),size);
        }catch(java.io.IOException exception){throw error(503,"STORAGE_UNAVAILABLE","图片存储不可用");}
    }

    @Transactional
    public JsonNode round(ActorContext actor, UUID roundId) {
        enabled(actor.actorId());
        Map<String,Object> r = one("""
                SELECT r.*, p.owner_actor_id FROM publication_round r JOIN project p ON p.id=r.project_id WHERE r.id=?
                """, roundId);
        boolean owner = actor.actorId().equals(r.get("owner_actor_id"));
        boolean participant = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND actor_id=?)", Boolean.class, roundId, actor.actorId()));
        if (!owner && !participant) throw error(404,"RESOURCE_NOT_FOUND","轮次不存在");
        return roundView(roundId, r);
    }

    @Transactional
    public JsonNode claimRound(ActorContext actor, UUID roundId, String key) {
        enabled(actor.actorId());
        Map<String,Object> r = one("SELECT * FROM publication_round WHERE id=? FOR UPDATE", roundId);
        if (!"HUMAN_POOL".equals(r.get("mode"))) throw error(422,"INVALID_EXECUTION_MODE","仅人工轮次可领取");
        if (!"OPEN".equals(r.get("status"))) throw error(409,"ROUND_CLOSED","轮次不可领取");
        open(project((UUID) r.get("project_id"), false));
        String op="POST:/rounds/"+roundId+"/claims", hash=hash("{}");
        JsonNode previous = replay(actor.actorId(),op,key,hash);
        if(previous!=null)return previous;
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND actor_id=? AND status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))",Boolean.class,roundId,actor.actorId())))
            throw error(409,"ALREADY_CLAIMED","本轮已领取");
        List<Map<String,Object>> slots=db.queryForList("""
                SELECT s.id, s.slot_no FROM package_slot s WHERE s.round_id=? AND NOT EXISTS
                  (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
                ORDER BY s.slot_no LIMIT 1
                """,roundId);
        if(slots.isEmpty())throw error(409,"SLOT_FULL","本轮已领满");
        UUID assignment=UUID.randomUUID();
        db.update("INSERT INTO assignment(id,slot_id,package_id,round_id,actor_id) VALUES (?,?,?,?,?)",assignment,slots.get(0).get("id"),r.get("package_id"),roundId,actor.actorId());
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
        boolean hasSubmitted = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND status='SUBMITTED')",Boolean.class,roundId));
        db.update("UPDATE publication_round SET status=?, revision=revision+1 WHERE id=?", hasSubmitted ? "CLOSED" : "CANCELLED", roundId);
        db.update("UPDATE assignment SET status='REVOKED', ended_at=CURRENT_TIMESTAMP, release_reason=? WHERE round_id=? AND status IN ('CLAIMED','IN_PROGRESS')", reason, roundId);
        db.update("DELETE FROM sample_work_claim WHERE round_id=?", roundId);
        JsonNode response = roundView(roundId, one("SELECT * FROM publication_round WHERE id=?", roundId));
        remember(actor.actorId(),op,key,hash,roundId,response);
        return response;
    }

    @Transactional
    public JsonNode reRoundPackage(ActorContext actor, UUID packageId, ReRoundInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> identity = one("""
                SELECT plan.project_id FROM task_package t
                JOIN package_plan plan ON plan.id=t.plan_id WHERE t.id=?
                """, packageId);
        UUID projectId = (UUID) identity.get("project_id");
        Map<String,Object> project = project(projectId, true);
        owner(actor, project);
        writable(project);
        Map<String,Object> pkg = one("""
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
        int roundNo = db.queryForObject("SELECT COALESCE(MAX(round_no),0)+1 FROM publication_round WHERE package_id=?", Integer.class, packageId);
        UUID roundId = UUID.randomUUID();
        db.update("""
                INSERT INTO publication_round(id,project_id,spec_version_id,package_id,round_no,mode,instructions,status)
                VALUES (?,?,?,?,?,?,?,'OPEN')
                """, roundId, pkg.get("project_id"), pkg.get("spec_version_id"), packageId, roundNo, "HUMAN_POOL", input.instructions());
        int ordinal = 1;
        for (var item : items) {
            UUID sampleId = (UUID) item.get("sample_id");
            db.update("INSERT INTO round_item(round_id,project_id,spec_version_id,sample_id,ordinal) VALUES (?,?,?,?,?)",
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
        db.update("UPDATE assignment SET status='RELEASED', ended_at=CURRENT_TIMESTAMP, release_reason=?, revision=revision+1 WHERE id=?", reason, assignmentId);
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
        db.update("UPDATE assignment SET status='REVOKED', ended_at=CURRENT_TIMESTAMP, release_reason=?, revision=revision+1 WHERE id=?", reason, assignmentId);
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
                "SELECT EXISTS(SELECT 1 FROM assignment WHERE round_id=? AND status IN ('CLAIMED','IN_PROGRESS'))", Boolean.class, roundId));
        boolean claimable = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM package_slot s WHERE s.round_id=? AND NOT EXISTS(SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status='SUBMITTED'))", Boolean.class, roundId));
        if (!active && !claimable) {
            db.update("UPDATE publication_round SET status='CLOSED', revision=revision+1 WHERE id=? AND status='OPEN'", roundId);
            db.update("DELETE FROM sample_work_claim WHERE round_id=?", roundId);
        }
    }

    private Map<String,Object> assignment(ActorContext actor,UUID id) {
        enabled(actor.actorId());
        Map<String,Object> a=one("""
                SELECT a.*,plan.project_id,plan.spec_version_id FROM assignment a
                JOIN task_package t ON t.id=a.package_id JOIN package_plan plan ON plan.id=t.plan_id
                WHERE a.id=? AND a.actor_id=?
                """,id,actor.actorId());
        open(project((UUID)a.get("project_id"),false));
        one("SELECT id FROM assignment WHERE id=? AND status IN ('CLAIMED','IN_PROGRESS','SUBMITTED') FOR SHARE",id);
        return a;
    }

    private JsonNode planView(UUID id) {
        Map<String,Object> p=one("SELECT * FROM package_plan WHERE id=?",id);
        int count=((Number)p.get("sample_count")).intValue(),size=((Number)p.get("package_size")).intValue();
        List<Integer> counts=new ArrayList<>();for(int start=0;start<count;start+=size)counts.add(Math.min(size,count-start));
        return json.valueToTree(Map.of("planId",id,"status",p.get("status"),"selectionHash",p.get("selection_hash"),
                "sampleCount",count,"packageSampleCounts",counts,"totalWorkload",(long)count*((Number)p.get("annotators_per_sample")).intValue(),
                "packages",db.queryForList("""
                        SELECT t.id AS "packageId", t.sequence_no AS "sequenceNo", t.sample_count AS "sampleCount",
                          t.required_count AS "requiredCount", r.id AS "roundId"
                        FROM task_package t JOIN publication_round r ON r.package_id=t.id AND r.round_no=1
                        WHERE t.plan_id=? ORDER BY t.sequence_no
                        """,id)));
    }
    private List<Map<String,Object>> samples(UUID batch) {
        var rows=db.queryForList("SELECT id,content_sha256 FROM sample WHERE batch_id=? ORDER BY id LIMIT ?",batch,maxSamples+1);
        if(rows.isEmpty()||rows.size()>maxSamples)throw error(422,"SAMPLE_LIMIT","批次样本为空或超过同步打包上限");return rows;
    }
    /** 按 owner 冻结的样本清单选样；可选携带同一项目内该样本的已提交结果作为参考。 */
    private List<Map<String,Object>> selectionSamples(UUID project, List<SelectionSample> input) {
        if (input == null || input.isEmpty()) throw error(422,"INVALID_REQUEST","样本范围不能为空");
        if (input.size() > maxSamples) throw error(422,"SAMPLE_LIMIT","样本范围超过同步打包上限");
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
            row.put("project_id", project);
            row.put("referenceResultId", refs.get(id));
            rows.add(row);
        }
        return rows;
    }
    /** 发布时从计划读取冻结样本；batch 计划取批次样本，selection 计划取冻结清单。 */
    private List<Map<String,Object>> planSamples(Map<String,Object> plan) {
        if (plan.get("batch_id") != null) return samples((UUID) plan.get("batch_id"));
        JsonNode list = read((String) plan.get("selection_json"));
        List<Map<String,Object>> rows = new ArrayList<>();
        for (JsonNode node : list) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", UUID.fromString(node.path("id").asText()));
            row.put("project_id", UUID.fromString(node.path("project_id").asText()));
            row.put("referenceResultId", node.hasNonNull("referenceResultId") ? UUID.fromString(node.path("referenceResultId").asText()) : null);
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
    private Map<String,Object> project(UUID id,boolean exclusive){return one("SELECT * FROM project WHERE id=? FOR "+(exclusive?"UPDATE":"SHARE"),id);}
    private void enabled(UUID actor){if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor).get("status")))throw error(403,"LABELING_DISABLED","标注账号已停用");}
    private void owner(ActorContext actor,Map<String,Object> project){if(!actor.actorId().equals(project.get("owner_actor_id")))throw error(404,"RESOURCE_NOT_FOUND","项目不存在");}
    private void writable(Map<String,Object> p){if(!List.of("DRAFT","OPEN").contains(p.get("status")))throw error(409,"PROJECT_CLOSED","项目当前不可发布");}
    private void open(Map<String,Object> p){if(!"OPEN".equals(p.get("status"))||!"PLATFORM".equals(p.get("visibility")))throw error(409,"PROJECT_CLOSED","项目当前不可访问");}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","请求资源不存在");return rows.get(0);}
    private void pageLimit(int limit){if(limit<1||limit>100)throw error(400,"INVALID_REQUEST","分页大小应为1至100");}
    private UUID cursor(String cursor){try{return cursor==null?new UUID(0,0):UUID.fromString(cursor);}catch(IllegalArgumentException ex){throw error(400,"INVALID_CURSOR","分页游标无效");}}
    private JsonNode page(List<Map<String,Object>> rows,int limit,String id){boolean more=rows.size()>limit;var selected=more?rows.subList(0,limit):rows;Map<String,Object> result=new LinkedHashMap<>();result.put("items",selected);result.put("nextCursor",more?selected.get(selected.size()-1).get(id).toString():null);return json.valueToTree(result);}
    private JsonNode replay(UUID actor,String op,String key,String hash){
        if(key==null||key.isBlank()||key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+op+":"+key);
        return db.query("SELECT request_hash,response_body::text FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",rs->{
            if(!rs.next())return null;if(!hash.equals(rs.getString(1)))throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
            return read(rs.getString(2));},actor,op,key);
    }
    private void remember(UUID actor,String op,String key,String hash,UUID id,JsonNode response){db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body) VALUES (?,?,?,?,?,?::jsonb)",actor,op,key,hash,id,write(response));}
    private JsonNode read(String s){try{return json.readTree(s);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    public static BusinessException error(int status,String code,String message){return new BusinessException(HttpStatus.valueOf(status),code,message);}
    public record PlanInput(UUID batchId, UUID specVersionId, Integer packageSize, Integer annotatorsPerSample, List<SelectionSample> samples, String instructions) {}
    public record SelectionSample(@NotNull UUID sampleId, UUID referenceResultId) {}
    public record ReRoundInput(String instructions) {}
}
