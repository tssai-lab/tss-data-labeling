package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.ImportService;
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

/** Phase-one project-owned classification. Never creates packages or claims behind the user's back. */
@Service
public class ProjectEditorService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportService imports;
    private final LabelStudioClient ls;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Set<String> projects;
    private final byte[] ticketKey;
    public ProjectEditorService(JdbcTemplate db,ObjectMapper json,ImportService imports,LabelStudioClient ls,
            PlatformTransactionManager manager,@Value("${labeling.project-editor.enabled:false}") boolean enabled,
            @Value("${labeling.editor.projects:}") String allowed,
            @Value("${labeling.project-editor.ticket-secret:}") String ticketSecret) {
        this.db=db;this.json=json;this.imports=imports;this.ls=ls;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
        this.projects=new HashSet<>();for(String id:allowed.split(","))projects.add(id.trim());
        if(enabled&&ticketSecret.length()<32)throw new IllegalArgumentException("Project editor requires LABELING_EDITOR_TICKET_SECRET with at least 32 characters");
        this.ticketKey=ticketSecret.getBytes(StandardCharsets.UTF_8);
    }
    public record Result(String projectId,String sampleId,String actorId,String specVersionId,long revision,
                         boolean explicitDone,String resultFormat,JsonNode result,String savedAt) {}
    public record Session(String editorSessionId,String projectId,String sampleId,String actorId,String specVersionId,
                          String mode,String editorUrl,String launchExpiresAt,String expiresAt,String config,Result draft) {}
    public record Browser(ActorContext actor,UUID project,UUID sample,UUID spec,String actorDisplayName,String sourcePath,String config,Result draft) {}
    private static BusinessException fail(int status,String code,String message){return new BusinessException(HttpStatus.valueOf(status),code,message);}
    private static void key(String key){if(key==null||key.isBlank()||key.length()>200)throw fail(400,"INVALID_REQUEST","需要有效的Idempotency-Key");}
    private Map<String,Object> guard(ActorContext actor,UUID project,UUID sample,UUID expectedSpec) {
        // Existing project and import APIs grant access to the owner only. Do not invent cross-project annotator access.
        var rows=db.queryForList("SELECT * FROM project WHERE id=? AND owner_actor_id=? FOR SHARE",project,actor.actorId());
        if(rows.isEmpty())throw fail(404,"RESOURCE_NOT_FOUND","项目不存在或无权访问");
        var p=rows.get(0);
        if(!enabled||!(projects.contains("*")||projects.contains(project.toString())))throw fail(404,"RESOURCE_NOT_FOUND","此项目尚未开启项目内标注");
        if(!"ENABLED".equals(db.queryForObject("SELECT status FROM actor WHERE id=? FOR SHARE",String.class,actor.actorId())))throw fail(403,"LABELING_DISABLED","账号已停用");
        if(!List.of("DRAFT","OPEN").contains(p.get("status")))throw fail(409,"PROJECT_CLOSED","项目暂停或已归档，不能继续标注");
        if(!"IMAGE_CLASSIFICATION".equals(p.get("task_type")))throw fail(422,"UNSUPPORTED_TASK_TYPE","本阶段仅支持图片分类");
        var samples=db.queryForList("SELECT s.source_path,s.content_sha256 FROM sample s JOIN import_batch b ON b.id=s.batch_id WHERE s.id=? AND s.project_id=? AND b.status='READY'",sample,project);
        if(samples.isEmpty())throw fail(404,"RESOURCE_NOT_FOUND","图片不存在、不属于当前项目或未导入完成");
        var specs=db.queryForList("SELECT id,labels::text AS labels,instructions FROM spec_version WHERE project_id=? ORDER BY version_no DESC LIMIT 1",project);
        if(specs.isEmpty())throw fail(422,"SPEC_REQUIRED","请先为项目配置分类标签");
        var spec=new HashMap<>(specs.get(0));
        if(expectedSpec!=null&&!expectedSpec.equals(spec.get("id")))throw fail(409,"SPEC_CHANGED","分类规范已更新，请重新打开编辑器");
        spec.putAll(samples.get(0));return spec;
    }
    private Map<String,Object> mapping(UUID project,UUID sample,UUID actor,UUID spec){
        var rows=db.queryForList("SELECT * FROM project_editor_mapping WHERE project_id=? AND sample_id=? AND actor_id=? AND spec_version_id=? FOR UPDATE",project,sample,actor,spec);
        if(rows.isEmpty())throw fail(503,"EDITOR_UNAVAILABLE","请先打开编辑器");return rows.get(0);
    }
    private Result result(UUID project,UUID sample,UUID actor,UUID spec){
        var rows=db.queryForList("SELECT revision,result::text AS result,saved_at FROM project_annotation WHERE project_id=? AND sample_id=? AND actor_id=? AND spec_version_id=?",project,sample,actor,spec);
        if(rows.isEmpty())return new Result(project.toString(),sample.toString(),actor.toString(),spec.toString(),0,false,"label-studio-json",json.createArrayNode(),null);
        var r=rows.get(0);return new Result(project.toString(),sample.toString(),actor.toString(),spec.toString(),((Number)r.get("revision")).longValue(),true,"label-studio-json",read(r.get("result")),((java.sql.Timestamp)r.get("saved_at")).toInstant().toString());
    }
    public Result get(ActorContext actor,UUID project,UUID sample){return tx.execute(s->{var c=guard(actor,project,sample,null);return result(project,sample,actor.actorId(),(UUID)c.get("id"));});}
    private Session replay(ActorContext actor,UUID project,UUID sample,UUID spec,String requestKey){
        var rows=db.queryForList("SELECT sample_id,spec_version_id,consumed,launch_expires_at,launch_hash,response_body::text AS body FROM project_editor_session WHERE actor_id=? AND project_id=? AND request_key=?",actor.actorId(),project,requestKey);
        if(rows.isEmpty())return null;var r=rows.get(0);
        if(!sample.equals(r.get("sample_id"))||!spec.equals(r.get("spec_version_id")))throw fail(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同图片或规范");
        if(Boolean.TRUE.equals(r.get("consumed"))||!((java.sql.Timestamp)r.get("launch_expires_at")).toInstant().isAfter(Instant.now()))throw fail(409,"EDITOR_ENTRY_EXPIRED","入口已使用或过期，请以新操作重新打开");
        try{
            var prior=json.treeToValue(read(r.get("body")),Session.class);String ticket=ticket(UUID.fromString(prior.editorSessionId()));
            if(!hash(ticket.getBytes(StandardCharsets.UTF_8)).equals(r.get("launch_hash")))throw fail(409,"EDITOR_ENTRY_EXPIRED","签发密钥已变化，请以新操作重新打开");
            return new Session(prior.editorSessionId(),prior.projectId(),prior.sampleId(),prior.actorId(),prior.specVersionId(),prior.mode(),entry(prior.editorSessionId(),ticket),prior.launchExpiresAt(),prior.expiresAt(),prior.config(),prior.draft());
        }catch(BusinessException e){throw e;}catch(Exception e){throw fail(503,"INVALID_STATE","会话记录异常");}
    }
    public synchronized Session open(ActorContext actor,UUID project,UUID sample,String requestKey){
        key(requestKey);
        var c=tx.execute(s->guard(actor,project,sample,null));UUID spec=(UUID)c.get("id");
        var previous=replay(actor,project,sample,spec,requestKey);if(previous!=null)return previous;
        String config=ClassificationTemplate.xml(read(c.get("labels")),(String)c.get("instructions"));
        // Persist the creation marker before talking to LS; ambiguous external writes require reconciliation.
        int inserted=tx.execute(s->{guard(actor,project,sample,spec);return db.update("INSERT INTO project_editor_mapping(project_id,sample_id,actor_id,spec_version_id,sync_status,image_sha256) VALUES (?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING",project,sample,actor.actorId(),spec,c.get("content_sha256"));});
        if(inserted==1){
            var file=imports.file(actor,project.toString(),sample.toString());byte[] bytes;
            try(var in=file.resource().getInputStream()){bytes=in.readNBytes(64*1024*1024+1);}catch(Exception e){throw fail(503,"STORAGE_UNAVAILABLE","图片不可读取");}
            if(bytes.length>64*1024*1024||bytes.length!=file.sizeBytes())throw fail(413,"UPLOAD_TOO_LARGE","图片超出编辑器范围");
            if(!hash(bytes).equals(c.get("content_sha256")))throw fail(503,"STORAGE_UNAVAILABLE","图片摘要不一致");
            var client=ls.connect();String author=LabelStudioClient.numeric(client.call("GET","/api/current-user/whoami",null).path("id").asText());
            String external=LabelStudioClient.numeric(client.call("POST","/api/projects/",Map.of("title","Platform project "+project.toString().substring(0,8),"label_config",config,"description","Platform-controlled project classification; do not edit using shared admin UI.")).path("id").asText());
            db.update("UPDATE project_editor_mapping SET external_project_id=?,external_author_id=? WHERE project_id=? AND sample_id=? AND actor_id=? AND spec_version_id=?",external,author,project,sample,actor.actorId(),spec);
            String task=client.importImage(external,bytes,file.mediaType(),sample+(file.mediaType().equals("image/png")?".png":".jpg"));
            tx.executeWithoutResult(s->{guard(actor,project,sample,spec);db.update("UPDATE project_editor_mapping SET external_task_id=?,sync_status='READY' WHERE project_id=? AND sample_id=? AND actor_id=? AND spec_version_id=?",task,project,sample,actor.actorId(),spec);});
        }
        return tx.execute(s->{guard(actor,project,sample,spec);
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"project-editor:"+actor.actorId()+":"+project+":"+requestKey);
            var again=replay(actor,project,sample,spec,requestKey);if(again!=null)return again;
            var m=mapping(project,sample,actor.actorId(),spec);
            if(!List.of("READY","SAVED").contains(m.get("sync_status")))throw fail(409,"EDITOR_RECONCILIATION_REQUIRED","外部导入状态待核对，请管理员处理后重试");
            UUID id=UUID.randomUUID();String ticket=ticket(id);Instant launch=Instant.now().plusSeconds(60),expires=Instant.now().plusSeconds(1800);
            var response=new Session(id.toString(),project.toString(),sample.toString(),actor.actorId().toString(),spec.toString(),"PROJECT_IMAGE_CLASSIFICATION","/api/labeling/v1/project-editor-ui/"+id+"/launch?ticket="+ticket,launch.toString(),expires.toString(),config,result(project,sample,actor.actorId(),spec));
            var stored=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(response);stored.remove("editorUrl");
            db.update("INSERT INTO project_editor_session(id,project_id,sample_id,actor_id,spec_version_id,expires_at,launch_hash,launch_expires_at,request_key,response_body) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",id,project,sample,actor.actorId(),spec,java.sql.Timestamp.from(expires),hash(ticket.getBytes(StandardCharsets.UTF_8)),java.sql.Timestamp.from(launch),requestKey,stored.toString());return response;
        });
    }
    public String launch(UUID session,String ticket){
        if(ticket==null||ticket.length()>128)throw fail(401,"UNAUTHENTICATED","编辑器入口无效");
        return tx.execute(s->{var rows=db.queryForList("SELECT * FROM project_editor_session WHERE id=? AND launch_hash=? AND consumed=false AND launch_expires_at>CURRENT_TIMESTAMP AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",session,hash(ticket.getBytes(StandardCharsets.UTF_8)));
            if(rows.isEmpty())throw fail(401,"UNAUTHENTICATED","入口已使用或过期，请重新打开");var r=rows.get(0);
            guard(actor(r),(UUID)r.get("project_id"),(UUID)r.get("sample_id"),(UUID)r.get("spec_version_id"));
            String cookie=secret();db.update("UPDATE project_editor_session SET consumed=true,cookie_hash=? WHERE id=?",hash(cookie.getBytes(StandardCharsets.UTF_8)),session);return cookie;});
    }
    public Browser browser(UUID session,String cookie){
        if(cookie==null||cookie.length()>128)throw fail(401,"UNAUTHENTICATED","请从平台打开编辑器");
        return tx.execute(s->{var rows=db.queryForList("SELECT * FROM project_editor_session WHERE id=? AND cookie_hash=? AND consumed=true AND expires_at>CURRENT_TIMESTAMP",session,hash(cookie.getBytes(StandardCharsets.UTF_8)));
            if(rows.isEmpty())throw fail(401,"UNAUTHENTICATED","会话无效或过期");var r=rows.get(0);var actor=actor(r);
            UUID project=(UUID)r.get("project_id"),sample=(UUID)r.get("sample_id"),spec=(UUID)r.get("spec_version_id");var c=guard(actor,project,sample,spec);
            return new Browser(actor,project,sample,spec,db.queryForObject("SELECT display_name FROM actor WHERE id=?",String.class,actor.actorId()),(String)c.get("source_path"),ClassificationTemplate.xml(read(c.get("labels")),(String)c.get("instructions")),result(project,sample,actor.actorId(),spec));});
    }
    public Result save(ActorContext actor,UUID project,UUID sample,UUID session,long baseRevision,JsonNode incoming){
        return tx.execute(s->{
            guard(actor,project,sample,null);
            var sessions=db.queryForList("SELECT spec_version_id FROM project_editor_session WHERE id=? AND project_id=? AND sample_id=? AND actor_id=? AND expires_at>CURRENT_TIMESTAMP",session,project,sample,actor.actorId());
            if(sessions.isEmpty())throw fail(409,"EDITOR_SESSION_EXPIRED","会话失效或不属于当前用户和图片");UUID spec=(UUID)sessions.get(0).get("spec_version_id");
            var c=guard(actor,project,sample,spec);var m=mapping(project,sample,actor.actorId(),spec);
            var canonical=ClassificationResultValidator.canonical(incoming,read(c.get("labels")));var current=result(project,sample,actor.actorId(),spec);
            if(current.revision()==baseRevision+1&&current.result().equals(canonical))return current;
            if(current.revision()!=baseRevision)throw fail(409,"REVISION_CONFLICT","结果已更新，请重新读取后保存");
            if(!List.of("READY","SAVED").contains(m.get("sync_status")))throw fail(503,"EDITOR_UNAVAILABLE","编辑器尚未就绪");
            var client=ls.connect();String task=number(m,"external_task_id"),author=number(m,"external_author_id"),annotation=m.get("external_annotation_id")==null?null:number(m,"external_annotation_id");
            if(annotation==null){var existing=client.call("GET","/api/tasks/"+task+"/",null).path("annotations");
                if(existing.size()>1)throw fail(409,"EDITOR_CONFLICT","外部任务出现多份答案，需要对账");
                if(existing.size()==1){verify(existing.get(0),task,author);annotation=LabelStudioClient.numeric(existing.get(0).path("id").asText());}}
            var saved=client.call(annotation==null?"POST":"PATCH",annotation==null?"/api/tasks/"+task+"/annotations/":"/api/annotations/"+annotation+"/",Map.of("result",canonical,"was_cancelled",false));
            String annotationId=LabelStudioClient.numeric(saved.path("id").asText());var external=client.call("GET","/api/annotations/"+annotationId+"/",null);verify(external,task,author);
            var back=ClassificationResultValidator.canonical(external.path("result"),read(c.get("labels")));
            if(!back.equals(canonical))throw fail(409,"EDITOR_CONFLICT","外部结果不一致，未确认平台保存成功");
            db.update("INSERT INTO project_annotation(project_id,sample_id,actor_id,spec_version_id,result,revision) VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT(project_id,sample_id,actor_id,spec_version_id) DO UPDATE SET result=EXCLUDED.result,revision=EXCLUDED.revision,saved_at=CURRENT_TIMESTAMP",project,sample,actor.actorId(),spec,back.toString(),baseRevision+1);
            db.update("UPDATE project_editor_mapping SET external_annotation_id=?,result_hash=?,sync_status='SAVED' WHERE project_id=? AND sample_id=? AND actor_id=? AND spec_version_id=?",annotationId,hash(back.toString().getBytes(StandardCharsets.UTF_8)),project,sample,actor.actorId(),spec);
            return result(project,sample,actor.actorId(),spec);
        });
    }
    private ActorContext actor(Map<String,Object> row){return new ActorContext((UUID)row.get("actor_id"),"ENABLED",false);}
    private String number(Map<String,Object> m,String key){return LabelStudioClient.numeric(Objects.toString(m.get(key),null));}
    private void verify(JsonNode a,String task,String author){String actual=a.path("completed_by").isObject()?a.path("completed_by").path("id").asText():a.path("completed_by").asText();if(!task.equals(a.path("task").asText())||!author.equals(actual)||a.path("was_cancelled").asBoolean())throw fail(409,"EDITOR_CONFLICT","外部任务或服务作者不匹配");}
    private JsonNode read(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw fail(503,"INVALID_STATE","数据格式异常");}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String ticket(UUID id){try{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(ticketKey,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("project-editor:"+id).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Cannot sign editor entry",e);}}
    private static String entry(String id,String ticket){return "/api/labeling/v1/project-editor-ui/"+id+"/launch?ticket="+ticket;}
}
