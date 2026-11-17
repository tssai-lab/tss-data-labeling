package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.identity.ActorService.ActorContext;
import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.tss.labeling.tasks.TaskService.error;

/** Optional importer that emits only native LS tasks, never a parallel task schema. */
@Service
public class CsvTaskMappingService {
    private final NativeTaskService tasks;private final ObjectMapper json;private final org.springframework.jdbc.core.JdbcTemplate db;private final ImportSettings settings;
    public CsvTaskMappingService(NativeTaskService tasks,ObjectMapper json,org.springframework.jdbc.core.JdbcTemplate db,ImportSettings settings){this.tasks=tasks;this.json=json;this.db=db;this.settings=settings;}
    @Transactional
    public JsonNode map(ActorContext actor,UUID project,UUID resource,Mapping input,String key) {
        var file=tasks.ownerInputResource(actor,project,resource);
        if(!"text/csv".equals(file.mediaType()))throw error(422,"INVALID_MAPPING_INPUT","请选择 CSV 资源");
        if("rows".equals(input.mode()))return rows(actor,project,resource,input,key,file);
        if(input.mode()!=null && !"timeseries".equals(input.mode()))throw error(422,"INVALID_MAPPING","未知映射方式");
        if(input.timeColumn()==null || input.timeColumn().isBlank() || input.channelColumns()==null || input.channelColumns().isEmpty())throw error(422,"INVALID_MAPPING","请选择时间列和通道列");
        String field=input.fieldName()==null||input.fieldName().isBlank()?"series":input.fieldName();
        if(field.contains(".") || field.contains("$") || field.contains("["))throw error(422,"INVALID_MAPPING","输入字段请使用一个不含路径表达式的名称");
        List<String> groups=input.groupColumns()==null?List.of():input.groupColumns();
        if(groups.contains(field) || input.channelColumns().contains(input.timeColumn()) || new HashSet<>(input.channelColumns()).size()!=input.channelColumns().size())throw error(422,"INVALID_MAPPING","分组、时间和通道配置冲突");
        ZoneId zone;
        try{zone=ZoneId.of(input.timezone()==null?"UTC":input.timezone());}catch(DateTimeException exc){throw error(422,"INVALID_MAPPING","时区无效");}
        boolean timestamp="timestamp".equals(input.timeType());
        if(!timestamp && !"number".equals(input.timeType()))throw error(422,"INVALID_MAPPING","时间类型应为 number 或 timestamp");
        Map<List<String>,TreeMap<Double,List<Double>>> partitions=new LinkedHashMap<>();
        try(var reader=new PushbackReader(new InputStreamReader(file.resource().getInputStream(),StandardCharsets.UTF_8.newDecoder()),1)) {
            int first=reader.read();if(first!=-1 && first!=0xFEFF)reader.unread(first);
            try(var csv=CSVFormat.DEFAULT.builder().setDelimiter(delimiter(input)).setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                Set<String> columns=new HashSet<>(csv.getHeaderNames());
                if(!columns.contains(input.timeColumn()) || !columns.containsAll(input.channelColumns()) || !columns.containsAll(groups))throw error(422,"INVALID_MAPPING","字段不属于当前 CSV");
                for(var row:csv) {
                    List<String> group=groups.stream().map(row::get).toList();
                    var points=partitions.computeIfAbsent(group,ignored->new TreeMap<>());
                    if(partitions.size()>settings.maxEntries())throw error(422,"MAPPING_SAMPLE_LIMIT","本次映射的样本数超过当前导入配置上限");
                    double time=timestamp?timestamp(row.get(input.timeColumn()),zone):number(row.get(input.timeColumn()));
                    List<Double> values=new ArrayList<>();
                    for(String channel:input.channelColumns())values.add(row.get(channel).isBlank()?null:number(row.get(channel)));
                    if(points.putIfAbsent(time,values)!=null)throw error(422,"DUPLICATE_TIME","同一分组存在重复时间，请检查分组列");
                }
            }
        }catch(IOException|UncheckedIOException exc){throw error(422,"INVALID_CSV","CSV 不可读取或格式无效");}
        var nativeTasks=json.createArrayNode();
        var resourceRow=db.queryForMap("SELECT batch_id,source_path,content_sha256 FROM input_resource WHERE id=? AND project_id=?",resource,project);
        for(var partition:partitions.entrySet()) {
            var task=nativeTasks.addObject();var data=task.putObject("data");
            for(int index=0;index<groups.size();index++)data.put(groups.get(index),partition.getKey().get(index));
            var series=data.putObject(field);var times=series.putArray(input.timeColumn());
            Map<String,com.fasterxml.jackson.databind.node.ArrayNode> channels=new LinkedHashMap<>();
            for(String channel:input.channelColumns())channels.put(channel,series.putArray(channel));
            for(var point:partition.getValue().entrySet()) {
                times.add(point.getKey());
                for(int index=0;index<input.channelColumns().size();index++) {
                    Double value=point.getValue().get(index);var array=channels.get(input.channelColumns().get(index));
                    if(value==null)array.addNull();else array.add(value);
                }
            }
            var meta=task.putObject("meta");meta.put("inputResourceId",resource.toString()).put("inputHash",(String)resourceRow.get("content_sha256"));
            meta.put("displayName",partition.getKey().isEmpty()?(String)resourceRow.get("source_path"):String.join(" · ",partition.getKey()));
            meta.set("mapping",json.valueToTree(input));
        }
        if(key==null || key.isBlank() || key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        int created=0;
        for(int start=0;start<nativeTasks.size();start+=1000) {
            var part=json.createArrayNode();for(int index=start;index<Math.min(start+1000,nativeTasks.size());index++)part.add(nativeTasks.get(index));
            String internalKey=ImportService.digest(("csv:"+key+":"+start).getBytes(StandardCharsets.UTF_8));
            created+=tasks.prepare(actor,project,(UUID)resourceRow.get("batch_id"),part,internalKey).path("createdTaskCount").asInt();
        }
        return json.createObjectNode().put("batchId",resourceRow.get("batch_id").toString()).put("createdTaskCount",created)
                .put("mappedTaskCount",nativeTasks.size()).put("suggestedConfig",config(input,field,timestamp));
    }
    /** One CSV record becomes one native task; resources retain their archive-relative identity. */
    private JsonNode rows(ActorContext actor,UUID project,UUID resource,Mapping input,String key,ImportService.SampleFile file) {
        if(input.columnMapping()==null || input.columnMapping().isEmpty())throw error(422,"INVALID_MAPPING","请选择任务字段和来源列");
        var row=db.queryForMap("SELECT batch_id,source_path,content_sha256 FROM input_resource WHERE id=? AND project_id=?",resource,project);
        Set<String> resourceFields=new HashSet<>(input.resourceFields()==null?List.of():input.resourceFields());
        Set<String> jsonFields=new HashSet<>(input.jsonFields()==null?List.of():input.jsonFields());
        if(!input.columnMapping().keySet().containsAll(resourceFields) || !input.columnMapping().keySet().containsAll(jsonFields))throw error(422,"INVALID_MAPPING","字段类型不属于当前映射");
        var output=json.createArrayNode();
        try(var reader=new PushbackReader(new InputStreamReader(file.resource().getInputStream(),StandardCharsets.UTF_8.newDecoder()),1)) {
            int first=reader.read();if(first!=-1 && first!=0xFEFF)reader.unread(first);
            try(var csv=CSVFormat.DEFAULT.builder().setDelimiter(delimiter(input)).setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                if(!csv.getHeaderNames().containsAll(input.columnMapping().values()))throw error(422,"INVALID_MAPPING","字段不属于当前 CSV");
                for(var record:csv) {
                    if(output.size()>=settings.maxEntries())throw error(422,"MAPPING_SAMPLE_LIMIT","本次映射的样本数超过当前导入配置上限");
                    var task=output.addObject();var data=task.putObject("data");
                    for(var entry:input.columnMapping().entrySet()) {
                        if(entry.getKey().isBlank() || entry.getKey().startsWith("$"))throw error(422,"INVALID_MAPPING","输入字段名无效");
                        String value=record.get(entry.getValue());
                        JsonNode mapped=jsonFields.contains(entry.getKey())?json.readTree(value):json.getNodeFactory().textNode(value);
                        if(mapped==null)throw error(422,"INVALID_MAPPING","JSON 字段不能为空");
                        if(resourceFields.contains(entry.getKey()))mapped=resourcePath(mapped,(UUID)row.get("batch_id"),(String)row.get("source_path"));
                        data.set(entry.getKey(),mapped);
                    }
                    task.putObject("meta").put("inputResourceId",resource.toString()).put("inputHash",(String)row.get("content_sha256")).put("sourceRow",record.getRecordNumber())
                        .set("mapping",json.valueToTree(input));
                }
            }
        }catch(IOException|UncheckedIOException exc){throw error(422,"INVALID_CSV","CSV 或 JSON 字段不可读取");}
        if(output.isEmpty())throw error(422,"INVALID_CSV","CSV 没有数据行");
        if(key==null || key.isBlank() || key.length()>200)throw error(400,"INVALID_REQUEST","需要有效的幂等键");
        int created=0;
        for(int start=0;start<output.size();start+=1000) {
            var part=json.createArrayNode();for(int i=start;i<Math.min(start+1000,output.size());i++)part.add(output.get(i));
            created+=tasks.prepare(actor,project,(UUID)row.get("batch_id"),part,ImportService.digest(("csv:"+key+":"+start).getBytes(StandardCharsets.UTF_8))).path("createdTaskCount").asInt();
        }
        return json.createObjectNode().put("batchId",row.get("batch_id").toString()).put("createdTaskCount",created).put("mappedTaskCount",output.size());
    }
    private JsonNode resourcePath(JsonNode value,UUID batch,String source) {
        if(value.isArray()){var out=json.createArrayNode();for(var item:value)out.add(resourcePath(item,batch,source));return out;}
        if(!value.isTextual() || value.asText().isBlank())throw error(422,"INVALID_RESOURCE_PATH","引用资源必须是路径或路径数组");
        try {
            String name=value.asText().replace('\\','/');
            if(name.contains(":") || name.startsWith("/"))throw error(422,"INVALID_RESOURCE_PATH","请引用同包内资源");
            java.nio.file.Path parent=java.nio.file.Path.of(source).getParent();
            var path=(parent==null?java.nio.file.Path.of(name):parent.resolve(name)).normalize();
            if(path.isAbsolute() || path.startsWith(".."))throw error(422,"INVALID_RESOURCE_PATH","资源路径越界");
            String normalized=path.toString().replace('\\','/');
            if(db.queryForList("SELECT id FROM input_resource WHERE batch_id=? AND source_path=?",batch,normalized).isEmpty())throw error(422,"RESOURCE_NOT_FOUND","CSV 引用的资源不存在："+name);
            return json.getNodeFactory().textNode(normalized);
        }catch(java.nio.file.InvalidPathException exc){throw error(422,"INVALID_RESOURCE_PATH","资源路径无效");}
    }
    private char delimiter(Mapping input){String delimiter=input.delimiter();if(delimiter==null || delimiter.isEmpty())return ',';if(delimiter.length()!=1 || "\r\n\"".contains(delimiter))throw error(422,"INVALID_MAPPING","分隔符无效");return delimiter.charAt(0);}
    private double number(String value){try{double number=Double.parseDouble(value);if(Double.isFinite(number))return number;}catch(NumberFormatException ignored){}throw error(422,"INVALID_CSV_VALUE","时间或通道包含非数值内容");}
    private double timestamp(String value,ZoneId zone) {
        try{return OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException ignored){}
        try{return LocalDateTime.parse(value.replace(' ','T')).atZone(zone).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException ignored){}
        try{return LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException ignored){}
        throw error(422,"INVALID_CSV_TIME","时间不是有效的 ISO 日期或时间，请使用数值时间或先规范化");
    }
    private String config(Mapping mapping,String field,boolean timestamp) {
        StringBuilder xml=new StringBuilder("<View><TimeSeries name=\"series_view\" value=\"$").append(escape(field)).append("\" valueType=\"json\" timeColumn=\"").append(escape(mapping.timeColumn())).append("\"");
        xml.append(timestamp?" timeFormat=\"%Q\" timeDisplayFormat=\"%Y-%m-%d %H:%M:%S\"":" timeDisplayFormat=\".6~g\"").append(" overviewWidth=\"100%\">");
        for(String channel:mapping.channelColumns()) {
            xml.append("<Channel column=\"").append(escape(channel)).append("\" legend=\"").append(escape(channel)).append("\"");
            if(mapping.units()!=null && mapping.units().containsKey(channel))xml.append(" units=\"").append(escape(mapping.units().get(channel))).append("\"");
            xml.append("/>");
        }
        return xml.append("</TimeSeries><Choices name=\"classification\" toName=\"series_view\" choice=\"single-radio\" required=\"true\"><Choice value=\"标签一\"/><Choice value=\"标签二\"/></Choices><TimeSeriesLabels name=\"events\" toName=\"series_view\"><Label value=\"事件\"/></TimeSeriesLabels></View>").toString();
    }
    private String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;").replace(">","&gt;");}
    public record Mapping(String timeColumn,List<String> channelColumns,List<String> groupColumns,String timeType,String timezone,String timeUnit,Map<String,String> units,String fieldName,String mode,Map<String,String> columnMapping,List<String> resourceFields,List<String> jsonFields,String delimiter){}
}
