package com.tss.labeling.plugins;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 将插件实现能力映射为稳定、可由前后端共同理解的契约。 */
@Component
public final class TaskCapabilityRegistry {
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("data.import", "数据导入", true, "importer"),
            new Definition("data.read", "数据读取", true, "inputReader"),
            new Definition("result.validate", "结果校验", true, "resultValidator"),
            new Definition("result.completion", "完成判定", true, "completion"),
            new Definition("result.export", "成果导出", true, "exporter"));

    public List<Capability> describe(TaskPlugin.Capabilities capabilities) {
        Map<String, Boolean> availability = Map.of(
                "importer", capabilities.importer(),
                "inputReader", capabilities.inputReader(),
                "resultValidator", capabilities.resultValidator(),
                "completion", capabilities.completion(),
                "exporter", capabilities.exporter());
        return DEFINITIONS.stream()
                .map(definition -> new Capability(
                        definition.id(), "1.0.0", definition.name(), definition.required(),
                        availability.get(definition.legacyField()) ? "READY" : "UNAVAILABLE"))
                .toList();
    }

    public boolean ready(TaskPlugin.Capabilities capabilities) {
        return describe(capabilities).stream()
                .filter(Capability::required)
                .allMatch(capability -> "READY".equals(capability.status()));
    }

    private record Definition(String id, String name, boolean required, String legacyField) {}

    public record Capability(String id, String version, String name, boolean required, String status) {}
}
