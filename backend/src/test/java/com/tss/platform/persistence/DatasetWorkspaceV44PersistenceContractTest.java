package com.tss.platform.persistence;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.DatasetVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetWorkspaceV44PersistenceContractTest {

    @Test
    void migrationAddsRevisionRawOverlaySoftDeleteAndWorkspaceUploadContracts()
            throws Exception {
        String resource = "db/migration/V44__dataset_version_workspace.sql";
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains("ADD COLUMN workspace_revision"));
            assertTrue(sql.contains("'ABANDONED'"));
            assertTrue(sql.contains("ADD COLUMN storage_kind"));
            assertTrue(sql.contains("'ZIP', 'RAW'"));
            assertTrue(sql.contains("'PRIMARY', 'APPEND', 'OVERLAY'"));
            assertTrue(sql.contains("uk_sd_sample_dt_sc_seq_active"));
            assertTrue(sql.contains("WHERE deleted = FALSE"));
            assertTrue(sql.contains("ALTER TABLE dataset_sample_data"));
            assertTrue(sql.contains("ADD COLUMN updated_at"));
            assertTrue(sql.contains("ALTER TABLE dataset_annotation"));
            assertTrue(sql.contains("ADD COLUMN workspace_base_revision"));
            assertTrue(sql.contains("'WORKSPACE_FILE'"));
            assertTrue(sql.contains("'CREATE', 'REPLACE'"));
        }
    }

    @Test
    void entitiesExposeTheMigratedWorkspaceFields() {
        DatasetVersion version = new DatasetVersion();
        version.setWorkspaceRevision(3L);
        assertNotNull(version.getWorkspaceRevision());

        DatasetPackage datasetPackage = new DatasetPackage();
        datasetPackage.setStorageKind("RAW");
        assertNotNull(datasetPackage.getStorageKind());

        DatasetSampleData data = new DatasetSampleData();
        data.setDeleted(false);
        data.setUpdatedAt(java.time.Instant.now());
        assertNotNull(data.getUpdatedAt());

        DatasetAnnotation annotation = new DatasetAnnotation();
        annotation.setDeleted(false);
        annotation.setUpdatedAt(java.time.Instant.now());
        assertNotNull(annotation.getUpdatedAt());

        DatasetUploadSession upload = new DatasetUploadSession();
        upload.setWorkspaceBaseRevision(4L);
        upload.setTargetKind("DATA");
        upload.setTargetOperation("REPLACE");
        assertNotNull(upload.getWorkspaceBaseRevision());
        assertNotNull(upload.getTargetKind());
        assertNotNull(upload.getTargetOperation());
    }
}
