package com.tss.labeling.imports;

import com.fasterxml.jackson.databind.*;
import com.tss.labeling.editor.FakeLabelStudio;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProjectEditorIntegrationTest {
    static final String API="/api/labeling/v1";
    static final FakeLabelStudio LS;
    static {try{LS=new FakeLabelStudio();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        ImportIntegrationTest.properties(p);p.add("labeling.project-editor.enabled",()->true);p.add("labeling.editor.projects",()->"*");
        p.add("labeling.project-editor.ticket-secret",()->"test-only-project-editor-signing-key-32-plus");
        p.add("labeling.editor.base-url",()->LS.baseUrl());p.add("labeling.editor.username",()->"fixture@example.invalid");p.add("labeling.editor.password",()->"fixture-password");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ApplicationContext context;
    ImportIntegrationTest f;
    @BeforeEach void fixture(){f=new ImportIntegrationTest();f.mvc=mvc;f.json=json;f.db=db;f.context=context;}
    record Project(String project,String sample,String spec){}
    JsonNode post(String path,String who,Object body,int code)throws Exception{return f.taskPost(path,who,UUID.randomUUID().toString(),body,code);}
    Project prepare()throws Exception {
        String project=f.project();String batch=f.uploaded(project,ImportIntegrationTest.zip(Map.of("one.png",ImportIntegrationTest.image("png"),"two.png",ImportIntegrationTest.image("png"))));
        f.command(batch,"complete",UUID.randomUUID().toString()).andExpect(status().isAccepted());f.processQueue();
        String spec=spec(project);String sample=db.queryForObject("SELECT id::text FROM sample WHERE project_id=? ORDER BY id LIMIT 1",String.class,UUID.fromString(project));
        return new Project(project,sample,spec);
    }
    String spec(String project)throws Exception{return post("/projects/"+project+"/spec-versions","owner",Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","1","labels",List.of(Map.of("id","normal","name","正常"),Map.of("id","broken","name","破损"))),201).path("specVersionId").asText();}
    JsonNode open(Project p)throws Exception{return post("/projects/"+p.project+"/editor-sessions","owner",Map.of("sampleId",p.sample),200);}
    JsonNode result(String name){return json.valueToTree(List.of(Map.of("from_name","category","to_name","image","type","choices","value",Map.of("choices",List.of(name)))));}
    JsonNode save(Project p,String who,String session,int base,String category,int status)throws Exception {
        return json.readTree(mvc.perform(put(API+"/projects/"+p.project+"/samples/"+p.sample+"/annotation").header("Authorization","Bearer "+who).contentType("application/json").content(json.writeValueAsString(Map.of("editorSessionId",session,"baseRevision",base,"result",result(category)))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void existingDraftProjectSavesWithoutAnyPackageOrClaim()throws Exception {
        Project p=prepare();int packages=db.queryForObject("SELECT count(*) FROM task_package",Integer.class),claims=db.queryForObject("SELECT count(*) FROM assignment",Integer.class);
        assertEquals("DRAFT",db.queryForObject("SELECT status FROM project WHERE id=?",String.class,UUID.fromString(p.project)));
        var session=open(p);assertEquals("PROJECT_IMAGE_CLASSIFICATION",session.path("mode").asText());assertFalse(session.has("assignmentId"));
        assertTrue(session.path("config").asText().contains("正常"));assertEquals(0,session.path("draft").path("revision").asInt());
        String id=session.path("editorSessionId").asText();
        var saved=save(p,"owner",id,0,"正常",200);assertEquals(1,saved.path("revision").asInt());assertEquals(session.path("actorId"),saved.path("actorId"));assertEquals(p.spec,saved.path("specVersionId").asText());
        assertEquals(1,save(p,"owner",id,0,"正常",200).path("revision").asInt());
        save(p,"owner",id,0,"破损",409);save(p,"owner",id,1,"无效标签",422);
        assertEquals(2,save(p,"owner",id,1,"破损",200).path("revision").asInt());
        assertEquals("破损",open(p).path("draft").path("result").get(0).path("value").path("choices").get(0).asText());
        assertEquals(packages,db.queryForObject("SELECT count(*) FROM task_package",Integer.class));assertEquals(claims,db.queryForObject("SELECT count(*) FROM assignment",Integer.class));
    }
    @Test void deniesOtherUsersAndCrossProjectSamplesAndAnonymous()throws Exception {
        Project a=prepare(),b=prepare();var session=open(a);
        post("/projects/"+a.project+"/editor-sessions","other-user",Map.of("sampleId",a.sample),404);
        post("/projects/"+a.project+"/editor-sessions","owner",Map.of("sampleId",b.sample),404);
        mvc.perform(get(API+"/projects/"+a.project+"/samples/"+a.sample+"/annotation").header("Authorization","Bearer other-user")).andExpect(status().isNotFound());
        save(a,"other-user",session.path("editorSessionId").asText(),0,"正常",404);
        mvc.perform(get(API+"/projects/"+a.project+"/samples/"+a.sample+"/annotation")).andExpect(status().isUnauthorized());
        var bs=open(b);save(a,"owner",bs.path("editorSessionId").asText(),0,"正常",409);
    }
    @Test void launchTicketCookieAndContextAreBoundToProjectAndPicture()throws Exception {
        Project p=prepare();var session=open(p);String id=session.path("editorSessionId").asText(),url=session.path("editorUrl").asText();
        mvc.perform(get(API+"/project-editor-ui/"+id+"/context")).andExpect(status().isUnauthorized());
        var launched=mvc.perform(get(url)).andExpect(status().isSeeOther()).andReturn().getResponse();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        String cookie=launched.getHeader("Set-Cookie").split(";",2)[0].substring("editor_session=".length());
        var c=new Cookie("editor_session",cookie);String path=API+"/project-editor-ui/"+id;
        mvc.perform(get(path+"/context").cookie(c)).andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value(p.project)).andExpect(jsonPath("$.data.assignmentId").doesNotExist());
        mvc.perform(get(path+"/image").cookie(c)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(put(path+"/result").cookie(c).contentType("application/json").content(json.writeValueAsString(Map.of("baseRevision",0,"result",result("正常"))))).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        var other=open(new Project(p.project,db.queryForObject("SELECT id::text FROM sample WHERE project_id=? AND id<>?",String.class,UUID.fromString(p.project),UUID.fromString(p.sample)),p.spec));
        mvc.perform(get(API+"/project-editor-ui/"+other.path("editorSessionId").asText()+"/context").cookie(c)).andExpect(status().isUnauthorized());
        db.update("UPDATE project SET status='PAUSED' WHERE id=?",UUID.fromString(p.project));
        mvc.perform(get(path+"/image").cookie(c)).andExpect(status().isConflict());save(p,"owner",id,1,"破损",409);
    }
    @Test void idempotencySpecVersionsAndExpiryCannotMixResults()throws Exception {
        Project p=prepare();String key=UUID.randomUUID().toString();var a=f.taskPost("/projects/"+p.project+"/editor-sessions","owner",key,Map.of("sampleId",p.sample),200);
        var b=f.taskPost("/projects/"+p.project+"/editor-sessions","owner",key,Map.of("sampleId",p.sample),200);assertEquals(a,b);
        String stored=db.queryForObject("SELECT response_body::text FROM project_editor_session WHERE id=?",String.class,UUID.fromString(a.path("editorSessionId").asText()));
        assertFalse(stored.contains("editorUrl"));assertFalse(stored.contains("ticket="));
        String ticket=a.path("editorUrl").asText().split("ticket=",2)[1];assertFalse(stored.contains(ticket));
        String other=db.queryForObject("SELECT id::text FROM sample WHERE project_id=? AND id<>?",String.class,UUID.fromString(p.project),UUID.fromString(p.sample));
        f.taskPost("/projects/"+p.project+"/editor-sessions","owner",key,Map.of("sampleId",other),409);
        String session=a.path("editorSessionId").asText();save(p,"owner",session,0,"正常",200);
        String newer=spec(p.project);save(p,"owner",session,1,"破损",409);var fresh=open(p);assertEquals(newer,fresh.path("specVersionId").asText());assertEquals(0,fresh.path("draft").path("revision").asInt());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM project_annotation WHERE project_id=? AND spec_version_id=?",Integer.class,UUID.fromString(p.project),UUID.fromString(p.spec)));
        db.update("UPDATE project_editor_session SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",UUID.fromString(fresh.path("editorSessionId").asText()));save(p,"owner",fresh.path("editorSessionId").asText(),0,"正常",409);
    }
    @Test void usedEntryCannotReplayItsTicket()throws Exception {
        Project p=prepare();String key=UUID.randomUUID().toString();
        var a=f.taskPost("/projects/"+p.project+"/editor-sessions","owner",key,Map.of("sampleId",p.sample),200);
        mvc.perform(get(a.path("editorUrl").asText())).andExpect(status().isSeeOther());
        f.taskPost("/projects/"+p.project+"/editor-sessions","owner",key,Map.of("sampleId",p.sample),409);
        var fresh=open(p);assertNotEquals(a.path("editorSessionId"),fresh.path("editorSessionId"));
    }
}
