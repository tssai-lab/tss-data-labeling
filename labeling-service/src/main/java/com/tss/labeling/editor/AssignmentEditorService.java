package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.tasks.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** All editor writes and reads are authorized against the current assignment, never a browser-supplied actor. */
@Service
public class AssignmentEditorService {
    private final JdbcTemplate db;private final ObjectMapper json;private final TaskService tasks;
    private final LabelStudioClient ls;private final TransactionTemplate tx;private final boolean enabled;
    private final Set<String> projects;
    public AssignmentEditorService(JdbcTemplate db,ObjectMapper json,TaskService tasks,LabelStudioClient ls,
            PlatformTransactionManager manager,@Value("${labeling.assignment-editor.enabled:false}") boolean enabled,
            @Value("${labeling.editor.projects:}") String allowed){
        this.db=db;this.json=json;this.tasks=tasks;this.ls=ls;this.tx=new TransactionTemplate(manager);
        this.enabled=enabled;this.projects=new HashSet<>(Arrays.asList(allowed.split(",")));
    }
    public record Draft(long revision,boolean explicitDone,JsonNode result,String resultFormat,String savedAt) {}
    public record EditorSession(String editorSessionId,String expiresAt,String assignmentId,String sampleId,
                                String actorId,String config,Draft draft,String mode,String editorUrl,String launchExpiresAt) {}
    public synchronized EditorSession open(ActorContext actor,UUID assignment,UUID sample,String requestKey) {
        key(requestKey);
        var context=tx.execute(s->guard(actor,assignment,sample,true));
        var replay=sessionReplay(actor,assignment,sample,requestKey);if(replay!=null)return replay;
        String config=ClassificationTemplate.xml(read(context.get("labels")),(String)context.get("instructions"));
        var mapping=db.queryForList("SELECT * FROM editor_mapping WHERE assignment_id=? AND sample_id=?",assignment,sample);
        if(mapping.isEmpty()) {
            var file=tasks.file(actor,assignment,sample);byte[] bytes;
            try(var in=file.resource().getInputStream()){bytes=in.readNBytes(64*1024*1024+1);}
            catch(Exception e){throw fail(503,"STORAGE_UNAVAILABLE","图片不可读取");}
            if(bytes.length!=file.sizeBytes()||bytes.length>64*1024*1024)throw fail(413,"UPLOAD_TOO_LARGE","图片超出编辑器范围");
            String hash=hash(bytes);
            int inserted=tx.execute(s->{var c=guard(actor,assignment,sample,true);
                return db.update("""
                    INSERT INTO editor_mapping(assignment_id,package_id,sample_id,actor_id,spec_version_id,sync_status,image_sha256)
                    VALUES (?,?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING
                    """,assignment,c.get("package_id"),sample,actor.actorId(),c.get("spec_version_id"),hash);});
            if(inserted==1) {
                // This PENDING row is committed before external mutations. Never blindly retry task creation.
                var client=ls.connect();String author=LabelStudioClient.numeric(client.call("GET","/api/current-user/whoami",null).path("id").asText());
                String project=LabelStudioClient.numeric(client.call("POST","/api/projects/",Map.of("title","Assignment "+assignment.toString().substring(0,8),"label_config",config,
                    "description","Platform-controlled assignment editor. Do not edit through shared admin interface.")).path("id").asText());
                db.update("UPDATE editor_mapping SET external_project_id=?,external_author_id=? WHERE assignment_id=? AND sample_id=?",project,author,assignment,sample);
                String task=client.importImage(project,bytes,file.mediaType(),sample+(file.mediaType().equals("image/png")?".png":".jpg"));
                tx.executeWithoutResult(s->{guard(actor,assignment,sample,true);
                    db.update("UPDATE editor_mapping SET external_task_id=?,sync_status='READY' WHERE assignment_id=? AND sample_id=?",task,assignment,sample);});
            }
        }
        return tx.execute(s->{guard(actor,assignment,sample,true);
            var previous=sessionReplay(actor,assignment,sample,requestKey);if(previous!=null)return previous;
            var m=mapping(assignment,sample);
            if(!List.of("READY","SAVED").contains(m.get("sync_status")))throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","编辑器导入未完成，请管理员对账后重试");
            UUID session=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(1800);
            String ticket=secret();Instant launchExpires=Instant.now().plusSeconds(60);
            var response=new EditorSession(session.toString(),expires.toString(),assignment.toString(),sample.toString(),actor.actorId().toString(),config,draft(assignment,sample),"EMBEDDED_LABEL_STUDIO",
                "/api/labeling/v1/editor-ui/"+session+"/launch?ticket="+ticket,launchExpires.toString());
            db.update("INSERT INTO editor_session(id,assignment_id,sample_id,actor_id,expires_at,launch_hash,launch_expires_at,request_key,response_body) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",session,assignment,sample,actor.actorId(),java.sql.Timestamp.from(expires),hash(ticket.getBytes(StandardCharsets.UTF_8)),java.sql.Timestamp.from(launchExpires),requestKey,json.valueToTree(response).toString());
            return response;});
    }
    private EditorSession sessionReplay(ActorContext actor,UUID assignment,UUID sample,String key){
        var rows=db.queryForList("SELECT sample_id,response_body::text AS body FROM editor_session WHERE actor_id=? AND assignment_id=? AND request_key=?",actor.actorId(),assignment,key);
        if(rows.isEmpty())return null;
        if(!sample.equals(rows.get(0).get("sample_id")))throw fail(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于另一张图片");
        try{return json.treeToValue(read(rows.get(0).get("body")),EditorSession.class);}catch(Exception e){throw fail(503,"INVALID_STATE","会话记录异常");}
    }
    private static void key(String key){if(key==null||key.isBlank()||key.length()>200)throw fail(400,"INVALID_REQUEST","需要有效的Idempotency-Key");}
    public record BrowserSession(ActorContext actor,UUID assignment,UUID sample,String config,Draft draft,String actorDisplayName,String sourcePath) {}
    public String launch(UUID session,String ticket) {
        if(ticket==null||ticket.length()>128)throw fail(401,"UNAUTHENTICATED","编辑器入口无效");
        return tx.execute(s->{var rows=db.queryForList("SELECT * FROM editor_session WHERE id=? AND launch_hash=? AND launch_expires_at>CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP AND consumed=false FOR UPDATE",session,hash(ticket.getBytes(StandardCharsets.UTF_8)));
            if(rows.isEmpty())throw fail(401,"UNAUTHENTICATED","入口已使用或过期，请从平台重新打开");var r=rows.get(0);
            guard(new ActorContext((UUID)r.get("actor_id"),"ENABLED",false),(UUID)r.get("assignment_id"),(UUID)r.get("sample_id"),true);
            String cookie=secret();db.update("UPDATE editor_session SET consumed=true,cookie_hash=? WHERE id=?",hash(cookie.getBytes(StandardCharsets.UTF_8)),session);return cookie;});
    }
    public BrowserSession browser(UUID session,String cookie) {
        if(cookie==null||cookie.length()>128)throw fail(401,"UNAUTHENTICATED","请从平台打开编辑器");
        return tx.execute(s->{var rows=db.queryForList("SELECT * FROM editor_session WHERE id=? AND cookie_hash=? AND consumed=true AND expires_at>CURRENT_TIMESTAMP",session,hash(cookie.getBytes(StandardCharsets.UTF_8)));
            if(rows.isEmpty())throw fail(401,"UNAUTHENTICATED","编辑器会话无效或过期");var r=rows.get(0);
            var actor=new ActorContext((UUID)r.get("actor_id"),"ENABLED",false);UUID assignment=(UUID)r.get("assignment_id"),sample=(UUID)r.get("sample_id");
            var c=guard(actor,assignment,sample,true);return new BrowserSession(actor,assignment,sample,ClassificationTemplate.xml(read(c.get("labels")),(String)c.get("instructions")),draft(assignment,sample),
                db.queryForObject("SELECT display_name FROM actor WHERE id=?",String.class,actor.actorId()),db.queryForObject("SELECT source_path FROM sample WHERE id=?",String.class,sample));});
    }
    private static String secret(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public Draft get(ActorContext actor,UUID assignment,UUID sample){return tx.execute(s->{guard(actor,assignment,sample,false);return draft(assignment,sample);});}
    public Draft save(ActorContext actor,UUID assignment,UUID sample,UUID session,long baseRevision,boolean done,String format,JsonNode result) {
        return tx.execute(s->{var c=guard(actor,assignment,sample,true);
            if(db.queryForObject("SELECT COUNT(*) FROM editor_session WHERE id=? AND assignment_id=? AND sample_id=? AND actor_id=? AND expires_at>CURRENT_TIMESTAMP",Integer.class,session,assignment,sample,actor.actorId())!=1)
                throw fail(409,"EDITOR_SESSION_EXPIRED","编辑会话已过期或不属于当前任务，请重新打开");
            if(!done||!"label-studio-json".equals(format))throw fail(422,"INVALID_RESULT","请选择一个类别后保存");
            JsonNode canonical=canonical(result,read(c.get("labels")));
            Draft current=draft(assignment,sample);
            // A lost response can replay the same save without incrementing the revision again.
            if(current.revision()==baseRevision+1&&current.explicitDone()&&current.result().equals(canonical))return current;
            if(current.revision()!=baseRevision)throw fail(409,"REVISION_CONFLICT","结果已更新，请重新加载后保存");
            var mapping=mapping(assignment,sample);
            if(!List.of("READY","SAVED").contains(mapping.get("sync_status")))throw fail(503,"EDITOR_UNAVAILABLE","编辑器尚未就绪");
            var client=ls.connect();String task=number(mapping,"external_task_id"),author=number(mapping,"external_author_id");
            String annotation=mapping.get("external_annotation_id")==null?null:number(mapping,"external_annotation_id");
            if(annotation==null) {
                var existing=client.call("GET","/api/tasks/"+task+"/",null).path("annotations");
                if(existing.size()>1)throw fail(409,"EDITOR_CONFLICT","外部任务出现多份结果，需要对账");
                if(existing.size()==1){verifyExternal(existing.get(0),task,author);annotation=LabelStudioClient.numeric(existing.get(0).path("id").asText());}
            }
            JsonNode saved=client.call(annotation==null?"POST":"PATCH",annotation==null?"/api/tasks/"+task+"/annotations/":"/api/annotations/"+annotation+"/",
                    Map.of("result",canonical,"was_cancelled",false));
            String id=LabelStudioClient.numeric(saved.path("id").asText());
            JsonNode external=client.call("GET","/api/annotations/"+id+"/",null);
            verifyExternal(external,task,author);
            JsonNode back=canonical(external.path("result"),read(c.get("labels")));
            if(!back.equals(canonical))throw fail(409,"EDITOR_CONFLICT","外部保存结果与本次请求不一致");
            persist(actor,assignment,sample,c,back,baseRevision);
            db.update("UPDATE editor_mapping SET external_annotation_id=?,result_hash=?,base_revision=?,sync_status='SAVED' WHERE assignment_id=? AND sample_id=?",
                    id,hash(back.toString().getBytes(StandardCharsets.UTF_8)),baseRevision+1,assignment,sample);
            return draft(assignment,sample);
        });
    }
    public Draft sync(ActorContext actor,UUID assignment,UUID sample,String requestKey) {
        key(requestKey);
        return tx.execute(s->{var c=guard(actor,assignment,sample,true);
            String op="POST:/assignments/"+assignment+"/editor-sync",digest=hash(sample.toString().getBytes(StandardCharsets.UTF_8));
            var previous=db.queryForList("SELECT request_hash,response_body::text AS body FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",actor.actorId(),op,requestKey);
            if(!previous.isEmpty()){
                if(!digest.equals(previous.get(0).get("request_hash")))throw fail(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
                try{return json.treeToValue(read(previous.get(0).get("body")),Draft.class);}catch(Exception e){throw fail(503,"INVALID_STATE","同步记录异常");}
            }
            var m=mapping(assignment,sample);
            if(!"SAVED".equals(m.get("sync_status")))throw fail(503,"EDITOR_UNAVAILABLE","尚无已验证的编辑器保存结果");
            JsonNode a=ls.connect().call("GET","/api/annotations/"+number(m,"external_annotation_id")+"/",null);
            verifyExternal(a,number(m,"external_task_id"),number(m,"external_author_id"));
            JsonNode back=canonical(a.path("result"),read(c.get("labels")));
            if(!hash(back.toString().getBytes(StandardCharsets.UTF_8)).equals(m.get("result_hash")))throw fail(409,"EDITOR_CONFLICT","外部结果已被其他入口修改，需要对账");
            Draft d=draft(assignment,sample);
            if(!d.result().equals(back))throw fail(409,"REVISION_CONFLICT","平台结果版本不一致");
            db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body) VALUES (?,?,?,?,?,?::jsonb)",actor.actorId(),op,requestKey,digest,assignment,json.valueToTree(d).toString());return d;});
    }
    private void persist(ActorContext actor,UUID assignment,UUID sample,Map<String,Object> c,JsonNode result,long base) {
        db.update("""
            INSERT INTO annotation_draft(assignment_id,package_id,sample_id,spec_version_id,result_format,result,explicit_done,revision)
            VALUES (?,?,?,?,'label-studio-json',?::jsonb,true,?)
            ON CONFLICT (assignment_id,sample_id) DO UPDATE SET result=EXCLUDED.result,explicit_done=true,
              revision=EXCLUDED.revision,saved_at=CURRENT_TIMESTAMP
            """,assignment,c.get("package_id"),sample,c.get("spec_version_id"),result.toString(),base+1);
        db.update("UPDATE assignment SET status='IN_PROGRESS' WHERE id=? AND status='CLAIMED'",assignment);
    }
    private Map<String,Object> guard(ActorContext actor,UUID assignment,UUID sample,boolean writing) {
        var found=db.queryForList("""
            SELECT a.package_id,a.actor_id,a.status,p.id AS project_id,plan.spec_version_id,s.labels::text AS labels,s.instructions
            FROM assignment a JOIN task_package t ON t.id=a.package_id JOIN package_plan plan ON plan.id=t.plan_id
            JOIN project p ON p.id=plan.project_id JOIN spec_version s ON s.id=plan.spec_version_id
            WHERE a.id=? AND a.actor_id=?
            """,assignment,actor.actorId());
        if(found.isEmpty())throw fail(404,"RESOURCE_NOT_FOUND","任务不存在");var c=found.get(0);
        if(!enabled||!(projects.contains("*")||projects.contains(c.get("project_id").toString())))throw fail(404,"RESOURCE_NOT_FOUND","编辑器未为此项目启用");
        var p=db.queryForMap("SELECT status FROM project WHERE id=? FOR SHARE",c.get("project_id"));
        var pkg=db.queryForMap("SELECT status FROM task_package WHERE id=? FOR SHARE",c.get("package_id"));
        var a=db.queryForMap("SELECT status FROM assignment WHERE id=? AND actor_id=? FOR UPDATE",assignment,actor.actorId());
        if(!"ENABLED".equals(db.queryForObject("SELECT status FROM actor WHERE id=? FOR SHARE",String.class,actor.actorId())))throw fail(403,"LABELING_DISABLED","账号已停用");
        if(!"OPEN".equals(p.get("status")))throw fail(409,"PROJECT_PAUSED","项目当前不可标注");
        if(!"OPEN".equals(pkg.get("status")))throw fail(409,"ASSIGNMENT_CLOSED","任务包当前不可标注");
        if(!List.of("CLAIMED","IN_PROGRESS").contains(a.get("status"))&&(writing||!"SUBMITTED".equals(a.get("status"))))throw fail(409,"ASSIGNMENT_CLOSED","领取已结束，不能继续编辑");
        if(db.queryForObject("SELECT COUNT(*) FROM package_item WHERE package_id=? AND sample_id=?",Integer.class,c.get("package_id"),sample)!=1)throw fail(404,"RESOURCE_NOT_FOUND","图片不属于该任务");
        return c;
    }
    private Map<String,Object> mapping(UUID assignment,UUID sample){var rows=db.queryForList("SELECT * FROM editor_mapping WHERE assignment_id=? AND sample_id=? FOR UPDATE",assignment,sample);
        if(rows.isEmpty())throw fail(503,"EDITOR_UNAVAILABLE","请先打开编辑器");return rows.get(0);}
    private Draft draft(UUID assignment,UUID sample){var rows=db.queryForList("SELECT revision,explicit_done,result::text AS result,saved_at FROM annotation_draft WHERE assignment_id=? AND sample_id=?",assignment,sample);
        if(rows.isEmpty())return new Draft(0,false,json.createArrayNode(),"label-studio-json",null);var r=rows.get(0);
        return new Draft(((Number)r.get("revision")).longValue(),(Boolean)r.get("explicit_done"),read(r.get("result")),"label-studio-json",((java.sql.Timestamp)r.get("saved_at")).toInstant().toString());}
    public static JsonNode canonical(JsonNode result,JsonNode labels) {
        if(!result.isArray()||result.size()!=1)throw fail(422,"INVALID_RESULT","单选分类只接受一个结果");var r=result.get(0);
        if(!"category".equals(r.path("from_name").asText())||!"image".equals(r.path("to_name").asText())||!"choices".equals(r.path("type").asText()))throw fail(422,"INVALID_RESULT","分类格式不匹配");
        var choices=r.path("value").path("choices");
        if(!choices.isArray()||choices.size()!=1||!choices.get(0).isTextual())throw fail(422,"INVALID_RESULT","请选择一个合法类别");
        String name=choices.get(0).asText();boolean valid=false;for(var label:labels)if(name.equals(label.path("name").asText()))valid=true;
        if(!valid)throw fail(422,"INVALID_RESULT","类别不属于本任务规范");
        var mapper=new ObjectMapper();var array=mapper.createArrayNode();var item=array.addObject();
        item.put("id","category");item.put("from_name","category");item.put("to_name","image");item.put("type","choices");
        item.putObject("value").putArray("choices").add(name);return array;
    }
    private void verifyExternal(JsonNode a,String task,String author) {
        String actual=a.path("completed_by").isObject()?a.path("completed_by").path("id").asText():a.path("completed_by").asText();
        if(!task.equals(a.path("task").asText())||!author.equals(actual)||a.path("was_cancelled").asBoolean())throw fail(409,"EDITOR_CONFLICT","外部结果的任务或服务作者不匹配");
    }
    private String number(Map<String,Object> m,String key){return LabelStudioClient.numeric(Objects.toString(m.get(key),null));}
    private JsonNode read(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw fail(503,"INVALID_STATE","数据格式异常");}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException fail(int status,String code,String message){return new BusinessException(HttpStatus.valueOf(status),code,message);}
}
