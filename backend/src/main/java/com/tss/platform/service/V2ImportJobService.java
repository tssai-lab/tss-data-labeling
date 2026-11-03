package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.dto.v2.V2ImportJobStatusDto;
import com.tss.platform.dto.v2.V2ImportJobRetryRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class V2ImportJobService {

    private static final Set<String> IMPORTING_STATUSES = Set.of("PENDING", "RUNNING");

    private final ImportJobQueryService importJobQueryService;
    private final DatasetWorkspaceImportRetryService workspaceRetryService;

    public V2ImportJobService(
            ImportJobQueryService importJobQueryService,
            DatasetWorkspaceImportRetryService workspaceRetryService
    ) {
        this.importJobQueryService = importJobQueryService;
        this.workspaceRetryService = workspaceRetryService;
    }

    public V2ImportJobStatusDto retry(
            String importJobId,
            V2ImportJobRetryRequest request
    ) {
        if (request == null) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMPORT_JOB_RETRY",
                    "请求体不能为空"
            );
        }
        try {
            DatasetWorkspaceImportRetryService.RetryResult result =
                    workspaceRetryService.retry(
                            importJobId,
                            request.mode(),
                            request.expectedWorkspaceRevision()
                    );
            V2ImportJobStatusDto dto = toV2(result.status());
            dto.setWorkspaceId(result.workspaceId());
            dto.setWorkspaceRevision(result.workspaceRevision());
            return dto;
        } catch (ImportJobQueryService.ImportJobAccessException exception) {
            throw notFound();
        } catch (ImportJobQueryService.ImportJobRetryRejectedException exception) {
            throw notRetryable(exception);
        }
    }

    public V2ImportJobStatusDto getStatus(String importJobId) {
        try {
            return toV2(importJobQueryService.getStatus(importJobId));
        } catch (ImportJobQueryService.ImportJobAccessException exception) {
            throw new V2BusinessException(
                    HttpStatus.NOT_FOUND,
                    "IMPORT_JOB_NOT_FOUND",
                    "导入任务不存在或无权访问"
            );
        } catch (IllegalArgumentException exception) {
            throw new V2BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IMPORT_JOB_STATUS_REQUEST",
                    "导入任务查询请求无效",
                    reasonDetails(exception)
            );
        }
    }

    private Map<String, Object> reasonDetails(RuntimeException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.isBlank()) {
            return Map.of();
        }
        return Map.of("reason", message);
    }

    private V2ImportJobStatusDto toV2(ImportJobStatusDto source) {
        V2ImportJobStatusDto dto = new V2ImportJobStatusDto();
        dto.setImportJobId(source.getImportJobId());
        dto.setStatus(source.getStatus());
        dto.setDisplayStatus(displayStatus(source.getStatus()));
        dto.setImportProgress(source.getProgress());
        dto.setTotalSamples(source.getTotalSamples());
        dto.setImportedSamples(source.getImportedSamples());
        dto.setFailedSamples(source.getFailedSamples());
        dto.setRetryModes(retryModes(source));
        dto.setRetryable(!dto.getRetryModes().isEmpty());
        dto.setUserError(V2ImportJobDisplayHelper.userError(source));
        return dto;
    }

    private List<String> retryModes(ImportJobStatusDto source) {
        if ("FAILED".equals(source.getStatus())) {
            return List.of("FULL");
        }
        if ("PARTIAL".equals(source.getStatus())
                && source.getFailedSamples() != null
                && source.getFailedSamples() > 0) {
            return List.of("INCREMENTAL");
        }
        return List.of();
    }

    private String displayStatus(String status) {
        if ("PARTIAL".equals(status)) {
            return "IMPORT_PARTIAL";
        }
        if ("FAILED".equals(status)) {
            return "IMPORT_FAILED";
        }
        if (IMPORTING_STATUSES.contains(status)) {
            return "IMPORTING";
        }
        if ("SUCCESS".equals(status)) {
            return "READY";
        }
        return status;
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "IMPORT_JOB_NOT_FOUND",
                "导入任务不存在或无权访问"
        );
    }

    private V2BusinessException notRetryable(RuntimeException exception) {
        return new V2BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IMPORT_JOB_NOT_RETRYABLE",
                "当前导入任务不可重试",
                reasonDetails(exception)
        );
    }
}
