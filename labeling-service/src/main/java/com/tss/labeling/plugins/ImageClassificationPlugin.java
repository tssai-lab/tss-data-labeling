package com.tss.labeling.plugins;

import static com.tss.labeling.common.WorkflowStore.error;
import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.imports.ArchiveParser;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ImageClassificationPlugin implements TaskPlugin {
    private final ArchiveParser parser;
    private final WorkflowStore s;
    public ImageClassificationPlugin(ArchiveParser parser, WorkflowStore s) { this.parser = parser; this.s = s; }
    public Descriptor descriptor() { return BuiltInPlugins.descriptor("IMAGE_CLASSIFICATION", "图片单标签分类"); }
    public ArchiveParser.ParsedArchive parseArchive(UUID batch, UUID project) throws IOException { return parser.parse(batch, descriptor().taskType()); }
    public JsonNode readInput(UUID project, UUID sample, String path) {
        var row = s.one("SELECT metadata::text AS metadata,media_type FROM sample WHERE id=? AND project_id=?", sample, project);
        int query=path.indexOf('?');
        String base=query < 0 ? path : path.substring(0,query);
        String file=(base.startsWith("/internal/labeling/v1/") ? "" : "/api/labeling/v1")+base+"/file"+(query < 0 ? "" : path.substring(query));
        return s.json.createObjectNode().put("sampleId",sample.toString()).put("inputFormat","image")
                .put("schemaId", descriptor().inputSchemaId()).put("url",file)
                .put("mediaType",(String)row.get("media_type")).set("metadata",s.read((String)row.get("metadata")));
    }
    public void validateResult(UUID spec, UUID sample, Set<String> labels, JsonNode result, boolean done) {
        if (result == null || !result.isObject() || result.size() > 1 || (!result.isEmpty() && !result.has("labelId")))
            throw error(422,"INVALID_RESULT","分类结果必须为 labelId 对象");
        if (!done && result.isEmpty()) return;
        if (!result.path("labelId").isTextual() || !labels.contains(result.path("labelId").asText()))
            throw error(422,"INVALID_RESULT","标签不属于冻结规范");
    }
    public Set<String> exportFormats() { return Set.of("classification-csv-zip"); }
    public ExportData export(String format, List<Map<String,Object>> rows) {
        if (!exportFormats().contains(format)) throw error(422,"UNSUPPORTED_EXPORT_FORMAT","图片插件不支持该导出格式");
        var csv = new StringBuilder("\uFEFFsampleId,sourcePath,specVersionId,resultId,labelId,reviewRevision,sourceKind,submissionId,executionId,inputHash\r\n");
        for (var row : rows) {
            var line = new StringJoiner(",");
            for (String field : List.of("sampleId","sourcePath","specVersionId","resultId","labelId","reviewRevision","sourceKind","submissionId","executionId","inputHash")) {
                Object value = "labelId".equals(field) ? ((JsonNode)row.get("result")).path("labelId").asText() : row.get(field);
                String text = value == null ? "" : value.toString();
                if (text.matches("^[\\s]*[=+@-].*")) text = "'" + text;
                line.add("\"" + text.replace("\"","\"\"") + "\"");
            }
            csv.append(line).append("\r\n");
        }
        return new ExportData("results.csv",csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
