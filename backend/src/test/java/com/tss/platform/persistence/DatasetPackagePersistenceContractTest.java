package com.tss.platform.persistence;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetPackage;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.DatasetVersionPackage;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetPackageRepository;
import com.tss.platform.repository.DatasetVersionPackageRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetPackagePersistenceContractTest {

    @Test
    void exposesPackageEntitiesRepositoriesAndPackageReferences() {
        assertNotNull(DatasetPackage.class);
        assertNotNull(DatasetVersionPackage.class);
        assertNotNull(DatasetPackageRepository.class);
        assertNotNull(DatasetVersionPackageRepository.class);

        DatasetSample sample = new DatasetSample();
        sample.setCreatedByPackageId("pkg-1");
        assertNotNull(sample.getCreatedByPackageId());

        DatasetSampleData data = new DatasetSampleData();
        data.setPackageId("pkg-1");
        assertNotNull(data.getPackageId());

        DatasetAnnotation annotation = new DatasetAnnotation();
        annotation.setPackageId("pkg-1");
        assertNotNull(annotation.getPackageId());

        ImportJob job = new ImportJob();
        job.setPackageId("pkg-1");
        assertNotNull(job.getPackageId());
    }

    @Test
    void migrationCreatesPackageModelAndKeepsLegacyVersionStorage() throws Exception {
        String resource = "db/migration/V10__dataset_package_model.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains("CREATE TABLE dataset_package"));
            assertTrue(sql.contains("CREATE TABLE dataset_version_package"));
            assertTrue(sql.contains("package_role IN ('PRIMARY', 'APPEND')"));
            assertTrue(sql.contains("created_by_package_id"));
            assertTrue(sql.contains("dataset_sample_data"));
            assertTrue(sql.contains("dataset_annotation"));
            assertTrue(sql.contains("ALTER TABLE import_job"));
            assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS uk_import_job_dataset_version"));
            assertTrue(sql.contains("WHERE package_id IS NOT NULL"));
            assertTrue(sql.contains("WHERE package_id IS NULL"));
            assertTrue(sql.contains("idx_dvp_version"));
            assertTrue(sql.contains("idx_dvp_package"));
            assertFalse(sql.contains("DROP COLUMN storage_path"));
            assertFalse(sql.contains("model_asset"));
        }
    }

    @Test
    void appendMigrationAddsExplicitUploadPurpose() throws Exception {
        DatasetUploadSession session = new DatasetUploadSession();
        assertTrue("INITIAL_DATASET".equals(session.getUploadPurpose()));
        session.setUploadPurpose("APPEND_PACKAGE");
        assertTrue("APPEND_PACKAGE".equals(session.getUploadPurpose()));

        String resource = "db/migration/V11__dataset_append_package_upload.sql";
        try (var input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN upload_purpose"));
            assertTrue(sql.contains("'INITIAL_DATASET'"));
            assertTrue(sql.contains("'APPEND_PACKAGE'"));
            assertFalse(sql.contains("model_asset"));
        }
    }
}
