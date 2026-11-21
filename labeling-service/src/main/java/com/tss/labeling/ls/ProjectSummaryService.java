package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.identity.ActorService.ActorContext;
import com.tss.labeling.imports.ImportStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.io.IOException;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

/** LS ProjectSummary counters over platform records, read from one consistent DB snapshot. */
@Service
public class ProjectSummaryService {
    private final JdbcTemplate db;private final ObjectMapper json;private final ImportStorage storage;
    public ProjectSummaryService(JdbcTemplate db,ObjectMapper json,ImportStorage storage){this.db=db;this.json=json;this.storage=storage;}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public JsonNode summary(ActorContext actor,UUID project,UUID previewSample){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM project p JOIN actor a ON a.id=? AND a.status='ENABLED' WHERE p.id=? AND p.owner_actor_id=a.id)",Boolean.class,actor.actorId(),project)))
            throw error(404,"RESOURCE_NOT_FOUND","项目不存在");
        // Backfill only pre-index native inputs; new imports are indexed atomically by V19.
        for(;;){
            var missing=db.queryForList("""
                    SELECT s.id,s.batch_id,s.media_type FROM sample s JOIN import_batch b ON b.id=s.batch_id
                    WHERE s.project_id=? AND b.status='READY' AND NOT EXISTS(SELECT 1 FROM sample_input_index i WHERE i.sample_id=s.id)
                    ORDER BY s.id LIMIT 500
                    """,project);
            if(missing.isEmpty())break;
            for(var row:missing){
                var fields=json.createArrayNode();
                if("application/json".equals(row.get("media_type")))try{
                    json.readTree(storage.sample((UUID)row.get("batch_id"),(UUID)row.get("id")).toFile()).path("data").fieldNames().forEachRemaining(fields::add);
                }catch(IOException exception){throw error(503,"STORAGE_UNAVAILABLE","项目输入索引暂不可读取");}
                else fields.add(row.get("media_type").toString().startsWith("image/")?"image":"csv");
                db.update("INSERT INTO sample_input_index(sample_id,project_id,fields) VALUES(?,?,?::jsonb) ON CONFLICT DO NOTHING",row.get("id"),project,fields.toString());
            }
        }
        var out=json.createObjectNode().put("scope","project");
        long taskCount=db.queryForObject("SELECT count(*) FROM sample s JOIN import_batch b ON b.id=s.batch_id WHERE s.project_id=? AND b.status='READY'",Long.class,project);
        out.put("task_count",taskCount);var all=out.putObject("all_data_columns");var common=out.putArray("common_data_columns");
        db.query("""
                SELECT field,count(*) AS n FROM sample_input_index i JOIN sample s ON s.id=i.sample_id
                JOIN import_batch b ON b.id=s.batch_id CROSS JOIN LATERAL jsonb_array_elements_text(i.fields) field
                WHERE i.project_id=? AND b.status='READY' GROUP BY field ORDER BY field
                """,(RowCallbackHandler)rs->{long count=rs.getLong("n");String field=rs.getString("field");all.put(field,count);if(count==taskCount)common.add(field);},project);
        out.putObject("created_annotations");out.putObject("created_labels");out.putObject("created_labels_drafts");var versions=out.putObject("spec_versions");
        db.query(connection->{
            var statement=connection.prepareStatement("""
                SELECT 'submitted' AS kind,s.spec_version_id,si.result::text AS result FROM submission_item si
                JOIN submission s ON s.id=si.submission_id WHERE s.project_id=?
                UNION ALL
                SELECT 'draft',p.spec_version_id,d.result::text FROM annotation_draft d JOIN assignment a ON a.id=d.assignment_id
                JOIN task_package t ON t.id=a.package_id JOIN package_plan p ON p.id=t.plan_id
                WHERE p.project_id=? AND a.status IN ('CLAIMED','IN_PROGRESS')
                """);statement.setObject(1,project);statement.setObject(2,project);statement.setFetchSize(100);return statement;
        },(RowCallbackHandler)rs->{
            JsonNode envelope;try{envelope=json.readTree(rs.getString("result"));}catch(IOException exc){throw new IllegalStateException(exc);}
            String spec=rs.getString("spec_version_id");
            ObjectNode scoped=versions.has(spec)?(ObjectNode)versions.get(spec):versions.putObject(spec);
            boolean draft="draft".equals(rs.getString("kind"));
            for(JsonNode region:envelope.path("result")){accumulate(out,region,draft);accumulate(scoped,region,draft);}
        });
        if(previewSample!=null){
            var rows=db.queryForList("SELECT fields::text AS fields FROM sample_input_index WHERE sample_id=? AND project_id=?",previewSample,project);
            if(rows.isEmpty())throw error(404,"RESOURCE_NOT_FOUND","预览样本不存在");
            try{out.set("preview_sample_columns",json.readTree(rows.get(0).get("fields").toString()));}catch(IOException exc){throw new IllegalStateException(exc);}
        }
        return out;
    }

    /** Port of LS ProjectSummary._get_annotation_key/_get_labels/counter semantics. */
    private void accumulate(ObjectNode out,JsonNode region,boolean draft){
        String type=region.path("type").asText(),from=region.path("from_name").asText(),to=region.path("to_name").asText();
        if(from.isBlank()||to.isBlank()||Set.of("relation","pairwise","").contains(type))return;
        if(!draft){var counts=out.withObject("/created_annotations");String key=from+"|"+to+"|"+type.toLowerCase(Locale.ROOT);counts.put(key,counts.path(key).asLong()+1);}
        JsonNode values=region.path("value").path(type.equals("videorectangle")?"labels":type);
        if(!values.isArray()||type.equals("text"))return;
        var labels=out.withObject(draft?"/created_labels_drafts":"/created_labels");
        ObjectNode counts=labels.has(from)?(ObjectNode)labels.get(from):labels.putObject(from);
        for(JsonNode value:values){
            if(type.equals("taxonomy")&&value.isArray())for(JsonNode leaf:value)add(counts,leaf.asText());
            else add(counts,value.asText());
        }
    }
    private void add(ObjectNode counts,String value){counts.put(value,counts.path(value).asLong()+1);}
}
