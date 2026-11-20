package com.tss.labeling.review;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.identity.ActorService.ActorContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@Service
public class ExportService {
    private final WorkflowStore s;
    private final com.tss.labeling.plugins.TaskPluginRegistry plugins;

    public ExportService(WorkflowStore s, com.tss.labeling.plugins.TaskPluginRegistry plugins) {
        this.s = s; this.plugins = plugins;
    }

    public record Input(String format, boolean includeOriginals, List<UUID> finalSnapshotIds) {}

    @Transactional
    public JsonNode formats(ActorContext actor, UUID snapshot) {
        var owner=s.one("SELECT project_id FROM final_snapshot WHERE id=?",snapshot);
        s.owner(actor,(UUID)owner.get("project_id"),false);
        var versions=s.db.queryForList("SELECT DISTINCT v.* FROM final_snapshot_item i JOIN spec_version v ON v.id=i.spec_version_id WHERE i.snapshot_id=?",snapshot);
        var formats=new LinkedHashSet<String>();
        String binding=null;
        for(var version:versions) {
            var plugin=plugins.frozen(version);
            String key=plugin.descriptor().id()+"@"+plugin.descriptor().version();
            if(binding!=null && !binding.equals(key)) throw error(422,"EXPORT_PLUGIN_AMBIGUOUS","不同插件版本的成果请分别导出");
            binding=key; formats.addAll(plugin.exportFormats());
        }
        return s.json.valueToTree(Map.of("formats",formats.stream().sorted().map(format->Map.of("name",format,"title",format,"enabled",true)).toList()));
    }
    @Transactional
    public JsonNode list(ActorContext actor, UUID project, UUID snapshot, int limit, String cursor) {
        s.owner(actor,project,false);
        if(limit<1 || limit>100) throw error(400,"INVALID_REQUEST","分页大小应为 1 至 100");
        UUID before;
        try { before=cursor==null?new UUID(-1L,-1L):UUID.fromString(cursor); }
        catch(IllegalArgumentException ex) { throw error(400,"INVALID_CURSOR","分页游标无效"); }
        var rows=s.db.queryForList("SELECT id AS \"exportId\",format,CASE WHEN expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE status END AS status,error_code AS \"errorCode\",created_at AS \"createdAt\",expires_at AS \"expiresAt\",octet_length(archive) AS \"fileSize\" FROM export_job WHERE project_id=? AND id<? AND (?::jsonb IS NULL OR snapshot_ids @> ?::jsonb) ORDER BY id DESC LIMIT ?",
                project,before,snapshot==null?null:s.write(List.of(snapshot)),snapshot==null?null:s.write(List.of(snapshot)),limit+1);
        boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);
        var out=s.json.createObjectNode();out.set("items",s.json.valueToTree(rows));
        if(more)out.put("nextCursor",rows.get(rows.size()-1).get("exportId").toString());else out.putNull("nextCursor");
        return out;
    }

    @Transactional
    public JsonNode create(ActorContext actor, UUID project, Input input, String key) {
        s.owner(actor, project, true);
        String op = "POST:/projects/" + project + "/exports";
        var replay = s.replay(actor, op, key, input);
        if (replay != null) return replay;
        com.tss.labeling.plugins.TaskPlugin exporter = null;
        if (input.includeOriginals()) throw error(422,"UNSUPPORTED_EXPORT_FORMAT","该插件导出暂不包含原始文件");
        if (input.finalSnapshotIds() == null
                || input.finalSnapshotIds().isEmpty()
                || input.finalSnapshotIds().size() > 100
                || input.finalSnapshotIds().contains(null)
                || new HashSet<>(input.finalSnapshotIds()).size()
                        != input.finalSnapshotIds().size())
            throw error(422, "INVALID_REQUEST", "需要 1 至 100 个不重复的成果快照");
        int count = 0;
        Map<UUID, String> content = new HashMap<>();
        for (UUID snapshot : input.finalSnapshotIds()) {
            var spec =
                    s.one(
                            "SELECT v.* FROM final_snapshot f JOIN spec_version v ON"
                                    + " v.id=f.spec_version_id WHERE f.id=? AND f.project_id=?",
                            snapshot,
                            project);
            var frozen = plugins.frozen(spec);
            if (!frozen.exportFormats().contains(input.format()))
                throw error(422,"UNSUPPORTED_EXPORT_FORMAT","快照插件与导出格式不匹配");
            if (exporter != null && (!frozen.descriptor().id().equals(exporter.descriptor().id()) || !frozen.descriptor().version().equals(exporter.descriptor().version())))
                throw error(422,"EXPORT_PLUGIN_AMBIGUOUS","不同插件版本的成果请分别导出");
            exporter = frozen;
            for (var row :
                    s.db.queryForList(
                            "SELECT sample_id,result::text AS"
                                + " result,spec_version_id,review_revision FROM final_snapshot_item"
                                + " WHERE snapshot_id=?",
                            snapshot)) {
                if (++count > 10000) throw error(422, "EXPORT_TOO_LARGE", "一次最多导出 10000 条成果记录");
                UUID sample = (UUID) row.get("sample_id");
                String digest = s.hash(row);
                String existing = content.putIfAbsent(sample, digest);
                if (existing != null && !existing.equals(digest))
                    throw error(409, "OVERLAPPING_SNAPSHOTS", "快照包含同一样本的不同成果，请分别导出");
            }
        }
        UUID id = UUID.randomUUID();
        s.db.update(
                "INSERT INTO export_job(id,project_id,created_by,format,status,snapshot_ids) VALUES"
                        + " (?,?,?,?,'QUEUED',?::jsonb)",
                id,
                project,
                actor.actorId(),
                input.format(),
                s.write(input.finalSnapshotIds()));
        return s.remember(actor, op, key, input, id, view(actor, id));
    }

    @Transactional
    public JsonNode view(ActorContext actor, UUID id) {
        var row = owned(actor, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportId", id);
        out.put("projectId", row.get("project_id"));
        out.put("format", row.get("format"));
        out.put("status", row.get("status"));
        out.put("errorCode", row.get("error_code"));
        out.put("sha256", row.get("sha256"));
        out.put("createdAt", row.get("created_at"));
        out.put("expiresAt", row.get("expires_at"));
        out.put(
                "downloadUrl",
                "SUCCEEDED".equals(row.get("status"))
                        ? "/api/labeling/v1/exports/" + id + "/download"
                        : null);
        return s.json.valueToTree(out);
    }

    @Transactional
    public byte[] download(ActorContext actor, UUID id) {
        var row = owned(actor, id);
        if (!"SUCCEEDED".equals(row.get("status"))) throw error(409, "EXPORT_NOT_READY", "导出未成功完成");
        return (byte[]) row.get("archive");
    }

    private Map<String, Object> owned(ActorContext actor, UUID id) {
        var row =
                s.one(
                        "SELECT *,expires_at<=CURRENT_TIMESTAMP AS expired FROM export_job WHERE"
                            + " id=?",
                        id);
        s.owner(actor, (UUID) row.get("project_id"), false);
        if (Boolean.TRUE.equals(row.get("expired")))
            throw error(410, "EXPORT_EXPIRED", "导出已过期，请重新创建");
        return row;
    }

    @Transactional
    public void process(UUID id) {
        var jobs =
                s.db.queryForList(
                        "SELECT *,snapshot_ids::text AS snapshot_ids FROM export_job WHERE id=? AND"
                                + " status='QUEUED' FOR UPDATE SKIP LOCKED",
                        id);
        if (jobs.isEmpty()) return;
        var job = jobs.get(0);
        try {
            var rows = new ArrayList<Map<String, Object>>();
            Set<UUID> seen = new HashSet<>();
            Map<UUID,Map<String,Object>> specifications=new LinkedHashMap<>();
            List<Map<String,Object>> snapshots=new ArrayList<>();
            for (var snapshot : s.read(job.get("snapshot_ids").toString())) {
                UUID snapshotId=UUID.fromString(snapshot.asText());
                var frozen=s.one("SELECT id AS \"finalSnapshotId\",spec_version_id AS \"specVersionId\",name,scope_hash AS \"scopeHash\",created_at AS \"createdAt\" FROM final_snapshot WHERE id=?",snapshotId);
                snapshots.add(frozen);UUID spec=(UUID)frozen.get("specVersionId");
                if(!specifications.containsKey(spec)){
                    var definition=s.one("SELECT id AS \"specVersionId\",version_no AS \"versionNo\",task_type AS \"taskType\",labels::text AS labels,instructions,editor_config::text AS \"editorConfig\",schema_version AS \"schemaVersion\",template_id AS \"templateId\",template_version AS \"templateVersion\",input_schema_id AS \"inputSchemaId\",result_schema_id AS \"resultSchemaId\",schema_digest AS \"schemaDigest\" FROM spec_version WHERE id=?",spec);
                    definition.put("labels",s.read((String)definition.get("labels")));definition.put("editorConfig",s.read((String)definition.get("editorConfig")));specifications.put(spec,definition);
                }
                for (var row :
                        s.db.queryForList(
                                """
SELECT f.snapshot_id AS "finalSnapshotId",f.sample_id AS "sampleId",f.spec_version_id AS "specVersionId",f.result_id AS "resultId",
f.source_kind AS "sourceKind",f.submission_id AS "submissionId",f.execution_id AS "executionId",f.result::text AS result,
f.review_revision AS "reviewRevision",f.source_path AS "sourcePath",f.content_hash AS "inputHash",s.metadata::text AS input
FROM final_snapshot_item f JOIN sample s ON s.id=f.sample_id WHERE f.snapshot_id=? ORDER BY f.sample_id
""",
                                UUID.fromString(snapshot.asText()))) {
                    if (!seen.add((UUID) row.get("sampleId"))) continue;
                    row.put("result", s.read((String) row.get("result")));
                    row.put("input", s.read((String) row.get("input")));
                    if(row.get("resultId")!=null){
                        var provenance=s.db.queryForList("SELECT c.source_actor AS \"sourceActor\",c.model_version AS \"modelVersion\",c.confidence,o.parent_result_id AS \"parentResultId\" FROM project_result_candidate c LEFT JOIN owner_result o ON o.id=c.result_id WHERE c.result_id=?",row.get("resultId"));
                        row.put("provenance",provenance.isEmpty()?null:provenance.get(0));
                    }
                    rows.add(row);
                }
            }
            UUID firstSnapshot=UUID.fromString(s.read(job.get("snapshot_ids").toString()).get(0).asText());
            var frozenPlugin=plugins.frozen(s.one("SELECT v.* FROM final_snapshot f JOIN spec_version v ON v.id=f.spec_version_id WHERE f.id=?",firstSnapshot));
            var exported = frozenPlugin.export((String)job.get("format"),rows);
            String filename = exported.filename();
            byte[] data = exported.bytes();
            if (data.length > 64 * 1024 * 1024) throw new IOException("export size limit");
            byte[] manifest =
                    s.write(
                                    Map.of(
                                            "schemaVersion",
                                            "1.0.0",
                                            "exportId",
                                            id,
                                            "projectId",
                                            job.get("project_id"),
                                            "format",
                                            job.get("format"),
                                            "finalSnapshotIds",
                                            s.read(job.get("snapshot_ids").toString()),
                                            "specifications",specifications.values(),
                                            "finalSnapshots",snapshots,
                                            "sampleCount",
                                            rows.size(),
                                            "files",
                                            List.of(
                                                    Map.of(
                                                            "path",
                                                            filename,
                                                            "sha256",
                                                            WorkflowStore.digest(data),
                                                            "sizeBytes",
                                                            data.length))))
                            .getBytes(StandardCharsets.UTF_8);
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, filename, data);
                entry(zip, "manifest.json", manifest);
            }
            byte[] archive = bytes.toByteArray();
            s.db.update(
                    "UPDATE export_job SET status='SUCCEEDED',archive=?,sha256=? WHERE id=?",
                    archive,
                    WorkflowStore.digest(archive),
                    id);
        } catch (IOException | IllegalStateException | com.tss.labeling.common.BusinessException ex) {
            s.db.update(
                    "UPDATE export_job SET status='FAILED',error_code='EXPORT_BUILD_FAILED' WHERE"
                        + " id=?",
                    id);
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.matches("^[\\s]*[=+@-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
