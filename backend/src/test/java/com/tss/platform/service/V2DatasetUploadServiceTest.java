package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.dto.v2.V2DatasetUploadDto;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2DatasetUploadServiceTest {

    @Test
    void initFailureIncludesOriginalValidationReasonInDetails() {
        Fixture fixture = new Fixture();
        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setFileName("dataset.zip");
        request.setFileSize(128L);
        when(fixture.uploadService.init(request))
                .thenThrow(new IllegalArgumentException("CV zip must contain at least 1 image file"));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.init(request)
        );

        assertEquals("INVALID_UPLOAD_REQUEST", error.getErrorCode());
        assertEquals(
                "CV zip must contain at least 1 image file",
                error.getDetails().get("reason")
        );
    }

    @Test
    void initialUploadDelegatesToExistingUploadService() {
        Fixture fixture = new Fixture();
        DatasetUploadInitRequest request = new DatasetUploadInitRequest();
        request.setFileName("dataset.zip");
        request.setFileSize(128L);
        DatasetUploadProgressDto progress = fixture.progress();
        when(fixture.uploadService.init(request)).thenReturn(progress);
        fixture.stubSession(fixture.initialSession());

        V2DatasetUploadDto result = fixture.service.init(request);

        verify(fixture.uploadService).init(request);
        assertEquals("upload-1", result.getUploadId());
        assertEquals("asset-1", result.getDatasetId());
        assertEquals("UPLOADING", result.getDisplayStatus());
    }

    @Test
    void initialCompleteUsesExistingInitialCompletePath() {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        progress.setStatus("COMPLETED");
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        fixture.stubSession(fixture.initialSession());

        fixture.service.complete("upload-1");

        verify(fixture.uploadService).complete(any(DatasetUploadCompleteRequest.class));
    }

    @Test
    void appendCompleteUsesVersionStoredInUploadSession() {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        progress.setStatus("COMPLETED");
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        DatasetUploadSession session = fixture.initialSession();
        session.setUploadPurpose("APPEND_PACKAGE");
        session.setVersionId("draft-2");
        fixture.stubSession(session);

        V2DatasetUploadDto result = fixture.service.complete("upload-1");

        verify(fixture.uploadService).completeAppendPackage(
                org.mockito.ArgumentMatchers.eq("draft-2"),
                any(DatasetUploadCompleteRequest.class)
        );
        assertEquals("draft-2", result.getWorkspaceId());
    }

    @Test
    void failedImportReturnsUserErrorAndRetryHandleWithoutStorageIdentifiers() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        progress.setStatus("COMPLETED");
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        DatasetUploadSession session = fixture.initialSession();
        session.setImportJobId("job-secret");
        fixture.stubSession(session);
        ImportJob job = new ImportJob();
        job.setId("job-secret");
        job.setDatasetVersionId("version-secret");
        job.setStatus("FAILED");
        job.setProgress(0);
        job.setErrorCode("INVALID_MANIFEST");
        job.setErrorMessage("Manifest 内容无效，请检查后重试");
        job.setErrorDetailsJson(
                "{\"field\":\"samples[0].data[0].path\","
                        + "\"path\":\"missing.png\","
                        + "\"reason\":\"path not found in zip\"}"
        );
        when(fixture.importJobRepo.findById("job-secret")).thenReturn(Optional.of(job));

        V2DatasetUploadDto result = fixture.service.get("upload-1");

        assertEquals("IMPORT_FAILED", result.getDisplayStatus());
        assertEquals("job-secret", result.getImportJobId());
        assertEquals("INVALID_MANIFEST", result.getUserError().getErrorCode());
        assertEquals(
                "samples[0].data[0].path",
                result.getUserError().getDetails().get("field")
        );
        assertEquals("missing.png", result.getUserError().getDetails().get("path"));
        String json = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(result);
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("ownerUserId"));
        assertFalse(json.contains("version-secret"));
        assertTrue(json.contains("\"importJobId\":\"job-secret\""));
    }

    @Test
    void appendInitUsesExistingAppendInitialization() {
        Fixture fixture = new Fixture();
        DatasetPackageAppendInitRequest request = new DatasetPackageAppendInitRequest();
        request.setFileName("append.zip");
        request.setFileSize(64L);
        DatasetUploadProgressDto progress = fixture.progress();
        when(fixture.uploadService.initAppendPackage("draft-2", request))
                .thenReturn(progress);
        DatasetUploadSession session = fixture.initialSession();
        session.setUploadPurpose("APPEND_PACKAGE");
        session.setVersionId("draft-2");
        fixture.stubSession(session);

        V2DatasetUploadDto result = fixture.service.initAppend("draft-2", request);

        verify(fixture.uploadService).initAppendPackage("draft-2", request);
        assertEquals("draft-2", result.getWorkspaceId());
    }

    @Test
    void uploadDtoSerializationDoesNotExposeStoragePath() throws Exception {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        DatasetUploadSession session = fixture.initialSession();
        session.setStoragePath("users/7/datasets/asset-1/v1/data.zip");
        fixture.stubSession(session);

        V2DatasetUploadDto dto = fixture.service.get("upload-1");

        String json = new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(dto);
        assertFalse(json.contains("storagePath"));
        assertFalse(json.contains("ownerUserId"));
        assertFalse(json.contains("objectName"));
    }

    @Test
    void failedCompletionReturnsStableUploadError() {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        progress.setStatus("FAILED");
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        DatasetUploadSession session = fixture.initialSession();
        session.setStatus("FAILED");
        session.setCompletionErrorCode("INVALID_DATASET_CONTENT");
        session.setCompletionErrorMessage("数据集内容格式无效，请检查文件后重试");
        session.setCompletionErrorDetails(Map.of("stage", "VALIDATION"));
        fixture.stubSession(session);

        V2DatasetUploadDto result = fixture.service.get("upload-1");

        assertEquals("FAILED", result.getStatus());
        assertEquals("UPLOAD_FAILED", result.getDisplayStatus());
        assertEquals("INVALID_DATASET_CONTENT", result.getUserError().getErrorCode());
        assertEquals("VALIDATION", result.getUserError().getDetails().get("stage"));
    }

    @Test
    void completionFailureUsesSafeReasonCodeInImmediate422() {
        Fixture fixture = new Fixture();
        DatasetUploadProgressDto progress = fixture.progress();
        when(fixture.uploadService.getProgress("upload-1")).thenReturn(progress);
        fixture.stubSession(fixture.initialSession());
        when(fixture.uploadService.complete(any(DatasetUploadCompleteRequest.class)))
                .thenThrow(new DatasetUploadCompletionException(
                        "DATASET_UPLOAD_STORAGE_FAILED",
                        "数据集文件处理失败，请稍后重试",
                        Map.of("stage", "STORAGE")
                ));

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> fixture.service.complete("upload-1")
        );

        assertEquals("DATASET_UPLOAD_NOT_COMPLETABLE", error.getErrorCode());
        assertEquals("DATASET_UPLOAD_STORAGE_FAILED", error.getDetails().get("reasonCode"));
        assertEquals("STORAGE", error.getDetails().get("stage"));
        assertFalse(error.getDetails().toString().contains("users/"));
    }

    private static final class Fixture {
        private final DatasetUploadService uploadService = mock(DatasetUploadService.class);
        private final DatasetUploadSessionRepository sessionRepo =
                mock(DatasetUploadSessionRepository.class);
        private final ImportJobRepository importJobRepo = mock(ImportJobRepository.class);
        private final V2DatasetUploadService service = new V2DatasetUploadService(
                uploadService,
                sessionRepo,
                importJobRepo,
                new ObjectMapper()
        );

        private DatasetUploadProgressDto progress() {
            DatasetUploadProgressDto progress = new DatasetUploadProgressDto();
            progress.setUploadId("upload-1");
            progress.setStatus("UPLOADING");
            progress.setFileName("dataset.zip");
            progress.setFileSize(128L);
            progress.setChunkSize(64);
            progress.setTotalChunks(2);
            progress.setUploadedChunks(0);
            progress.setUploadedBytes(0L);
            progress.setAssetId("asset-1");
            progress.setVersionId("version-1");
            progress.setVersionLabel("v1");
            progress.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            progress.setUpdatedAt(progress.getCreatedAt());
            return progress;
        }

        private DatasetUploadSession initialSession() {
            DatasetUploadSession session = new DatasetUploadSession();
            session.setId("upload-1");
            session.setUploadPurpose("INITIAL_DATASET");
            session.setStatus("UPLOADING");
            session.setAssetId("asset-1");
            session.setVersionId("version-1");
            session.setVersionLabel("v1");
            session.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            session.setUpdatedAt(session.getCreatedAt());
            return session;
        }

        private void stubSession(DatasetUploadSession session) {
            when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        }
    }
}
