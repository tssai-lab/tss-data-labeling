package com.tss.labeling.tasks;

import static com.tss.labeling.common.WorkflowStore.error;

import com.tss.labeling.common.WorkflowStore;

import java.util.*;

/** Trusted built-in registry; template bindings are never executable client configuration. */
public final class TaskTemplates {
    private TaskTemplates() {}

    public static Map<String, Object> forType(String type) {
        String id, input, result, inputSchema, resultSchema, editor;
        switch (type) {
            case "IMAGE_CLASSIFICATION" -> {
                id = "image-classification";
                input = "image";
                result = "image-classification-v1";
                inputSchema = "image-classification-input@1.0.0";
                resultSchema = "image-classification-result@1.0.0";
                editor = "image-classification-editor";
            }
            case "TIMESERIES_ANNOTATION" -> {
                id = "energy-station-day";
                input = "energy-station-day-input-v1";
                result = "annotation-record-v1";
                inputSchema = "energy-station-day-input@1.0.0";
                resultSchema = "energy-station-day-result@1.0.0";
                editor = "energy-station-day-editor";
            }
            default -> throw error(422, "TEMPLATE_UNSUPPORTED", "未注册该任务类型的模板");
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("templateId", id);
        t.put("templateVersion", "1.0.0");
        t.put("taskType", type);
        t.put("inputFormat", input);
        t.put("resultFormat", result);
        t.put("inputSchemaId", inputSchema);
        t.put("resultSchemaId", resultSchema);
        t.put("editorKey", editor);
        t.put("enabled", true);
        return t;
    }

    public static void validate(String type, String id, String version) {
        var t = forType(type);
        if ((id != null && !id.equals(t.get("templateId")))
                || (version != null && !version.equals("1.0.0")))
            throw error(422, "TEMPLATE_MISMATCH", "模板与任务类型或注册版本不匹配");
    }

    public static String digest(String type) {
        return WorkflowStore.digest(
                forType(type).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
