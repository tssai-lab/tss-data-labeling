package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

@Service
public class SourceExportService {
    private final JdbcTemplate db;private final ObjectMapper json;private final LsSourceAdapter source;
    private final NativeTaskService inputs;private final ImportStorage storage;private final Path root;
    public SourceExportService(JdbcTemplate db,ObjectMapper json,LsSourceAdapter source,NativeTaskService inputs,
                               ImportStorage storage,ImportSettings settings) {
        this.db=db;this.json=json;this.source=source;this.inputs=inputs;this.storage=storage;
        this.root=Path.of(settings.storageRoot()).toAbsolutePath().normalize().resolve("exports");
    }

    @Transactional(readOnly=true)
    public JsonNode formats(ActorContext actor,UUID snapshot) {
        snapshot(actor,snapshot);var versions=versions(snapshot);var combined=json.createArrayNode();
        Map<String,com.fasterxml.jackson.databind.node.ObjectNode> merged=new LinkedHashMap<>();
        for(var version:versions){
            JsonNode config=read((String)version.get("editor_config"));JsonNode formats=config.path("exportFormats");
            if(!formats.isArray())formats=source.inspect(xml(version)).path("formats");
            for(JsonNode format:formats){String name=format.path("name").asText();
                if(!merged.containsKey(name))merged.put(name,(ObjectNode)format.deepCopy());
                else {var current=merged.get(name);current.put("enabled",current.path("enabled").asBoolean()&&format.path("enabled").asBoolean());current.put("supported",current.path("supported").asBoolean()&&format.path("supported").asBoolean());}
            }
        }
        merged.values().forEach(combined::add);var out=json.createObjectNode();out.set("formats",combined);out.put("specVersionCount",versions.size());return out;
    }

    @Transactional
    public JsonNode create(ActorContext actor,UUID snapshot,String format,String key) {
        snapshot(actor,snapshot);
        if(key==null || key.isBlank() || key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        String operation="POST:/final-snapshots/"+snapshot+"/exports";
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor.actorId()+operation+key);
        String hash=hash(format==null?"":format);
        var old=db.queryForList("SELECT request_hash,resource_id FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",actor.actorId(),operation,key);
        if(!old.isEmpty()) {
            if(!hash.equals(old.get(0).get("request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
            return get(actor,(UUID)old.get(0).get("resource_id"));
        }
        boolean enabled=false;for(JsonNode f:formats(actor,snapshot).path("formats"))if(f.path("name").asText().equals(format)&&f.path("enabled").asBoolean())enabled=true;
        if(!enabled)throw error(422,"EXPORT_FORMAT_NOT_SUPPORTED","该成果不支持所选导出格式");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO source_export_job(id,snapshot_id,owner_actor_id,format) VALUES (?,?,?,?)",id,snapshot,actor.actorId(),format);
        db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id) VALUES (?,?,?,?,?)",actor.actorId(),operation,key,hash,id);
        return get(actor,id);
    }

    @Transactional(readOnly=true)
    public JsonNode get(ActorContext actor,UUID id) {
        enabled(actor);
        var row=one("SELECT id AS \"exportId\",snapshot_id AS \"finalSnapshotId\",format,status,file_size AS \"fileSize\",error_code AS \"errorCode\" FROM source_export_job WHERE id=? AND owner_actor_id=?",id,actor.actorId());
        var out=(ObjectNode)json.valueToTree(row);
        if("READY".equals(row.get("status")))out.put("downloadUrl","/api/labeling/v1/exports/"+id+"/file");
        return out;
    }

    @Transactional(readOnly=true)
    public JsonNode list(ActorContext actor,UUID snapshot,int limit,String cursor) {
        snapshot(actor,snapshot);
        if(limit<1 || limit>100)throw error(400,"INVALID_REQUEST","分页大小应为 1 至 100");
        UUID after;
        try{after=cursor==null?new UUID(-1,-1):UUID.fromString(cursor);}catch(IllegalArgumentException exc){throw error(400,"INVALID_CURSOR","分页游标无效");}
        var rows=db.queryForList("""
                SELECT id AS "exportId",snapshot_id AS "finalSnapshotId",format,status,file_size AS "fileSize",error_code AS "errorCode",created_at AS "createdAt"
                FROM source_export_job WHERE snapshot_id=? AND owner_actor_id=? AND id<? ORDER BY id DESC LIMIT ?
                """,snapshot,actor.actorId(),after,limit+1);
        boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);
        var out=json.createObjectNode();out.set("items",json.valueToTree(rows));
        if(more)out.put("nextCursor",rows.get(rows.size()-1).get("exportId").toString());else out.putNull("nextCursor");return out;
    }

    @Transactional(readOnly=true)
    public ImportService.SampleFile file(ActorContext actor,UUID id) {
        JsonNode job=get(actor,id);
        if(!"READY".equals(job.path("status").asText()))throw error(409,"EXPORT_NOT_READY","导出尚未完成");
        Path file=root.resolve(id.toString()).resolve("artifact.zip");
        try {
            if(!Files.isRegularFile(file) || Files.size(file)!=job.path("fileSize").asLong())throw new IOException();
            return new ImportService.SampleFile(new FileSystemResource(file),"application/zip",Files.size(file));
        }catch(IOException exc){throw error(503,"EXPORT_FILE_UNAVAILABLE","导出文件不可读取");}
    }

    @Transactional
    public void generate(UUID id) {
        var jobs=db.queryForList("SELECT * FROM source_export_job WHERE id=? AND status='QUEUED' FOR UPDATE SKIP LOCKED",id);
        if(jobs.isEmpty())return;
        var job=jobs.get(0);
        ActorContext actor=new ActorContext((UUID)job.get("owner_actor_id"),"ENABLED",false);
        UUID snapshotId=(UUID)job.get("snapshot_id");
        var snapshot=snapshot(actor,snapshotId);
        UUID project=(UUID)snapshot.get("project_id");String format=(String)job.get("format");
        var groups=versions(snapshotId);Path directory=root.resolve(id.toString()),partial=directory.resolve("artifact.part");
        try {
            Files.createDirectories(directory);var manifest=json.createObjectNode().put("formatVersion",1).put("finalSnapshotId",snapshotId.toString()).put("format",format);
            var entries=manifest.putArray("groups");
            try(var archive=new java.util.zip.ZipOutputStream(Files.newOutputStream(partial),StandardCharsets.UTF_8)){
                for(var version:groups){
                    UUID spec=(UUID)version.get("spec_version_id");String prefix=groups.size()==1?"":"spec-"+version.get("version_no")+"-"+spec+"/";
                    Path groupFile=directory.resolve("group-"+spec+".zip");
                    try(var output=Files.newOutputStream(groupFile)){writeGroup(actor,snapshotId,project,spec,version,format,output);}
                    try(var groupZip=new java.util.zip.ZipFile(groupFile.toFile(),StandardCharsets.UTF_8)){
                        var files=groupZip.entries();while(files.hasMoreElements()){
                            var file=files.nextElement();var entry=new java.util.zip.ZipEntry(prefix+file.getName());
                            if(file.getName().endsWith(".jpg")||file.getName().endsWith(".png")){entry.setMethod(java.util.zip.ZipEntry.STORED);entry.setSize(file.getSize());entry.setCrc(file.getCrc());}
                            archive.putNextEntry(entry);try(var input=groupZip.getInputStream(file)){input.transferTo(archive);}archive.closeEntry();
                        }
                    }
                    Files.deleteIfExists(groupFile);
                    JsonNode config=read((String)version.get("editor_config"));
                    entries.addObject().put("specVersionId",spec.toString()).put("versionNo",((Number)version.get("version_no")).intValue()).put("directory",prefix)
                        .put("templateVersion",config.path("templateVersion").asText()).put("pluginVersion",config.path("pluginVersion").asText())
                        .put("configSha256",config.path("configSha256").asText()).put("sampleCount",db.queryForObject("SELECT count(*) FROM final_snapshot_item WHERE snapshot_id=? AND spec_version_id=?",Long.class,snapshotId,spec));
                }
                archive.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));writeManifest(archive,manifest,snapshotId);archive.closeEntry();
            }
            Path target=directory.resolve("artifact.zip");
            try{Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException exc){Files.move(partial,target,StandardCopyOption.REPLACE_EXISTING);}
            db.update("UPDATE source_export_job SET status='READY',file_size=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",Files.size(target),id);
        }catch(IOException exc){throw error(503,"EXPORT_IO_FAILED","导出文件生成失败");}
    }

    private void writeGroup(ActorContext actor,UUID snapshotId,UUID project,UUID spec,Map<String,Object> snapshot,String format,OutputStream output)throws IOException{
        Map<String,Path> resources=new LinkedHashMap<>();
                source.writeExportZipPrepared(xml(snapshot),format,sink->db.query(connection->{
                    var statement=connection.prepareStatement("""
                            SELECT f.*,s.batch_id,s.media_type FROM final_snapshot_item f JOIN sample s ON s.id=f.sample_id
                            WHERE f.snapshot_id=? AND f.spec_version_id=? ORDER BY f.sample_id
                            """);
                    statement.setObject(1,snapshotId);statement.setObject(2,spec);statement.setFetchSize(100);return statement;
                },rs->{
                    while(rs.next()) {
                        UUID sample=rs.getObject("sample_id",UUID.class);
                        try{if(!rs.getString("content_hash").equals(ImportStorage.sha256(storage.sample(rs.getObject("batch_id",UUID.class),sample))))throw error(409,"INPUT_HASH_MISMATCH","成果输入文件与冻结摘要不一致");}catch(IOException exc){throw new UncheckedIOException(exc);}
                        ObjectNode data,importMeta=json.createObjectNode();
                        if(TaskRegistry.FORMAT.equals(snapshot.get("schema_version"))) {
                            JsonNode context=inputs.ownerTask(actor,project,sample,spec);
                            data=(ObjectNode)context.path("task").path("data");
                            if(context.path("task").path("meta").isObject())importMeta=context.path("task").path("meta").deepCopy();
                            for(JsonNode resource:context.path("resources")) {
                                UUID resourceId=UUID.fromString(resource.path("resourceId").asText());
                                String media=resource.path("mediaType").asText();
                                String ext=extension(media);
                                String name=("YOLO".equals(format)&&media.startsWith("image/")?"images/"+sample:"resources/"+resourceId)+ext;
                                NativeTaskService.set(data,resource.path("field").asText(),json.getNodeFactory().textNode(name));
                                var stored=one("SELECT batch_id,content_sha256,source_path FROM input_resource WHERE id=? UNION ALL SELECT batch_id,content_sha256,source_path FROM sample WHERE id=?",resourceId,resourceId);
                                try{Path file=storage.sample((UUID)stored.get("batch_id"),resourceId);
                                    if(!stored.get("content_sha256").equals(ImportStorage.sha256(file)))throw error(409,"INPUT_HASH_MISMATCH","引用资源与导入摘要不一致");
                                    resources.put(name,file);
                                }
                                catch(IOException exc){throw new UncheckedIOException(exc);}
                            }
                        } else {
                            data=json.createObjectNode();String name="resources/"+sample+extension(rs.getString("media_type"));data.put("image",name);
                            try{resources.put(name,storage.sample(rs.getObject("batch_id",UUID.class),sample));}
                            catch(IOException exc){throw new UncheckedIOException(exc);}
                        }
                        JsonNode frozen=read(rs.getString("result"));
                        JsonNode nativeResult=frozen.path("result");
                        if(!TaskRegistry.FORMAT.equals(snapshot.get("schema_version"))) {
                            var region=json.createObjectNode().put("from_name","classification").put("to_name","image").put("type","choices");
                            region.putObject("value").putArray("choices").add(frozen.path("labelId").asText());
                            nativeResult=json.createArrayNode().add(region);
                        }
                        if(!nativeResult.isArray())throw error(422,"INVALID_SNAPSHOT_RESULT","成果不包含原生结果");
                        ObjectNode task=json.createObjectNode().put("id",sample.toString());task.set("data",data);
                        task.set("meta",importMeta);var meta=importMeta;meta.put("finalSnapshotId",snapshotId.toString()).put("specVersionId",spec.toString())
                                .put("sourceKind",rs.getString("source_kind")).put("reviewRevision",rs.getLong("review_revision"))
                                .put("inputHash",rs.getString("content_hash")).put("sourcePath",rs.getString("source_path"));
                        JsonNode config=read((String)snapshot.get("editor_config"));
                        meta.put("templateVersion",config.path("templateVersion").asText()).put("pluginVersion",config.path("pluginVersion").asText()).put("configSha256",config.path("configSha256").asText());
                        var annotation=task.putArray("annotations").addObject();
                        annotation.put("id",Objects.toString(rs.getObject("submission_id"),Objects.toString(rs.getObject("execution_id"),"")));
                        String completedBy="ai:"+Objects.toString(rs.getObject("execution_id"),"");
                        if("HUMAN".equals(rs.getString("source_kind")))completedBy=Objects.toString(db.queryForObject("SELECT submitted_by FROM submission WHERE id=?",UUID.class,rs.getObject("submission_id")),"");
                        annotation.put("completed_by",completedBy);annotation.set("result",nativeResult);
                        if(frozen.has("emptyReason"))meta.set("emptyReason",frozen.get("emptyReason"));
                        try{sink.write(task);}catch(IOException exc){throw new UncheckedIOException(exc);}
                    }
                    return null;
                }),resources,output,true);
    }

    @Transactional
    public void fail(UUID id,String code) {
        db.update("UPDATE source_export_job SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='QUEUED'",code,id);
        try{Files.deleteIfExists(root.resolve(id.toString()).resolve("artifact.part"));}catch(IOException ignored){}
    }

    private Map<String,Object> snapshot(ActorContext actor,UUID id) {
        enabled(actor);
        return one("""
                SELECT f.*,sv.editor_config::text AS editor_config,sv.schema_version,sv.labels::text AS labels
                FROM final_snapshot f JOIN project p ON p.id=f.project_id LEFT JOIN spec_version sv ON sv.id=f.spec_version_id
                WHERE f.id=? AND p.owner_actor_id=?
                """,id,actor.actorId());
    }
    private List<Map<String,Object>> versions(UUID snapshot){
        return db.queryForList("""
            SELECT DISTINCT sv.id AS spec_version_id,sv.version_no,sv.schema_version,sv.editor_config::text AS editor_config,sv.labels::text AS labels
            FROM final_snapshot_item f JOIN spec_version sv ON sv.id=f.spec_version_id WHERE f.snapshot_id=? ORDER BY sv.version_no,sv.id
            """,snapshot);
    }
    private void writeManifest(OutputStream output,JsonNode header,UUID snapshot)throws IOException{
        OutputStream caller=new FilterOutputStream(output){@Override public void close()throws IOException{flush();}};
        try(var generator=json.getFactory().createGenerator(caller)){
            generator.writeStartObject();var keys=header.fieldNames();while(keys.hasNext()){String key=keys.next();generator.writeObjectField(key,header.get(key));}
            generator.writeObjectField("sources",Map.of("editor","1.23.2","converter","2.1.2"));generator.writeArrayFieldStart("samples");
            db.query(connection->{var statement=connection.prepareStatement("""
                SELECT sample_id,spec_version_id,source_kind,submission_id,execution_id,review_revision,source_path,content_hash
                FROM final_snapshot_item WHERE snapshot_id=? ORDER BY sample_id
                """);statement.setObject(1,snapshot);statement.setFetchSize(100);return statement;},(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                try{generator.writeStartObject();
                    for(String field:new String[]{"sample_id","spec_version_id","source_kind","submission_id","execution_id","source_path","content_hash"})generator.writeStringField(field,rs.getString(field));
                    generator.writeNumberField("review_revision",rs.getLong("review_revision"));generator.writeEndObject();
                }catch(IOException exc){throw new UncheckedIOException(exc);}
            });generator.writeEndArray();generator.writeEndObject();
        }
    }
    private String xml(Map<String,Object> snapshot) {
        if(TaskRegistry.FORMAT.equals(snapshot.get("schema_version")))return read((String)snapshot.get("editor_config")).path("labelConfig").asText();
        StringBuilder xml=new StringBuilder("<View><Image name=\"image\" value=\"$image\"/><Choices name=\"classification\" toName=\"image\">");
        for(JsonNode label:read((String)snapshot.get("labels")))xml.append("<Choice value=\"").append(escape(label.path("name").asText())).append("\" alias=\"").append(escape(label.path("id").asText())).append("\"/>");
        return xml.append("</Choices></View>").toString();
    }
    private String escape(String value){return value.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private String extension(String media){return switch(media){case "image/png"->".png";case "image/jpeg"->".jpg";case "text/csv"->".csv";default->".json";};}
    private void enabled(ActorContext actor){if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=?",actor.actorId()).get("status")))throw error(403,"LABELING_DISABLED","当前账号已停用");}
    private Map<String,Object> one(String sql,Object...args){var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","请求资源不存在");return rows.get(0);}
    private JsonNode read(String value){try{return json.readTree(value);}catch(IOException exc){throw new IllegalStateException(exc);}}
    private String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException exc){throw new IllegalStateException(exc);}}
}
