package com.tss.labeling.tasks;
import static com.tss.labeling.common.WorkflowStore.error;
import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.ls.NativeResultValidator;
import com.tss.labeling.ls.TaskRegistry;
import com.tss.labeling.plugins.TaskPluginRegistry;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TaskResultValidator {
    private final WorkflowStore s;
    private final NativeResultValidator nativeResults;
    private final TaskPluginRegistry plugins;
    public TaskResultValidator(WorkflowStore s, NativeResultValidator nativeResults, TaskPluginRegistry plugins) {
        this.s = s; this.nativeResults = nativeResults; this.plugins = plugins;
    }
    private Map<String,Object> spec(UUID id) {
        return s.one("SELECT task_type,schema_version,template_id,template_version,input_schema_id,result_schema_id,schema_digest,editor_config::text AS editor_config,labels::text AS labels FROM spec_version WHERE id=?", id);
    }
    public String format(UUID id) {
        var row = spec(id);
        var plugin = plugins.frozen(row);
        return TaskRegistry.FORMAT.equals(row.get("schema_version")) ? TaskRegistry.FORMAT : plugin.descriptor().resultFormat();
    }
    public void validate(UUID id, UUID sample, String format, JsonNode result, boolean done) {
        var row = spec(id);
        var plugin = plugins.writable(plugins.frozen(row));
        String expected = TaskRegistry.FORMAT.equals(row.get("schema_version")) ? TaskRegistry.FORMAT : plugin.descriptor().resultFormat();
        if (format != null && !expected.equals(format)) throw error(422,"UNSUPPORTED_FORMAT","结果格式与冻结规范不一致");
        if (TaskRegistry.FORMAT.equals(row.get("schema_version"))) {
            nativeResults.validate(s.read((String)row.get("editor_config")), result, done, sample);
            return;
        }
        Set<String> labels = new HashSet<>();
        for (var label : s.read((String)row.get("labels"))) labels.add(label.path("id").asText());
        plugin.validateResult(id, sample, labels, result, done);
    }
}
