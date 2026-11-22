package com.tss.labeling.ls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.tss.labeling.tasks.TaskService.error;

/** Developer-registered families; project-specific XML never registers executable code. */
@Service
public class TaskRegistry {
    public static final String FORMAT = "label-studio-v1";
    private static final Set<String> TYPES = Set.of("IMAGE_CLASSIFICATION", "COMPUTER_VISION", "TIME_SERIES", "TIMESERIES_ANNOTATION");
    private final LsSourceAdapter source;
    private final ObjectMapper json;
    private final TemplateCatalog templates;
    public static final String PLUGIN_VERSION="ls-native-2";
    private static final Set<String> CONTROLS=Set.of("Choices","TimeSeriesLabels","Labels","Rectangle","RectangleLabels","Polygon","PolygonLabels","Ellipse","EllipseLabels","KeyPoint","KeyPointLabels","Brush","BrushLabels","TextArea","Number","Rating","Taxonomy");
    public TaskRegistry(LsSourceAdapter source, ObjectMapper json,TemplateCatalog templates) { this.source=source; this.json=json;this.templates=templates; }

    public static void requireType(String type) {
        if (!TYPES.contains(type)) throw error(422,"TASK_TYPE_NOT_REGISTERED","该任务类别尚未登记");
    }

    public List<Map<String,Object>> list() {
        return List.of(Map.of("taskType","COMPUTER_VISION","name","图像标注","version",PLUGIN_VERSION,"resultFormat",FORMAT,"aiAvailable",false),
                Map.of("taskType","TIME_SERIES","name","时序标注","version",PLUGIN_VERSION,"resultFormat",FORMAT,"aiAvailable",false));
    }

    public JsonNode freeze(String type, String schema, JsonNode config) {
        requireType(type);
        if (!FORMAT.equals(schema)) {
            if (!Set.of("IMAGE_CLASSIFICATION", "TIMESERIES_ANNOTATION").contains(type)) throw error(422,"UNSUPPORTED_FORMAT","请使用原生 LS 规范版本");
            return config;
        }
        String xml=config.path("labelConfig").asText();
        JsonNode inspected=source.inspect(xml);
        JsonNode parsed=inspected.path("parsedConfig");
        checkCategory(type,parsed);
        validateEnergyRules(type, parsed, config.path("energyRules"));
        ObjectNode frozen=config.deepCopy();
        frozen.put("engine","label-studio");
        frozen.put("pluginVersion",PLUGIN_VERSION);
        frozen.put("capabilityContractVersion",2);
        frozen.put("editorSourceVersion","1.23.2");frozen.put("converterSourceVersion","2.1.2");
        frozen.put("resultFormat",FORMAT);
        frozen.set("parsedConfig",parsed);
        frozen.set("exportFormats",inspected.path("formats"));
        // Keep template IDs as provenance, not an executable registration.
        if (frozen.path("templateId").asText().isBlank()) frozen.put("templateId","custom");
        String id=frozen.path("templateId").asText();
        if("custom".equals(id))frozen.put("templateVersion","custom:"+digest(xml).substring(0,16));
        else{
            JsonNode template=templates.find(id);
            String group=isTimeSeries(type)?"Time Series Analysis":"Computer Vision";
            if(!group.equals(template.path("group").asText())||!"community".equals(template.path("type").asText()))throw error(422,"TEMPLATE_CATEGORY_MISMATCH","模板与项目类别或已开放能力不匹配");
            frozen.put("templateVersion",template.path("version").asText());
            frozen.put("templateSourceSha256",template.path("sourceSha256").asText());
        }
        frozen.put("configSha256",digest(xml));
        var capabilities=frozen.putObject("capabilities");capabilities.put("version",PLUGIN_VERSION).put("resultFormat",FORMAT).put("aiAvailable",false);
        capabilities.putArray("completionStages").add("draft").add("complete").add("submit");
        capabilities.putArray("inputFormats").add("label-studio-task-v1").add("csv-resource-v1").add("image-resource-v1");
        var controls=capabilities.putArray("controls");var seen=new java.util.TreeSet<String>();for(JsonNode control:parsed)seen.add(control.path("type").asText());seen.forEach(controls::add);
        capabilities.set("exportFormats",inspected.path("formats"));
        capabilities.putArray("compatiblePluginVersions").add("1").add(PLUGIN_VERSION);
        return frozen;
    }

    public JsonNode validateForPreview(String type,String xml) {
        requireType(type);
        JsonNode parsed=source.validateConfig(xml).path("parsedConfig");
        checkCategory(type,parsed);
        var config=json.createObjectNode().put("labelConfig",xml);config.set("parsedConfig",parsed);return config;
    }

    private void checkCategory(String type,JsonNode parsed) {
        if (parsed.isEmpty()) throw error(422,"INVALID_CONFIG","请至少配置一个标注控件");
        for(JsonNode control:parsed)if(!CONTROLS.contains(control.path("type").asText()))throw error(422,"CONTROL_NOT_ENABLED","此控件尚未开放："+control.path("type").asText());
        for (JsonNode control:parsed) for(JsonNode input:control.path("inputs")) {
            String object=input.path("type").asText();
            if (isTimeSeries(type) && !Set.of("TimeSeries","TimeSeriesChannel","Text","Header").contains(object))
                throw error(422,"CONFIG_CATEGORY_MISMATCH","时序项目包含其他类别的数据控件");
            if (!isTimeSeries(type) && Set.of("TimeSeries","Audio","Video").contains(object))
                throw error(422,"CONFIG_CATEGORY_MISMATCH","当前项目尚未登记该数据控件");
        }
    }
    private boolean isTimeSeries(String type) {
        return Set.of("TIME_SERIES", "TIMESERIES_ANNOTATION").contains(type);
    }

    private void validateEnergyRules(String type, JsonNode parsed, JsonNode rules) {
        if (rules.isMissingNode()) return;
        if (!rules.isObject() || !isTimeSeries(type)
                || !"TimeSeriesLabels".equals(parsed.path("energy_events").path("type").asText())) {
            throw error(422, "INVALID_ENERGY_RULES", "站点日能耗模板支持配置能耗阈值");
        }
        rules.fields().forEachRemaining(rule -> {
            JsonNode value = rule.getValue();
            double percent = value.asDouble();
            boolean valid = switch (rule.getKey()) {
                case "lowDailyPercent", "dropPercent" -> percent > 0 && percent <= 100;
                case "highDailyPercent", "spikePercent" -> percent >= 100;
                default -> false;
            };
            if (!value.isNumber() || !Double.isFinite(percent) || !valid) {
                throw error(422, "INVALID_ENERGY_RULES", "低负荷和跌落阈值须大于 0% 且不超过 100%，高负荷和尖峰阈值须至少为 100%");
            }
        });
    }

    private String digest(String value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException exc){throw new IllegalStateException(exc);}}
}
