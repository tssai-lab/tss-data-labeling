package com.tss.labeling.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

@Service
public class ResultService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ResultService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    @Transactional
    public JsonNode save(ActorContext actor, UUID assignment, UUID sample, ResultController.DraftInput input) {
        Map<String,Object> a = lockedAssignment(actor, assignment, true);
        active(a);
        one("SELECT sample_id FROM package_item WHERE package_id=? AND sample_id=?",a.get("package_id"),sample);
        validate(a, input.resultFormat(), input.result(), input.explicitDone());
        Long current = db.query("SELECT revision FROM annotation_draft WHERE assignment_id=? AND sample_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, assignment, sample);
        if (current.longValue() != input.baseRevision().longValue()) throw error(409,"REVISION_CONFLICT","草稿已变化，请重新读取");
        db.update("""
                INSERT INTO annotation_draft(assignment_id,package_id,sample_id,result_format,result,explicit_done,revision)
                VALUES (?,?,?,?,?::jsonb,?,1)
                ON CONFLICT (assignment_id,sample_id) DO UPDATE SET result_format=EXCLUDED.result_format,
                  result=EXCLUDED.result,explicit_done=EXCLUDED.explicit_done,
                  revision=annotation_draft.revision+1,saved_at=CURRENT_TIMESTAMP
                """,assignment,a.get("package_id"),sample,input.resultFormat(),write(input.result()),input.explicitDone());
        db.update("UPDATE assignment SET status='IN_PROGRESS',revision=revision+1 WHERE id=?",assignment);
        return draft(assignment,sample);
    }

    @Transactional
    public JsonNode getDraft(ActorContext actor, UUID assignment, UUID sample) {
        Map<String,Object> a=lockedAssignment(actor,assignment,false);
        if (!List.of("CLAIMED","IN_PROGRESS","SUBMITTED").contains(a.get("status")))
            throw error(409,"ASSIGNMENT_CLOSED","领取已结束");
        one("SELECT sample_id FROM package_item WHERE package_id=? AND sample_id=?",a.get("package_id"),sample);
        return draft(assignment,sample);
    }

    @Transactional
    public JsonNode submit(ActorContext actor, UUID assignment, ResultController.SubmitInput input, String key) {
        Map<String,Object> a=lockedAssignment(actor,assignment,true);
        if (key==null||key.isBlank()||key.length()>200) throw error(400,"INVALID_REQUEST","需要有效幂等键");
        TreeMap<String,Long> revisions=new TreeMap<>();
        for (var item:input.items()) {
            if(revisions.put(item.sampleId().toString(),item.revision())!=null)
                throw error(422,"INVALID_RESULT","提交清单不能含重复样本");
        }
        String requestHash=hash(write(revisions)), op="POST:/assignments/"+assignment+"/submissions";
        JsonNode replay=db.query("SELECT request_hash,response_body::text FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",rs->{
            if(!rs.next())return null;
            if(!requestHash.equals(rs.getString(1)))throw error(409,"IDEMPOTENCY_CONFLICT","幂等键对应不同提交内容");
            return read(rs.getString(2));
        },actor.actorId(),op,key);
        if(replay!=null)return replay;
        active(a);
        if(!"IN_PROGRESS".equals(a.get("status")))throw error(422,"INCOMPLETE_PACKAGE","请先完成包内标注");
        var rows=db.queryForList("""
                SELECT i.sample_id,d.revision,d.explicit_done,d.result_format,d.result::text AS result
                FROM package_item i LEFT JOIN annotation_draft d ON d.assignment_id=? AND d.sample_id=i.sample_id
                WHERE i.package_id=? ORDER BY i.sample_id
                """,assignment,a.get("package_id"));
        Set<String> expected=new HashSet<>();for(var row:rows)expected.add(row.get("sample_id").toString());
        if(!expected.equals(revisions.keySet()))throw error(422,"INCOMPLETE_PACKAGE","提交清单必须包含且仅包含整包样本");
        for(var row:rows){
            if(row.get("revision")==null||!Boolean.TRUE.equals(row.get("explicit_done")))throw error(422,"INCOMPLETE_PACKAGE","存在未完成样本");
            if(((Number)row.get("revision")).longValue()!=revisions.get(row.get("sample_id").toString()))throw error(409,"REVISION_CONFLICT","提交版本不是最新草稿");
            validate(a,(String)row.get("result_format"),read((String)row.get("result")),true);
        }
        UUID id=UUID.randomUUID();
        db.update("""
                INSERT INTO submission(id,assignment_id,package_id,plan_id,project_id,spec_version_id,submitted_by,content_hash)
                VALUES (?,?,?,?,?,?,?,?)
                """,id,assignment,a.get("package_id"),a.get("plan_id"),a.get("project_id"),a.get("spec_version_id"),actor.actorId(),hash(write(rows)));
        db.update("""
                INSERT INTO submission_item(submission_id,package_id,sample_id,draft_revision,result_format,result)
                SELECT ?,package_id,sample_id,revision,result_format,result FROM annotation_draft WHERE assignment_id=?
                """,id,assignment);
        db.update("UPDATE assignment SET status='SUBMITTED',revision=revision+1,ended_at=CURRENT_TIMESTAMP WHERE id=?",assignment);
        JsonNode response=json.valueToTree(Map.of("submissionId",id,"assignmentId",assignment,"status","SUBMITTED","sampleCount",rows.size()));
        db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body) VALUES (?,?,?,?,?,?::jsonb)",actor.actorId(),op,key,requestHash,id,write(response));
        return response;
    }

    @Transactional
    public JsonNode submission(ActorContext actor,UUID id) {
        enabled(actor.actorId());
        Map<String,Object> result=one("""
                SELECT s.id AS "submissionId",s.assignment_id AS "assignmentId",s.package_id AS "packageId",
                  s.project_id AS "projectId",s.spec_version_id AS "specVersionId",s.submitted_by AS "submittedBy",
                  s.submitted_at::text AS "submittedAt",s.content_hash AS "contentHash"
                FROM submission s JOIN project p ON p.id=s.project_id
                WHERE s.id=? AND (s.submitted_by=? OR p.owner_actor_id=?)
                """,id,actor.actorId(),actor.actorId());
        var items=db.queryForList("SELECT sample_id AS \"sampleId\",draft_revision AS revision,result_format AS \"resultFormat\",result::text AS result FROM submission_item WHERE submission_id=? ORDER BY sample_id",id);
        for(var item:items)item.put("result",read((String)item.get("result")));
        result.put("items",items);
        return json.valueToTree(result);
    }

    @Transactional
    public JsonNode list(ActorContext actor,UUID project,int limit,String cursor) {
        enabled(actor.actorId());
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=?",project,actor.actorId());
        if(limit<1||limit>100)throw error(400,"INVALID_REQUEST","分页大小应为1至100");
        UUID after;
        try{after=cursor==null?new UUID(0,0):UUID.fromString(cursor);}
        catch(IllegalArgumentException ex){throw error(400,"INVALID_CURSOR","游标无效");}
        var rows=db.queryForList("SELECT id AS \"submissionId\",assignment_id AS \"assignmentId\",package_id AS \"packageId\",submitted_by AS \"submittedBy\",submitted_at::text AS \"submittedAt\" FROM submission WHERE project_id=? AND id>? ORDER BY id LIMIT ?",project,after,limit+1);
        boolean more=rows.size()>limit;
        var items=more?rows.subList(0,limit):rows;
        Map<String,Object> response=new LinkedHashMap<>();response.put("items",items);
        response.put("nextCursor",more?items.get(items.size()-1).get("submissionId").toString():null);
        return json.valueToTree(response);
    }

    private Map<String,Object> lockedAssignment(ActorContext actor,UUID id,boolean writing) {
        enabled(actor.actorId());
        Map<String,Object> a=one("""
                SELECT a.package_id,p.project_id,p.spec_version_id,p.id AS plan_id
                FROM assignment a JOIN task_package t ON t.id=a.package_id JOIN package_plan p ON p.id=t.plan_id
                WHERE a.id=? AND a.actor_id=?
                """,id,actor.actorId());
        Map<String,Object> project=one("SELECT status FROM project WHERE id=? FOR SHARE",a.get("project_id"));
        Map<String,Object> current=one("SELECT status,revision FROM assignment WHERE id=? AND actor_id=? FOR UPDATE",id,actor.actorId());
        a.putAll(current);
        // 已提交的个人结果允许在项目暂停后读回，工作草稿仍要求项目开放。
        if(!"OPEN".equals(project.get("status"))&&(writing||!"SUBMITTED".equals(a.get("status"))))throw error(409,"PROJECT_CLOSED","项目当前不可标注");
        return a;
    }
    private void active(Map<String,Object> a){if(!List.of("CLAIMED","IN_PROGRESS").contains(a.get("status")))throw error(409,"ASSIGNMENT_CLOSED","领取已结束，结果不能覆盖");}
    private void validate(Map<String,Object> a,String format,JsonNode result,boolean done){
        Map<String,Object> spec=one("SELECT task_type,labels::text AS labels FROM spec_version WHERE id=?",a.get("spec_version_id"));
        if(!"IMAGE_CLASSIFICATION".equals(spec.get("task_type"))||!"image-classification-v1".equals(format))throw error(422,"UNSUPPORTED_FORMAT","首期仅支持图片单标签分类结果");
        if(result==null||!result.isObject()||result.size()>1||(result.size()==1&&!result.has("labelId")))throw error(422,"INVALID_RESULT","分类结果必须为 labelId 对象");
        if(result.isEmpty()&&!done)return;
        JsonNode label=result.get("labelId");
        if(label==null||!label.isTextual()||label.asText().isBlank())throw error(422,"INVALID_RESULT","请选择一个标签");
        for(var allowed:read((String)spec.get("labels")))if(label.asText().equals(allowed.path("id").asText()))return;
        throw error(422,"INVALID_RESULT","标签不属于领取绑定的规范版本");
    }
    private JsonNode draft(UUID assignment,UUID sample){
        var rows=db.queryForList("SELECT revision,explicit_done AS \"explicitDone\",result_format AS \"resultFormat\",result::text AS result,saved_at::text AS \"savedAt\" FROM annotation_draft WHERE assignment_id=? AND sample_id=?",assignment,sample);
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("assignmentId",assignment);value.put("sampleId",sample);
        if(rows.isEmpty()){value.put("revision",0);value.put("explicitDone",false);value.put("resultFormat","image-classification-v1");value.put("result",json.createObjectNode());value.put("savedAt",null);}
        else{value.putAll(rows.get(0));value.put("result",read((String)value.get("result")));}
        return json.valueToTree(value);
    }
    private void enabled(UUID actor){if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor).get("status")))throw error(403,"LABELING_DISABLED","标注账号已停用");}
    private Map<String,Object> one(String sql,Object... args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","资源不存在");return rows.get(0);}
    private JsonNode read(String value){try{return json.readTree(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
