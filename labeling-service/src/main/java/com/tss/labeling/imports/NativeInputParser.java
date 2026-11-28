package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned input profiles. CSV is one resource per task; JSON contains native LS tasks. */
@Component
public class NativeInputParser {
    private final ObjectMapper json;
    public NativeInputParser(ObjectMapper json) { this.json=json; }

    Map<String,Object> csvMetadata(Path file,long size) throws IOException {
        List<List<String>> preview=new ArrayList<>();char delimiter=csvDelimiter(file);
        try(var reader=new PushbackReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()),1)) {
            int first=reader.read(); if(first!=-1 && first!=0xFEFF) reader.unread(first);
            try(var parser=CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
                List<String> headers=parser.getHeaderNames();
                if(headers.isEmpty() || headers.size()>4096 || headers.stream().anyMatch(String::isBlank)
                        || new HashSet<>(headers).size()!=headers.size()) throw new IOException("CSV 列名为空或重复");
                long count=0;
                for(var record:parser) {
                    if(!record.isConsistent()) throw new IOException("CSV 行与列数不一致");
                    count++;
                    if(preview.size()<5) preview.add(record.toList());
                }
                if(count==0) throw new IOException("CSV 没有观测数据");
                return Map.of("inputFormat","csv-resource-v1","sizeBytes",size,
                        "columns",headers,"rowCount",count,"previewRows",preview,"delimiter",String.valueOf(delimiter));
            }
        } catch(UncheckedIOException exception) { throw exception.getCause(); }
    }

    private char csvDelimiter(Path file)throws IOException {
        int[] counts=new int[3];char[] candidates={',',';','\t'};boolean quoted=false;
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            for(int i=0;i<65536;i++){int value=reader.read();if(value<0)break;char ch=(char)value;
                if(ch=='"')quoted=!quoted;
                if(!quoted){if(ch=='\n'||ch=='\r')break;for(int j=0;j<candidates.length;j++)if(ch==candidates[j])counts[j]++;}
            }
        }
        int best=0;for(int j=1;j<counts.length;j++)if(counts[j]>counts[best])best=j;return candidates[best];
    }

    List<JsonNode> tasks(Path file) throws IOException {
        JsonNode root=json.readTree(file.toFile());
        List<JsonNode> tasks=new ArrayList<>();
        if(root!=null && root.isArray()) root.forEach(tasks::add); else if(root!=null) tasks.add(root);
        if(tasks.stream().noneMatch(task->task.isObject() && task.has("data")))return List.of();
        if(tasks.isEmpty() || tasks.size()>100000) throw new IOException("JSON 任务数不在有效范围");
        for(JsonNode task:tasks) if(!task.isObject() || !task.path("data").isObject())
            throw new IOException("JSON 必须是包含 data 对象的 LS 任务或任务数组");
        return tasks;
    }

    void writeTask(Path path,JsonNode task) throws IOException {
        var stored=json.createObjectNode();
        stored.set("data",task.path("data"));
        if(task.has("meta")) stored.set("meta",task.get("meta"));
        if(task.has("id")) stored.set("sourceTaskId",task.get("id"));
        // Imported annotations/predictions are not execution or review records.
        json.writeValue(path.toFile(),stored);
    }
    Map<String,Object> metadata(JsonNode task,long bytes) {
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("inputFormat","label-studio-task-v1");metadata.put("sizeBytes",bytes);
        List<String> fields=new ArrayList<>();task.path("data").fieldNames().forEachRemaining(fields::add);metadata.put("inputFields",fields);
        String name=task.path("meta").path("displayName").asText(task.path("id").asText(""));
        if(name.isBlank()) name = singleSourceFileName(task);
        if(!name.isBlank())metadata.put("displayName",name);
        return metadata;
    }

    String singleSourceFileName(JsonNode task) {
        Set<String> files = new LinkedHashSet<>();
        collectFileReferences(task.path("data"), files);
        if (files.size() != 1) return "";
        String path = files.iterator().next();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void collectFileReferences(JsonNode value, Set<String> files) {
        if (files.size() > 1) return;
        if (value.isContainerNode()) {
            for (JsonNode child : value) collectFileReferences(child, files);
        } else if (value.isTextual()) {
            String path = value.asText();
            if (!path.startsWith("/") && !path.contains(":") && !path.contains("\\")
                    && (ImageFormats.isReference(path) || path.toLowerCase(Locale.ROOT).endsWith(".csv"))) {
                files.add(path);
            }
        }
    }

}
