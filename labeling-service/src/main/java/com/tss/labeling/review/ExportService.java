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

    public ExportService(WorkflowStore s) {
        this.s = s;
    }

    public record Input(String format, boolean includeOriginals, List<UUID> finalSnapshotIds) {}

    @Transactional
    public JsonNode create(ActorContext actor, UUID project, Input input, String key) {
        s.owner(actor, project, true);
        String op = "POST:/projects/" + project + "/exports";
        var replay = s.replay(actor, op, key, input);
        if (replay != null) return replay;
        if (input.format() == null
                || !Set.of("classification-csv-zip", "energy-jsonl-zip").contains(input.format())
                || input.includeOriginals())
            throw error(422, "UNSUPPORTED_EXPORT_FORMAT", "仅支持分类 CSV 或能耗 JSONL ZIP，暂不包含原始文件");
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
                            "SELECT v.task_type FROM final_snapshot f JOIN spec_version v ON"
                                    + " v.id=f.spec_version_id WHERE f.id=? AND f.project_id=?",
                            snapshot,
                            project);
            if (!("classification-csv-zip".equals(input.format())
                            ? "IMAGE_CLASSIFICATION"
                            : "TIMESERIES_ANNOTATION")
                    .equals(spec.get("task_type")))
                throw error(422, "UNSUPPORTED_EXPORT_FORMAT", "快照任务类型与导出格式不一致");
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
            String filename;
            byte[] data;
            if ("classification-csv-zip".equals(job.get("format"))) {
                filename = "results.csv";
                StringBuilder csv =
                        new StringBuilder(
                                "sampleId,sourcePath,specVersionId,resultId,labelId,reviewRevision,sourceKind,submissionId,executionId,inputHash\r\n");
                for (var row : rows) {
                    StringJoiner line = new StringJoiner(",");
                    for (String field :
                            List.of(
                                    "sampleId",
                                    "sourcePath",
                                    "specVersionId",
                                    "resultId",
                                    "labelId",
                                    "reviewRevision",
                                    "sourceKind",
                                    "submissionId",
                                    "executionId",
                                    "inputHash"))
                        line.add(
                                cell(
                                        "labelId".equals(field)
                                                ? ((JsonNode) row.get("result"))
                                                        .path("labelId")
                                                        .asText()
                                                : row.get(field)));
                    csv.append(line).append("\r\n");
                }
                data = csv.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                filename = "results.jsonl";
                StringBuilder lines = new StringBuilder();
                for (var row : rows) lines.append(s.write(row)).append('\n');
                data = lines.toString().getBytes(StandardCharsets.UTF_8);
            }
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
        } catch (IOException | IllegalStateException ex) {
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
