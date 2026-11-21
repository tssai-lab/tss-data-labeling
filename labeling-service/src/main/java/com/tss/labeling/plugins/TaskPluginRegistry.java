package com.tss.labeling.plugins;

import static com.tss.labeling.common.WorkflowStore.error;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TaskPluginRegistry {
    private final Map<String, TaskPlugin> versions = new LinkedHashMap<>();
    private final Map<String, TaskPlugin> defaults = new LinkedHashMap<>();
    public TaskPluginRegistry(List<TaskPlugin> plugins) {
        for (TaskPlugin plugin : plugins) {
            var d = plugin.descriptor();
            if (versions.putIfAbsent(d.id() + "@" + d.version(), plugin) != null)
                throw new IllegalStateException("插件重复注册：" + d.id() + "@" + d.version());
            if (d.defaultVersion() && defaults.putIfAbsent(d.taskType(), plugin) != null)
                throw new IllegalStateException("同一任务类型存在多个默认插件：" + d.taskType());
            if (d.capabilities().exporter() && plugin.exportFormats().isEmpty())
                throw new IllegalStateException("插件声明了导出能力但未登记格式：" + d.id());
        }
        if (versions.isEmpty()) throw new IllegalStateException("至少需要注册一个任务插件");
    }
    public List<Map<String, Object>> templates() {
        return versions.values().stream().map(plugin -> {
            var d = plugin.descriptor();
            var view = new LinkedHashMap<>(d.binding());
            view.put("name", d.name()); view.put("enabled", d.enabled() && d.capabilities().ready());
            view.put("capabilities", d.capabilities()); view.put("schemaDigest", d.digest());
            view.put("defaultVersion", d.defaultVersion()); view.put("exportFormats", plugin.exportFormats());
            // 保留旧契约以处理历史项目，新界面仅开放通用数据类型入口。
            view.put("creationAvailable", d.enabled() && d.capabilities().ready()
                    && !Set.of("IMAGE_CLASSIFICATION", "TIMESERIES_ANNOTATION").contains(d.taskType()));
            return (Map<String, Object>) view;
        }).toList();
    }
    public TaskPlugin resolve(String taskType, String id, String version) {
        TaskPlugin plugin;
        if (id == null && version == null) plugin = defaults.get(taskType);
        else {
            if (id == null || version == null) throw error(422, "TEMPLATE_MISMATCH", "插件 ID 和版本必须同时指定");
            plugin = versions.get(id + "@" + version);
        }
        if (plugin == null) throw error(422, "TEMPLATE_UNAVAILABLE", "插件版本未安装，禁止回退到其他版本");
        if (!plugin.descriptor().taskType().equals(taskType)) throw error(422, "TEMPLATE_MISMATCH", "插件与任务类型不匹配");
        return plugin;
    }
    public TaskPlugin forCreation(String taskType, String id, String version) {
        var plugin = resolve(taskType, id, version);
        return writable(plugin);
    }
    public TaskPlugin writable(TaskPlugin plugin) {
        if (!plugin.descriptor().enabled() || !plugin.descriptor().capabilities().ready())
            throw error(422, "TEMPLATE_UNSUPPORTED", "插件尚未启用或必要能力未就绪");
        return plugin;
    }
    public TaskPlugin frozen(Map<String, Object> spec) {
        var plugin = resolve((String)spec.get("task_type"), (String)spec.get("template_id"), (String)spec.get("template_version"));
        var d = plugin.descriptor();
        for (var entry : Map.of("input_schema_id", d.inputSchemaId(), "result_schema_id", d.resultSchemaId(), "schema_digest", d.digest()).entrySet()) {
            Object frozen = spec.get(entry.getKey());
            if (frozen != null && !entry.getValue().equals(frozen.toString().trim()))
                throw error(422, "TEMPLATE_MISMATCH", "冻结插件契约不一致，禁止重新解释历史数据");
        }
        return plugin;
    }
}
