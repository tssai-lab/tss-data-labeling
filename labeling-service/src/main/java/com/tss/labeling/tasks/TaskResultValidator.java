package com.tss.labeling.tasks;

import static com.tss.labeling.common.WorkflowStore.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.ls.NativeResultValidator;
import com.tss.labeling.ls.TaskRegistry;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** Frozen specification and input determine validation for every producer of results. */
@Component
public class TaskResultValidator {
    private final WorkflowStore s;
    private final NativeResultValidator nativeResults;

    public TaskResultValidator(WorkflowStore s, NativeResultValidator nativeResults) {
        this.s = s;
        this.nativeResults = nativeResults;
    }

    public String format(UUID spec) {
        var row = s.one("SELECT task_type,schema_version FROM spec_version WHERE id=?", spec);
        if (TaskRegistry.FORMAT.equals(row.get("schema_version"))) return TaskRegistry.FORMAT;
        return "TIMESERIES_ANNOTATION".equals(row.get("task_type"))
                ? "annotation-record-v1" : "image-classification-v1";
    }

    public void validate(UUID spec, UUID sample, String format, JsonNode result, boolean done) {
        var row = s.one("SELECT task_type,schema_version,editor_config::text AS editor_config,labels::text AS labels FROM spec_version WHERE id=?", spec);
        String expected = format(spec);
        if (format != null && !expected.equals(format))
            throw error(422, "UNSUPPORTED_FORMAT", "结果格式与冻结规范不一致");
        if (TaskRegistry.FORMAT.equals(row.get("schema_version"))) {
            nativeResults.validate(s.read((String) row.get("editor_config")), result, done, sample);
            return;
        }
        Set<String> labels = new HashSet<>();
        for (var label : s.read((String) row.get("labels"))) labels.add(label.path("id").asText());
        if ("IMAGE_CLASSIFICATION".equals(row.get("task_type"))) {
            if (result == null
                    || !result.isObject()
                    || result.size() > 1
                    || (!result.isEmpty() && !result.has("labelId"))) invalid("分类结果必须为 labelId 对象");
            if (!done && result.isEmpty()) return;
            if (!result.path("labelId").isTextual()
                    || !labels.contains(result.path("labelId").asText())) invalid("标签不属于冻结规范");
            return;
        }
        if (!"TIMESERIES_ANNOTATION".equals(row.get("task_type")))
            throw error(422, "UNSUPPORTED_FORMAT", "不支持该任务类型");
        if (result == null
                || !result.isObject()
                || !"1.0.0".equals(result.path("schemaVersion").asText())
                || !result.path("annotations").isArray()
                || result.size() != 2) invalid("能耗结果需要 schemaVersion=1.0.0 和 annotations 数组");
        if (result.path("annotations").size() > 10000) invalid("标注条目超过上限");
        var input =
                s.one(
                        "SELECT metadata::text AS metadata FROM sample WHERE id=? AND"
                            + " project_id=(SELECT project_id FROM spec_version WHERE id=?)",
                        sample,
                        spec);
        var metadata = s.read((String) input.get("metadata"));
        Instant start;
        try {
            start =
                    LocalDate.parse(metadata.path("payload").path("dateLocal").asText())
                            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                            .toInstant();
        } catch (Exception e) {
            throw error(422, "INVALID_ENERGY_INPUT", "样本缺少合法站点日输入");
        }
        Instant end = start.plusSeconds(86400);
        Set<String> controls = new HashSet<>();
        Set<String> ids = new HashSet<>(), primary = new HashSet<>(), secondary = new HashSet<>();
        int primaryCount = 0;
        for (var a : result.path("annotations")) {
            if (!a.isObject()
                    || !a.path("id").isTextual()
                    || a.path("id").asText().isBlank()
                    || !ids.add(a.path("id").asText())
                    || !"load_curve".equals(a.path("to_name").asText())
                    || !a.path("value").isObject()) invalid("标注 ID、目标或 value 无效");
            exact(a, Set.of("id", "from_name", "to_name", "type", "value"));
            var value = a.path("value");
            String name = a.path("from_name").asText(), type = a.path("type").asText();
            if (!Set.of("point_labels", "interval_labels").contains(name) && !controls.add(name))
                invalid("同一结果的单值字段不能重复");
            exact(
                    value,
                    switch (type) {
                        case "choices" -> Set.of("labelIds");
                        case "timeseries_point" -> Set.of("timestamp", "channel", "labelIds");
                        case "timeseries_interval" -> Set.of("start", "end", "channel", "labelIds");
                        case "text" -> Set.of("text");
                        default -> Set.of();
                    });
            switch (name) {
                case "primary_label" -> {
                    if (!"choices".equals(type) || ++primaryCount > 1) invalid("只能有一个主标签条目");
                    primary.addAll(labelIds(value, labels, true));
                }
                case "secondary_labels" -> {
                    if (!"choices".equals(type)) invalid("副标签类型无效");
                    for (String id : labelIds(value, labels, false))
                        if (!secondary.add(id)) invalid("副标签不能重复");
                }
                case "point_labels" -> {
                    if (!"timeseries_point".equals(type)) invalid("点标注类型无效");
                    channel(value);
                    Instant point = time(value.path("timestamp"));
                    if (point.isBefore(start) || !point.isBefore(end) || !bucket(point, start))
                        invalid("标注点必须位于站点日内");
                    labelIds(value, labels, true);
                }
                case "interval_labels" -> {
                    if (!"timeseries_interval".equals(type)) invalid("区间标注类型无效");
                    channel(value);
                    Instant from = time(value.path("start")), to = time(value.path("end"));
                    if (from.isBefore(start)
                            || to.isAfter(end)
                            || !from.isBefore(to)
                            || !bucket(from, start)
                            || !bucket(to, start)) invalid("区间必须为站点日内的非空半开区间");
                    labelIds(value, labels, true);
                }
                case "notes", "whole_day_reason" -> {
                    if (!"text".equals(type)
                            || !value.path("text").isTextual()
                            || value.path("text").asText().isBlank()
                            || value.path("text").asText().length() > 2000)
                        invalid("备注需要 text 字符串");
                }
                case "self_reported_certainty" -> {
                    if (!"choices".equals(type)
                            || !value.path("labelIds").isArray()
                            || value.path("labelIds").size() != 1
                            || !Set.of("HIGH", "MEDIUM", "LOW", "UNKNOWN")
                                    .contains(value.path("labelIds").get(0).asText()))
                        invalid("人工自评无效");
                }
                default -> invalid("未知标注字段");
            }
        }
        if (done && (primaryCount != 1 || primary.size() != 1)) invalid("完成标注需要一个合法主标签");
        if (primary.stream().anyMatch(secondary::contains)) invalid("副标签不能重复主标签");
    }

    private static boolean bucket(Instant value, Instant start) {
        return value.getNano() == 0
                && (value.getEpochSecond() - start.getEpochSecond()) % 3600 == 0;
    }

    private static void exact(JsonNode value, Set<String> allowed) {
        value.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!allowed.contains(field)) invalid("未知核心字段: " + field);
                        });
    }

    private static Set<String> labelIds(JsonNode value, Set<String> allowed, boolean one) {
        var list = value.path("labelIds");
        if (!list.isArray() || list.isEmpty() || (one && list.size() != 1)) invalid("标签数量无效");
        Set<String> ids = new HashSet<>();
        for (var id : list)
            if (!id.isTextual() || !allowed.contains(id.asText()) || !ids.add(id.asText()))
                invalid("标签重复或不属于冻结规范");
        return ids;
    }

    private static void channel(JsonNode value) {
        if (!"value_kwh".equals(value.path("channel").asText())) invalid("仅支持 value_kwh 通道");
    }

    private static Instant time(JsonNode value) {
        try {
            return OffsetDateTime.parse(value.asText()).toInstant();
        } catch (Exception e) {
            throw error(422, "INVALID_RESULT", "时间需包含明确时区");
        }
    }

    private static void invalid(String message) {
        throw error(422, "INVALID_RESULT", message);
    }
}
