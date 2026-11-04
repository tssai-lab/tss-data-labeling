package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.ImportJobStatusDto;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.ImportJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetWorkspaceImportRetryService {

    private final ImportJobRepository importJobRepo;
    private final DatasetWorkspaceCommandService commandService;
    private final ImportJobQueryService importJobQueryService;

    public DatasetWorkspaceImportRetryService(
            ImportJobRepository importJobRepo,
            DatasetWorkspaceCommandService commandService,
            ImportJobQueryService importJobQueryService
    ) {
        this.importJobRepo = importJobRepo;
        this.commandService = commandService;
        this.importJobQueryService = importJobQueryService;
    }

    @Transactional
    public RetryResult retry(
            String importJobId,
            String mode,
            Long expectedWorkspaceRevision
    ) {
        ImportJob snapshot = importJobRepo.findById(importJobId)
                .orElseThrow(() -> new V2BusinessException(
                        HttpStatus.NOT_FOUND,
                        "IMPORT_JOB_NOT_FOUND",
                        "导入任务不存在或无权访问"
                ));
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        snapshot.getDatasetVersionId(),
                        expectedWorkspaceRevision
                );
        ImportJobStatusDto result = importJobQueryService.retry(
                importJobId,
                mode
        );
        return new RetryResult(
                result,
                access.workspace().getId(),
                commandService.revision(access.workspace())
        );
    }

    public record RetryResult(
            ImportJobStatusDto status,
            String workspaceId,
            long workspaceRevision
    ) {
    }
}
