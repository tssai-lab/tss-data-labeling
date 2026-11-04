package com.tss.platform.persistence;

import com.tss.platform.entity.DatasetAnnotation;
import com.tss.platform.entity.DatasetSample;
import com.tss.platform.entity.DatasetSampleData;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetAnnotationRepository;
import com.tss.platform.repository.DatasetSampleDataRepository;
import com.tss.platform.repository.DatasetSampleRepository;
import com.tss.platform.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultimodalPersistenceContractTest {

    @Test
    void exposesMultimodalEntitiesAndRepositories() {
        assertNotNull(DatasetSample.class);
        assertNotNull(DatasetSampleData.class);
        assertNotNull(DatasetAnnotation.class);
        assertNotNull(ImportJob.class);
        assertNotNull(DatasetSampleRepository.class);
        assertNotNull(DatasetSampleDataRepository.class);
        assertNotNull(DatasetAnnotationRepository.class);
        assertNotNull(ImportJobRepository.class);
    }

    @Test
    void migrationCreatesPostgresqlMultimodalSkeletonWithoutChangingModelAsset() throws Exception {
        String resource = "db/migration/V8__multimodal_dataset_skeleton.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains("CREATE TABLE dataset_sample"));
            assertTrue(sql.contains("CREATE TABLE dataset_sample_data"));
            assertTrue(sql.contains("CREATE TABLE dataset_annotation"));
            assertTrue(sql.contains("CREATE TABLE import_job"));
            assertTrue(sql.contains("COALESCE(sensor, '')"));
            assertTrue(sql.contains("COALESCE(channel, '')"));
            assertTrue(sql.contains("'IMAGE', 'TEXT', 'POINT_CLOUD', 'AUDIO', 'VIDEO', 'OTHER'"));
            assertTrue(sql.contains("'PENDING', 'RUNNING', 'SUCCESS', 'FAILED'"));
            assertTrue(sql.contains("sample_grouping"));
            assertTrue(sql.contains("manifest_path"));
            assertTrue(sql.contains("import_job_id"));
            assertTrue(sql.contains("asset_created_by_upload"));
            assertTrue(sql.contains("heartbeat_at"));
            assertTrue(sql.contains("executor_id"));
            assertFalse(sql.contains("model_asset"));
        }
    }

    @Test
    void autoDirectoryMigrationExtendsOnlyDatasetUploadGrouping() throws Exception {
        String resource = "db/migration/V12__multimodal_auto_directory_grouping.sql";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains(
                    "DROP CONSTRAINT IF EXISTS ck_dataset_upload_session_sample_grouping"
            ));
            assertTrue(sql.contains("'MANIFEST'"));
            assertTrue(sql.contains("'AUTO_DIRECTORY'"));
            assertFalse(sql.contains("model_asset"));
        }
    }
}
