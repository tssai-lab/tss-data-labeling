package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.editor.FakeLabelStudio;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AssignmentEditorIntegrationTest {
    static final String API="/api/labeling/v1";
    static final FakeLabelStudio LS;
    static {try{LS=new FakeLabelStudio();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        ImportIntegrationTest.properties(p);
        p.add("labeling.assignment-editor.enabled",()->true);p.add("labeling.editor.projects",()->"*");
        p.add("labeling.editor.base-url",()->System.getenv().getOrDefault("EDITOR_REAL_URL",LS.baseUrl()));
        p.add("labeling.editor.username",()->System.getenv().getOrDefault("EDITOR_REAL_USERNAME","fixture@example.invalid"));
        p.add("labeling.editor.password",()->System.getenv().getOrDefault("EDITOR_REAL_PASSWORD","fixture-password"));
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ApplicationContext context;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    ImportIntegrationTest f;
    @BeforeEach void fixture(){f=new ImportIntegrationTest();f.mvc=mvc;f.json=json;f.db=db;f.context=context;}
    JsonNode postAs(String path,String who,Object body,int code)throws Exception{return f.taskPost(path,who,UUID.randomUUID().toString(),body,code);}
    record Task(String a,String b,String sample){}
    Task prepare()throws Exception {
        String project=f.project();String real=System.getenv("EDITOR_SAMPLE_FILE");
        byte[] image=real==null?ImportIntegrationTest.image("png"):Files.readAllBytes(Path.of(real));
        String batch=f.uploaded(project,ImportIntegrationTest.zip(Map.of(real==null?"fixture.png":"platform-sample.JPG",image)));
        f.command(batch,"complete",UUID.randomUUID().toString()).andExpect(status().isAccepted());f.processQueue();
        var spec=postAs("/projects/"+project+"/spec-versions","owner",Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","1",
            "labels",List.of(Map.of("id","normal","name","正常"),Map.of("id","broken","name","破损"))),201);
        var plan=postAs("/projects/"+project+"/package-plans","owner",Map.of("batchId",batch,"specVersionId",spec.path("specVersionId").asText(),"packageSize",1,"annotatorsPerSample",2),201);
        var published=postAs("/package-plans/"+plan.path("planId").asText()+"/publish","owner",Map.of("selectionHash",plan.path("selectionHash").asText()),200);
        String pkg=published.path("packages").get(0).path("packageId").asText();
        String a=postAs("/packages/"+pkg+"/claims","annotator-a",Map.of(),201).path("assignmentId").asText();
        String b=postAs("/packages/"+pkg+"/claims","annotator-b",Map.of(),201).path("assignmentId").asText();
        String sample=db.queryForObject("SELECT sample_id::text FROM package_item WHERE package_id=?",String.class,UUID.fromString(pkg));
        return new Task(a,b,sample);
    }
    JsonNode open(String who,String assignment,String sample,int status)throws Exception{return postAs("/assignments/"+assignment+"/editor-sessions",who,Map.of("sampleId",sample),status);}
    JsonNode result(String name){return json.valueToTree(List.of(Map.of("id","client-id","from_name","category","to_name","image","type","choices","value",Map.of("choices",List.of(name)))));}
    JsonNode save(String who,String assignment,String sample,String session,long revision,String name,int code)throws Exception {
        String body=mvc.perform(put(API+"/assignments/"+assignment+"/drafts/"+sample).header("Authorization","Bearer "+who).contentType("application/json")
            .content(json.writeValueAsString(Map.of("editorSessionId",session,"baseRevision",revision,"explicitDone",true,"resultFormat","label-studio-json","result",result(name)))))
            .andExpect(status().is(code)).andReturn().getResponse().getContentAsString();return json.readTree(body).path("data");
    }
    @Test void savesRealMappingRejectsCrossUserAndHandlesReplayAndRevision()throws Exception {
        Task t=prepare();var a=open("annotator-a",t.a,t.sample,200);var b=open("annotator-b",t.b,t.sample,200);
        assertNotEquals(a.path("actorId"),b.path("actorId"));assertTrue(a.path("config").asText().contains("破损"));
        String sa=a.path("editorSessionId").asText(),sb=b.path("editorSessionId").asText();
        open("annotator-b",t.a,t.sample,404);save("annotator-b",t.a,t.sample,sa,0,"正常",404);
        save("annotator-b",t.b,t.sample,sa,0,"正常",409);save("annotator-a",t.a,t.sample,sa,0,"无效标签",422);
        assertEquals(1,save("annotator-a",t.a,t.sample,sa,0,"正常",200).path("revision").asInt());
        assertEquals(1,save("annotator-a",t.a,t.sample,sa,0,"正常",200).path("revision").asInt());
        save("annotator-a",t.a,t.sample,sa,0,"破损",409);save("annotator-b",t.b,t.sample,sb,0,"破损",200);
        mvc.perform(get(API+"/assignments/"+t.a+"/drafts/"+t.sample).header("Authorization","Bearer annotator-b")).andExpect(status().isNotFound());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM annotation_draft WHERE sample_id=?",Integer.class,UUID.fromString(t.sample)));
        var reopened=open("annotator-a",t.a,t.sample,200);assertEquals(1,reopened.path("draft").path("revision").asInt());
        postAs("/assignments/"+t.a+"/editor-sync","annotator-a",Map.of("sampleId",t.sample),200);
        db.update("UPDATE assignment SET status='REVOKED',ended_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(t.a));
        save("annotator-a",t.a,t.sample,sa,1,"破损",409);
    }
    @Test void launchIsOneTimeCookieBoundAndCannotReadOtherSession()throws Exception {
        Task t=prepare();var a=open("annotator-a",t.a,t.sample,200);var b=open("annotator-b",t.b,t.sample,200);
        String url=a.path("editorUrl").asText();var launch=mvc.perform(get(url)).andExpect(status().isSeeOther()).andReturn().getResponse();
        String cookie=launch.getHeader("Set-Cookie").split(";",2)[0].substring("editor_session=".length());
        var auth=new Cookie("editor_session",cookie);String base=API+"/editor-ui/"+a.path("editorSessionId").asText();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/context")).andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/context").cookie(auth)).andExpect(status().isOk());
        mvc.perform(get(base+"/image").cookie(auth)).andExpect(status().isOk());
        mvc.perform(get(API+"/editor-ui/"+b.path("editorSessionId").asText()+"/context").cookie(auth)).andExpect(status().isUnauthorized());
        db.update("UPDATE assignment SET status='REVOKED',ended_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(t.a));
        mvc.perform(get(base+"/image").cookie(auth)).andExpect(status().isConflict());
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="EDITOR_BROWSER_DRIVER",matches=".+")
    void browserClassifiesAndRestoresBothActors()throws Exception {
        Task t=prepare();var a=open("annotator-a",t.a,t.sample,200);var b=open("annotator-b",t.b,t.sample,200);
        Process driver=new ProcessBuilder("node",System.getenv("EDITOR_BROWSER_DRIVER"),"http://127.0.0.1:"+port,
            a.path("editorUrl").asText(),b.path("editorUrl").asText()).inheritIO().start();
        assertTrue(driver.waitFor(180,java.util.concurrent.TimeUnit.SECONDS),"Browser test timeout");assertEquals(0,driver.exitValue());
        for(String assignment:List.of(t.a,t.b))assertEquals(1,db.queryForObject("SELECT revision::int FROM annotation_draft WHERE assignment_id=? AND sample_id=?",Integer.class,UUID.fromString(assignment),UUID.fromString(t.sample)));
    }
    @Test void sessionReplaysAndExpiredSessionCannotWrite()throws Exception {
        Task t=prepare();String key=UUID.randomUUID().toString();
        var a=f.taskPost("/assignments/"+t.a+"/editor-sessions","annotator-a",key,Map.of("sampleId",t.sample),200);
        var replay=f.taskPost("/assignments/"+t.a+"/editor-sessions","annotator-a",key,Map.of("sampleId",t.sample),200);
        assertEquals(a,replay);
        String sid=a.path("editorSessionId").asText();
        db.update("UPDATE editor_session SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",UUID.fromString(sid));
        save("annotator-a",t.a,t.sample,sid,0,"正常",409);
        mvc.perform(get(a.path("editorUrl").asText())).andExpect(status().isUnauthorized());
    }
    @Test void pausedProjectRejectsExistingBrowserSessionAndSave()throws Exception {
        Task t=prepare();var a=open("annotator-a",t.a,t.sample,200);
        UUID project=db.queryForObject("SELECT plan.project_id FROM assignment a JOIN task_package p ON p.id=a.package_id JOIN package_plan plan ON plan.id=p.plan_id WHERE a.id=?",UUID.class,UUID.fromString(t.a));
        db.update("UPDATE project SET status='PAUSED' WHERE id=?",project);
        save("annotator-a",t.a,t.sample,a.path("editorSessionId").asText(),0,"正常",409);
        mvc.perform(get(a.path("editorUrl").asText())).andExpect(status().isConflict());
    }
}
