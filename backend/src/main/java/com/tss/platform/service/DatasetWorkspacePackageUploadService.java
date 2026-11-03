package com.tss.platform.service;

import com.tss.platform.controller.v2.V2BusinessException;
import com.tss.platform.dto.DatasetPackageAppendInitRequest;
import com.tss.platform.dto.DatasetUploadCompleteRequest;
import com.tss.platform.dto.v2.V2DatasetUploadCompleteRequest;
import com.tss.platform.entity.DatasetUploadChunk;
import com.tss.platform.entity.DatasetUploadSession;
import com.tss.platform.repository.DatasetUploadChunkRepository;
import com.tss.platform.repository.DatasetUploadSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class DatasetWorkspacePackageUploadService {

    public static final String PURPOSE = "APPEND_PACKAGE";

    private final DatasetWorkspaceCommandService commandService;
    private final DatasetUploadService uploadService;
    private final DatasetUploadSessionRepository sessionRepo;
    private final DatasetUploadChunkRepository chunkRepo;
    private final MinioDeleteTaskService deleteTaskService;
    private final DatasetWorkspaceAuditService auditService;

    public DatasetWorkspacePackageUploadService(
            DatasetWorkspaceCommandService commandService,
            DatasetUploadService uploadService,
            DatasetUploadSessionRepository sessionRepo,
            DatasetUploadChunkRepository chunkRepo,
            MinioDeleteTaskService deleteTaskService,
            DatasetWorkspaceAuditService auditService
    ) {
        this.commandService = commandService;
        this.uploadService = uploadService;
        this.sessionRepo = sessionRepo;
        this.chunkRepo = chunkRepo;
        this.deleteTaskService = deleteTaskService;
        this.auditService = auditService;
    }

    @Transactional
    public DatasetUploadSession init(
            String workspaceId,
            DatasetPackageAppendInitRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForMutation(
                        workspaceId,
                        request.getExpectedWorkspaceRevision()
                );
        var progress = uploadService.initAppendPackage(workspaceId, request);
        DatasetUploadSession session = sessionRepo
                .findByIdForUpdate(progress.getUploadId())
                .orElseThrow(this::notFound);
        long nextRevision = commandService.revision(access.workspace()) + 1L;
        session.setWorkspaceBaseRevision(nextRevision);
        sessionRepo.saveAndFlush(session);
        long revision = commandService.incrementRevision(access.workspace());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "PACKAGE_UPLOAD_REVISION_STARTED",
                "UPLOAD_SESSION",
                session.getId(),
                null,
                null,
                Map.of("workspaceRevision", revision)
        );
        return session;
    }

    @Transactional
    public DatasetUploadSession complete(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetUploadSession snapshot = requireSession(uploadId);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForOperationSettlement(
                        snapshot.getVersionId(),
                        request.expectedWorkspaceRevision(),
                        uploadId
                );
        DatasetUploadSession locked = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(this::notFound);
        requireSession(locked, access.workspace().getId());
        requireSessionOwner(locked, access);
        if ("COMPLETED".equals(locked.getStatus())) {
            return locked;
        }
        DatasetUploadCompleteRequest legacyRequest =
                new DatasetUploadCompleteRequest();
        legacyRequest.setUploadId(uploadId);
        uploadService.completeAppendPackage(
                access.workspace().getId(),
                legacyRequest
        );
        DatasetUploadSession completed = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(this::notFound);
        if (!"COMPLETED".equals(completed.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DATASET_UPLOAD_NOT_COMPLETABLE",
                    "追加包上传未能进入完成状态"
            );
        }
        long revision = commandService.incrementRevision(access.workspace());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "PACKAGE_UPLOAD_REVISION_COMPLETED",
                "UPLOAD_SESSION",
                completed.getId(),
                null,
                null,
                Map.of("workspaceRevision", revision)
        );
        return completed;
    }

    @Transactional
    public DatasetUploadSession cancel(
            String uploadId,
            V2DatasetUploadCompleteRequest request
    ) {
        if (request == null) {
            throw invalid("请求体不能为空");
        }
        DatasetUploadSession snapshot = requireSession(uploadId);
        DatasetWorkspaceCommandService.WorkspaceAccess access =
                commandService.lockForOperationSettlement(
                        snapshot.getVersionId(),
                        request.expectedWorkspaceRevision(),
                        uploadId
                );
        DatasetUploadSession session = sessionRepo.findByIdForUpdate(uploadId)
                .orElseThrow(this::notFound);
        requireSession(session, access.workspace().getId());
        requireSessionOwner(session, access);
        if ("DISCARDED".equals(session.getStatus())) {
            return session;
        }
        if ("COMPLETED".equals(session.getStatus())) {
            throw new V2BusinessException(
                    HttpStatus.CONFLICT,
                    "UPLOAD_STATE_CONFLICT",
                    "已完成上传不能取消"
            );
        }
        List<DatasetUploadChunk> chunks =
                chunkRepo.findByUploadIdOrderByPartIndexAsc(uploadId);
        session.setStatus("DISCARDED");
        session.setUpdatedAt(Instant.now());
        sessionRepo.saveAndFlush(session);
        long revision = commandService.incrementRevision(access.workspace());
        auditService.recordUserAction(
                access.asset(),
                access.workspace(),
                "PACKAGE_UPLOAD_CANCELLED",
                "UPLOAD_SESSION",
                session.getId(),
                null,
                null,
                Map.of("workspaceRevision", revision)
        );
        registerChunkCleanup(session, chunks);
        return session;
    }

    private DatasetUploadSession requireSession(String uploadId) {
        DatasetUploadSession session = sessionRepo.findById(uploadId)
                .orElseThrow(this::notFound);
        requireSession(session, session.getVersionId());
        return session;
    }

    private void requireSession(
            DatasetUploadSession session,
            String workspaceId
    ) {
        if (!PURPOSE.equals(session.getUploadPurpose())
                || workspaceId == null
                || !workspaceId.equals(session.getVersionId())) {
            throw notFound();
        }
    }

    private void requireSessionOwner(
            DatasetUploadSession session,
            DatasetWorkspaceCommandService.WorkspaceAccess access
    ) {
        if (!access.asset().getId().equals(session.getAssetId())
                || !java.util.Objects.equals(
                        access.asset().getOwnerUserId(),
                        session.getOwnerUserId()
                )) {
            throw notFound();
        }
    }

    private void registerChunkCleanup(
            DatasetUploadSession session,
            List<DatasetUploadChunk> chunks
    ) {
        Runnable cleanup = () -> {
            for (DatasetUploadChunk chunk : chunks) {
                try {
                    deleteTaskService.enqueueDefaultBucketDeleteImmediately(
                            chunk.getObjectName(),
                            MinioDeleteTaskService.SOURCE_DATASET_UPLOAD_CHUNK,
                            session.getId(),
                            session.getOwnerUserId()
                    );
                } catch (Exception ignored) {
                    // Cleanup is retried by the delete-task worker.
                }
            }
            try {
                chunkRepo.deleteByUploadId(session.getId());
            } catch (Exception ignored) {
                // Metadata cleanup does not alter cancellation.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    }
            );
        } else {
            cleanup.run();
        }
    }

    private static V2BusinessException invalid(String message) {
        return new V2BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_UPLOAD_REQUEST",
                message
        );
    }

    private V2BusinessException notFound() {
        return new V2BusinessException(
                HttpStatus.NOT_FOUND,
                "DATASET_UPLOAD_NOT_FOUND",
                "数据集上传任务不存在或无权访问"
        );
    }
}
