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
class AssignmentEditorIntegrationTest {
    static final String API="/api/labeling/v1";
    static final FakeLabelStudio LS;
    static {try{LS=new FakeLabelStudio();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        ImportIntegrationTest.properties(p);p.add("labeling.editor.enabled",()->true);p.add("labeling.editor.projects",()->"*");
        p.add("labeling.editor.ticket-secret",()->"test-only-assignment-editor-signing-key-32-plus");
        p.add("labeling.editor.base-url",()->LS.baseUrl());p.add("labeling.editor.username",()->"fixture@example.invalid");p.add("labeling.editor.password",()->"fixture-password");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired ApplicationContext context;
    ImportIntegrationTest f;
    @BeforeEach void fixture(){f=new ImportIntegrationTest();f.mvc=mvc;f.json=json;f.db=db;f.context=context;}
    @AfterEach void clear(){LS.failNextAnnotationRead=false;LS.afterWrite=null;}
    record Claim(String project,String pkg,String assignment,String sample,String otherSample,String secondAssignment,String spec){}
    record Browser(String path,Cookie cookie,String session){}
    JsonNode post(String path,String user,Object input,int status)throws Exception{return f.taskPost(path,user,UUID.randomUUID().toString(),input,status);}
    Claim prepare()throws Exception {
        String project=f.project();String batch=f.uploaded(project,ImportIntegrationTest.zip(Map.of("one.png",ImportIntegrationTest.image("png"),"two.png",ImportIntegrationTest.image("png"))));
        f.command(batch,"complete",UUID.randomUUID().toString()).andExpect(status().isAccepted());f.processQueue();
        var spec=post("/projects/"+project+"/spec-versions","owner",Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","1","labels",List.of(Map.of("id","normal","name","正常"),Map.of("id","broken","name","破损"))),201);
        String specId=spec.path("specVersionId").asText();
        var plan=post("/projects/"+project+"/package-plans","owner",Map.of("batchId",batch,"specVersionId",specId,"packageSize",1,"annotatorsPerSample",2),201);
        var published=f.publishTask(plan);String pkg=published.path("packages").get(0).path("packageId").asText();
        String assignment=post("/packages/"+pkg+"/claims","worker",Map.of(),201).path("assignmentId").asText();
        String second=post("/packages/"+pkg+"/claims","worker2",Map.of(),201).path("assignmentId").asText();
        String sample=db.queryForObject("SELECT sample_id::text FROM package_item WHERE package_id=?",String.class,UUID.fromString(pkg));
        String other=db.queryForObject("SELECT id::text FROM sample WHERE project_id=? AND id<>?",String.class,UUID.fromString(project),UUID.fromString(sample));
        return new Claim(project,pkg,assignment,sample,other,second,specId);
    }
    JsonNode open(Claim c,String assignment,String user)throws Exception{return post("/assignments/"+assignment+"/editor-sessions",user,Map.of("sampleId",c.sample),200);}
    Browser launch(JsonNode session)throws Exception {
        var response=mvc.perform(get(session.path("editorUrl").asText())).andExpect(status().isSeeOther()).andReturn().getResponse();
        String cookie=response.getHeader("Set-Cookie").split(";",2)[0].substring("editor_session=".length());
        String id=session.path("editorSessionId").asText();return new Browser(API+"/editor-ui/"+id,new Cookie("editor_session",cookie),id);
    }
    JsonNode lsResult(String category){return json.valueToTree(List.of(Map.of("from_name","category","to_name","image","type","choices","value",Map.of("choices",List.of(category)))));}
    JsonNode save(Browser b,int base,String category,int status)throws Exception {
        return json.readTree(mvc.perform(put(b.path+"/result").cookie(b.cookie).contentType("application/json").content(json.writeValueAsString(Map.of("baseRevision",base,"result",lsResult(category)))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode draft(Claim c)throws Exception{return json.readTree(mvc.perform(get(API+"/assignments/"+c.assignment+"/drafts/"+c.sample).header("Authorization","Bearer worker")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    @Test void savesStandardBusinessDraftAndUpdatesWithoutCreatingClaims()throws Exception {
        Claim c=prepare();int count=db.queryForObject("SELECT count(*) FROM assignment",Integer.class);var session=open(c,c.assignment,"worker");
        assertEquals("ASSIGNMENT_IMAGE_CLASSIFICATION",session.path("mode").asText());assertEquals(c.spec,session.path("specVersionId").asText());
        Browser b=launch(session);var saved=save(b,0,"正常",200);
        assertEquals("image-classification-v1",saved.path("resultFormat").asText());assertEquals("normal",saved.path("result").path("labelId").asText());assertEquals(1,saved.path("revision").asInt());
        assertEquals(1,save(b,0,"正常",200).path("revision").asInt());save(b,0,"破损",409);save(b,1,"非法类别",422);
        assertEquals(2,save(b,1,"破损",200).path("revision").asInt());assertEquals("broken",draft(c).path("result").path("labelId").asText());
        assertEquals("IN_PROGRESS",db.queryForObject("SELECT status FROM assignment WHERE id=?",String.class,UUID.fromString(c.assignment)));
        mvc.perform(get(b.path+"/context").cookie(b.cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.editorResult[0].value.choices[0]").value("破损"));
        assertEquals(count,db.queryForObject("SELECT count(*) FROM assignment",Integer.class));
    }
    @Test void twoWorkersKeepIndependentAnswersAndSubmissions()throws Exception {
        Claim c=prepare();Browser first=launch(open(c,c.assignment,"worker")),second=launch(open(c,c.secondAssignment,"worker2"));
        save(first,0,"正常",200);save(second,0,"破损",200);
        assertEquals(2,db.queryForObject("SELECT count(*) FROM annotation_draft WHERE sample_id=?",Integer.class,UUID.fromString(c.sample)));
        var submitted=post("/assignments/"+c.assignment+"/submissions","worker",Map.of("items",List.of(Map.of("sampleId",c.sample,"revision",1))),201);
        save(first,1,"破损",409);post("/assignments/"+c.assignment+"/editor-sessions","worker",Map.of("sampleId",c.sample),409);
        save(second,1,"正常",200);
        mvc.perform(get(API+"/submissions/"+submitted.path("submissionId").asText()).header("Authorization","Bearer worker2")).andExpect(status().isNotFound());
        mvc.perform(get(API+"/submissions/"+submitted.path("submissionId").asText()).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].result.labelId").value("normal"));
    }
    @Test void deniesOthersOwnerWithoutClaimOutOfPackageAndAnonymous()throws Exception {
        Claim c=prepare();post("/assignments/"+c.assignment+"/editor-sessions","owner",Map.of("sampleId",c.sample),404);
        post("/assignments/"+c.assignment+"/editor-sessions","worker2",Map.of("sampleId",c.sample),404);
        post("/assignments/"+c.assignment+"/editor-sessions","worker",Map.of("sampleId",c.otherSample),404);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/assignments/"+c.assignment+"/editor-sessions").header("Idempotency-Key","anonymous").contentType("application/json").content(json.writeValueAsString(Map.of("sampleId",c.sample)))).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API+"/projects/"+c.project+"/editor-sessions").header("Authorization","Bearer owner").header("Idempotency-Key","obsolete").contentType("application/json").content(json.writeValueAsString(Map.of("sampleId",c.sample)))).andExpect(status().isNotFound());
        post("/assignments/"+c.assignment+"/editor-sync","worker2",Map.of("sampleId",c.sample),404);
    }
    @Test void ticketsCookiesExpiryAndIdempotencyRemainBound()throws Exception {
        Claim c=prepare();String key=UUID.randomUUID().toString();String path="/assignments/"+c.assignment+"/editor-sessions";
        var session=f.taskPost(path,"worker",key,Map.of("sampleId",c.sample),200);assertEquals(session,f.taskPost(path,"worker",key,Map.of("sampleId",c.sample),200));
        String stored=db.queryForObject("SELECT response_body::text FROM editor_session WHERE id=?",String.class,UUID.fromString(session.path("editorSessionId").asText()));assertFalse(stored.contains("editorUrl"));assertFalse(stored.contains("ticket="));
        Browser b=launch(session);mvc.perform(get(session.path("editorUrl").asText())).andExpect(status().isUnauthorized());f.taskPost(path,"worker",key,Map.of("sampleId",c.sample),409);
        mvc.perform(get(b.path+"/context")).andExpect(status().isUnauthorized());
        Browser other=launch(open(c,c.secondAssignment,"worker2"));mvc.perform(get(other.path+"/context").cookie(b.cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get(b.path+"/image").cookie(b.cookie)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        db.update("UPDATE editor_session SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",UUID.fromString(b.session));save(b,0,"正常",401);
    }
    @Test void frozenSpecUsedInsteadOfLatestProjectSpec()throws Exception {
        Claim c=prepare();post("/projects/"+c.project+"/spec-versions","owner",Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","2","labels",List.of(Map.of("id","new","name","新标签"))),201);
        var session=open(c,c.assignment,"worker");assertEquals(c.spec,session.path("specVersionId").asText());Browser b=launch(session);
        save(b,0,"新标签",422);save(b,0,"正常",200);
    }
    @Test void pausedDisabledAndRevokedRequestsCannotContinue()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));
        db.update("UPDATE project SET status='PAUSED' WHERE id=?",UUID.fromString(c.project));save(b,0,"正常",409);
        mvc.perform(get(b.path+"/image").cookie(b.cookie)).andExpect(status().isConflict());db.update("UPDATE project SET status='OPEN' WHERE id=?",UUID.fromString(c.project));
        UUID actor=db.queryForObject("SELECT actor_id FROM assignment WHERE id=?",UUID.class,UUID.fromString(c.assignment));
        db.update("UPDATE actor SET status='DISABLED' WHERE id=?",actor);save(b,0,"正常",403);db.update("UPDATE actor SET status='ENABLED' WHERE id=?",actor);
        db.update("UPDATE assignment SET status='REVOKED',ended_at=CURRENT_TIMESTAMP,release_reason='test' WHERE id=?",UUID.fromString(c.assignment));save(b,0,"正常",409);
    }
    @Test void uncertainExternalSaveCanReadBackWithoutRepeatingWrites()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));int before=LS.annotationCount();LS.failNextAnnotationRead=true;
        save(b,0,"正常",503);assertEquals(0,draft(c).path("revision").asInt());assertEquals(before+1,LS.annotationCount());
        save(b,0,"正常",409);var synced=post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),200);
        assertEquals(1,synced.path("revision").asInt());assertEquals(before+1,LS.annotationCount());assertEquals(1,save(b,0,"正常",200).path("revision").asInt());
        String key=UUID.randomUUID().toString(),path="/assignments/"+c.assignment+"/editor-sync";
        var first=f.taskPost(path,"worker",key,Map.of("sampleId",c.sample),200);
        assertEquals(first,f.taskPost(path,"worker",key,Map.of("sampleId",c.sample),200));
    }
    @Test void lateSyncNeverOverwritesNewerBusinessDraft()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));
        LS.afterWrite=()->{try {
            mvc.perform(put(API+"/assignments/"+c.assignment+"/drafts/"+c.sample).header("Authorization","Bearer worker").contentType("application/json").content("{\"baseRevision\":0,\"explicitDone\":true,\"resultFormat\":\"image-classification-v1\",\"result\":{\"labelId\":\"broken\"}}")).andExpect(status().isOk());
        }catch(Exception e){throw new RuntimeException(e);}};
        save(b,0,"正常",409);assertEquals("broken",draft(c).path("result").path("labelId").asText());
        post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),409);
    }
    @Test void wrongExternalAuthorIsRejected()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));save(b,0,"正常",200);
        String id=db.queryForObject("SELECT external_annotation_id FROM editor_mapping WHERE assignment_id=?",String.class,UUID.fromString(c.assignment));
        LS.setAuthor(id,666);post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),409);
        save(b,1,"破损",409);assertEquals("normal",draft(c).path("result").path("labelId").asText());
    }
    @Test void pauseDuringExternalSaveBlocksFinalWriteUntilAuthorizedSync()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));
        LS.afterWrite=()->db.update("UPDATE project SET status='PAUSED' WHERE id=?",UUID.fromString(c.project));
        save(b,0,"正常",409);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM annotation_draft WHERE assignment_id=?",Integer.class,UUID.fromString(c.assignment)));
        post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),409);
        db.update("UPDATE project SET status='OPEN' WHERE id=?",UUID.fromString(c.project));
        assertEquals(1,post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),200).path("revision").asInt());
    }
    @Test void changedExternalContentCannotBeImportedBySync()throws Exception {
        Claim c=prepare();Browser b=launch(open(c,c.assignment,"worker"));LS.failNextAnnotationRead=true;save(b,0,"正常",503);
        String task=db.queryForObject("SELECT external_task_id FROM editor_mapping WHERE assignment_id=?",String.class,UUID.fromString(c.assignment));
        LS.tamperTask(task,lsResult("破损"));
        post("/assignments/"+c.assignment+"/editor-sync","worker",Map.of("sampleId",c.sample),409);
        assertEquals(0,draft(c).path("revision").asInt());
    }
}
