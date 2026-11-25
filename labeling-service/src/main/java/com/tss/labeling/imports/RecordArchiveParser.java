package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.labeling.common.BusinessException;
import com.tss.labeling.ls.LsSourceAdapter;
import org.apache.commons.compress.archivers.zip.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import static com.tss.labeling.imports.ArchiveParser.rejected;

/** Same archive boundary as image/time-series import; codecs only see generated private paths. */
@Component
public class RecordArchiveParser {
    private final ImportSettings settings;
    private final ImportStorage storage;
    private final ObjectMapper json;
    private final LsSourceAdapter source;
    public RecordArchiveParser(ImportSettings settings, ImportStorage storage, ObjectMapper json, LsSourceAdapter source) {
        this.settings=settings; this.storage=storage; this.json=json; this.source=source;
    }
    public ArchiveParser.ParsedArchive parse(UUID batch, boolean structured, String inputFormat) throws IOException {
        ZipSafety.check(storage.archive(batch),settings);
        var samples=new ArrayList<ArchiveParser.ParsedSample>();
        var resources=new ArrayList<ArchiveParser.ParsedSample>();
        var errors=new ArrayList<ArchiveParser.FileError>();
        int errorCount=0, entries=0;
        long expanded=0, outputBytes=0;
        Set<String> paths=new HashSet<>();
        try(var zip=ZipFile.builder().setPath(storage.archive(batch)).setCharset(settings.zipEncoding()).get()) {
            var iterator=zip.getEntries();
            while(iterator.hasMoreElements()) {
                var entry=iterator.nextElement();
                String name=ArchiveParser.sourcePath(entry.getName());
                int mode=entry.getUnixMode() & 0170000;
                if(!paths.add(name) || entry.isUnixSymlink() || (mode!=0 && mode!=0100000 && mode!=0040000))
                    throw rejected("UNSAFE_ARCHIVE","归档含重复路径或特殊文件");
                if(++entries>settings.maxEntries() || entry.getSize()<0 || entry.getSize()>settings.maxFileBytes()
                        || entry.getSize()>settings.maxExpandedBytes()-expanded)
                    throw rejected("EXPANSION_LIMIT","归档展开超过上限");
                if(!zip.canReadEntryData(entry)) throw rejected("UNSUPPORTED_FORMAT","无法读取压缩条目");
                if(entry.isDirectory()) {
                    if(entry.getSize()!=0) throw rejected("INVALID_ARCHIVE","目录含有数据");
                    continue;
                }
                Path temporary=Files.createTempFile(storage.sampleDirectory(batch),"record-",".input");
                try {
                    var crc=new CRC32(); long length=0;
                    try(var input=zip.getInputStream(entry);var out=Files.newOutputStream(temporary)) {
                        byte[] buffer=new byte[65536]; int size;
                        while((size=input.read(buffer))!=-1) {
                            if(size>settings.maxFileBytes()-length || size>settings.maxExpandedBytes()-expanded)
                                throw rejected("EXPANSION_LIMIT","实际展开超过上限");
                            length+=size; expanded+=size; crc.update(buffer,0,size); out.write(buffer,0,size);
                        }
                    }
                    if(length!=entry.getSize() || crc.getValue()!=entry.getCrc()) throw rejected("INVALID_ARCHIVE","条目长度或 CRC 校验失败");
                    var parsed=source.parseRecords(temporary,name,structured);
                    UUID resource=UUID.nameUUIDFromBytes((batch+"\nsource:"+name).getBytes(StandardCharsets.UTF_8));
                    String sourceHash=ImportStorage.sha256(temporary);
                    for(var failure:parsed.path("errors")) {
                        if(errors.size()<settings.maxErrors()) errors.add(new ArchiveParser.FileError(name+"#row-"+failure.path("row").asText(),"INVALID_RECORD",failure.path("error").asText()));
                    }
                    errorCount+=parsed.path("errorCount").asInt(parsed.path("errors").size());
                    for(var record:parsed.path("records")) {
                        if(samples.size()>=Math.min(10000,settings.maxEntries())) throw rejected("EXPANSION_LIMIT","单次最多 10000 条记录");
                        String location=name+(record.has("sheet")?"#sheet-"+record.path("sheet").asText():"")+"#row-"+record.path("row").asText();
                        UUID id=UUID.nameUUIDFromBytes((batch+"\n"+location).getBytes(StandardCharsets.UTF_8));
                        var payload=json.createObjectNode();
                        payload.put("inputFormat",inputFormat).put("sourcePath",name).put("sourceRow",record.path("row").asLong());
                        payload.put("sourceResourceId",resource.toString()).put("sourceHash",sourceHash);
                        if(structured) {
                            var columns=payload.putArray("columns");record.path("record").fieldNames().forEachRemaining(columns::add);
                        }
                        if(record.has("sheet")) payload.set("sheet",record.path("sheet"));
                        payload.set("record",record.path("record"));
                        if(record.has("raw")) payload.set("raw",record.path("raw"));
                        byte[] bytes=json.writeValueAsBytes(payload);
                        outputBytes+=bytes.length;
                        if(outputBytes>Math.min(settings.maxExpandedBytes(),64L*1024*1024) || bytes.length>settings.maxFileBytes())
                            throw rejected("EXPANSION_LIMIT","解析后的记录超过存储上限");
                        Path target=storage.sampleDirectory(batch).resolve(id+".bin");
                        Files.write(target,bytes);
                        Map<String,Object> metadata=json.convertValue(payload,Map.class);
                        metadata.put("displayName",location);metadata.put("sizeBytes",bytes.length);
                        samples.add(new ArchiveParser.ParsedSample(id,location,batch+"/samples/"+id+".bin",ImportStorage.sha256(target),"application/json",metadata));
                    }
                    if(!parsed.path("records").isEmpty()) {
                        Files.copy(temporary,storage.sampleDirectory(batch).resolve(resource+".bin"),StandardCopyOption.REPLACE_EXISTING);
                        resources.add(new ArchiveParser.ParsedSample(resource,name,batch+"/samples/"+resource+".bin",sourceHash,"application/octet-stream",Map.of("inputFormat","original-record-file-v1","sizeBytes",length)));
                    }
                } catch(BusinessException ex) {
                    // Infrastructure failures must remain retryable, never mark them as bad user data.
                    if(ex.status().is5xxServerError()) throw ex;
                    errorCount++;
                    if(errors.size()<settings.maxErrors()) errors.add(new ArchiveParser.FileError(name,ex.code(),ex.getMessage()));
                } finally { Files.deleteIfExists(temporary); }
            }
        }
        return new ArchiveParser.ParsedArchive(samples,resources,errors,errorCount);
    }
}
