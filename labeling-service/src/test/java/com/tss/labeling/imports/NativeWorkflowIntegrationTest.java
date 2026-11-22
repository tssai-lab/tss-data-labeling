package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tss.labeling.ls.SourceExportWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="ls.test.python",matches=".+")
class NativeWorkflowIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties){
        ImportIntegrationTest.properties(properties);
        properties.add("labeling.exports.worker-enabled",()->false);
        properties.add("labeling.ls-source.python",()->System.getProperty("ls.test.python","python"));
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ImportWorker imports;@Autowired SourceExportWorker exports;
    static final String API="/api/labeling/v1";
    static final String XML="""
            <View><TimeSeries name="curve" value="$readings" valueType="json" timeColumn="t"><Channel column="a"/><Channel column="b"/></TimeSeries>
            <Choices name="state" toName="curve" required="true"><Choice value="正常"/><Choice value="异常"/></Choices>
            <TimeSeriesLabels name="events" toName="curve"><Label value="异常"/></TimeSeriesLabels></View>
            """;
    JsonNode postApi(String user,String path,Object body,int status) throws Exception {
        var response=mvc.perform(post(API+path).header("Authorization","Bearer "+user).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(status,response.getStatus(),response.getContentAsString());
        String content=response.getContentAsString();
        return json.readTree(content).path("data");
    }
    JsonNode getApi(String user,String path) throws Exception {
        return json.readTree(mvc.perform(get(API+path).header("Authorization","Bearer "+user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    String upload(String project,Map<String,byte[]> files) throws Exception {
        byte[] bytes=ImportIntegrationTest.zip(files);
        String batch=postApi("owner","/projects/"+project+"/imports",Map.of("filename","inputs.zip","sizeBytes",bytes.length,"sha256",ImportIntegrationTest.sha(bytes)),201).path("batchId").asText();
        for(int offset=0;offset<bytes.length;offset+=256) {
            byte[] part=Arrays.copyOfRange(bytes,offset,Math.min(offset+256,bytes.length));
            mvc.perform(put(API+"/imports/"+batch+"/chunks").queryParam("offset",Integer.toString(offset))
                    .header("Authorization","Bearer owner").header("X-Chunk-SHA256",ImportIntegrationTest.sha(part)).contentType("application/octet-stream").content(part))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(API+"/imports/"+batch+"/complete").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isAccepted());
        imports.runOnce();assertEquals("READY",getApi("owner","/imports/"+batch).path("status").asText());return batch;
    }
    JsonNode result(String label) {
        return json.valueToTree(Map.of("result",List.of(
                Map.of("id","class","from_name","state","to_name","curve","type","choices","value",Map.of("choices",List.of(label))),
                Map.of("id","interval","from_name","events","to_name","curve","type","timeserieslabels","value",Map.of("start",0.25,"end",2.5,"timeserieslabels",List.of("异常"))))));
    }

    @Test void nativeTaskJsonUsesExistingClaimReviewFinalizationAndActualSourceExports() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","通用时序闭环","taskType","TIME_SERIES"),201).path("projectId").asText();
        String batch=upload(project,Map.of("tasks.json",json.writeValueAsBytes(List.of(Map.of("data",Map.of("readings",List.of(
                Map.of("t",0.25,"a",-3,"b",1),Map.of("t",2.5,"a",0,"b",2)),"station","站点甲")))),
                "unused.csv","t,a,b\n0,1,2\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1,getApi("owner","/imports/"+batch).path("validSampleCount").asInt());
        assertEquals(1,getApi("owner","/imports/"+batch).path("resourceCount").asInt());
        String sample=getApi("owner","/projects/"+project+"/input-samples").path("items").get(0).path("sampleId").asText();
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1",
                "labels",List.of(),"editorConfig",Map.of("labelConfig",XML,"templateId","custom")),201).path("specVersionId").asText();
        JsonNode task=getApi("owner","/projects/"+project+"/samples/"+sample+"/task?specVersionId="+spec);
        assertEquals(-3,task.at("/task/data/readings/0/a").asInt());
        JsonNode plan=postApi("owner","/projects/"+project+"/package-plans",Map.of("samples",List.of(Map.of("sampleId",sample)),"specVersionId",spec,"packageSize",10,"annotatorsPerSample",1),201);
        JsonNode published=postApi("owner","/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String pkg=published.path("packages").get(0).path("packageId").asText();
        // The requester can annotate too: no role-based exclusion.
        String assignment=postApi("owner","/packages/"+pkg+"/claims",Map.of(),201).path("assignmentId").asText();
        getApi("owner","/assignments/"+assignment+"/samples/"+sample+"/task");
        mvc.perform(get(API+"/assignments/"+assignment+"/samples/"+sample+"/task").header("Authorization","Bearer outsider"))
                .andExpect(status().isNotFound());
        String draftPath="/assignments/"+assignment+"/drafts/"+sample;
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",result("不存在")))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",result("异常")))))
                .andExpect(status().isOk());
        mvc.perform(put(API+draftPath).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",result("正常")))))
                .andExpect(status().isConflict());
        String submission=postApi("owner","/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        // Review revisions preserve the original immutable submission.
        mvc.perform(put(API+"/submissions/"+submission+"/review/items").header("Authorization","Bearer owner")
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("items",List.of(Map.of("sampleId",sample,"action","MODIFY","result",result("正常")))))))
                .andExpect(status().isOk());
        postApi("owner","/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        String snapshot=postApi("owner","/projects/"+project+"/final-snapshots",Map.of("name","已确认成果","specVersionId",spec,"sampleIds",List.of(sample)),201).path("finalSnapshotId").asText();
        assertEquals(1,getApi("owner","/projects/"+project+"/final-snapshots").path("items").get(0).path("sampleCount").asInt());
        long revision=getApi("owner","/projects/"+project).path("revision").asLong();
        postApi("owner","/projects/"+project+"/complete",Map.of("baseRevision",revision,"finalSnapshotId",snapshot),200);
        postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig",XML)),404);
        for(String format:List.of("JSON","CSV","TSV")) {
            String export=postApi("owner","/final-snapshots/"+snapshot+"/exports",Map.of("format",format),202).path("exportId").asText();
            exports.runOnce();assertEquals("READY",getApi("owner","/source-exports/"+export).path("status").asText());
            byte[] archive=mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer owner"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String,String> files=new HashMap<>();
            try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)) {
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
            }
            assertTrue(files.containsKey("label_config.xml"));
            if(format.equals("JSON")) {
                JsonNode output=json.readTree(files.get("result.json"));
                assertEquals("正常",output.at("/0/annotations/0/result/0/value/choices/0").asText());
                assertEquals(0.25,output.at("/0/annotations/0/result/1/value/start").asDouble());
            }else assertTrue(files.values().stream().anyMatch(value->value.contains("正常")&&value.contains("站点甲")));
        }
    }

    @Autowired com.tss.labeling.ai.AiController aiController;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    @Test void fixedModelWorkerCanReadNativeInputsAndPersistCandidates() throws Exception {
        byte[] picture = ImportIntegrationTest.image("png");
        verifyPrelabel("COMPUTER_VISION", "yolo-pcb", "yoloPcbAgentId",
                """
                <View><Image name="image" value="$image"/>
                <RectangleLabels name="defect" toName="image"><Label value="Scratch"/></RectangleLabels></View>
                """,
                Map.of("image", "pcb.png"), Map.of("pcb.png", picture),
                Map.of("id", "defect1", "from_name", "defect", "to_name", "image", "type", "rectanglelabels",
                        "original_width", 8, "original_height", 6,
                        "value", Map.of("x", 10, "y", 10, "width", 20, "height", 20, "rotation", 0,
                                "rectanglelabels", List.of("Scratch"))), picture);
        verifyPrelabel("COMPUTER_VISION", "qwen-image", "qwenImageAgentId",
                """
                <View><Image name="image" value="$image"/>
                <Choices name="quality" toName="image"><Choice value="Anomaly"/></Choices></View>
                """,
                Map.of("image", "pcb.png"), Map.of("pcb.png", picture),
                Map.of("id", "quality1", "from_name", "quality", "to_name", "image", "type", "choices",
                        "value", Map.of("choices", List.of("Anomaly"))), picture);
        verifyPrelabel("TIME_SERIES", "qwen-timeseries", "qwenTimeseriesAgentId",
                """
                <View><TimeSeries name="curve" value="$series" valueType="json" timeColumn="hour">
                <Channel column="kwh"/></TimeSeries>
                <TimeSeriesLabels name="events" toName="curve"><Label value="Spike"/></TimeSeriesLabels></View>
                """,
                Map.of("series", Map.of("hour", List.of(0, 1, 2), "kwh", List.of(1, 9, 1))), Map.of(),
                Map.of("id", "event1", "from_name", "events", "to_name", "curve", "type", "timeserieslabels",
                        "value", Map.of("start", 1, "end", 1, "instant", true, "timeserieslabels", List.of("Spike"))), null);
    }

    private void verifyPrelabel(String taskType, String modelId, String field, String xml,
            Map<String, Object> data, Map<String, byte[]> resources, Map<String, Object> candidate,
            byte[] expectedImage) throws Exception {
        String project = postApi("owner", "/projects", Map.of("name", modelId, "taskType", taskType), 201)
                .path("projectId").asText();
        var files = new HashMap<>(resources);
        if (expectedImage == null) {
            files.put("tasks.json", json.writeValueAsBytes(List.of(Map.of("data", data))));
        }
        String batch = upload(project, files);
        if (expectedImage != null) {
            postApi("owner", "/projects/" + project + "/imports/" + batch + "/tasks",
                    Map.of("tasks", List.of(Map.of("data", data))), 200);
        }
        JsonNode listedSample = getApi("owner", "/projects/" + project + "/input-samples").at("/items/0");
        String sample = listedSample.path("sampleId").asText();
        if (expectedImage != null) {
            assertEquals("pcb.png", listedSample.at("/metadata/displayName").asText());
            // Older mapped samples have the filename only in the stored task input.
            try (var connection = ImportIntegrationTest.PG.getPostgresDatabase().getConnection();
                    var update = connection.prepareStatement(
                            "UPDATE labeling.sample SET metadata=metadata-'displayName' WHERE id=?")) {
                update.setObject(1, UUID.fromString(sample));
                assertEquals(1, update.executeUpdate());
            }
            assertEquals("pcb.png", getApi("owner", "/projects/" + project + "/input-samples")
                    .at("/items/0/metadata/displayName").asText());
            assertEquals("pcb.png", getApi("owner", "/projects/" + project + "/samples/" + sample
                    + "/native-input").path("displayName").asText());
        }
        String spec = postApi("owner", "/projects/" + project + "/spec-versions",
                Map.of("taskType", taskType, "schemaVersion", "label-studio-v1", "labels", List.of(),
                        "editorConfig", Map.of("labelConfig", xml, "templateId", "custom")), 201)
                .path("specVersionId").asText();
        String credential = UUID.randomUUID().toString();
        String adminUser = "native-prelabel-admin";
        getApi(adminUser, "/me");
        db.update("""
                INSERT INTO actor_role_grant(actor_id,role_code)
                SELECT id, 'LABELING_ADMIN' FROM actor WHERE identity_user_id=?
                ON CONFLICT DO NOTHING
                """, adminUser);
        String agent = postApi(adminUser, "/admin/agents",
                Map.of("taskType", taskType, "displayName", modelId, "modelVersion", "fixture",
                        "workerKey", credential), 201).path("agentId").asText();
        Object previous = org.springframework.test.util.ReflectionTestUtils.getField(aiController, field);
        org.springframework.test.util.ReflectionTestUtils.setField(aiController, field, agent);
        String execution;
        try {
            execution = postApi("owner", "/projects/" + project + "/prelabel-runs",
                    Map.of("modelId", modelId, "specVersionId", spec, "instructions", "fixture guide",
                            "samples", List.of(Map.of("sampleId", sample))), 202).path("executionId").asText();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(aiController, field, previous);
        }
        String root = "/internal/labeling/v1/executions";
        var claim = json.readTree(mvc.perform(post(root + "/claim").header("X-Worker-Key", credential))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(execution, claim.path("executionId").asText());
        assertEquals(xml, claim.at("/editorConfig/labelConfig").asText());
        assertEquals("fixture guide", claim.path("instructions").asText());
        long generation = claim.path("generation").asLong();
        String path = root + "/" + execution;
        String samplePath = path + "/samples/" + sample;
        var input = json.readTree(mvc.perform(get(samplePath + "/data").header("X-Worker-Key", credential)
                .queryParam("generation", Long.toString(generation))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(json.valueToTree(data), input.at("/task/data"));
        if (expectedImage != null) {
            String resource = input.at("/resources/0/resourceId").asText();
            var image = mvc.perform(get(samplePath + "/resources/" + resource).header("X-Worker-Key", credential)
                    .queryParam("generation", Long.toString(generation))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertArrayEquals(expectedImage, image);
            mvc.perform(get(samplePath + "/resources/" + UUID.randomUUID()).header("X-Worker-Key", credential)
                    .queryParam("generation", Long.toString(generation))).andExpect(status().isNotFound());
        }
        mvc.perform(put(path + "/outputs/" + sample).header("X-Worker-Key", credential)
                .contentType("application/json").content(json.writeValueAsString(Map.of("generation", generation,
                        "status", "SUCCEEDED", "result", Map.of("result", List.of(candidate))))))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/finish").header("X-Worker-Key", credential)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("generation", generation)))).andExpect(status().isOk());
        assertEquals("SUCCEEDED", getApi("owner", "/ai-executions/" + execution).path("status").asText());
        var output = getApi("owner", "/ai-executions/" + execution + "/results").at("/items/0");
        assertTrue(output.path("reviewable").asBoolean());
        assertEquals(candidate.get("type"), output.at("/result/result/0/type").asText());
    }

    @Test void csvIsAResourceUntilExplicitNativeTasksArePrepared() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","资源与任务分离","taskType","TIME_SERIES"),201).path("projectId").asText();
        String batch=upload(project,Map.of("daily.csv","t,a,b\n0,1,2\n1,3,4\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0,getApi("owner","/projects/"+project+"/input-samples").path("items").size());
        JsonNode resource=getApi("owner","/projects/"+project+"/input-resources").path("items").get(0);
        assertEquals("t",resource.at("/metadata/columns/0").asText());
        postApi("owner","/projects/"+project+"/imports/"+batch+"/tasks",Map.of("tasks",List.of(Map.of("data",Map.of("readings","daily.csv")))),200);
        String sample=getApi("owner","/projects/"+project+"/input-samples").path("items").get(0).path("sampleId").asText();
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1","labels",List.of(),
                "editorConfig",Map.of("labelConfig",XML.replace("valueType=\"json\"","valueType=\"url\""))),201).path("specVersionId").asText();
        JsonNode context=getApi("owner","/projects/"+project+"/samples/"+sample+"/task?specVersionId="+spec);
        String url=context.path("resources").get(0).path("url").asText();
        mvc.perform(get(url).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().string("t,a,b\n0,1,2\n1,3,4\n"));
        mvc.perform(get(url).header("Authorization","Bearer outsider")).andExpect(status().isNotFound());
    }

    @Test void configurationWorkspaceUsesAuthenticatedHttpForCatalogValidationAndPreview() throws Exception {
        mvc.perform(get(API+"/label-configs/templates")).andExpect(status().isUnauthorized());
        JsonNode templates=getApi("owner","/label-configs/templates");
        assertEquals(17,templates.path("templates").size());assertEquals(2,templates.path("groups").size());
        for(JsonNode template:templates.path("templates"))assertEquals("community",template.path("type").asText());
        String filtered=mvc.perform(get(API+"/label-configs/templates").queryParam("category","Time Series Analysis").header("Authorization","Bearer owner"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(5,json.readTree(filtered).at("/data/templates").size());
        var standalone=Map.of("labelConfig",XML,"taskType","TIME_SERIES");
        assertTrue(postApi("owner","/label-configs/validate",standalone,200).path("valid").asBoolean());
        JsonNode generated=postApi("owner","/label-configs/sample-task",standalone,200);
        assertTrue(generated.path("isExample").asBoolean());assertEquals(100,generated.at("/sample_task/readings/t").size());
        var csv=Map.of("labelConfig",XML.replace("valueType=\"json\"","valueType=\"url\""),"taskType","TIME_SERIES");
        JsonNode generatedCsv=postApi("owner","/label-configs/sample-task",csv,200);
        assertTrue(generatedCsv.at("/resources/0/content").asText().startsWith("t,a,b"));
        mvc.perform(get(API+"/label-configs/preview-image").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        String project=postApi("owner","/projects",Map.of("name","真实配置预览","taskType","TIME_SERIES"),201).path("projectId").asText();
        upload(project,Map.of("tasks.json",json.writeValueAsBytes(List.of(Map.of("data",Map.of("readings",Map.of("t",List.of(0,1),"a",List.of(-7,0),"b",List.of(3,4))))))));
        String sample=getApi("owner","/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        var request=Map.of("projectId",project,"sampleId",sample,"labelConfig",XML);
        JsonNode actual=postApi("owner","/label-configs/sample-task",request,200);
        assertFalse(actual.path("isExample").asBoolean());assertEquals(-7,actual.at("/sample_task/readings/a/0").asInt());
        JsonNode summary=getApi("owner","/label-configs/data-summary?projectId="+project+"&sampleId="+sample);
        assertEquals("readings",summary.at("/common_data_columns/0").asText());
        postApi("other","/label-configs/sample-task",request,404);
        postApi("owner","/label-configs/validate",Map.of("projectId",project,"labelConfig",XML.replace("TimeSeries name=", "UnknownTag name=")),422);
        postApi("owner","/label-configs/sample-task",Map.of("projectId",project,"sampleId",sample,"labelConfig",XML.replace("column=\"a\"","column=\"missing\"")),422);
        assertEquals(0,getApi("owner","/projects/"+project).path("specVersions").size());
    }

    @Test void csvMappingGroupsRowsWithoutInventingEnergyFieldsAndDeduplicatesRetries() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","通用字段映射","taskType","TIME_SERIES"),201).path("projectId").asText();
        upload(project,Map.of("observations.csv","machine,day,t,temperature\nA,2026-11-01,1,-3\nB,2026-11-01,0,0\nA,2026-11-01,0,\nB,2026-11-01,1,4\n".getBytes(StandardCharsets.UTF_8)));
        String resource=getApi("owner","/projects/"+project+"/input-resources").path("items").get(0).path("resourceId").asText();
        var mapping=Map.of("timeColumn","t","channelColumns",List.of("temperature"),"groupColumns",List.of("machine","day"),"timeType","number","timeUnit","s","units",Map.of("temperature","°C"),"fieldName","measurements");
        JsonNode first=postApi("owner","/projects/"+project+"/input-resources/"+resource+"/task-mapping",mapping,200);
        assertEquals(2,first.path("createdTaskCount").asInt());
        assertTrue(first.path("suggestedConfig").asText().contains("$measurements"));
        JsonNode second=postApi("owner","/projects/"+project+"/input-resources/"+resource+"/task-mapping",mapping,200);
        assertEquals(0,second.path("createdTaskCount").asInt());
        JsonNode samples=getApi("owner","/projects/"+project+"/input-samples").path("items");
        assertEquals(2,samples.size());
        boolean sawMissing=false;
        for(JsonNode sample:samples) {
            JsonNode task=getApi("owner","/projects/"+project+"/samples/"+sample.path("sampleId").asText()+"/native-input");
            assertEquals(0,task.at("/task/data/measurements/t/0").asInt());
            if(task.at("/task/data/machine").asText().equals("A")) {
                assertTrue(task.at("/task/data/measurements/temperature/0").isNull());
                assertEquals(-3,task.at("/task/data/measurements/temperature/1").asInt());sawMissing=true;
            }
        }
        assertTrue(sawMissing);
    }

    @Test void projectSummaryTracksAllFieldsAndDraftReplacementWithoutCountingResources() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","项目摘要","taskType","TIME_SERIES"),201).path("projectId").asText();
        var curve=Map.of("t",List.of(0.25,2.5),"a",List.of(-2,3),"b",List.of(0,4));
        upload(project,Map.of("tasks.json",json.writeValueAsBytes(List.of(Map.of("data",Map.of("readings",curve,"extra","one")),Map.of("data",Map.of("readings",curve)))),"sidecar.csv","x,y\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        JsonNode summary=getApi("owner","/label-configs/data-summary?projectId="+project);
        assertEquals("project",summary.path("scope").asText());assertEquals(2,summary.path("task_count").asInt());
        assertEquals(2,summary.at("/all_data_columns/readings").asInt());assertEquals(1,summary.at("/all_data_columns/extra").asInt());
        assertEquals(json.readTree("[\"readings\"]"),summary.path("common_data_columns"));
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig",XML,"templateId","ls:time-series-analysis/change-point-detection")),201).path("specVersionId").asText();
        JsonNode frozen=getApi("owner","/projects/"+project).at("/specVersions/0/editorConfig");
        assertTrue(frozen.path("templateVersion").asText().startsWith("1.23.2:"));assertEquals(64,frozen.path("configSha256").asText().length());
        assertEquals("ls-native-2",frozen.path("capabilities").path("version").asText());
        String sample=getApi("owner","/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        JsonNode plan=postApi("owner","/projects/"+project+"/package-plans",Map.of("specVersionId",spec,"samples",List.of(Map.of("sampleId",sample)),"packageSize",1),201);
        JsonNode published=postApi("owner","/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String assignment=postApi("owner","/packages/"+published.at("/packages/0/packageId").asText()+"/claims",Map.of(),201).path("assignmentId").asText();
        for(int revision=0;revision<2;revision++)mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",revision,"explicitDone",true,"resultFormat","label-studio-v1","result",result(revision==0?"正常":"异常"))))).andExpect(status().isOk());
        summary=getApi("owner","/label-configs/data-summary?projectId="+project);
        assertEquals(1,summary.at("/created_labels_drafts/state/异常").asInt());assertTrue(summary.at("/created_labels_drafts/state/正常").isMissingNode());
        postApi("owner","/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",2))),201);
        summary=getApi("owner","/label-configs/data-summary?projectId="+project);
        assertEquals(0,summary.path("created_labels_drafts").size());assertEquals(1,summary.at("/created_labels/state/异常").asInt());
        assertEquals(1,summary.path("created_annotations").path("state|curve|choices").asInt());
        mvc.perform(get(API+"/label-configs/data-summary").param("projectId",project).header("Authorization","Bearer other")).andExpect(status().isNotFound());
    }

    private record Done(String sample,String spec,String assignment,String submission){}
    private Done annotateOne(String project,String sample,String spec,String reference) throws Exception {
        var selection=new LinkedHashMap<String,Object>();selection.put("sampleId",sample);if(reference!=null)selection.put("referenceResultId",reference);
        JsonNode plan=postApi("owner","/projects/"+project+"/package-plans",Map.of("specVersionId",spec,"samples",List.of(selection),"packageSize",1),201);
        JsonNode published=postApi("owner","/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String assignment=postApi("owner","/packages/"+published.at("/packages/0/packageId").asText()+"/claims",Map.of(),201).path("assignmentId").asText();
        if(reference!=null){JsonNode item=getApi("owner","/assignments/"+assignment+"/items").at("/items/0");assertFalse(item.path("explicitDone").asBoolean());assertTrue(item.path("referenceResult").isObject());}
        mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",result("正常"))))).andExpect(status().isOk());
        String submission=postApi("owner","/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        postApi("owner","/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        return new Done(sample,spec,assignment,submission);
    }

    @Test void partialReworkKeepsHistoryAndMultiSpecOutcomeExportsSeparateConfigs() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","跨规范成果","taskType","TIME_SERIES"),201).path("projectId").asText();
        var curve=Map.of("t",List.of(0.25,2.5),"a",List.of(-2,3),"b",List.of(0,4));
        upload(project,Map.of("tasks.json",json.writeValueAsBytes(List.of(Map.of("data",Map.of("readings",curve,"name","one")),Map.of("data",Map.of("readings",curve,"name","two"))))));
        JsonNode samples=getApi("owner","/projects/"+project+"/input-samples").path("items");
        String first=samples.get(0).path("sampleId").asText(),second=samples.get(1).path("sampleId").asText();
        String spec1=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig",XML)),201).path("specVersionId").asText();
        Done old=annotateOne(project,first,spec1,null);JsonNode historical=getApi("owner","/submissions/"+old.submission());
        String spec2=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","TIME_SERIES","schemaVersion","label-studio-v1","labels",List.of(),"instructions","第二版","editorConfig",Map.of("labelConfig",XML.replace("<View>","<View><Header value=\"第二版\"/>"))),201).path("specVersionId").asText();
        Done revised=annotateOne(project,first,spec2,old.submission());Done other=annotateOne(project,second,spec1,null);
        assertEquals(historical,getApi("owner","/submissions/"+old.submission()));
        assertEquals(1,getApi("owner","/assignments/"+revised.assignment()+"/items").path("items").size());
        JsonNode page=getApi("owner","/projects/"+project+"/sample-reviews?limit=1");int count=page.path("items").size();
        while(!page.path("nextCursor").isNull()){page=getApi("owner","/projects/"+project+"/sample-reviews?limit=1&cursor="+page.path("nextCursor").asText());count+=page.path("items").size();}
        assertEquals(3,count);
        var selections=List.of(Map.of("sampleId",first,"specVersionId",spec2),Map.of("sampleId",second,"specVersionId",spec1));
        postApi("owner","/projects/"+project+"/final-snapshots",Map.of("name","重复","selections",List.of(selections.get(0),selections.get(0))),422);
        JsonNode snapshot=postApi("owner","/projects/"+project+"/final-snapshots",Map.of("name","汇总成果","selections",selections),201);
        assertTrue(snapshot.path("specVersionId").isNull());assertEquals(2,snapshot.path("specVersionIds").size());
        String snapshotId=snapshot.path("finalSnapshotId").asText();
        for(String format:List.of("JSON","JSON_MIN","CSV","TSV")){
            String export=postApi("owner","/final-snapshots/"+snapshotId+"/exports",Map.of("format",format),202).path("exportId").asText();exports.runOnce();
            assertEquals("READY",getApi("owner","/source-exports/"+export).path("status").asText());
            byte[] bytes=mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String,byte[]> files=new HashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes());}
            JsonNode manifest=json.readTree(files.get("manifest.json"));assertEquals(2,manifest.path("groups").size());assertEquals(2,manifest.path("samples").size());
            assertEquals(2,files.keySet().stream().filter(name->name.endsWith("label_config.xml")).count());
        }
        long revision=getApi("owner","/projects/"+project).path("revision").asLong();
        postApi("owner","/projects/"+project+"/complete",Map.of("baseRevision",revision,"finalSnapshotId",snapshotId),200);
    }

    @Test void nativeImageArraysRelativeResourcesAndDetectionExportsUseActualInputDimensions() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","通用检测","taskType","COMPUTER_VISION"),201).path("projectId").asText();
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        upload(project,Map.of("images/a.png",png.toByteArray(),"tasks/task.json",json.writeValueAsBytes(Map.of("data",Map.of("pictures",List.of(Map.of("url","../images/a.png")))))));
        String xml="<View><Image name=\"image\" value=\"$pictures[0].url\" zoom=\"true\"/><RectangleLabels name=\"boxes\" toName=\"image\"><Label value=\"设备\" alias=\"machine\"/></RectangleLabels></View>";
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","COMPUTER_VISION","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig",xml,"templateId","ls:computer-vision/object-detection-with-bounding-boxes")),201).path("specVersionId").asText();
        String sample=getApi("owner","/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        JsonNode plan=postApi("owner","/projects/"+project+"/package-plans",Map.of("specVersionId",spec,"samples",List.of(Map.of("sampleId",sample))),201);
        JsonNode published=postApi("owner","/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String assignment=postApi("owner","/packages/"+published.at("/packages/0/packageId").asText()+"/claims",Map.of(),201).path("assignmentId").asText();
        JsonNode context=getApi("owner","/assignments/"+assignment+"/samples/"+sample+"/task");
        String resourceUrl=context.at("/resources/0/url").asText();
        mvc.perform(get(resourceUrl).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get(resourceUrl).header("Authorization","Bearer other")).andExpect(status().isNotFound());
        ObjectNode region=json.createObjectNode().put("id","box").put("from_name","boxes").put("to_name","image").put("type","rectanglelabels").put("original_width",4).put("original_height",4);
        region.putObject("value").put("x",10).put("y",10).put("width",50).put("height",50).put("rotation",0).putArray("rectanglelabels").add("machine");
        for(String invalid:List.of("size","bounds","labels")){
            ObjectNode broken=region.deepCopy();if(invalid.equals("size"))broken.put("original_width",5);
            else if(invalid.equals("bounds"))((ObjectNode)broken.path("value")).put("x",80);
            else ((ObjectNode)broken.path("value")).putArray("rectanglelabels").add("unknown");
            mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",Map.of("result",List.of(broken)))))).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer owner").contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision",0,"explicitDone",true,"resultFormat","label-studio-v1","result",Map.of("result",List.of(region)))))).andExpect(status().isOk());
        String submission=postApi("owner","/assignments/"+assignment+"/submissions",Map.of("items",List.of(Map.of("sampleId",sample,"revision",1))),201).path("submissionId").asText();
        postApi("owner","/submissions/"+submission+"/review/decide",Map.of("action","ACCEPT"),200);
        String snapshot=postApi("owner","/projects/"+project+"/final-snapshots",Map.of("name","检测成果","specVersionId",spec,"sampleIds",List.of(sample)),201).path("finalSnapshotId").asText();
        for(String format:List.of("JSON","JSON_MIN","CSV","TSV","COCO","YOLO")){
            String export=postApi("owner","/final-snapshots/"+snapshot+"/exports",Map.of("format",format),202).path("exportId").asText();exports.runOnce();
            assertEquals("READY",getApi("owner","/source-exports/"+export).path("status").asText(),format);
            byte[] archive=mvc.perform(get(API+"/source-exports/"+export+"/file").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            Map<String,byte[]> files=new HashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes());}
            assertTrue(files.containsKey("manifest.json"));assertTrue(files.keySet().stream().anyMatch(name->name.endsWith(".png")));
            if(format.equals("COCO"))assertEquals(4,json.readTree(files.get("result.json")).at("/annotations/0/bbox").size());
            if(format.equals("YOLO"))assertTrue(files.keySet().stream().anyMatch(name->name.equals("labels/"+sample+".txt")));
        }
    }
    @Test void imageValueListBindsEveryResourceAndPreservesTheArray() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","多图输入","taskType","COMPUTER_VISION"),201).path("projectId").asText();
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        upload(project,Map.of("a.png",png.toByteArray(),"tasks.json",json.writeValueAsBytes(Map.of("data",Map.of("pictures",List.of("a.png","a.png"))))));
        String xml="<View><Image name=\"image\" valueList=\"$pictures\"/><Choices name=\"c\" toName=\"image\"><Choice value=\"甲\"/></Choices></View>";
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","COMPUTER_VISION","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig",xml)),201).path("specVersionId").asText();
        String sample=getApi("owner","/projects/"+project+"/input-samples").at("/items/0/sampleId").asText();
        JsonNode task=getApi("owner","/projects/"+project+"/samples/"+sample+"/task?specVersionId="+spec);
        assertEquals(2,task.path("resources").size());assertEquals(2,task.at("/task/data/pictures").size());
        assertEquals("pictures.0",task.at("/resources/0/field").asText());assertEquals("pictures.1",task.at("/resources/1/field").asText());
    }

    @Test void csvRowsGenerateNativeTasksWithRelativeResourcesArraysAndStableReimport() throws Exception {
        String project=postApi("owner","/projects",Map.of("name","CSV 图片任务","taskType","COMPUTER_VISION"),201).path("projectId").asText();
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        upload(project,Map.of("images/a.png",png.toByteArray(),"rows/inputs.csv","photo;caption;options\n../images/a.png;设备;[1]\n".getBytes(StandardCharsets.UTF_8)));
        JsonNode resources=getApi("owner","/projects/"+project+"/input-resources").path("items");String csv="";
        for(var resource:resources)if(resource.path("mediaType").asText().equals("text/csv"))csv=resource.path("resourceId").asText();
        var mapping=Map.of("mode","rows","columnMapping",Map.of("image","photo","text","caption","options","options"),"resourceFields",List.of("image"),"jsonFields",List.of("options"),"delimiter",";");
        assertEquals(1,postApi("owner","/projects/"+project+"/input-resources/"+csv+"/task-mapping",mapping,200).path("createdTaskCount").asInt());
        assertEquals(0,postApi("owner","/projects/"+project+"/input-resources/"+csv+"/task-mapping",mapping,200).path("createdTaskCount").asInt());
        JsonNode samples=getApi("owner","/projects/"+project+"/input-samples").path("items");assertEquals(1,samples.size());
        String spec=postApi("owner","/projects/"+project+"/spec-versions",Map.of("taskType","COMPUTER_VISION","schemaVersion","label-studio-v1","labels",List.of(),"editorConfig",Map.of("labelConfig","<View><Image name=\"image\" value=\"$image\"/><Choices name=\"c\" toName=\"image\"><Choice value=\"甲\"/></Choices></View>")),201).path("specVersionId").asText();
        JsonNode task=getApi("owner","/projects/"+project+"/samples/"+samples.get(0).path("sampleId").asText()+"/task?specVersionId="+spec);
        assertEquals("设备",task.at("/task/data/text").asText());assertEquals(1,task.at("/task/data/options/0").asInt());
    }

}
