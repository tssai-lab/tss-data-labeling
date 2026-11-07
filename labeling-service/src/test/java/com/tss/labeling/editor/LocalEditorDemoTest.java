package com.tss.labeling.editor;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Opt-in local harness. Never packaged in the production JAR and never validates real platform accounts. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(LocalEditorDemoTest.DemoConfiguration.class)
@EnabledIfEnvironmentVariable(named="EDITOR_LOCAL_DEMO",matches="true")
public class LocalEditorDemoTest {
    static final String REAL_IDENTITY=System.getenv("EDITOR_REAL_IDENTITY_URL");
    static final Path ROOT=Path.of(System.getenv().getOrDefault("EDITOR_DEMO_DIR","./target/local-editor-demo")).toAbsolutePath();
    static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,String> TOKENS=new LinkedHashMap<>();
    static final EmbeddedPostgres PG;
    static final HttpServer IDENTITY;
    static volatile Map<String,Object> model=Map.of("ready",false);
    static {
        try {
            Files.createDirectories(ROOT);
            for(String who:List.of("demo-owner","demo-a","demo-b"))TOKENS.put(who,UUID.randomUUID()+"-"+UUID.randomUUID());
            PG=EmbeddedPostgres.builder().setDataDirectory(ROOT.resolve("postgres")).setCleanDataDirectory(false).start();
            try(var c=PG.getPostgresDatabase().getConnection();var s=c.createStatement()) {
                s.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='tss_labeling_app') THEN CREATE ROLE tss_labeling_app LOGIN; END IF; END $$");
            }
            IDENTITY=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            IDENTITY.createContext("/api/user/current-user",exchange->{
                String auth=exchange.getRequestHeaders().getFirst("Authorization");
                String who=TOKENS.entrySet().stream().filter(e->("Bearer "+e.getValue()).equals(auth)).map(Map.Entry::getKey).findFirst().orElse(null);
                byte[] bytes=JSON.writeValueAsBytes(who==null?Map.of("code",401):Map.of("code",200,"data",Map.of("id",who,"username",who)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(who==null?401:200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            });IDENTITY.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->8088);p.add("server.address",()->"127.0.0.1");
        p.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+PG.getPort()+"/postgres?currentSchema=labeling");
        p.add("spring.datasource.username",()->"tss_labeling_app");p.add("spring.datasource.password",()->"");
        p.add("spring.flyway.user",()->"postgres");p.add("spring.flyway.password",()->"");
        p.add("labeling.identity.base-url",()->REAL_IDENTITY==null?"http://127.0.0.1:"+IDENTITY.getAddress().getPort():REAL_IDENTITY);
        p.add("labeling.imports.storage-root",()->ROOT.resolve("images").toString());
        p.add("labeling.imports.worker-enabled",()->true);
        p.add("labeling.assignment-editor.enabled",()->true);p.add("labeling.editor.projects",()->"*");
        p.add("labeling.project-editor.enabled",()->true);
        p.add("labeling.editor.base-url",()->"http://127.0.0.1:8085");
        p.add("labeling.editor.username",()->System.getenv("EDITOR_DEMO_LS_USERNAME"));
        p.add("labeling.editor.password",()->System.getenv("EDITOR_DEMO_LS_PASSWORD"));
    }
    static JsonNode api(String who,String method,String path,byte[] body,String type,Map<String,String> headers)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8088/api/labeling/v1"+path)).timeout(Duration.ofSeconds(120))
            .header("Authorization","Bearer "+TOKENS.get(who));
        if(type!=null)request.header("Content-Type",type);headers.forEach(request::header);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("Local demo "+method+" "+path+": "+response.statusCode());
        return JSON.readTree(response.body()).path("data");
    }
    static JsonNode post(String who,String path,Object body)throws Exception {
        return api(who,"POST",path,body==null?null:JSON.writeValueAsBytes(body),body==null?null:"application/json",Map.of("Idempotency-Key",UUID.randomUUID().toString()));
    }
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static Map<String,Object> seed()throws Exception {
        Path state=ROOT.resolve("seed.json");
        if(Files.exists(state))return JSON.readValue(Files.readString(state),Map.class);
        String project=post("demo-owner","/projects",Map.of("name","本地独立图片分类演示","description","仅本机测试，不是服务器项目","taskType","IMAGE_CLASSIFICATION")).path("projectId").asText();
        String spec=post("demo-owner","/projects/"+project+"/spec-versions",Map.of("taskType","IMAGE_CLASSIFICATION","schemaVersion","1",
            "instructions","本地功能验证：请选择正常或破损，分类不代表实际质量判断。","labels",List.of(Map.of("id","normal","name","正常"),Map.of("id","broken","name","破损")))).path("specVersionId").asText();
        byte[] image=Files.readAllBytes(Path.of(System.getenv("EDITOR_DEMO_IMAGE")));var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("local-sample.JPG"));zip.write(image);zip.closeEntry();}
        byte[] archive=out.toByteArray();
        var batch=post("demo-owner","/projects/"+project+"/imports",Map.of("filename","local-sample.zip","sizeBytes",archive.length,"sha256",hash(archive)));
        String batchId=batch.path("batchId").asText();int size=batch.path("chunkSizeBytes").asInt();
        for(int offset=0;offset<archive.length;offset+=size){byte[] chunk=Arrays.copyOfRange(archive,offset,Math.min(archive.length,offset+size));
            api("demo-owner","PUT","/imports/"+batchId+"/chunks?offset="+offset,chunk,"application/octet-stream",Map.of("X-Chunk-SHA256",hash(chunk)));}
        post("demo-owner","/imports/"+batchId+"/complete",null);
        long until=System.currentTimeMillis()+60000;JsonNode status;
        do{status=api("demo-owner","GET","/imports/"+batchId,null,null,Map.of());
            if("FAILED".equals(status.path("status").asText()))throw new IllegalStateException("Local image import failed");
            if("READY".equals(status.path("status").asText()))break;Thread.sleep(300);
        }while(System.currentTimeMillis()<until);
        if(!"READY".equals(status.path("status").asText()))throw new IllegalStateException("Local image import timeout");
        String sample=api("demo-owner","GET","/imports/"+batchId+"/samples",null,null,Map.of()).path("items").get(0).path("sampleId").asText();
        var plan=post("demo-owner","/projects/"+project+"/package-plans",Map.of("batchId",batchId,"specVersionId",spec,"packageSize",1,"annotatorsPerSample",2));
        var published=post("demo-owner","/package-plans/"+plan.path("planId").asText()+"/publish",Map.of("selectionHash",plan.path("selectionHash").asText()));
        String pkg=published.path("packages").get(0).path("packageId").asText();
        String a=post("demo-a","/packages/"+pkg+"/claims",Map.of()).path("assignmentId").asText();
        String b=post("demo-b","/packages/"+pkg+"/claims",Map.of()).path("assignmentId").asText();
        var result=Map.<String,Object>of("projectId",project,"sampleId",sample,"assignmentA",a,"assignmentB",b);
        Files.writeString(state,JSON.writeValueAsString(result));return result;
    }
    @Test void runLocalDemoUntilStopped()throws Exception {
        if(REAL_IDENTITY==null){
            Map<String,Object> data=new LinkedHashMap<>(seed());data.put("tokens",TOKENS);data.put("ready",true);model=data;
        }
        Files.writeString(ROOT.resolve("ready.json"),JSON.writeValueAsString(Map.of("ready",true,"realIdentity",REAL_IDENTITY!=null)));
        System.out.println(REAL_IDENTITY==null?"LOCAL DEMO READY: http://127.0.0.1:8088/local-demo (local test identities only)":"LOCAL PLATFORM READY: real identity, isolated local business database");
        try{while(!Files.exists(ROOT.resolve("stop.request")))Thread.sleep(500);}
        finally{model=Map.of("ready",false);IDENTITY.stop(0);PG.close();Files.deleteIfExists(ROOT.resolve("ready.json"));}
    }
    @TestConfiguration static class DemoConfiguration {
        @Bean LocalDemoController localDemoController(){return new LocalDemoController();}
    }
    @RestController static class LocalDemoController {
        @GetMapping(value="/local-demo",produces="text/html;charset=UTF-8")
        String page()throws Exception{checkDemo();return new String(new ClassPathResource("editor-demo/index.html").getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
        @GetMapping("/local-demo/model") Map<String,Object> model(){checkDemo();return model;}
        void checkDemo(){if(REAL_IDENTITY!=null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);}
    }
}
