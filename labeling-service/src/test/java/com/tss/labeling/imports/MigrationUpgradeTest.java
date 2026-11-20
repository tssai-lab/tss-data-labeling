package com.tss.labeling.imports;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** A dedicated throwaway cluster, never the developer's running database. */
class MigrationUpgradeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path legacyMigrations;
    @Test void upgradesPopulatedV14ToV24WithoutChangingLegacyResults() throws Exception {
        try (var pg = EmbeddedPostgres.builder().start()) {
            var db = new JdbcTemplate(pg.getPostgresDatabase());
            db.execute("CREATE ROLE tss_labeling_app LOGIN");
            var old = Flyway.configure().dataSource(pg.getPostgresDatabase()).defaultSchema("labeling")
                    .schemas("labeling").target("7").load();
            old.migrate();
            UUID actor=UUID.randomUUID(),project=UUID.randomUUID(),spec=UUID.randomUUID(),batch=UUID.randomUUID(),
                    sample=UUID.randomUUID(),plan=UUID.randomUUID(),pkg=UUID.randomUUID(),slot=UUID.randomUUID(),
                    assignment=UUID.randomUUID(),submission=UUID.randomUUID(),snapshot=UUID.randomUUID();
            String hash="a".repeat(64),answer="{\"labelId\":\"normal\"}";
            db.update("INSERT INTO labeling.actor(id,kind,identity_user_id,display_name) VALUES (?,'HUMAN','migration-owner','历史标注人')",actor);
            db.update("INSERT INTO labeling.project(id,owner_actor_id,name,task_type) VALUES (?,?,'历史图像项目','IMAGE_CLASSIFICATION')",project,actor);
            db.update("INSERT INTO labeling.spec_version(id,project_id,version_no,task_type,labels,schema_version) VALUES (?,?,1,'IMAGE_CLASSIFICATION','[{\"id\":\"normal\",\"name\":\"正常\"}]','image-classification-v1')",spec,project);
            db.update("INSERT INTO labeling.import_batch(id,project_id,status,original_name,storage_key,size_bytes,received_bytes,chunk_size_bytes,sha256,original_complete,valid_sample_count) VALUES (?,?,'READY','legacy.zip','legacy',10,10,10,?,true,1)",batch,project,hash);
            db.update("INSERT INTO labeling.sample(id,project_id,batch_id,source_path,storage_key,content_sha256,media_type,metadata) VALUES (?,?,?,'legacy.jpg','legacy.jpg',?,'image/jpeg','{\"width\":10,\"height\":10}')",sample,project,batch,hash);
            db.update("INSERT INTO labeling.package_plan(id,project_id,batch_id,spec_version_id,package_size,annotators_per_sample,sample_count,status,selection_hash,published_at) VALUES (?,?,?,?,1,1,1,'PUBLISHED',?,CURRENT_TIMESTAMP)",plan,project,batch,spec,hash);
            db.update("INSERT INTO labeling.task_package(id,plan_id,sequence_no,sample_count,required_count) VALUES (?,?,1,1,1)",pkg,plan);
            db.update("INSERT INTO labeling.package_item(package_id,plan_id,batch_id,sample_id,ordinal) VALUES (?,?,?,?,1)",pkg,plan,batch,sample);
            db.update("INSERT INTO labeling.package_slot(id,package_id,slot_no) VALUES (?,?,1)",slot,pkg);
            db.update("INSERT INTO labeling.assignment(id,slot_id,package_id,actor_id,status,ended_at) VALUES (?,?,?,?,'SUBMITTED',CURRENT_TIMESTAMP)",assignment,slot,pkg,actor);
            db.update("INSERT INTO labeling.annotation_draft(assignment_id,package_id,sample_id,result_format,result,explicit_done,revision) VALUES (?,?,?,'image-classification-v1',?::jsonb,true,1)",assignment,pkg,sample,answer);
            db.update("INSERT INTO labeling.submission(id,assignment_id,package_id,plan_id,project_id,spec_version_id,submitted_by,content_hash) VALUES (?,?,?,?,?,?,?,?)",submission,assignment,pkg,plan,project,spec,actor,hash);
            db.update("INSERT INTO labeling.submission_item(submission_id,package_id,sample_id,draft_revision,result_format,result) VALUES (?,?,?,1,'image-classification-v1',?::jsonb)",submission,pkg,sample,answer);
            db.update("INSERT INTO labeling.review_item(submission_id,sample_id,action,reviewed_by,reason) VALUES (?,?,'ACCEPT',?,'历史确认')",submission,sample,actor);
            db.update("INSERT INTO labeling.review_audit(id,project_id,submission_id,actor_id,action,sample_id,after_result) VALUES (?,?,?,?,'REVIEW_ITEM',?,?::jsonb)",UUID.randomUUID(),project,submission,actor,sample,answer);
            Flyway.configure().dataSource(pg.getPostgresDatabase()).defaultSchema("labeling").schemas("labeling").target("14").load().migrate();
            db.update("INSERT INTO labeling.final_snapshot(id,project_id,spec_version_id,name,scope_hash,created_by) VALUES (?,?,?,'历史成果',?,?)",snapshot,project,spec,hash,actor);
            db.update("INSERT INTO labeling.final_snapshot_item(snapshot_id,project_id,sample_id,spec_version_id,source_kind,submission_id,result,review_revision,source_path,content_hash) VALUES (?,?,?,?,'HUMAN',?,?::jsonb,1,'legacy.jpg',?)",snapshot,project,sample,spec,submission,answer,hash);
            var before = db.queryForMap("SELECT result::text AS result,result_id FROM labeling.submission_item WHERE submission_id=?",submission);
            String frozen = db.queryForObject("SELECT result::text FROM labeling.final_snapshot_item WHERE snapshot_id=?",String.class,snapshot);

            var current = Flyway.configure().dataSource(pg.getPostgresDatabase()).defaultSchema("labeling").schemas("labeling").load();
            assertEquals(10,current.migrate().migrationsExecuted);
            current.validate();
            assertEquals(0,current.migrate().migrationsExecuted);
            assertEquals("image-classification",db.queryForObject("SELECT template_id FROM labeling.project WHERE id=?",String.class,project));
            assertEquals("1.0.0",db.queryForObject("SELECT template_version FROM labeling.import_batch WHERE id=?",String.class,batch));
            assertEquals(before,db.queryForMap("SELECT result::text AS result,result_id FROM labeling.submission_item WHERE submission_id=?",submission));
            assertEquals(frozen,db.queryForObject("SELECT result::text FROM labeling.final_snapshot_item WHERE snapshot_id=?",String.class,snapshot));
            assertEquals(spec,db.queryForObject("SELECT spec_version_id FROM labeling.final_snapshot WHERE id=?",UUID.class,snapshot));
            assertEquals("image-classification-v1",db.queryForObject("SELECT result_format FROM labeling.annotation_draft WHERE sample_id=?",String.class,sample));
            assertEquals("历史确认",db.queryForObject("SELECT reason FROM labeling.review_item WHERE sample_id=?",String.class,sample));
            assertEquals("[\"image\"]",db.queryForObject("SELECT fields::text FROM labeling.sample_input_index WHERE sample_id=?",String.class,sample));
            assertEquals(1,db.queryForObject("SELECT count(*) FROM labeling.review_audit",Integer.class));
            // Validate with only the SQL files actually present in the old V14 JAR.
            for (var resource : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*.sql")) {
                String name=resource.getFilename();
                if(Integer.parseInt(name.substring(1,name.indexOf("__")))<=14) {
                    try(var input=resource.getInputStream()) { java.nio.file.Files.copy(input,legacyMigrations.resolve(name)); }
                }
            }
            Flyway.configure().dataSource(pg.getPostgresDatabase()).defaultSchema("labeling").schemas("labeling")
                    .locations("filesystem:"+legacyMigrations.toAbsolutePath().toString().replace('\\','/'))
                    .ignoreMigrationPatterns("*:future").load().validate();
            try (var connection=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+pg.getPort()+"/postgres","tss_labeling_app", "")) {
                assertTrue(connection.createStatement().executeQuery("SELECT fields FROM labeling.sample_input_index").next());
                assertThrows(java.sql.SQLException.class,()->connection.createStatement().execute("UPDATE labeling.flyway_schema_history SET description='forbidden'"));
            }
        }
    }
}
