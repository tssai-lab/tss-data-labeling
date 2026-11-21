package com.tss.labeling.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.tss.labeling.common.WorkflowStore;
import com.tss.labeling.imports.ArchiveParser;
import com.tss.labeling.imports.EnergyArchiveParser;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.tss.labeling.common.WorkflowStore.error;

@Component
public class EnergyStationDayPlugin implements TaskPlugin {
    private final EnergyArchiveParser parser;
    private final WorkflowStore s;
    private final EnergyResultRules rules;
    public EnergyStationDayPlugin(EnergyArchiveParser parser, WorkflowStore s, EnergyResultRules rules) { this.parser = parser; this.s = s; this.rules = rules; }
    public Descriptor descriptor() { return BuiltInPlugins.descriptor("TIMESERIES_ANNOTATION", "站点日能耗标注"); }
    public ArchiveParser.ParsedArchive parseArchive(UUID batch, UUID project) throws IOException {
        try { return parser.parse(batch,project); }
        catch (EnergyArchiveParser.Invalid failure) { throw new ImportFailure(failure.code,EnergyArchiveParser.SOURCE,failure.row,failure.field,failure.getMessage()); }
    }
    public void validateImport(UUID project, ArchiveParser.ParsedArchive archive) {
        for (var sample : archive.samples()) {
            var source = (Map<?,?>)sample.metadata().get("source");
            var payload = (Map<?,?>)sample.metadata().get("payload");
            if (Boolean.TRUE.equals(s.db.queryForObject("SELECT EXISTS(SELECT 1 FROM sample_input WHERE project_id=? AND dataset_version=? AND (source_task_id=? OR (station_key=? AND date_local=?::date)))",
                    Boolean.class, project, source.get("datasetVersion"), source.get("sourceTaskId"), payload.get("stationKey"), payload.get("dateLocal"))))
                throw new ImportFailure("DUPLICATE_ENERGY_SAMPLE", EnergyArchiveParser.SOURCE, ((Number)source.get("sourceRow")).longValue(), "task_id", "项目内同数据集版本的任务或站点日已存在");
        }
    }
    public void persistInputs(UUID project, UUID batch, ArchiveParser.ParsedArchive archive) {
        if (archive.samples().isEmpty()) return;
        UUID asset = UUID.randomUUID();
        var source = (Map<?,?>)archive.samples().get(0).metadata().get("source");
        s.db.update("INSERT INTO source_asset(id,project_id,batch_id,dataset_version,source_path,archive_sha256) VALUES (?,?,?,?,?,?)",
                asset, project, batch, source.get("datasetVersion"), EnergyArchiveParser.SOURCE, s.db.queryForObject("SELECT sha256 FROM import_batch WHERE id=?",String.class,batch));
        for (var sample : archive.samples()) {
            var origin = (Map<?,?>)sample.metadata().get("source");
            var payload = (Map<?,?>)sample.metadata().get("payload");
            s.db.update("INSERT INTO sample_input(sample_id,project_id,asset_id,dataset_version,source_task_id,station_key,date_local,input_format,input_hash,source_row,input_payload) VALUES (?,?,?,?,?,?,?::date,?,?,?,?::jsonb)",
                    sample.id(),project,asset,origin.get("datasetVersion"),origin.get("sourceTaskId"),payload.get("stationKey"),payload.get("dateLocal"),sample.metadata().get("inputFormat"),sample.sha256(),origin.get("sourceRow"),s.write(sample.metadata()));
        }
    }
    public JsonNode readInput(UUID project, UUID sample, String path) {
        var row = s.one("SELECT input_payload::text AS input FROM sample_input WHERE sample_id=? AND project_id=?",sample,project);
        return s.read((String)row.get("input"));
    }
    public void validateResult(UUID spec, UUID sample, Set<String> labels, JsonNode result, boolean done) { rules.validate(spec,sample,labels,result,done); }
    public Set<String> exportFormats() {
        var formats = new HashSet<>(GenericTaskExport.formats());
        formats.add("energy-jsonl-zip");
        return Set.copyOf(formats);
    }
    public ExportData export(String format,List<Map<String,Object>> rows) {
        var generic = GenericTaskExport.export(format, rows);
        if (generic != null) return generic;
        if (!exportFormats().contains(format)) throw error(422,"UNSUPPORTED_EXPORT_FORMAT","能耗插件不支持该导出格式");
        var lines = new StringBuilder();
        for (var row : rows) lines.append(s.write(row)).append('\n');
        return new ExportData("results.jsonl",lines.toString().getBytes(StandardCharsets.UTF_8));
    }
}
