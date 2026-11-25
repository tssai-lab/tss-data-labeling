package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.review.ExportWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="ls.test.python",matches=".+")
class RecordWorkflowIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        ImportIntegrationTest.properties(p);
        p.add("labeling.ls-source.python",()->System.getProperty("ls.test.python"));
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ImportWorker imports; @Autowired ExportWorker exports;
    static final String API="/api/labeling/v1";
    JsonNode postApi(String path,Object body,int expected) throws Exception {
        var response=mvc.perform(post(API+path).header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsString()).path("data");
    }
    JsonNode getApi(String path) throws Exception {
        return json.readTree(mvc.perform(get(API+path).header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    String upload(String project,String filename,String content) throws Exception {
        byte[] bytes=ImportIntegrationTest.zip(Map.of(filename,content.getBytes(StandardCharsets.UTF_8)));
        String batch=postApi("/projects/"+project+"/imports",Map.of("filename","records.zip","sizeBytes",bytes.length,"sha256",ImportIntegrationTest.sha(bytes)),201).path("batchId").asText();
        for(int offset=0;offset<bytes.length;offset+=256) {
            byte[] chunk=Arrays.copyOfRange(bytes,offset,Math.min(offset+256,bytes.length));
            mvc.perform(put(API+"/imports/"+batch+"/chunks?offset="+offset).header("Authorization","Bearer owner").header("X-Chunk-SHA256",ImportIntegrationTest.sha(chunk)).contentType("application/octet-stream").content(chunk)).andExpect(status().isOk());
        }
        mvc.perform(post(API+"/imports/"+batch+"/complete").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isAccepted());imports.runOnce();
        assertEquals("READY",getApi("/imports/"+batch).path("status").asText());return batch;
    }
    @Test void structuredRowCellValidationReviewAndAllExports() throws Exception { workflow(true); }
    @Test void documentNodeEntitiesRelationsAndSidecarRoundTrip() throws Exception { workflow(false); }
    @Test void invalidRowsAreReportedWithTheirSourceWhileValidPrimaryKeysSurvive() throws Exception {
        for(boolean table:List.of(true,false)) {
            String type=table?"STRUCTURED":"SEMI_STRUCTURED";
            String project=postApi("/projects",Map.of("name","错误行验证","taskType",type),201).path("projectId").asText();
            String batch=upload(project,table?"rows.csv":"records.jsonl",table?"id,amount\n001,0\n2,多余,列\n":"{\"id\":\"001\"}\nnot-json\n");
            var imported=getApi("/imports/"+batch);
            assertEquals(1,imported.path("validSampleCount").asInt());assertEquals(1,imported.path("errorCount").asInt());
            assertTrue(imported.path("errors").toString().contains("#row-"));
        }
    }
    void workflow(boolean table) throws Exception {
        String type=table?"STRUCTURED":"SEMI_STRUCTURED",id=table?"structured-records":"semi-structured-documents",format=id+"-result-v1";
        var catalog=getApi("/task-templates");
        assertTrue(catalog.toString().contains(id));
        String project=postApi("/projects",Map.of("name","记录验证","taskType",type,"templateId",id,"templateVersion","1.0.0"),201).path("projectId").asText();
        String raw="{ \"id\": \"001\", \"a/b\": \"甲😀乙\", \"label\": \"原值\" }";
        upload(project,table?"data.csv":"data.json",table?"id,a/b,label\n001,甲😀乙,原值\n":raw);
        String sample=getApi("/projects/"+project+"/input-samples").path("items").get(0).path("sampleId").asText();
        var input=getApi("/projects/"+project+"/samples/"+sample+"/data");
        assertEquals("001",input.at("/record/id").asText());
        assertEquals("甲😀乙",input.at("/record/a~1b").asText());
        mvc.perform(get(API+"/projects/"+project+"/samples/"+sample+"/data").header("Authorization","Bearer outsider")).andExpect(status().isNotFound());
        Map<String,Object> definition=new LinkedHashMap<>(Map.of("taskType",type,"schemaVersion",format,"labels",List.of(Map.of("id","ok","name","已复核")),"editorConfig",Map.of("schema",Map.of("type","object","required",List.of("id")))));
        String spec=postApi("/projects/"+project+"/spec-versions",definition,201).path("specVersionId").asText();
        definition.put("editorConfig",Map.of("schema",Map.of("$ref","https://example.invalid/remote")));
        postApi("/projects/"+project+"/spec-versions",definition,422);
        assertEquals(0,getApi("/projects/"+project+"/samples/"+sample+"/record-validation?specVersionId="+spec).path("issues").size());
        var plan=postApi("/projects/"+project+"/package-plans",Map.of("samples",List.of(Map.of("sampleId",sample)),"specVersionId",spec,"packageSize",10,"annotatorsPerSample",1),201);
        var publication=postApi("/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String pkg=publication.path("packages").get(0).path("packageId").asText();
        String assignment=postApi("/packages/"+pkg+"/claims",Map.of(),201).path("assignmentId").asText();
        String dataPath="/assignments/"+assignment+"/samples/"+sample+"/data",draftPath="/assignments/"+assignment+"/drafts/"+sample;
        getApi(dataPath);getApi("/assignments/"+assignment+"/samples/"+sample+"/record-validation");
        mvc.perform(get(API+dataPath).header("Authorization","Bearer outsider")).andExpect(status().isNotFound());
        var result=json.createObjectNode().put("labelId","ok");
        result.set("nodes",json.valueToTree(List.of(Map.of("path","/a~1b","labelId","ok"))));
        if(!table) {
            result.set("entities",json.valueToTree(List.of(Map.of("id","e1","path","/a~1b","start",0,"end",1,"labelId","ok"),Map.of("id","e2","path","/a~1b","start",1,"end",3,"labelId","ok"))));
            result.set("relations",json.valueToTree(List.of(Map.of("from","e1","to","e2","labelId","ok"))));
        }
        var body=new LinkedHashMap<String,Object>(Map.of("baseRevision",0,"explicitDone",true,"inputHash",input.path("inputHash").asText(),"resultFormat",format,"result",result));
        var invalid=result.deepCopy();invalid.set("nodes",json.valueToTree(List.of(Map.of("path","/absent","labelId","ok"))));
        body.put("result",invalid);
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isUnprocessableEntity());
        body.put("result",result);
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isConflict());
        String submission=postApi("/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        postApi("/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        String snapshot=postApi("/projects/"+project+"/final-snapshots",Map.of("name","记录成果","specVersionId",spec,"sampleIds",List.of(sample)),201).path("finalSnapshotId").asText();
        for(String exportFormat:table?List.of("structured-csv-zip","structured-jsonl-zip","structured-parquet-zip"):List.of("documents-jsonl-sidecar-zip")) {
            String export=postApi("/projects/"+project+"/exports",Map.of("format",exportFormat,"includeOriginals",false,"finalSnapshotIds",List.of(snapshot)),202).path("exportId").asText();
            exports.runOnce();assertEquals("SUCCEEDED",getApi("/exports/"+export).path("status").asText());
            byte[] archive=mvc.perform(get(API+"/exports/"+export+"/download").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String,byte[]> files=new HashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)) { for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes()); }
            var sidecar=json.readTree(new String(files.get("annotations.jsonl"),StandardCharsets.UTF_8));
            assertEquals(sample,sidecar.path("sampleId").asText());assertEquals(result,sidecar.path("annotations"));
            assertFalse(sidecar.path("reviewer").isNull());assertFalse(sidecar.path("annotator").isNull());
            assertTrue(json.readTree(files.get("manifest.json")).path("files").toString().contains("annotations.jsonl"));
            if(!table) { assertEquals(input.path("record"),json.readTree(files.get("documents.jsonl")));assertEquals(raw,sidecar.at("/source/raw").asText());assertEquals(raw,new String(files.get(sidecar.at("/source/originalArchivePath").asText()),StandardCharsets.UTF_8)); }
            else { var columns=json.readTree(files.get("annotation-columns.json"));assertEquals("_label",columns.path("label").asText());
                if(exportFormat.contains("jsonl")) { var exported=json.readTree(files.get("records.jsonl"));assertEquals("原值",exported.path("label").asText());assertEquals("ok",exported.path("_label").asText());assertEquals("001",exported.path("id").asText()); }
                if(exportFormat.contains("parquet")) assertArrayEquals("PAR1".getBytes(StandardCharsets.UTF_8),Arrays.copyOf(files.get("records.parquet"),4));
            }
        }
    }
}
