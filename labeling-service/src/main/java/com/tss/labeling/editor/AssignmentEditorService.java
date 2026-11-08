package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.tasks.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Assignment authorization and LS adaptation. All external calls are outside database transactions. */
@Service
public class AssignmentEditorService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TaskService tasks;
    private final ResultService results;
    private final LabelStudioClient ls;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Set<String> projects;
    private final byte[] ticketKey;

    public AssignmentEditorService(JdbcTemplate db,ObjectMapper json,TaskService tasks,ResultService results,
            LabelStudioClient ls,PlatformTransactionManager manager,
            @Value("${labeling.editor.enabled:false}") boolean enabled,
            @Value("${labeling.editor.projects:}") String allowed,
            @Value("${labeling.editor.ticket-secret:}") String secret) {
        this.db=db;this.json=json;this.tasks=tasks;this.results=results;this.ls=ls;
        this.tx=new TransactionTemplate(manager);this.enabled=enabled;
        projects=new HashSet<>();for(String id:allowed.split(","))projects.add(id.trim());
        if(enabled&&secret.length()<32)throw new IllegalArgumentException("LABELING_EDITOR_TICKET_SECRET requires at least 32 characters");
        ticketKey=secret.getBytes(StandardCharsets.UTF_8);
    }

    public record Context(ActorContext actor,UUID assignment,UUID project,UUID sample,UUID spec,
                          String actorDisplayName,String sourcePath,String config,JsonNode draft,JsonNode editorResult) {}
    public record Session(String editorSessionId,String assignmentId,String projectId,String sampleId,
                          String actorId,String specVersionId,String mode,String editorUrl,
                          String launchExpiresAt,String expiresAt,JsonNode draft) {}
    private record Operation(ActorContext actor,UUID assignment,UUID sample,UUID id,long base,
                             JsonNode canonical,Map<String,Object> mapping,JsonNode completed) {}

    private static BusinessException fail(int status,String code,String message) {
        return new BusinessException(HttpStatus.valueOf(status),code,message);
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);
        if(rows.isEmpty())throw fail(404,"RESOURCE_NOT_FOUND","领取或图片不存在，或无权访问");
        return rows.get(0);
    }
    /** Lock order agrees with ResultService: actor -> project -> assignment. Recheck every request. */
    private Map<String,Object> guard(ActorContext actor,UUID assignment,UUID sample) {
        if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor.actorId()).get("status")))
            throw fail(403,"LABELING_DISABLED","标注账号已停用");
        var a=one("""
                SELECT a.package_id,p.project_id,p.spec_version_id FROM assignment a
                JOIN task_package t ON t.id=a.package_id JOIN package_plan p ON p.id=t.plan_id
                WHERE a.id=? AND a.actor_id=?
                """,assignment,actor.actorId());
        var project=one("SELECT status,task_type FROM project WHERE id=? FOR SHARE",a.get("project_id"));
        if(!enabled||!(projects.contains("*")||projects.contains(a.get("project_id").toString())))
            throw fail(404,"RESOURCE_NOT_FOUND","此项目尚未开启编辑器");
        if(!"OPEN".equals(project.get("status")))throw fail(409,"PROJECT_CLOSED","项目当前不可标注");
        if(!"IMAGE_CLASSIFICATION".equals(project.get("task_type")))throw fail(422,"UNSUPPORTED_TASK_TYPE","仅支持图片分类");
        var pkg=one("SELECT status FROM task_package WHERE id=? FOR SHARE",a.get("package_id"));
        if(!"OPEN".equals(pkg.get("status")))throw fail(409,"ASSIGNMENT_CLOSED","任务包当前不可标注");
        var current=one("SELECT status FROM assignment WHERE id=? AND actor_id=? FOR UPDATE",assignment,actor.actorId());
        if(!List.of("CLAIMED","IN_PROGRESS").contains(current.get("status")))
            throw fail(409,"ASSIGNMENT_CLOSED","领取已结束或已提交，不能继续编辑");
        a.putAll(one("""
                SELECT s.source_path,s.content_sha256 FROM package_item i JOIN sample s ON s.id=i.sample_id
                JOIN import_batch b ON b.id=s.batch_id
                WHERE i.package_id=? AND s.id=? AND s.project_id=? AND b.status='READY'
                """,a.get("package_id"),sample,a.get("project_id")));
        a.putAll(one("SELECT labels::text AS labels,instructions FROM spec_version WHERE id=? AND project_id=?",
                a.get("spec_version_id"),a.get("project_id")));
        // Validate stable IDs and unique names before adapting LS's displayed choices.
        ClassificationTemplate.xml(read(a.get("labels")),(String)a.get("instructions"));
        return a;
    }
    private Map<String,Object> mapping(UUID assignment,UUID sample) {
        return one("SELECT * FROM editor_mapping WHERE assignment_id=? AND sample_id=? AND provider='LABEL_STUDIO' FOR UPDATE",assignment,sample);
    }
    private void checkMapping(Map<String,Object> a,Map<String,Object> m,ActorContext actor) {
        if(!actor.actorId().equals(m.get("actor_id"))||!a.get("spec_version_id").equals(m.get("spec_version_id"))
                ||!a.get("package_id").equals(m.get("package_id"))||!a.get("content_sha256").equals(m.get("image_sha256")))
            throw fail(409,"EDITOR_CONFLICT","外部映射的领取、作者、规范或图片摘要不匹配");
    }
    private JsonNode toPlatform(JsonNode canonical,JsonNode labels) {
        String name=canonical.get(0).path("value").path("choices").get(0).asText();
        for(var label:labels)if(name.equals(label.path("name").asText()))
            return json.createObjectNode().put("labelId",label.path("id").asText());
        throw fail(422,"INVALID_RESULT","类别不属于领取绑定规范");
    }
    private JsonNode toEditor(JsonNode draft,JsonNode labels) {
        if(draft.path("result").isEmpty())return json.createArrayNode();
        String id=draft.path("result").path("labelId").asText();
        for(var label:labels)if(id.equals(label.path("id").asText())) {
            var item=json.createObjectNode();item.put("from_name","category");item.put("to_name","image");item.put("type","choices");
            item.putObject("value").putArray("choices").add(label.path("name").asText());
            return ClassificationResultValidator.canonical(json.createArrayNode().add(item),labels);
        }
        throw fail(409,"EDITOR_CONFLICT","已有草稿标签不属于冻结规范");
    }

    public Session open(ActorContext actor,UUID assignment,UUID sample,String key) {
        if(key==null||key.isBlank()||key.length()>200)throw fail(400,"INVALID_REQUEST","需要有效幂等键");
        var initial=tx.execute(s->{var a=guard(actor,assignment,sample);return new AbstractMap.SimpleEntry<>(a,replay(actor,assignment,sample,key));});
        if(initial.getValue()!=null)return initial.getValue();
        var a=initial.getKey();UUID spec=(UUID)a.get("spec_version_id");
        int created=tx.execute(s->{guard(actor,assignment,sample);return db.update("""
                INSERT INTO editor_mapping(assignment_id,package_id,sample_id,actor_id,spec_version_id,sync_status,image_sha256)
                VALUES (?,?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING
                """,assignment,a.get("package_id"),sample,actor.actorId(),spec,a.get("content_sha256"));});
        if(created==1) {
            // Durable PENDING marker prevents blind recreation after uncertain external writes.
            var file=tasks.file(actor,assignment,sample);byte[] bytes;
            try(var in=file.resource().getInputStream()){bytes=in.readNBytes(64*1024*1024+1);}
            catch(Exception e){throw fail(503,"STORAGE_UNAVAILABLE","图片不可读取");}
            if(bytes.length>64*1024*1024||bytes.length!=file.sizeBytes()||!hash(bytes).equals(a.get("content_sha256")))
                throw fail(503,"STORAGE_UNAVAILABLE","图片大小或摘要不一致");
            var client=ls.connect();String author=LabelStudioClient.numeric(client.call("GET","/api/current-user/whoami",null).path("id").asText());
            String external=LabelStudioClient.numeric(client.call("POST","/api/projects/",Map.of(
                    "title","Platform assignment "+assignment.toString().substring(0,8),
                    "label_config",ClassificationTemplate.xml(read(a.get("labels")),(String)a.get("instructions")),
                    "description","Platform-controlled assignment; service account only.")).path("id").asText());
            db.update("UPDATE editor_mapping SET external_project_id=?,external_author_id=? WHERE assignment_id=? AND sample_id=?",external,author,assignment,sample);
            String task=client.importImage(external,bytes,file.mediaType(),sample+(file.mediaType().equals("image/png")?".png":".jpg"));
            tx.executeWithoutResult(s->{guard(actor,assignment,sample);db.update("UPDATE editor_mapping SET external_task_id=?,sync_status='READY' WHERE assignment_id=? AND sample_id=?",task,assignment,sample);});
        }
        return tx.execute(s->{var current=guard(actor,assignment,sample);
            var prior=replay(actor,assignment,sample,key);if(prior!=null)return prior;
            var m=mapping(assignment,sample);checkMapping(current,m,actor);
            if(!List.of("READY","SAVED").contains(m.get("sync_status")))throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","编辑器有待对账操作，请先同步或联系管理员");
            UUID id=UUID.randomUUID();String ticket=ticket(id);Instant launch=Instant.now().plusSeconds(60),expiry=Instant.now().plusSeconds(1800);
            var response=new Session(id.toString(),assignment.toString(),current.get("project_id").toString(),sample.toString(),
                    actor.actorId().toString(),spec.toString(),"ASSIGNMENT_IMAGE_CLASSIFICATION",entry(id,ticket),launch.toString(),expiry.toString(),results.getDraft(actor,assignment,sample));
            var stored=(ObjectNode)json.valueToTree(response);stored.remove("editorUrl");
            db.update("""
                    INSERT INTO editor_session(id,assignment_id,sample_id,actor_id,expires_at,launch_hash,launch_expires_at,request_key,response_body)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                    """,id,assignment,sample,actor.actorId(),java.sql.Timestamp.from(expiry),hash(ticket.getBytes(StandardCharsets.UTF_8)),java.sql.Timestamp.from(launch),key,stored.toString());
            return response;
        });
    }
    private Session replay(ActorContext actor,UUID assignment,UUID sample,String key) {
        var rows=db.queryForList("SELECT * FROM editor_session WHERE actor_id=? AND assignment_id=? AND request_key=?",actor.actorId(),assignment,key);
        if(rows.isEmpty())return null;var r=rows.get(0);
        if(!sample.equals(r.get("sample_id")))throw fail(409,"IDEMPOTENCY_CONFLICT","幂等键不能用于其他图片");
        if(Boolean.TRUE.equals(r.get("consumed"))||!((java.sql.Timestamp)r.get("launch_expires_at")).toInstant().isAfter(Instant.now()))
            throw fail(409,"EDITOR_ENTRY_EXPIRED","入口已使用或过期，请使用新幂等键");
        try {
            var old=json.treeToValue(read(r.get("response_body")),Session.class);UUID id=UUID.fromString(old.editorSessionId());String ticket=ticket(id);
            if(!hash(ticket.getBytes(StandardCharsets.UTF_8)).equals(r.get("launch_hash")))throw fail(409,"EDITOR_ENTRY_EXPIRED","签发密钥已变化");
            return new Session(old.editorSessionId(),old.assignmentId(),old.projectId(),old.sampleId(),old.actorId(),old.specVersionId(),old.mode(),entry(id,ticket),old.launchExpiresAt(),old.expiresAt(),old.draft());
        }catch(BusinessException e){throw e;}catch(Exception e){throw fail(503,"INVALID_STATE","会话记录异常");}
    }
    public String launch(UUID id,String ticket) {
        if(ticket==null||ticket.length()>128)throw fail(401,"UNAUTHENTICATED","无效入口");
        return tx.execute(s->{
            var r=session(id,null,ticket); // Authorization locks precede locking the session row.
            var actor=actor(r);guard(actor,(UUID)r.get("assignment_id"),(UUID)r.get("sample_id"));
            var locked=db.queryForList("SELECT * FROM editor_session WHERE id=? AND launch_expires_at>CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",id);
            if(locked.isEmpty())throw fail(401,"UNAUTHENTICATED","入口已过期");r=locked.get(0);
            if(Boolean.TRUE.equals(r.get("consumed")))throw fail(401,"UNAUTHENTICATED","入口已使用");
            String cookie=secret();db.update("UPDATE editor_session SET consumed=true,cookie_hash=? WHERE id=?",hash(cookie.getBytes(StandardCharsets.UTF_8)),id);return cookie;
        });
    }
    private Map<String,Object> session(UUID id,String cookie,String ticket) {
        String credential=ticket!=null?ticket:cookie;
        if(credential==null||credential.length()>128)throw fail(401,"UNAUTHENTICATED","请从平台打开编辑器");
        String condition=ticket!=null?"launch_hash=? AND consumed=false AND launch_expires_at>CURRENT_TIMESTAMP":"cookie_hash=? AND consumed=true";
        var rows=db.queryForList("SELECT * FROM editor_session WHERE id=? AND expires_at>CURRENT_TIMESTAMP AND "+condition,id,hash(credential.getBytes(StandardCharsets.UTF_8)));
        if(rows.isEmpty())throw fail(401,"UNAUTHENTICATED","会话或入口无效、已使用或过期");return rows.get(0);
    }
    public Context browser(UUID id,String cookie) {
        return tx.execute(s->{var r=session(id,cookie,null);var actor=actor(r);
            UUID assignment=(UUID)r.get("assignment_id"),sample=(UUID)r.get("sample_id");var a=guard(actor,assignment,sample);session(id,cookie,null);
            var draft=results.getDraft(actor,assignment,sample);
            return new Context(actor,assignment,(UUID)a.get("project_id"),sample,(UUID)a.get("spec_version_id"),
                    (String)one("SELECT display_name FROM actor WHERE id=?",actor.actorId()).get("display_name"),
                    (String)a.get("source_path"),ClassificationTemplate.xml(read(a.get("labels")),(String)a.get("instructions")),draft,toEditor(draft,read(a.get("labels"))));
        });
    }
    public JsonNode save(UUID id,String cookie,long base,JsonNode incoming) {
        var context=browser(id,cookie);
        var op=tx.execute(s->{
            session(id,cookie,null);var a=guard(context.actor(),context.assignment(),context.sample());
            var draft=results.getDraft(context.actor(),context.assignment(),context.sample());var m=mapping(context.assignment(),context.sample());checkMapping(a,m,context.actor());
            var canonical=ClassificationResultValidator.canonical(incoming,read(a.get("labels")));
            String digest=hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
            if("SAVED".equals(m.get("sync_status"))&&m.get("synced_revision")!=null
                    &&((Number)m.get("synced_revision")).longValue()==base+1&&draft.path("revision").asLong()==base+1
                    &&digest.equals(m.get("result_hash"))&&draft.path("result").equals(toPlatform(canonical,read(a.get("labels")))))
                return new Operation(context.actor(),context.assignment(),context.sample(),null,base,canonical,m,draft);
            if(draft.path("revision").asLong()!=base)throw fail(409,"REVISION_CONFLICT","草稿已变化，请重新读取");
            if(!List.of("READY","SAVED").contains(m.get("sync_status")))throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","已有待对账操作，请先调用editor-sync，不能重复外部写入");
            UUID operation=UUID.randomUUID();db.update("""
                    UPDATE editor_mapping SET sync_status='SYNCING',operation_id=?,base_revision=?,pending_result=?::jsonb,result_hash=?
                    WHERE assignment_id=? AND sample_id=?
                    """,operation,base,canonical.toString(),digest,context.assignment(),context.sample());
            return new Operation(context.actor(),context.assignment(),context.sample(),operation,base,canonical,m,null);
        });
        if(op.completed()!=null)return op.completed();
        var client=ls.connect();String task=number(op.mapping(),"external_task_id"),author=number(op.mapping(),"external_author_id");
        var found=client.call("GET","/api/tasks/"+task+"/",null).path("annotations");
        if(!found.isArray()||found.size()>1)throw fail(409,"EDITOR_CONFLICT","外部任务答案数量异常，需要对账");
        String annotation=null;
        if(found.size()==1){verify(found.get(0),task,author);annotation=LabelStudioClient.numeric(found.get(0).path("id").asText());}
        if(op.mapping().get("external_annotation_id")!=null&&!Objects.equals(annotation,op.mapping().get("external_annotation_id")))
            throw fail(409,"EDITOR_CONFLICT","外部答案映射发生变化，需要对账");
        var saved=client.call(annotation==null?"POST":"PATCH",annotation==null?"/api/tasks/"+task+"/annotations/":"/api/annotations/"+annotation+"/",Map.of("result",op.canonical(),"was_cancelled",false));
        String annotationId=LabelStudioClient.numeric(saved.path("id").asText());
        var external=client.call("GET","/api/annotations/"+annotationId+"/",null);
        verify(external,task,author);
        return finish(op,external,annotationId,null);
    }
    /** Recovery is read-only towards LS; it never blindly repeats an uncertain POST/PATCH. */
    public JsonNode sync(ActorContext actor,UUID assignment,UUID sample,String key) {
        if(key==null||key.isBlank()||key.length()>200)throw fail(400,"INVALID_REQUEST","需要有效幂等键");
        var op=tx.execute(s->{var a=guard(actor,assignment,sample);var draft=results.getDraft(actor,assignment,sample);var m=mapping(assignment,sample);checkMapping(a,m,actor);
            var prior=syncReplay(actor,assignment,sample,key);
            if(prior!=null)return new Operation(actor,assignment,sample,null,0,null,m,prior);
            if(!List.of("SYNCING","SAVED").contains(m.get("sync_status")))throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","尚无可同步保存操作，不能盲目创建外部答案");
            return new Operation(actor,assignment,sample,(UUID)m.get("operation_id"),((Number)m.get("base_revision")).longValue(),read(m.get("pending_result")),m,null);
        });
        if(op.completed()!=null)return op.completed();
        var client=ls.connect();String task=number(op.mapping(),"external_task_id");
        var answers=client.call("GET","/api/tasks/"+task+"/",null).path("annotations");
        if(!answers.isArray()||answers.size()!=1)throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","外部答案尚不能唯一确认，请管理员对账");
        String annotation=LabelStudioClient.numeric(answers.get(0).path("id").asText());
        if(op.mapping().get("external_annotation_id")!=null&&!annotation.equals(op.mapping().get("external_annotation_id")))
            throw fail(409,"EDITOR_CONFLICT","外部答案映射发生变化，需要管理员对账");
        var external=client.call("GET","/api/annotations/"+annotation+"/",null);verify(external,task,number(op.mapping(),"external_author_id"));
        return finish(op,external,annotation,key);
    }
    private JsonNode syncReplay(ActorContext actor,UUID assignment,UUID sample,String key) {
        var rows=db.queryForList("SELECT request_hash,response_body::text AS body FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",actor.actorId(),"POST:/assignments/"+assignment+"/editor-sync",key);
        if(rows.isEmpty())return null;
        if(!hash(sample.toString().getBytes(StandardCharsets.UTF_8)).equals(rows.get(0).get("request_hash")))throw fail(409,"IDEMPOTENCY_CONFLICT","同一同步幂等键不能用于其他图片");
        return read(rows.get(0).get("body"));
    }
    private JsonNode rememberSync(Operation op,String key,JsonNode draft) {
        if(key!=null)db.update("""
                INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body)
                VALUES (?,?,?,?,?,?::jsonb)
                """,op.actor().actorId(),"POST:/assignments/"+op.assignment()+"/editor-sync",key,
                hash(op.sample().toString().getBytes(StandardCharsets.UTF_8)),op.assignment(),draft.toString());
        return draft;
    }
    private JsonNode finish(Operation op,JsonNode external,String annotation,String key) {
        return tx.execute(s->{var a=guard(op.actor(),op.assignment(),op.sample());
            var current=results.getDraft(op.actor(),op.assignment(),op.sample());var m=mapping(op.assignment(),op.sample());checkMapping(a,m,op.actor());
            if(key!=null){var prior=syncReplay(op.actor(),op.assignment(),op.sample(),key);if(prior!=null)return prior;}
            var back=ClassificationResultValidator.canonical(external.path("result"),read(a.get("labels")));
            if(!back.equals(op.canonical()))throw fail(409,"EDITOR_CONFLICT","外部答案与待保存分类不同，平台未确认成功");
            if(!op.id().equals(m.get("operation_id")))throw fail(409,"EDITOR_CONFLICT","同步操作已变化");
            if("SAVED".equals(m.get("sync_status"))&&current.path("revision").asLong()==((Number)m.get("synced_revision")).longValue()
                    &&current.path("result").equals(toPlatform(back,read(a.get("labels")))))return rememberSync(op,key,current);
            if(!"SYNCING".equals(m.get("sync_status"))||current.path("revision").asLong()!=op.base())
                throw fail(409,"REVISION_CONFLICT","草稿已被其他操作更新，禁止迟到同步覆盖");
            var draft=results.save(op.actor(),op.assignment(),op.sample(),new ResultController.DraftInput(
                    op.base(),true,"image-classification-v1",toPlatform(back,read(a.get("labels")))));
            db.update("UPDATE editor_mapping SET sync_status='SAVED',external_annotation_id=?,synced_revision=? WHERE assignment_id=? AND sample_id=?",
                    annotation,draft.path("revision").asLong(),op.assignment(),op.sample());
            return rememberSync(op,key,draft);
        });
    }
    private ActorContext actor(Map<String,Object> r){return new ActorContext((UUID)r.get("actor_id"),"ENABLED",false);}
    private void verify(JsonNode a,String task,String author) {
        String actual=a.path("completed_by").isObject()?a.path("completed_by").path("id").asText():a.path("completed_by").asText();
        if(!task.equals(a.path("task").asText())||!author.equals(actual)||a.path("was_cancelled").asBoolean())
            throw fail(409,"EDITOR_CONFLICT","外部任务或技术作者不匹配");
    }
    private JsonNode read(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw fail(503,"INVALID_STATE","持久化数据格式异常");}}
    private String number(Map<String,Object> m,String key){return LabelStudioClient.numeric(Objects.toString(m.get(key),null));}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String secret(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String ticket(UUID id){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(ticketKey,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("assignment-editor:"+id).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String entry(UUID id,String ticket){return "/api/labeling/v1/editor-ui/"+id+"/launch?ticket="+ticket;}
}
