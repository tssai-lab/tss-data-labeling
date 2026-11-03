package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.v2.V2UserError;
import com.tss.platform.entity.DatasetVersion;
import com.tss.platform.entity.ImportJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class V2ImportJobDisplayHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsUserErrorWithParsedDetailsForFailedOrPartialJobs() {
        ImportJob job = new ImportJob();
        job.setStatus("FAILED");
        job.setErrorCode("DUPLICATE_SAMPLE");
        job.setErrorMessage("上传内容包含已存在的样本");
        job.setErrorDetailsJson("{\"sampleName\":\"scene-1\"}");

        V2UserError error = V2ImportJobDisplayHelper.userError(job, objectMapper);

        assertEquals("DUPLICATE_SAMPLE", error.getErrorCode());
        assertEquals("上传内容包含已存在的样本", error.getErrorMessage());
        assertEquals("scene-1", error.getDetails().get("sampleName"));
    }

    @Test
    void usesStableDefaultMessagesForPartialAndFailedJobs() {
        ImportJob partial = new ImportJob();
        partial.setStatus("PARTIAL");
        ImportJob failed = new ImportJob();
        failed.setStatus("FAILED");

        assertEquals(
                "PARTIAL_IMPORT_FAILED",
                V2ImportJobDisplayHelper.userError(partial, objectMapper).getErrorCode()
        );
        assertEquals(
                "IMPORT_FAILED",
                V2ImportJobDisplayHelper.userError(failed, objectMapper).getErrorCode()
        );
        assertNull(V2ImportJobDisplayHelper.userError(null, objectMapper));
    }

    @Test
    void exposesSharedPublishTerminalAndDisplayVersionRules() {
        DatasetVersion version = new DatasetVersion();
        version.setVersion("v1");
        assertEquals("v1", V2ImportJobDisplayHelper.displayVersion(version));

        version.setVersionLabel("release");
        assertEquals("release", V2ImportJobDisplayHelper.displayVersion(version));
        assertEquals(true, V2ImportJobDisplayHelper.isPublishTerminalJobStatus("SUCCESS"));
        assertEquals(true, V2ImportJobDisplayHelper.isPublishTerminalJobStatus("SUPERSEDED"));
        assertEquals(false, V2ImportJobDisplayHelper.isPublishTerminalJobStatus("FAILED"));
    }

    @Test
    void resolvesCatalogDisplayStatusFromSharedImportAndDraftRules() {
        DatasetVersion ready = version("READY");
        DatasetVersion draft = version("DRAFT");

        assertEquals(
                "IMPORT_PARTIAL",
                V2ImportJobDisplayHelper.catalogDisplayStatus(ready, draft, job("PARTIAL"))
        );
        assertEquals(
                "IMPORT_FAILED",
                V2ImportJobDisplayHelper.catalogDisplayStatus(ready, draft, job("FAILED"))
        );
        assertEquals(
                "IMPORTING",
                V2ImportJobDisplayHelper.catalogDisplayStatus(ready, draft, job("RUNNING"))
        );
        assertEquals("EDITING", V2ImportJobDisplayHelper.catalogDisplayStatus(ready, draft, null));
        assertEquals("READY", V2ImportJobDisplayHelper.catalogDisplayStatus(ready, null, null));
        assertEquals("EMPTY", V2ImportJobDisplayHelper.catalogDisplayStatus(null, null, null));
    }

    @Test
    void resolvesWorkspaceDisplayStatusFromSharedImportRules() {
        assertEquals(
                "IMPORT_PARTIAL",
                V2ImportJobDisplayHelper.editSessionDisplayStatus(job("PARTIAL"))
        );
        assertEquals(
                "IMPORT_FAILED",
                V2ImportJobDisplayHelper.editSessionDisplayStatus(job("FAILED"))
        );
        assertEquals(
                "IMPORTING",
                V2ImportJobDisplayHelper.editSessionDisplayStatus(job("RUNNING"))
        );
        assertEquals("EDITING", V2ImportJobDisplayHelper.editSessionDisplayStatus(null));
    }

    private static ImportJob job(String status) {
        ImportJob job = new ImportJob();
        job.setStatus(status);
        return job;
    }

    private static DatasetVersion version(String status) {
        DatasetVersion version = new DatasetVersion();
        version.setStatus(status);
        return version;
    }
}
