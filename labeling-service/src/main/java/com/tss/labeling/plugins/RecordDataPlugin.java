package com.tss.labeling.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.imports.*;
import com.tss.labeling.ls.LsSourceAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.tss.labeling.common.WorkflowStore.error;

/** Two trusted registrations share codecs, but retain independent versioned input/result contracts. */
public class RecordDataPlugin implements TaskPlugin {
    private final Descriptor descriptor;
    private final RecordArchiveParser parser;
    private final WorkflowStore s;
    private final LsSourceAdapter source;
    private final ImportStorage storage;
    public RecordDataPlugin(Descriptor descriptor,RecordArchiveParser parser,WorkflowStore s,LsSourceAdapter source,ImportStorage storage) {
        this.descriptor=descriptor;this.parser=parser;this.s=s;this.source=source;this.storage=storage;
    }
    private boolean structured() { return "STRUCTURED".equals(descriptor.taskType()); }
    public Descriptor descriptor() { return descriptor; }
    public ArchiveParser.ParsedArchive parseArchive(UUID batch,UUID project) throws IOException {
        return parser.parse(batch,structured(),descriptor.inputFormat());
    }
    public JsonNode freezeConfig(JsonNode config) {
        source.recordOperation("recordsConfig",s.json.createObjectNode().set("config",config));
        return config.deepCopy();
    }
    public JsonNode readInput(UUID project,UUID sample,String path) {
        var row=s.one("SELECT metadata::text AS input,content_sha256 FROM sample WHERE id=? AND project_id=?",sample,project);
        var input=(ObjectNode)s.read((String)row.get("input"));
        if(!descriptor.inputFormat().equals(input.path("inputFormat").asText())) throw error(422,"TEMPLATE_MISMATCH","输入契约不匹配");
        input.put("sampleId",sample.toString()).put("inputHash",(String)row.get("content_sha256")).put("schemaId",descriptor.inputSchemaId());
        return input;
    }
    public void validateResult(UUID spec,UUID sample,Set<String> labels,JsonNode result,boolean done) {
        var definition=s.one("SELECT editor_config::text AS config,project_id FROM spec_version WHERE id=?",spec);
        var input=readInput((UUID)definition.get("project_id"),sample,"");
        ObjectNode request=s.json.createObjectNode().put("structured",structured()).put("done",done);
        request.set("config",s.read((String)definition.get("config")));
        request.set("record",input.path("record"));request.set("result",result);request.set("labels",s.json.valueToTree(labels));
        source.recordOperation("recordsValidate",request);
    }
    public Set<String> exportFormats() {
        return structured()?Set.of("structured-csv-zip","structured-jsonl-zip","structured-parquet-zip"):Set.of("documents-jsonl-sidecar-zip");
    }
    public ExportData export(String format,List<Map<String,Object>> rows) {
        if(!exportFormats().contains(format)) throw error(422,"UNSUPPORTED_EXPORT_FORMAT","记录插件不支持该格式");
        var annotations=new StringBuilder();var originals=new StringBuilder();var exported=s.json.createArrayNode();
        var fields=new LinkedHashSet<String>();
        var sidecars=new LinkedHashMap<String,byte[]>();
        for(var row:rows) {
            JsonNode input=(JsonNode)row.get("input");
            for(var column:input.path("columns")) fields.add(column.asText());
            input.path("record").fieldNames().forEachRemaining(fields::add);
            if(!structured() && input.has("sourceResourceId")) {
                UUID resource=UUID.fromString(input.path("sourceResourceId").asText());
                String extension=input.path("sourcePath").asText().replaceAll("^.*\\.","").toLowerCase(Locale.ROOT);
                if(!extension.matches("[a-z0-9]{1,10}")) extension="txt";
                String filename="original-"+resource+"."+extension;
                if(!sidecars.containsKey(filename)) {
                    var origin=s.one("SELECT batch_id FROM input_resource WHERE id=? AND batch_id=?",resource,row.get("batchId"));
                    try {
                        var original=storage.sample((UUID)origin.get("batch_id"),resource);
                        if(java.nio.file.Files.size(original)+sidecars.values().stream().mapToLong(bytes->bytes.length).sum()>64L*1024*1024)
                            throw new IOException("原始文档导出超过 64 MiB");
                        sidecars.put(filename,java.nio.file.Files.readAllBytes(original));
                    }
                    catch(IOException ex) { throw new IllegalStateException("原始文档不可读取",ex); }
                }
                ((ObjectNode)input).put("originalArchivePath",filename);
            }
        }
        var names=new LinkedHashMap<String,String>();
        for(String key:List.of("label","annotator","reviewer","updated_at","sampleId","annotations")) {
            String name=key;
            while(fields.contains(name)) name="_"+name;
            names.put(key,name);fields.add(name);
        }
        for(var row:rows) {
            JsonNode input=(JsonNode)row.get("input"),result=(JsonNode)row.get("result");
            var sidecar=s.json.createObjectNode();
            for(String key:List.of("sampleId","sourcePath","inputHash","specVersionId","resultId","reviewRevision","annotator","reviewer","updated_at"))
                sidecar.set(key,s.json.valueToTree(row.get(key)));
            sidecar.set("source",input.deepCopy());sidecar.set("annotations",result);
            annotations.append(s.write(sidecar)).append('\n');originals.append(s.write(input.path("record"))).append('\n');
            if((long)annotations.length()+originals.length()>32L*1024*1024) throw error(422,"EXPORT_SIZE_LIMIT","记录导出内容超过单次大小限制，请缩小成果范围");
            if(structured()) {
                var record=(ObjectNode)input.path("record").deepCopy();
                record.set(names.get("label"),result.path("labelId"));
                for(String key:List.of("annotator","reviewer","updated_at","sampleId")) record.set(names.get(key),s.json.valueToTree(row.get(key)));
                record.set(names.get("annotations"),result);exported.add(record);
            }
        }
        sidecars.put("annotations.jsonl",annotations.toString().getBytes(StandardCharsets.UTF_8));
        if(!structured()) return new ExportData("documents.jsonl",originals.toString().getBytes(StandardCharsets.UTF_8),sidecars);
        sidecars.put("annotation-columns.json",s.write(names).getBytes(StandardCharsets.UTF_8));
        if("structured-parquet-zip".equals(format)) return new ExportData("records.parquet",source.recordParquet(exported),sidecars);
        if("structured-jsonl-zip".equals(format)) {
            var lines=new StringBuilder();for(var record:exported) lines.append(s.write(record)).append('\n');
            return new ExportData("records.jsonl",lines.toString().getBytes(StandardCharsets.UTF_8),sidecars);
        }
        var csv=new StringBuilder("\uFEFF");csv.append(String.join(",",fields.stream().map(RecordDataPlugin::cell).toList())).append("\r\n");
        for(var record:exported) {
            var line=new StringJoiner(",");
            for(String field:fields) {
                JsonNode value=record.path(field);
                line.add(cell(value.isMissingNode() || value.isNull()?"":value.isTextual()?value.asText():s.write(value)));
            }
            csv.append(line).append("\r\n");
        }
        return new ExportData("records.csv",csv.toString().getBytes(StandardCharsets.UTF_8),sidecars);
    }
    private static String cell(String value) {
        if(value.matches("^[\\s]*[=+@-].*")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
}
