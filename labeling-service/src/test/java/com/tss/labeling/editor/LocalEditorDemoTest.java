package com.tss.labeling.editor;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;

/** Opt-in real-identity local runner; never packaged in the application JAR. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnabledIfEnvironmentVariable(named="EDITOR_LOCAL_DEMO",matches="true")
public class LocalEditorDemoTest {
    static final String REAL_IDENTITY=System.getenv("EDITOR_REAL_IDENTITY_URL");
    static final Path ROOT=Path.of(System.getenv().getOrDefault("EDITOR_DEMO_DIR","./target/local-editor-demo")).toAbsolutePath();
    static final EmbeddedPostgres PG;
    static {
        if(REAL_IDENTITY==null)throw new IllegalStateException("Set EDITOR_REAL_IDENTITY_URL for the explicit local runner");
        try {
            Files.createDirectories(ROOT);
            PG=EmbeddedPostgres.builder().setDataDirectory(ROOT.resolve("postgres")).setCleanDataDirectory(false).start();
            try(var c=PG.getPostgresDatabase().getConnection();var stmt=c.createStatement()) {
                stmt.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='tss_labeling_app') THEN CREATE ROLE tss_labeling_app LOGIN; END IF; END $$");
            }
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->8088);p.add("server.address",()->"127.0.0.1");
        p.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+PG.getPort()+"/postgres?currentSchema=labeling");
        p.add("spring.datasource.username",()->"tss_labeling_app");p.add("spring.datasource.password",()->"");
        p.add("spring.flyway.user",()->"postgres");p.add("spring.flyway.password",()->"");
        p.add("labeling.identity.base-url",()->REAL_IDENTITY);
        p.add("labeling.imports.storage-root",()->ROOT.resolve("images").toString());
        p.add("labeling.imports.worker-enabled",()->true);
        p.add("labeling.editor.projects",()->"*");
        p.add("labeling.project-editor.enabled",()->true);
        p.add("labeling.project-editor.ticket-secret",()->System.getenv("EDITOR_DEMO_LS_PASSWORD"));
        p.add("labeling.editor.base-url",()->"http://127.0.0.1:8085");
        p.add("labeling.editor.username",()->System.getenv("EDITOR_DEMO_LS_USERNAME"));
        p.add("labeling.editor.password",()->System.getenv("EDITOR_DEMO_LS_PASSWORD"));
    }
    @Test void runUntilStopped()throws Exception {
        Files.writeString(ROOT.resolve("ready.json"),"{\"ready\":true,\"realIdentity\":true}");
        System.out.println("LOCAL PLATFORM READY: real identity, isolated local business database");
        try{while(!Files.exists(ROOT.resolve("stop.request")))Thread.sleep(500);}
        finally{PG.close();Files.deleteIfExists(ROOT.resolve("ready.json"));}
    }
}
