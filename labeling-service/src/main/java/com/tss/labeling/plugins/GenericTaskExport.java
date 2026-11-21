package com.tss.labeling.plugins;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** 插件通用的任务结果交换格式。 */
public final class GenericTaskExport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FORMATS = Set.of("JSON", "JSON_MIN", "CSV");

    private GenericTaskExport() {}

    public static Set<String> formats() {
        return FORMATS;
    }

    public static TaskPlugin.ExportData export(String format, List<Map<String, Object>> rows) {
        if (!FORMATS.contains(format)) return null;
        try {
            if ("JSON".equals(format)) {
                return new TaskPlugin.ExportData("tasks.json", JSON.writeValueAsBytes(Map.of("tasks", rows)));
            }
            if ("JSON_MIN".equals(format)) {
                var tasks = rows.stream().map(GenericTaskExport::minimal).toList();
                return new TaskPlugin.ExportData("tasks.min.json", JSON.writeValueAsBytes(Map.of("tasks", tasks)));
            }
            var csv = new StringBuilder("sampleId,sourcePath,specVersionId,resultId,result,reviewRevision,sourceKind,inputHash\r\n");
            for (var row : rows) {
                var line = new StringJoiner(",");
                for (String key : List.of("sampleId", "sourcePath", "specVersionId", "resultId", "result", "reviewRevision", "sourceKind", "inputHash")) {
                    Object value = row.get(key);
                    line.add(cell(value == null ? "" : value.toString()));
                }
                csv.append(line).append("\r\n");
            }
            return new TaskPlugin.ExportData("tasks.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("通用标注结果序列化失败", exception);
        }
    }

    private static ObjectNode minimal(Map<String, Object> row) {
        var task = JSON.createObjectNode();
        for (String key : List.of("sampleId", "sourcePath", "result", "sourceKind")) {
            Object value = row.get(key);
            if (value != null) task.set(key, JSON.valueToTree(value));
        }
        return task;
    }

    private static String cell(String value) {
        String text = value;
        if (text.matches("^[\\s]*[=+@-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
