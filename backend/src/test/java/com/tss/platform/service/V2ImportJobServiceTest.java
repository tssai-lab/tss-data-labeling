package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.dto.v2.V2ImportJobStatusDto;
import com.tss.platform.dto.v2.V2ImportJobRetryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2ImportJobServiceTest {

    @Test
    void retryDelegatesToImportJobQueryServiceAndReturnsV2Status() {
        ImportJobQueryService delegate = mock(ImportJobQueryService.class);
        ImportJobStatusDto retryResult = new ImportJobStatusDto();
        retryResult.setImportJobId("ijob-1");
        retryResult.setStatus("PENDING");
        retryResult.setProgress(0);
        DatasetWorkspaceImportRetryService workspaceRetry =
                mock(DatasetWorkspaceImportRetryService.class);
        when(workspaceRetry.retry("ijob-1", "FULL", 3L))
                .thenReturn(new DatasetWorkspaceImportRetryService.RetryResult(
                        retryResult,
                        "workspace-1",
                        4L
                ));
        V2ImportJobService service = new V2ImportJobService(delegate, workspaceRetry);

        V2ImportJobStatusDto result = service.retry(
                "ijob-1",
                new V2ImportJobRetryRequest("FULL", 3L)
        );

        assertEquals("ijob-1", result.getImportJobId());
        assertEquals("PENDING", result.getStatus());
        assertEquals("IMPORTING", result.getDisplayStatus());
        assertEquals(0, result.getImportProgress());
        assertEquals("workspace-1", result.getWorkspaceId());
        assertEquals(4L, result.getWorkspaceRevision());
        assertNull(result.getUserError());
    }

    @Test
    void getStatusMapsPartialImportToExplicitV2StatusAndUserError() {
        ImportJobQueryService delegate = mock(ImportJobQueryService.class);
        ImportJobStatusDto status = new ImportJobStatusDto();
        status.setImportJobId("ijob-1");
        status.setStatus("PARTIAL");
        status.setProgress(50);
        status.setTotalSamples(2);
        status.setImportedSamples(1);
        status.setFailedSamples(1);
        status.setErrorCode("PARTIAL_IMPORT_FAILED");
        status.setErrorMessage("部分样本导入失败，可增量重试");
        when(delegate.getStatus("ijob-1")).thenReturn(status);
        V2ImportJobService service = new V2ImportJobService(
                delegate,
                mock(DatasetWorkspaceImportRetryService.class)
        );

        V2ImportJobStatusDto result = service.getStatus("ijob-1");

        assertEquals("ijob-1", result.getImportJobId());
        assertEquals("PARTIAL", result.getStatus());
        assertEquals("IMPORT_PARTIAL", result.getDisplayStatus());
        assertEquals(50, result.getImportProgress());
        assertEquals(1, result.getFailedSamples());
        assertEquals("INCREMENTAL", result.getRetryModes().get(0));
        assertEquals("PARTIAL_IMPORT_FAILED", result.getUserError().getErrorCode());
        assertEquals(1, result.getUserError().getDetails().get("failedSamples"));
        assertEquals(2, result.getUserError().getDetails().get("totalSamples"));
    }

    @Test
    void getStatusMarksFailedImportJobRetryableWithFullMode() {
        ImportJobQueryService delegate = mock(ImportJobQueryService.class);
        ImportJobStatusDto status = new ImportJobStatusDto();
        status.setImportJobId("ijob-2");
        status.setStatus("FAILED");
        status.setProgress(20);
        status.setErrorCode("IMPORT_FAILED");
        status.setErrorMessage("manifest validation failed");
        status.setErrorDetailsJson(
                "{\"field\":\"samples[0].data[0].path\","
                        + "\"path\":\"missing.png\","
                        + "\"reason\":\"path not found in zip\"}"
        );
        when(delegate.getStatus("ijob-2")).thenReturn(status);
        V2ImportJobService service = new V2ImportJobService(
                delegate,
                mock(DatasetWorkspaceImportRetryService.class)
        );

        V2ImportJobStatusDto result = service.getStatus("ijob-2");

        assertEquals("ijob-2", result.getImportJobId());
        assertEquals("FAILED", result.getStatus());
        assertEquals("IMPORT_FAILED", result.getDisplayStatus());
        assertEquals(Boolean.TRUE, result.getRetryable());
        assertEquals(List.of("FULL"), result.getRetryModes());
        assertEquals("IMPORT_FAILED", result.getUserError().getErrorCode());
        assertEquals(
                "samples[0].data[0].path",
                result.getUserError().getDetails().get("field")
        );
        assertEquals(
                "missing.png",
                result.getUserError().getDetails().get("path")
        );
    }

    @Test
    void retryMapsMissingImportJobToV2NotFound() {
        ImportJobQueryService delegate = mock(ImportJobQueryService.class);
        DatasetWorkspaceImportRetryService workspaceRetry =
                mock(DatasetWorkspaceImportRetryService.class);
        when(workspaceRetry.retry("ijob-missing", "FULL", 3L))
                .thenThrow(new ImportJobQueryService.ImportJobAccessException(
                        "importJob not found or no permission"
                ));
        V2ImportJobService service = new V2ImportJobService(delegate, workspaceRetry);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.retry(
                        "ijob-missing",
                        new V2ImportJobRetryRequest("FULL", 3L)
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("IMPORT_JOB_NOT_FOUND", error.getErrorCode());
    }

    @Test
    void retryMapsNonRetryableImportJobToV2BusinessError() {
        ImportJobQueryService delegate = mock(ImportJobQueryService.class);
        DatasetWorkspaceImportRetryService workspaceRetry =
                mock(DatasetWorkspaceImportRetryService.class);
        when(workspaceRetry.retry("ijob-running", "FULL", 3L))
                .thenThrow(new ImportJobQueryService.ImportJobRetryRejectedException(
                        "only FAILED ImportJob can be retried"
                ));
        V2ImportJobService service = new V2ImportJobService(delegate, workspaceRetry);

        V2BusinessException error = assertThrows(
                V2BusinessException.class,
                () -> service.retry(
                        "ijob-running",
                        new V2ImportJobRetryRequest("FULL", 3L)
                )
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        assertEquals("IMPORT_JOB_NOT_RETRYABLE", error.getErrorCode());
        assertEquals("only FAILED ImportJob can be retried", error.getDetails().get("reason"));
    }
}
