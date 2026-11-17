package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.tasks.TaskService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

@Service
public class NativeTaskService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ImportService imports;
    private final ImportStorage storage;
    private final TaskService tasks;
    private final NativeInputParser parser;
    public NativeTaskService(JdbcTemplate db,ObjectMapper json,ImportService imports,ImportStorage storage,TaskService tasks,NativeInputParser parser) {
        this.db=db;this.json=json;this.imports=imports;this.storage=storage;this.tasks=tasks;
        this.parser=parser;
    }

    @Transactional
    public JsonNode list(ActorContext actor,UUID project,int limit,String cursor) {
        owner(actor,project);
        if(limit<1 || limit>100) throw error(400,"INVALID_REQUEST","分页大小应为 1 至 100");
        UUID after;
        try { after=cursor==null?new UUID(0,0):UUID.fromString(cursor); }
        catch(IllegalArgumentException exc) { throw error(400,"INVALID_CURSOR","分页游标无效"); }
        var rows=db.queryForList("""
                SELECT s.id AS "sampleId",s.batch_id AS "batchId",s.source_path AS "sourcePath",
                s.content_sha256 AS "contentSha256",s.media_type AS "mediaType",s.metadata::text AS metadata
                FROM sample s JOIN import_batch b ON b.id=s.batch_id
                WHERE s.project_id=? AND b.status='READY' AND s.id>? ORDER BY s.id LIMIT ?
                """,project,after,limit+1);
        boolean more=rows.size()>limit; if(more) rows.remove(rows.size()-1);
        for(var row:rows) row.put("metadata",read((String)row.get("metadata")));
        var out=json.createObjectNode();out.set("items",json.valueToTree(rows));
        if(more) out.put("nextCursor",rows.get(rows.size()-1).get("sampleId").toString());else out.putNull("nextCursor");
        return out;
    }

    @Transactional
    public JsonNode ownerTask(ActorContext actor,UUID project,UUID sample,UUID spec) {
        ownerDataAccess(actor,project);
        var version=one("SELECT editor_config::text AS config FROM spec_version WHERE id=? AND project_id=?",spec,project);
        JsonNode context=context(project,sample,read((String)version.get("config")),"/projects/"+project+"/samples/"+sample);
        for(JsonNode resource:context.path("resources")) {
            String url=resource.path("url").asText()+"?specVersionId="+spec;
            ((ObjectNode)resource).put("url",url);
            set((ObjectNode)context.path("task").path("data"),resource.path("field").asText(),json.getNodeFactory().textNode(url));
        }
        return context;
    }

    @Transactional
    public JsonNode assignmentTask(ActorContext actor,UUID assignment,UUID sample) {
        tasks.file(actor,assignment,sample);
        var version=one("""
                SELECT p.project_id,sv.editor_config::text AS config FROM assignment a
                JOIN task_package t ON t.id=a.package_id JOIN package_plan p ON p.id=t.plan_id
                JOIN spec_version sv ON sv.id=p.spec_version_id WHERE a.id=?
                """,assignment);
        return context((UUID)version.get("project_id"),sample,read((String)version.get("config")),
                "/assignments/"+assignment+"/samples/"+sample);
    }

    @Transactional
    public JsonNode referenceTask(ActorContext actor,UUID assignment,UUID sample){
        tasks.file(actor,assignment,sample);
        var version=one("""
            SELECT s.project_id,sv.editor_config::text AS config FROM assignment a
            JOIN round_item r ON r.round_id=a.round_id AND r.sample_id=?
            JOIN submission s ON s.id=r.reference_result_id JOIN submission_item si ON si.submission_id=s.id AND si.sample_id=r.sample_id
            JOIN spec_version sv ON sv.id=s.spec_version_id WHERE a.id=?
            """,sample,assignment);
        return context((UUID)version.get("project_id"),sample,read((String)version.get("config")),"/assignments/"+assignment+"/samples/"+sample+"/reference");
    }

    @Transactional
    public ImportService.SampleFile referenceResource(ActorContext actor,UUID assignment,UUID sample,UUID resource){
        JsonNode context=referenceTask(actor,assignment,sample);boolean allowed=false;
        for(JsonNode item:context.path("resources"))if(resource.toString().equals(item.path("resourceId").asText()))allowed=true;
        if(!allowed)throw error(404,"RESOURCE_NOT_FOUND","参考资源不存在");
        var row=media(resource);try{
            Path path=storage.sample((UUID)row.get("batch_id"),resource);long size=Long.parseLong((String)row.get("size"));
            if(Files.size(path)!=size)throw new IOException();return new ImportService.SampleFile(new FileSystemResource(path),(String)row.get("media_type"),size);
        }catch(IOException exc){throw error(503,"STORAGE_UNAVAILABLE","参考资源暂不可读取");}
    }

    @Transactional
    public ImportService.SampleFile resource(ActorContext actor,UUID scope,UUID sample,UUID resource,UUID spec,boolean assignment) {
        JsonNode context=assignment?assignmentTask(actor,scope,sample):ownerTask(actor,scope,sample,spec);
        boolean permitted=false;
        for(JsonNode item:context.path("resources")) if(resource.toString().equals(item.path("resourceId").asText())) permitted=true;
        if(!permitted) throw error(404,"RESOURCE_NOT_FOUND","输入资源不存在");
        var row=media(resource);
        try {
            Path path=storage.sample((UUID)row.get("batch_id"),resource);
            long size=Long.parseLong((String)row.get("size"));
            if(Files.size(path)!=size) throw new IOException();
            return new ImportService.SampleFile(new FileSystemResource(path),(String)row.get("media_type"),size);
        } catch(IOException exc) { throw error(503,"STORAGE_UNAVAILABLE","样本存储不可用"); }
    }

    /** Used by authorized final snapshot export; no owner/assignment URLs enter stored data. */
    public JsonNode rawData(UUID sample) {
        return rawTask(sample).path("data");
    }

    public JsonNode rawTask(UUID sample) {
        var row=one("SELECT batch_id,media_type FROM sample WHERE id=?",sample);
        if(!"application/json".equals(row.get("media_type"))) return json.createObjectNode().set("data",json.createObjectNode());
        try { return json.readTree(storage.sample((UUID)row.get("batch_id"),sample).toFile()); }
        catch(IOException exc) { throw error(503,"STORAGE_UNAVAILABLE","任务输入不可读取"); }
    }

    @Transactional
    public JsonNode ownerInput(ActorContext actor,UUID project,UUID sample) {
        ownerDataAccess(actor,project);
        var row=one("SELECT * FROM sample WHERE id=? AND project_id=?",sample,project);
        ObjectNode task=(ObjectNode)rawTask(sample).deepCopy();task.put("id",sample.toString());
        JsonNode data=task.path("data");
        var resources=json.createArrayNode();
        collectResources(data,"",(UUID)row.get("batch_id"),(String)row.get("source_path"),project,resources);
        var out=json.createObjectNode();out.set("task",task);
        out.set("resources",resources);out.put("sourcePath",(String)row.get("source_path"));
        out.put("displayName",read(row.get("metadata").toString()).path("displayName").asText((String)row.get("source_path")));
        return out;
    }

    private void collectResources(JsonNode node,String field,UUID batch,String source,UUID project,com.fasterxml.jackson.databind.node.ArrayNode resources) {
        if(node.isObject()) {
            var keys=node.fieldNames();while(keys.hasNext()){String key=keys.next();collectResources(node.get(key),field.isEmpty()?key:field+"."+key,batch,source,project,resources);}
        } else if(node.isArray()) {
            for(int i=0;i<node.size();i++)collectResources(node.get(i),field+"."+i,batch,source,project,resources);
        } else if(node.isTextual() && node.asText().toLowerCase(Locale.ROOT).matches(".*\\.(csv|png|jpg|jpeg|json)$")) {
            String value=node.asText();
            if(value.contains(":") || value.startsWith("/") || value.contains("\\"))return;
            String path=source.split("#task-",2)[0];Path base=Path.of(path).getParent();
            Path resolved=(base==null?Path.of(value):base.resolve(value)).normalize();
            if(resolved.startsWith(".."))return;
            var rows=db.queryForList("SELECT id,media_type FROM input_resource WHERE batch_id=? AND source_path=?",batch,resolved.toString().replace('\\','/'));
            if(!rows.isEmpty()) {
                var resource=rows.get(0);
                resources.addObject().put("field",field).put("resourceId",resource.get("id").toString()).put("mediaType",(String)resource.get("media_type"))
                        .put("url","/api/labeling/v1/projects/"+project+"/input-resources/"+resource.get("id")+"/file");
            }
        }
    }

    @Transactional
    public String configurationTaskType(ActorContext actor,UUID project) {
        owner(actor,project);
        return (String)one("SELECT task_type FROM project WHERE id=?",project).get("task_type");
    }

    /** Unsaved configuration preview is read-only and uses project-owner resources. */
    @Transactional
    public JsonNode configurationPreview(ActorContext actor,UUID project,UUID sample,JsonNode config) {
        ownerDataAccess(actor,project);
        JsonNode context=context(project,sample,config,"/projects/"+project+"/samples/"+sample);
        for(JsonNode item:context.path("resources")) {
            var resource=(ObjectNode)item;
            String url="/api/labeling/v1/projects/"+project+"/input-resources/"+item.path("resourceId").asText()+"/file";
            resource.put("url",url);
            set((ObjectNode)context.path("task").path("data"),item.path("field").asText(),json.getNodeFactory().textNode(url));
        }
        var out=json.createObjectNode();out.set("sample_task",context.path("task").path("data"));
        out.set("resources",context.path("resources"));out.put("isExample",false);out.put("sampleId",sample.toString());
        return out;
    }

    private JsonNode context(UUID project,UUID sample,JsonNode config,String route) {
        var row=one("SELECT * FROM sample WHERE id=? AND project_id=?",sample,project);
        JsonNode parsed=config.path("parsedConfig");
        if(parsed.isEmpty()) throw error(422,"NATIVE_CONFIG_REQUIRED","此规范未包含原生 LS 配置");
        ObjectNode task=(ObjectNode)rawTask(sample).deepCopy();task.put("id",sample.toString());
        ObjectNode data=(ObjectNode)task.path("data");
        var resources=json.createArrayNode();
        Set<String> visited=new HashSet<>();
        for(JsonNode control:parsed) for(JsonNode input:control.path("inputs")) {
            String type=input.path("type").asText(), field=input.path("value").asText();
            if(!Set.of("Image","TimeSeries","TimeSeriesChannel").contains(type) || !visited.add(field)) continue;
            if(field.isBlank() && !type.equals("Image")) continue;
            JsonNode existing=at(data,field);
            if(type.equals("Image") && existing!=null && existing.isArray()) {
                if(existing.isEmpty())throw error(422,"INPUT_SHAPE_INVALID","图片列表不能为空");
                for(int index=0;index<existing.size();index++) bind(data,field+"."+index,input,row,sample,route,resources);
            } else if(!type.equals("Image") && existing!=null && existing.isContainerNode()) {
                if("url".equals(input.path("valueType").asText()))throw error(422,"INPUT_TYPE_MISMATCH","内联曲线请使用 valueType=json");
                List<String> columns=new ArrayList<>();
                JsonNode header=existing.isArray()?existing.path(0):existing;
                if(header.isArray())for(int index=0;index<header.size();index++)columns.add(Integer.toString(index));
                else header.fieldNames().forEachRemaining(columns::add);
                checkColumns(input,columns);
                validateSeriesShape(input,existing,columns);
            } else {
                if(!type.equals("Image") && "json".equals(input.path("valueType").asText()))throw error(422,"INPUT_TYPE_MISMATCH","引用曲线文件请使用 valueType=url");
                bind(data,field,input,row,sample,route,resources);
            }
        }
        var response=json.createObjectNode();
        response.set("task",task);
        response.set("resources",resources);response.set("editorConfig",config);
        response.put("sourcePath",(String)row.get("source_path"));
        response.put("displayName",read(row.get("metadata").toString()).path("displayName").asText((String)row.get("source_path")));
        return response;
    }

    private void bind(ObjectNode data,String field,JsonNode input,Map<String,Object> row,UUID sample,String route,com.fasterxml.jackson.databind.node.ArrayNode resources) {
        String type=input.path("type").asText();
        JsonNode existing=at(data,field);UUID target=sample;
        if("application/json".equals(row.get("media_type"))) {
            if(existing==null || !existing.isTextual())throw error(422,"INPUT_FIELD_MISSING","任务输入缺少资源字段："+field);
            target=reference((UUID)row.get("batch_id"),(String)row.get("source_path"),existing.asText());
        }
        var resource=media(target);String media=(String)resource.get("media_type");
        if(type.equals("Image")?!media.startsWith("image/"):!Set.of("text/csv","application/json").contains(media))
            throw error(422,"INPUT_TYPE_MISMATCH","引用资源类型与数据控件不匹配");
        if(!type.equals("Image") && "text/csv".equals(media)) {
            List<String> columns=new ArrayList<>();for(JsonNode name:read((String)resource.get("metadata")).path("columns"))columns.add(name.asText());
            checkColumns(input,columns);
        }
        String url="/api/labeling/v1"+route+"/native-resources/"+target;
        set(data,field,json.getNodeFactory().textNode(url));
        resources.addObject().put("field",field).put("resourceId",target.toString()).put("url",url).put("mediaType",media);
    }

    private void checkColumns(JsonNode input,List<String> columns) {
        Set<String> available=new HashSet<>();for(String column:columns)available.add(column.toLowerCase(Locale.ROOT));
        List<String> required=new ArrayList<>();if(!input.path("timeColumn").asText().isBlank())required.add(input.path("timeColumn").asText());
        for(JsonNode name:input.path("channels"))required.add(name.asText());
        for(String name:required) {
            if(available.contains(name.toLowerCase(Locale.ROOT)))continue;
            if(name.matches("\\d+") && Long.parseLong(name)<columns.size())continue;
            throw error(422,"INPUT_COLUMN_MISSING","样本缺少配置中的列："+name);
        }
    }

    private void validateSeriesShape(JsonNode input,JsonNode data,List<String> columns){
        List<String> required=new ArrayList<>();if(!input.path("timeColumn").asText().isBlank())required.add(input.path("timeColumn").asText());
        for(JsonNode channel:input.path("channels"))required.add(channel.asText());
        int length=-1;
        if(data.isObject()){
            for(String field:required){String key=seriesColumn(field,columns);JsonNode values=data.path(key);
                if(!values.isArray()||values.isEmpty())throw error(422,"INPUT_SHAPE_INVALID","时序列必须为非空数组");
                if(length<0)length=values.size();else if(length!=values.size())throw error(422,"INPUT_SHAPE_INVALID","时序各列长度不一致");
            }
        }else{
            if(data.isEmpty())throw error(422,"INPUT_SHAPE_INVALID","时序输入不能为空");
            for(JsonNode row:data){if(row.isObject())for(String field:required){if(!row.has(seriesColumn(field,columns)))throw error(422,"INPUT_COLUMN_MISSING","时序行缺少配置列");}
                else if(row.isArray()){if(row.size()!=columns.size())throw error(422,"INPUT_SHAPE_INVALID","时序行长度不一致");}
                else throw error(422,"INPUT_SHAPE_INVALID","时序必须为列数组或观测行数组");}
        }
    }
    private String seriesColumn(String requested,List<String> columns){
        for(String field:columns)if(field.equalsIgnoreCase(requested))return field;
        return requested.matches("\\d+")&&Integer.parseInt(requested)<columns.size()?columns.get(Integer.parseInt(requested)):requested;
    }

    public void validateForPublication(UUID project,UUID spec,List<UUID> samples) {
        var version=one("SELECT schema_version,editor_config::text AS config FROM spec_version WHERE id=? AND project_id=?",spec,project);
        if(!"label-studio-v1".equals(version.get("schema_version")))return;
        JsonNode config=read((String)version.get("config"));
        for(UUID sample:samples)context(project,sample,config,"/projects/"+project+"/samples/"+sample);
    }

    public Map<String,Path> timeResources(UUID sample,JsonNode config) {
        var row=one("SELECT batch_id,source_path FROM sample WHERE id=?",sample);
        JsonNode data=rawData(sample);Map<String,Path> resources=new LinkedHashMap<>();
        for(JsonNode control:config.path("parsedConfig"))for(JsonNode input:control.path("inputs")) {
            if(!"TimeSeries".equals(input.path("type").asText()))continue;
            JsonNode value=at(data,input.path("value").asText());
            if(value==null || !value.isTextual())continue;
            UUID resource=reference((UUID)row.get("batch_id"),(String)row.get("source_path"),value.asText());
            var stored=media(resource);
            try{resources.put(value.asText(),storage.sample((UUID)stored.get("batch_id"),resource));}
            catch(IOException exc){throw error(503,"STORAGE_UNAVAILABLE","时序输入不可读取");}
        }
        return resources;
    }

    public JsonNode resultMetadata(UUID sample,JsonNode config){
        var row=one("SELECT batch_id,source_path FROM sample WHERE id=?",sample);JsonNode data=rawData(sample);var out=json.createObjectNode();
        for(JsonNode control:config.path("parsedConfig")){
            int index=0;
            for(JsonNode input:control.path("inputs")){
                String name=control.path("to_name").path(index++).asText();if(!"Image".equals(input.path("type").asText())||out.has(name))continue;
                JsonNode value=at(data,input.path("value").asText());
                if(value!=null&&value.isArray()){
                    var items=out.putObject(name).putArray("items");for(JsonNode image:value)items.add(imageMetadata(row,image));
                }else if(value!=null)out.set(name,imageMetadata(row,value));
            }
        }
        return out;
    }
    private JsonNode imageMetadata(Map<String,Object> row,JsonNode value){
        if(!value.isTextual())throw error(422,"INPUT_TYPE_MISMATCH","图片输入必须引用已上传资源");
        UUID resource=reference((UUID)row.get("batch_id"),(String)row.get("source_path"),value.asText());
        var stored=media(resource);if(!stored.get("media_type").toString().startsWith("image/"))throw error(422,"INPUT_TYPE_MISMATCH","图片引用资源类型不正确");
        return read((String)stored.get("metadata"));
    }

    public UUID reference(UUID batch,String source,String value) {
        if(value.startsWith("/") || value.contains(":") || value.contains("\\")) throw error(422,"RESOURCE_NOT_LOCAL","资源必须来自当前上传批次");
        String path=source.split("#task-",2)[0];
        Path base=Path.of(path).getParent();
        Path resolved=(base==null?Path.of(value):base.resolve(value)).normalize();
        if(resolved.startsWith("..") || resolved.isAbsolute()) throw error(422,"INVALID_RESOURCE_PATH","资源路径越界");
        String normalized=resolved.toString().replace('\\','/');
        return (UUID)one("SELECT id FROM input_resource WHERE batch_id=? AND source_path=? UNION ALL SELECT id FROM sample WHERE batch_id=? AND source_path=?",batch,normalized,batch,normalized).get("id");
    }

    @Transactional
    public JsonNode resources(ActorContext actor,UUID project,int limit,String cursor) {
        owner(actor,project);
        if(limit<1 || limit>100) throw error(400,"INVALID_REQUEST","分页大小应为 1 至 100");
        UUID after;
        try {after=cursor==null?new UUID(0,0):UUID.fromString(cursor);}catch(IllegalArgumentException exc){throw error(400,"INVALID_CURSOR","分页游标无效");}
        var rows=db.queryForList("""
                SELECT r.id AS "resourceId",r.batch_id AS "batchId",r.source_path AS "sourcePath",r.media_type AS "mediaType",
                r.content_sha256 AS "contentSha256",r.metadata::text AS metadata
                FROM input_resource r JOIN import_batch b ON b.id=r.batch_id
                WHERE r.project_id=? AND r.id>? AND b.status='READY' ORDER BY r.id LIMIT ?
                """,project,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);
        for(var row:rows)row.put("metadata",read((String)row.get("metadata")));
        var out=json.createObjectNode();out.set("items",json.valueToTree(rows));
        if(more)out.put("nextCursor",rows.get(rows.size()-1).get("resourceId").toString());else out.putNull("nextCursor");
        return out;
    }

    @Transactional
    public ImportService.SampleFile ownerInputResource(ActorContext actor,UUID project,UUID resource) {
        owner(actor,project);
        one("SELECT id FROM input_resource WHERE id=? AND project_id=?",resource,project);
        var row=media(resource);
        try {
            Path path=storage.sample((UUID)row.get("batch_id"),resource);
            long size=Long.parseLong((String)row.get("size"));
            if(Files.size(path)!=size)throw new IOException();
            return new ImportService.SampleFile(new FileSystemResource(path),(String)row.get("media_type"),size);
        }catch(IOException exc){throw error(503,"STORAGE_UNAVAILABLE","输入资源不可读取");}
    }

    @Transactional
    public JsonNode prepare(ActorContext actor,UUID project,UUID batch,JsonNode tasks,String key) {
        owner(actor,project);
        String state=(String)one("SELECT status FROM project WHERE id=? FOR UPDATE",project).get("status");
        if(!List.of("DRAFT","OPEN").contains(state))throw error(409,"PROJECT_CLOSED","项目当前只读");
        one("SELECT id FROM import_batch WHERE id=? AND project_id=? AND status='READY' FOR UPDATE",batch,project);
        if(key==null || key.isBlank() || key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        if(tasks==null || !tasks.isArray() || tasks.isEmpty() || tasks.size()>10000)throw error(422,"INVALID_TASKS","请提供 1 至 10000 个原生 LS 任务");
        String op="POST:/imports/"+batch+"/tasks";
        String encoded;
        try{encoded=json.writeValueAsString(tasks);}catch(IOException exc){throw new IllegalStateException(exc);}
        String hash=ImportService.digest(encoded.getBytes(StandardCharsets.UTF_8));
        var replays=db.queryForList("SELECT request_hash,response_body::text AS body FROM idempotency_record WHERE actor_id=? AND operation=? AND idempotency_key=?",actor.actorId(),op,key);
        if(!replays.isEmpty()) {
            if(!hash.equals(replays.get(0).get("request_hash")))throw error(409,"IDEMPOTENCY_CONFLICT","同一幂等键请求内容不同");
            return read((String)replays.get(0).get("body"));
        }
        int created=0;
        for(JsonNode task:tasks) {
            if(!task.isObject() || !task.path("data").isObject())throw error(422,"INVALID_TASKS","任务必须包含 data 对象");
            String taskHash;
            try{taskHash=ImportService.digest(json.writeValueAsBytes(task));}catch(IOException exc){throw new IllegalStateException(exc);}
            String source="mapped-"+taskHash;
            UUID id=UUID.nameUUIDFromBytes((batch+"\n"+source).getBytes(StandardCharsets.UTF_8));
            try {
                Path path=storage.sampleDirectory(batch).resolve(id+".bin");
                parser.writeTask(path,task);
                created+=db.update("""
                        INSERT INTO sample(id,project_id,batch_id,source_path,storage_key,content_sha256,media_type,metadata)
                        VALUES (?,?,?,?,?,?,'application/json',?::jsonb) ON CONFLICT(batch_id,source_path) DO NOTHING
                        """,id,project,batch,source,batch+"/samples/"+id+".bin",ImportStorage.sha256(path),
                        json.writeValueAsString(parser.metadata(task,Files.size(path))));
            }catch(IOException exc){throw error(503,"STORAGE_UNAVAILABLE","任务输入保存失败");}
        }
        db.update("UPDATE import_batch SET valid_sample_count=(SELECT count(*) FROM sample WHERE batch_id=?) WHERE id=?",batch,batch);
        var out=json.createObjectNode().put("batchId",batch.toString()).put("createdTaskCount",created).put("mappedTaskCount",tasks.size());
        db.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id,response_body) VALUES (?,?,?,?,?,?::jsonb)",
                actor.actorId(),op,key,hash,batch,out.toString());
        return out;
    }

    private Map<String,Object> media(UUID resource) {
        return one("""
                SELECT batch_id,media_type,metadata->>'sizeBytes' AS size,metadata::text AS metadata FROM input_resource WHERE id=?
                UNION ALL SELECT batch_id,media_type,metadata->>'sizeBytes' AS size,metadata::text AS metadata FROM sample WHERE id=?
                """,resource,resource);
    }

    private void owner(ActorContext actor,UUID project) {
        if(!"ENABLED".equals(one("SELECT status FROM actor WHERE id=? FOR SHARE",actor.actorId()).get("status"))) throw error(403,"LABELING_DISABLED","当前账号已停用");
        one("SELECT id FROM project WHERE id=? AND owner_actor_id=?",project,actor.actorId());
    }
    private void ownerDataAccess(ActorContext actor,UUID project) {
        owner(actor,project);
        if("PAUSED".equals(one("SELECT status FROM project WHERE id=?",project).get("status")))
            throw error(409,"PROJECT_PAUSED","项目已暂停");
    }
    public static JsonNode at(JsonNode root,String field) {
        if(root.has(field))return root.get(field);
        JsonNode node=root;for(String key:field.replaceAll("\\[(\\d+)\\]", ".$1").split("\\.")) {
            try { node=node.isArray()?node.get(Integer.parseInt(key)):node.get(key); }
            catch(NumberFormatException exc){return null;}
            if(node==null)return null;
        }return node;
    }
    public static void set(ObjectNode root,String field,JsonNode value) {
        if(root.has(field)){root.set(field,value);return;}
        String[] keys=field.replaceAll("\\[(\\d+)\\]", ".$1").split("\\.");JsonNode node=root;
        for(int i=0;i<keys.length-1;i++) {
            if(node.isArray())node=node.get(Integer.parseInt(keys[i]));
            else {
                if(!node.path(keys[i]).isContainerNode())((ObjectNode)node).putObject(keys[i]);
                node=node.get(keys[i]);
            }
        }
        if(node.isArray())((com.fasterxml.jackson.databind.node.ArrayNode)node).set(Integer.parseInt(keys[keys.length-1]),value);
        else ((ObjectNode)node).set(keys[keys.length-1],value);
    }
    private Map<String,Object> one(String sql,Object... args) {var rows=db.queryForList(sql,args);if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","请求资源不存在");return rows.get(0);}
    private JsonNode read(String value) {try{return json.readTree(value);}catch(IOException exc){throw new IllegalStateException(exc);}}
}
