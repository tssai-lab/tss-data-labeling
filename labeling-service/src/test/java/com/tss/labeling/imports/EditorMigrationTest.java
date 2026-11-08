package com.tss.labeling.imports;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EditorMigrationTest {
    @Test void upgradesBusinessV5WithoutReplacingItsTablesOrChecksum()throws Exception {
        String name="editor_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var c=ImportIntegrationTest.PG.getPostgresDatabase().getConnection();var q=c.createStatement()) {
            q.execute("CREATE DATABASE "+name);
        }
        String url="jdbc:postgresql://127.0.0.1:"+ImportIntegrationTest.PG.getPort()+"/"+name;
        var v5=Flyway.configure().dataSource(url,"postgres","").schemas("labeling").defaultSchema("labeling")
                .locations("classpath:db/migration").target("5").load();
        v5.migrate();
        String checksum;
        try(var c=DriverManager.getConnection(url,"postgres","");var q=c.createStatement()) {
            try(var rows=q.executeQuery("SELECT checksum FROM labeling.flyway_schema_history WHERE version='5'")){assertTrue(rows.next());checksum=rows.getString(1);}
            q.execute("INSERT INTO labeling.actor(id,kind,identity_user_id,display_name) VALUES ('"+UUID.randomUUID()+"','HUMAN','upgrade-fixture','Preserved actor')");
        }
        var current=Flyway.configure().dataSource(url,"postgres","").schemas("labeling").defaultSchema("labeling")
                .locations("classpath:db/migration").load();
        assertEquals(1,current.migrate().migrationsExecuted);current.validate();
        try(var c=DriverManager.getConnection(url,"postgres","");var q=c.createStatement()) {
            try(var r=q.executeQuery("SELECT checksum FROM labeling.flyway_schema_history WHERE version='5'")){assertTrue(r.next());assertEquals(checksum,r.getString(1));}
            try(var r=q.executeQuery("SELECT count(*) FROM labeling.actor WHERE identity_user_id='upgrade-fixture'")){r.next();assertEquals(1,r.getInt(1));}
            try(var r=q.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='labeling' AND table_name IN ('editor_mapping','editor_session','annotation_draft','submission','submission_item')")){r.next();assertEquals(5,r.getInt(1));}
            try(var r=q.executeQuery("SELECT to_regclass('labeling.project_annotation') IS NULL")){r.next();assertTrue(r.getBoolean(1));}
            try(var r=q.executeQuery("SELECT has_table_privilege('tss_labeling_app','labeling.editor_mapping','SELECT,INSERT,UPDATE'),has_table_privilege('tss_labeling_app','labeling.editor_mapping','DELETE')")){r.next();assertTrue(r.getBoolean(1));assertFalse(r.getBoolean(2));}
        }
        try(var c=DriverManager.getConnection(url+"?currentSchema=labeling","tss_labeling_app","");var q=c.createStatement()) {
            try(var r=q.executeQuery("SELECT count(*) FROM editor_session")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
}
