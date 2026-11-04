package com.tss.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.DatasetUploadInitRequest;
import com.tss.platform.dto.DatasetUploadProgressDto;
import com.tss.platform.dto.v2.V2DatasetUploadDto;
import com.tss.platform.dto.v2.V2DatasetUploadCompleteRequest;
import com.tss.platform.dto.v2.V2DatasetWorkspaceFileUploadInitRequest;
import com.tss.platform.dto.v2.V2UserError;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.entity.ImportJob;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import com.tss.platform.repository.DatasetVersionRepository;
import com.tss.platform.repository.ImportJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class V2DatasetUploadService {

    private static final String APPEND_PACKAGE = "APPEND_PACKAGE";
    private static final String WORKSPACE_FILE = "WORKSPACE_FILE";

    private final DatasetUploadService uploadService;
    private final DatasetUploadSessionRepository sessionRepo;
    private final ImportJobRepository importJobRepo;
    private final ObjectMapper objectMapper;
    private DatasetWorkspaceFileUploadService workspaceFileUploadService;
    private DatasetWorkspacePackageUploadService workspacePackageUploadService;
    private DatasetVersionRepository versionRepo;

    public V2DatasetUploadService(
            DatasetUploadService uploadService,
            DatasetUploadSessionRepository sessionRepo,
            ImportJobRepository importJobRepo,
            ObjectMapper objectMapper
    ) {
        this.uploadService = uploadService;
        this.sessionRepo = sessionRepo;
        this.importJobRepo = importJobRepo;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setWorkspaceFileUploadService(
            DatasetWorkspaceFileUploadService workspaceFileUploadService
    ) {
        this.workspaceFileUploadService = workspaceFileUploadService;
    }

    @Autowired(required = false)
    void setWorkspacePackageUploadService(
            DatasetWorkspacePackageUploadService workspacePackageUploadService
    ) {
        this.workspacePackageUploadService = workspacePackageUploadService;
    }

    @Autowired(required = false)
    void setDatasetVersionRepository(DatasetVersionRepository versionRepo) {
        this.versionRepo = versionRepo;
    }

    public V2DatasetUploadDto initWorkspaceFile(
            String workspaceId,
            V2DatasetWorkspaceFileUploadInitRequest request
    ) {
        if (workspaceFileUploadService == null) {
            throw new V2BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKSPACE_FILE_UPLOAD_UNAVAILABLE",
                    "工作区文件上传暂时不可用"
            );
        }
        return describe(workspaceFileUploadService.init(workspaceId, request));
    }

    public V2DatasetUploadDto init(DatasetUploadInitRequest request) {
        try {
            return describe(uploadService.init(request));
        } catch (IllegalArgumentException exception) {
            if (exception instanceof AssetNameConflictException) {
                throw datasetNameConflict();
            }
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_UPLOAD_REQUEST",
                    "上传请求无效，请检查 ZIP 文件和数据集信息",
                    reasonDetails(exception)
            );
        }
    }

    public V2DatasetUploadDto initAppend(
            String workspaceId,
            DatasetPackageAppendInitRequest request
    ) {
        try {
            if (workspacePackageUploadService == null) {
                return describe(uploadService.initAppendPackage(
                        workspaceId,
                        request
                ));
            }
            return describe(workspacePackageUploadService.init(
                    workspaceId,
                    request
            ));
        } catch (IllegalArgumentException exception) {
            if (exception instanceof AssetNameConflictException) {
                throw datasetNameConflict();
            }
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_UPLOAD_REQUEST",
                    "上传请求无效，请检查 ZIP 文件和上传参数",
                    reasonDetails(exception)
            );
        }
    }

    public V2DatasetUploadDto saveChunk(
            String uploadId,
            Integer partIndex,
            MultipartFile file
    ) {
        try {
            return describe(uploadService.saveChunk(uploadId, partIndex, file));
        } catch (IllegalArgumentException exception) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UPLOAD_CHUNK_FAILED",
                    "文件分片上传失败，请重试",
                    reasonDetails(exception)
            );
        }
    }

    public V2DatasetUploadDto get(String uploadId) {
        try {
            return describe(uploadService.getProgress(uploadId));
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
    }

    public V2DatasetUploadDto complete(String uploadId) {
        return complete(uploadId, null);
    }

    public V2DatasetUploadDto complete(
            String uploadId,
            V2DatasetUploadCompleteRequest workspaceRequest
    ) {
        DatasetUploadProgressDto progress;
        try {
            progress = uploadService.getProgress(uploadId);
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
        DatasetUploadSession session = requireSession(progress.getUploadId());
        if (WORKSPACE_FILE.equals(session.getUploadPurpose())) {
            if (workspaceRequest == null) {
                throw new V2BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                        "expectedWorkspaceRevision 不能为空"
                );
            }
            workspaceFileUploadService.complete(uploadId, workspaceRequest);
            return describe(uploadService.getProgress(uploadId));
        }
        DatasetUploadCompleteRequest request = new DatasetUploadCompleteRequest();
        request.setUploadId(uploadId);
        try {
            if (APPEND_PACKAGE.equals(session.getUploadPurpose())) {
                if (workspacePackageUploadService == null) {
                    if (session.getVersionId() == null
                            || session.getVersionId().isBlank()) {
                        throw new IllegalArgumentException(
                                "append version is missing"
                        );
                    }
                    uploadService.completeAppendPackage(
                            session.getVersionId(),
                            request
                    );
                } else if (workspaceRequest == null) {
                    throw new V2BusinessException(
                            HttpStatus.BAD_REQUEST,
                            "EXPECTED_WORKSPACE_REVISION_REQUIRED",
                            "expectedWorkspaceRevision 不能为空"
                    );
                } else {
                    workspacePackageUploadService.complete(
                            uploadId,
                            workspaceRequest
                    );
                }
            } else {
                uploadService.complete(request);
            }
            return describe(uploadService.getProgress(uploadId));
        } catch (IllegalArgumentException exception) {
            if (exception instanceof AssetNameConflictException) {
                throw datasetNameConflict();
            }
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATASET_UPLOAD_NOT_COMPLETABLE",
                    "上传尚未完成或文件校验失败，请检查后重试",
                    reasonDetails(exception)
            );
        }
    }

    public V2DatasetUploadDto cancel(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        DatasetUploadSession session = requireSession(uploadId);
        if (WORKSPACE_FILE.equals(session.getUploadPurpose())
                && workspaceFileUploadService != null) {
            return describe(workspaceFileUploadService.cancel(
                    uploadId,
                    request
            ));
        }
        if (APPEND_PACKAGE.equals(session.getUploadPurpose())
                && workspacePackageUploadService != null) {
            return describe(workspacePackageUploadService.cancel(
                    uploadId,
                    request
            ));
        }
        {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_NOT_CANCELLABLE",
                    "该上传任务不支持当前取消入口"
            );
        }
    }

    private V2DatasetUploadDto describe(DatasetUploadSession session) {
        return describe(uploadService.getProgress(session.getId()));
    }

    private V2DatasetUploadDto describe(DatasetUploadProgressDto source) {
        DatasetUploadSession session = requireSession(source.getUploadId());
        ImportJob job = session.getImportJobId() == null
                ? null
                : importJobRepo.findById(session.getImportJobId()).orElse(null);

        V2DatasetUploadDto dto = new V2DatasetUploadDto();
        dto.setUploadId(source.getUploadId());
        dto.setStatus(source.getStatus());
        dto.setFileName(source.getFileName());
        dto.setFileSize(source.getFileSize());
        dto.setChunkSize(source.getChunkSize());
        dto.setTotalChunks(source.getTotalChunks());
        dto.setUploadedChunks(source.getUploadedChunks());
        dto.setUploadedBytes(source.getUploadedBytes());
        dto.setUploadedPartIndexes(source.getUploadedPartIndexes());
        dto.setImportJobId(session.getImportJobId());
        dto.setDatasetId(source.getAssetId());
        boolean workspaceUpload = APPEND_PACKAGE.equals(session.getUploadPurpose())
                || WORKSPACE_FILE.equals(session.getUploadPurpose());
        dto.setWorkspaceId(workspaceUpload ? session.getVersionId() : null);
        dto.setWorkspaceRevision(workspaceRevision(session, job));
        dto.setTargetKind(session.getTargetKind());
        dto.setTargetOperation(session.getTargetOperation());
        dto.setTargetResourceId(session.getTargetResourceId());
        dto.setVersionLabel(source.getVersionLabel());
        dto.setStrictManifest(Boolean.TRUE.equals(session.getStrictManifest()));
        dto.setArtifactSpecId(source.getArtifactSpecId());
        dto.setDisplayStatus(displayStatus(source.getStatus(), job));
        dto.setImportProgress(job == null ? null : job.getProgress());
        V2UserError importError = V2ImportJobDisplayHelper.userError(
                job,
                objectMapper
        );
        dto.setUserError(importError == null
                ? uploadFailure(session)
                : importError);
        dto.setCreatedAt(source.getCreatedAt());
        dto.setUpdatedAt(source.getUpdatedAt());
        return dto;
    }

    private Long workspaceRevision(
            DatasetUploadSession session,
            ImportJob job
    ) {
        if (!APPEND_PACKAGE.equals(session.getUploadPurpose())
                && !WORKSPACE_FILE.equals(session.getUploadPurpose())) {
            return null;
        }
        if (versionRepo != null && session.getVersionId() != null) {
            var workspace = versionRepo
                    .findByIdAndDeletedFalse(session.getVersionId())
                    .orElse(null);
            if (workspace != null) {
                return workspace.getWorkspaceRevision() == null
                        ? 0L
                        : workspace.getWorkspaceRevision();
            }
        }
        Long base = session.getWorkspaceBaseRevision();
        if (base == null) {
            return null;
        }
        long revision = "COMPLETED".equals(session.getStatus())
                || "DISCARDED".equals(session.getStatus())
                ? base + 1L
                : base;
        if (APPEND_PACKAGE.equals(session.getUploadPurpose())
                && job != null
                && ("SUCCESS".equals(job.getStatus())
                || "FAILED".equals(job.getStatus())
                || "PARTIAL".equals(job.getStatus())
                || "SUPERSEDED".equals(job.getStatus()))) {
            revision += 1L;
        }
        return revision;
    }

    private DatasetUploadSession requireSession(String uploadId) {
        return sessionRepo.findById(uploadId).orElseThrow(this::notFound);
    }

    private String displayStatus(String uploadStatus, ImportJob job) {
        if (job != null && "PARTIAL".equals(job.getStatus())) {
            return "IMPORT_PARTIAL";
        }
        if (job != null && "FAILED".equals(job.getStatus())) {
            return "IMPORT_FAILED";
        }
        if (job != null
                && ("PENDING".equals(job.getStatus())
                || "RUNNING".equals(job.getStatus()))) {
            return "IMPORTING";
        }
        if (job != null && "SUCCESS".equals(job.getStatus())) {
            return "READY";
        }
        if ("COMPLETING".equals(uploadStatus)) {
            return "PROCESSING";
        }
        if ("COMPLETED".equals(uploadStatus)) {
            return "READY";
        }
        if ("DISCARDED".equals(uploadStatus)) {
            return "CANCELLED";
        }
        if ("FAILED".equals(uploadStatus)) {
            return "UPLOAD_FAILED";
        }
        return "UPLOADING";
    }

    private V2UserError uploadFailure(DatasetUploadSession session) {
        if (!"FAILED".equals(session.getStatus())
                || session.getCompletionErrorCode() == null
                || session.getCompletionErrorMessage() == null) {
            return null;
        }
        Map<String, Object> details = session.getCompletionErrorDetails() == null
                ? Map.of()
                : Map.copyOf(session.getCompletionErrorDetails());
        return new V2UserError(
                session.getCompletionErrorCode(),
                session.getCompletionErrorMessage(),
                details
        );
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_UPLOAD_NOT_FOUND",
                "数据集上传任务不存在或无权访问"
        );
    }

    private V2BusinessException datasetNameConflict() {
        return new V2BusinessException(
                HttpStatus.CONFLICT,
                "DATASET_NAME_CONFLICT",
                "同一用户下已存在同名数据集资产"
        );
    }

    private Map<String, Object> reasonDetails(IllegalArgumentException exception) {
        if (exception instanceof DatasetUploadCompletionException completion) {
            Map<String, Object> details = new LinkedHashMap<>(completion.getDetails());
            details.put("reasonCode", completion.getReasonCode());
            return Map.copyOf(details);
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? Map.of()
                : Map.of("reason", message);
    }
}
