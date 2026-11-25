package com.tss.labeling.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.imports.ArchiveParser;
import java.io.IOException;
import java.util.*;

/** 可信插件接口。实现随服务发布，项目权限、事务和任务生命周期仍由平台管理。 */
public interface TaskPlugin {
    Descriptor descriptor();
    ArchiveParser.ParsedArchive parseArchive(UUID batch, UUID project) throws IOException;
    default void validateImport(UUID project, ArchiveParser.ParsedArchive archive) {}
    default void persistInputs(UUID project, UUID batch, ArchiveParser.ParsedArchive archive) {}
    default JsonNode freezeConfig(JsonNode config) { return config; }
    JsonNode readInput(UUID project, UUID sample, String authorizedSamplePath);
    void validateResult(UUID spec, UUID sample, Set<String> labels, JsonNode result, boolean done);
    Set<String> exportFormats();
    ExportData export(String format, List<Map<String, Object>> rows);

    record ExportData(String filename, byte[] bytes, Map<String, byte[]> sidecars) {
        public ExportData(String filename, byte[] bytes) { this(filename, bytes, Map.of()); }
        public ExportData { sidecars = Collections.unmodifiableMap(new TreeMap<>(sidecars)); }
    }
    class ImportFailure extends RuntimeException {
        public final String code, sourcePath, field;
        public final long sourceRow;
        public ImportFailure(String code, String sourcePath, long sourceRow, String field, String message) {
            super(message); this.code=code; this.sourcePath=sourcePath; this.sourceRow=sourceRow; this.field=field;
        }
    }
    record Capabilities(boolean importer, boolean inputReader, boolean resultValidator,
                        boolean completion, boolean exporter) {
        public boolean ready() { return importer && inputReader && resultValidator && completion && exporter; }
    }
    record Descriptor(String id, String version, String name, String taskType, String inputFormat,
                      String resultFormat, String inputSchemaId, String resultSchemaId, String editorKey,
                      boolean enabled, boolean defaultVersion, Capabilities capabilities) {
        public Descriptor {
            for (String value : List.of(id, version, name, taskType, inputFormat, resultFormat,
                    inputSchemaId, resultSchemaId, editorKey))
                if (value.isBlank()) throw new IllegalArgumentException("插件描述字段不能为空");
            if (!id.matches("[a-z][a-z0-9-]*") || !version.matches("\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?"))
                throw new IllegalArgumentException("插件 ID 或版本不合法");
            Objects.requireNonNull(capabilities, "插件必须声明能力状态");
        }
        public Map<String, Object> binding() {
            var value = new LinkedHashMap<String, Object>();
            value.put("templateId", id); value.put("templateVersion", version); value.put("taskType", taskType);
            value.put("inputFormat", inputFormat); value.put("resultFormat", resultFormat);
            value.put("inputSchemaId", inputSchemaId); value.put("resultSchemaId", resultSchemaId);
            value.put("editorKey", editorKey);
            // 保持已冻结内置规范的摘要兼容，启停状态不参与版本契约变更。
            value.put("enabled", true);
            return Collections.unmodifiableMap(value);
        }
        public String digest() {
            return com.tss.labeling.common.WorkflowStore.digest(binding().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
