package com.tss.labeling.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.ImportService.SampleFile;
import com.tss.labeling.imports.ImportStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class TaskService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportStorage storage;
    private final int maxSamples;
    private final int maxPackageSize;
    private final int maxAnnotators;

    public TaskService(JdbcTemplate db, ObjectMapper json, ImportStorage storage,
            @Value("${labeling.tasks.max-samples:10000}") int maxSamples,
            @Value("${labeling.tasks.max-package-size:1000}") int maxPackageSize,
            @Value("${labeling.tasks.max-annotators:10}") int maxAnnotators) {
        this.db = db; this.json = json; this.storage = storage;
        this.maxSamples = maxSamples; this.maxPackageSize = maxPackageSize; this.maxAnnotators = maxAnnotators;
    }

    @Transactional
    public JsonNode createPlan(ActorContext actor, UUID project, PlanInput input, String key) {
        enabled(actor.actorId());
        Map<String,Object> p = project(project, false);
        owner(actor, p); writable(p);
        int size = input.packageSize() == null ? 10 : input.packageSize();
        int people = input.annotatorsPerSample() == null ? 1 : input.annotatorsPerSample();
        if (size < 1 || size > maxPackageSize || people < 1 || people > maxAnnotators)
            throw error(400, "INVALID_REQUEST", "包大小或标注人数超出限制");
        String op = "POST:/projects/" + project + "/package-plans", hash = hash(write(input));
        JsonNode previous = replay(actor.actorId(), op, key, hash);
        if (previous != null) return previous;
        Map<String,Object> batch = one("SELECT * FROM import_batch WHERE id = ? AND project_id = ? FOR SHARE", input.batchId(), project);
        if (!"READY".equals(batch.get("status"))) throw error(409,"BATCH_NOT_READY","数据尚未导入完成");
        Map<String,Object> spec = one("SELECT * FROM spec_version WHERE id = ? AND project_id = ?", input.specVersionId(), project);
        if (!Objects.equals(spec.get("task_type"), p.get("task_type"))) throw error(409,"SPEC_MISMATCH","规范任务类型不匹配");
        List<Map<String,Object>> samples = samples(input.batchId());
        UUID plan = UUID.randomUUID();
        db.update("""
                INSERT INTO package_plan(id, project_id, batch_id, spec_version_id, package_size,
                  annotators_per_sample, sample_count, selection_hash) VALUES (?,?,?,?,?,?,?,?)
                """, plan, project, input.batchId(), input.specVersionId(), size, people, samples.size(), selection(samples));
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
            UUID batchId = (UUID) plan.get("batch_id");
            Map<String,Object> batch = one("SELECT * FROM import_batch WHERE id = ? FOR UPDATE",batchId);
            if (!"READY".equals(batch.get("status"))) throw error(409,"BATCH_NOT_READY","批次未就绪");
            if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM package_plan WHERE batch_id = ? AND published_at IS NOT NULL)",Boolean.class,batchId)))
                throw error(409,"BATCH_ALREADY_PUBLISHED","同一批次只能发布一次");
            List<Map<String,Object>> samples = samples(batchId);
            if (!selection(samples).equals(expectedHash)) throw error(409,"SELECTION_CHANGED","样本集合已改变");
            int size = ((Number)plan.get("package_size")).intValue(), people = ((Number)plan.get("annotators_per_sample")).intValue();
            // ponytail: 有界同步发布，超过样本上限需拆分批次；大规模发布再引入后台任务。
            for (int start=0, sequence=1; start<samples.size(); start+=size, sequence++) {
                UUID pkg = UUID.randomUUID(); int count = Math.min(size, samples.size()-start);
                db.update("INSERT INTO task_package(id,plan_id,sequence_no,sample_count,required_count) VALUES (?,?,?,?,?)",pkg,planId,sequence,count,people);
                for (int i=0;i<count;i++) db.update("INSERT INTO package_item(package_id,plan_id,batch_id,sample_id,ordinal) VALUES (?,?,?,?,?)",pkg,planId,batchId,samples.get(start+i).get("id"),i+1);
                for (int slot=1;slot<=people;slot++) db.update("INSERT INTO package_slot(id,package_id,slot_no) VALUES (?,?,?)",UUID.randomUUID(),pkg,slot);
            }
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
                  t.sample_count AS "sampleCount", t.required_count AS "requiredCount",
                  t.required_count - (SELECT count(*) FROM assignment a WHERE a.package_id=t.id
                    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED')) AS "remainingSlots"
                FROM task_package t JOIN package_plan plan ON plan.id=t.plan_id JOIN project p ON p.id=plan.project_id
                WHERE p.status='OPEN' AND p.visibility='PLATFORM' AND t.status='OPEN' AND plan.status='PUBLISHED'
                  AND t.id > ? AND (? = '' OR p.task_type = ?)
                  AND NOT EXISTS(SELECT 1 FROM assignment a WHERE a.package_id=t.id AND a.actor_id=?
                    AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
                  AND EXISTS(SELECT 1 FROM package_slot s WHERE s.package_id=t.id AND NOT EXISTS
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
        // 同一包领取串行，数据库唯一索引再防止同人重复和席位超领。
        Map<String,Object> pkg=one("SELECT * FROM task_package WHERE id=? FOR UPDATE",packageId);
        if(!"OPEN".equals(pkg.get("status")))throw error(409,"PACKAGE_CLOSED","任务包不可领取");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assignment WHERE package_id=? AND actor_id=? AND status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))",Boolean.class,packageId,actor.actorId())))
            throw error(409,"ALREADY_CLAIMED","已领取此任务包");
        List<Map<String,Object>> slots=db.queryForList("""
                SELECT s.id, s.slot_no FROM package_slot s WHERE s.package_id=? AND NOT EXISTS
                  (SELECT 1 FROM assignment a WHERE a.slot_id=s.id AND a.status IN ('CLAIMED','IN_PROGRESS','SUBMITTED'))
                ORDER BY s.slot_no LIMIT 1
                """,packageId);
        if(slots.isEmpty())throw error(409,"SLOT_FULL","任务包已领满");
        UUID assignment=UUID.randomUUID();
        db.update("INSERT INTO assignment(id,slot_id,package_id,actor_id) VALUES (?,?,?,?)",assignment,slots.get(0).get("id"),packageId,actor.actorId());
        JsonNode response=json.valueToTree(Map.of("assignmentId",assignment,"packageId",packageId,"slotNo",slots.get(0).get("slot_no"),"status","CLAIMED","revision",1));
        remember(actor.actorId(),op,key,hash,assignment,response);
        return response;
    }

    @Transactional
    public JsonNode mine(ActorContext actor,int limit,String cursor) {
        enabled(actor.actorId());pageLimit(limit);
        return page(db.queryForList("""
                SELECT a.id AS "assignmentId", a.package_id AS "packageId", a.status, a.revision,
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
                  d.result_format AS "resultFormat", d.result::text AS result
                FROM package_item i JOIN sample s ON s.id=i.sample_id
                LEFT JOIN annotation_draft d ON d.assignment_id=? AND d.sample_id=s.id
                WHERE i.package_id=? AND s.id>?
                ORDER BY s.id LIMIT ?
                """,assignment,a.get("package_id"),cursor(cursor),limit+1);
        for(var row:rows) {
            if(row.get("result")!=null)row.put("result",read((String)row.get("result")));
            row.put("fileUrl","/api/labeling/v1/assignments/"+assignment+"/samples/"+row.get("sampleId")+"/file");
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
                "packages",db.queryForList("SELECT id AS \"packageId\",sequence_no AS \"sequenceNo\",sample_count AS \"sampleCount\",required_count AS \"requiredCount\" FROM task_package WHERE plan_id=? ORDER BY sequence_no",id)));
    }
    private List<Map<String,Object>> samples(UUID batch) {
        var rows=db.queryForList("SELECT id,content_sha256 FROM sample WHERE batch_id=? ORDER BY id LIMIT ?",batch,maxSamples+1);
        if(rows.isEmpty()||rows.size()>maxSamples)throw error(422,"SAMPLE_LIMIT","批次样本为空或超过同步打包上限");return rows;
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
    public record PlanInput(UUID batchId,UUID specVersionId,Integer packageSize,Integer annotatorsPerSample){}
}
