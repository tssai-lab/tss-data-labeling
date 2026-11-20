package com.tss.labeling.plugins;
import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.imports.*;
import com.tss.labeling.ls.NativeResultValidator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static com.tss.labeling.common.WorkflowStore.error;

/** 已有通用 LS 任务也通过插件登记，保持现有原生工作流。 */
public class NativeTaskPlugin implements TaskPlugin {
    private final Descriptor descriptor;
    private final ArchiveParser parser;
    private final WorkflowStore s;
    private final ImportStorage storage;
    private final NativeResultValidator validator;
    public NativeTaskPlugin(Descriptor descriptor,ArchiveParser parser,WorkflowStore s,ImportStorage storage,NativeResultValidator validator) {
        this.descriptor=descriptor;this.parser=parser;this.s=s;this.storage=storage;this.validator=validator;
    }
    public Descriptor descriptor() { return descriptor; }
    public ArchiveParser.ParsedArchive parseArchive(UUID batch,UUID project) throws IOException { return parser.parse(batch,descriptor.taskType()); }
    public JsonNode readInput(UUID project,UUID sample,String path) {
        var row=s.one("SELECT batch_id,media_type FROM sample WHERE id=? AND project_id=?",sample,project);
        if (!"application/json".equals(row.get("media_type"))) throw error(422,"INPUT_TYPE_MISMATCH","通用任务需要原生 JSON 输入");
        try { return s.json.createObjectNode().put("sampleId",sample.toString()).put("inputFormat",descriptor.inputFormat()).set("task",s.json.readTree(Files.readString(storage.sample((UUID)row.get("batch_id"),sample)))); }
        catch(IOException ex) { throw error(503,"STORAGE_UNAVAILABLE","任务输入暂不可读取"); }
    }
    public void validateResult(UUID spec,UUID sample,Set<String> labels,JsonNode result,boolean done) {
        var row=s.one("SELECT editor_config::text AS config FROM spec_version WHERE id=?",spec);
        validator.validate(s.read((String)row.get("config")),result,done,sample);
    }
    public Set<String> exportFormats() { return Set.of(descriptor.id()+"-jsonl-zip"); }
    public ExportData export(String format,List<Map<String,Object>> rows) {
        if (!exportFormats().contains(format)) throw error(422,"UNSUPPORTED_EXPORT_FORMAT","插件不支持该导出格式");
        var lines=new StringBuilder();for(var row:rows)lines.append(s.write(row)).append('\n');
        return new ExportData("results.jsonl",lines.toString().getBytes(StandardCharsets.UTF_8));
    }
}
